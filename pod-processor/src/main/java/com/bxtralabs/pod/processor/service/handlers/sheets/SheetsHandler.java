package com.bxtralabs.pod.processor.service.handlers.sheets;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.AccountRejectedException;
import com.bxtralabs.pod.processor.service.handlers.ActionHandler;
import com.bxtralabs.pod.processor.service.handlers.AppCalls;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import com.bxtralabs.pod.processor.service.handlers.StepContext;
import com.bxtralabs.pod.processor.service.handlers.StepCredentials;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Google Sheets actions, through the step's Sheets connection (Google OAuth, spreadsheets scope):
//   sheets.add_row     spreadsheetId (ID or link), sheet, values (column header -> value)
//                      -> {rowNumber, range, spreadsheetId, sheet, values}
//   sheets.update_row  spreadsheetId, sheet, rowNumber (2 or more), values -> {rowNumber, updatedCells, ...}
// Columns are found by their header in row 1 (exact, then ignoring case and spaces), so the sheet's
// column order doesn't matter. Values are entered as if typed (USER_ENTERED): "42" is a number,
// "2026-10-31" a date, "=A2*2" a formula.
// Not twice: Sheets has no idempotency key. Updating sets the same cells again, which is harmless.
// Adding: if an earlier attempt may have added the row, the sheet's last 50 rows are checked for one
// with exactly these values, and that one is returned instead of adding another.
// Errors the user must fix (spreadsheet not shared with the account, no such tab, unknown column)
// fail the step for good; rate limits and 5xx are retried.
@Component
@Order(1)
public class SheetsHandler implements ActionHandler {

    public static final String ADD_ROW = "sheets.add_row";
    public static final String UPDATE_ROW = "sheets.update_row";
    static final int RECENT_ROWS = 50;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,}");
    private static final Pattern LINK = Pattern.compile("https?://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]+).*");
    private static final Pattern ROW_IN_RANGE = Pattern.compile("![A-Z]+(\\d+)(?::[A-Z]+\\d+)?$");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public SheetsHandler(JsonMapper jsonMapper,
                         @Value("${connectors.sheets.api-base:https://sheets.googleapis.com/v4}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(GraphNode node) {
        return ADD_ROW.equals(node.type()) || UPDATE_ROW.equals(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws Exception {
        return execute(node, input, null);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials) throws Exception {
        return execute(node, input, credentials, null);
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input, StepCredentials credentials,
                                       StepContext context) throws Exception {
        String token = credentials == null ? null : credentials.get("access_token");
        if (token == null || token.isBlank()) {
            throw new PermanentStepException("This step needs a Google Sheets account. Choose one in the step's setup.");
        }
        String spreadsheet = spreadsheetId(text(input.get("spreadsheetId")));
        String sheet = text(input.get("sheet")).isEmpty() ? "Sheet1" : text(input.get("sheet"));
        if (!(input.get("values") instanceof Map<?, ?> given) || given.isEmpty()) {
            throw new PermanentStepException("Set at least one column in Values");
        }
        List<String> headers = headers(token, spreadsheet, sheet);
        // column index -> value
        TreeMap<Integer, String> cells = new TreeMap<>();
        for (Map.Entry<?, ?> e : given.entrySet()) {
            cells.put(column(headers, String.valueOf(e.getKey()), sheet), cell(e.getValue()));
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("spreadsheetId", spreadsheet);
        out.put("sheet", sheet);
        Map<String, Object> written = new LinkedHashMap<>();
        cells.forEach((i, v) -> written.put(headers.get(i), v));

        if (UPDATE_ROW.equals(node.type())) {
            long row = rowNumber(input.get("rowNumber"));
            List<Map<String, Object>> data = new ArrayList<>();
            cells.forEach((i, v) -> data.add(Map.of("range", a1(sheet, columnLetters(i) + row), "values", List.of(List.of(v)))));
            Map<?, ?> res = post(token, "/spreadsheets/" + spreadsheet + "/values:batchUpdate",
                    Map.of("valueInputOption", "USER_ENTERED", "data", data), "row " + row, false);
            out.put("rowNumber", row);
            out.put("updatedCells", res.get("totalUpdatedCells"));
            out.put("values", written);
            return out;
        }

        List<String> row = new ArrayList<>();
        for (int i = 0; i <= cells.lastKey(); i++) row.add(cells.getOrDefault(i, ""));
        if (context != null && context.mayHaveHappened()) {
            Long earlier = earlierRow(token, spreadsheet, sheet, row);
            if (earlier != null) {
                out.put("rowNumber", earlier);
                out.put("range", sheet + "!A" + earlier);
                out.put("values", written);
                out.put("alreadyDone", true);
                return out;
            }
        }
        Map<?, ?> res = post(token, "/spreadsheets/" + spreadsheet + "/values/" + enc(a1(sheet, "A1"))
                        + ":append?valueInputOption=USER_ENTERED&insertDataOption=INSERT_ROWS",
                Map.of("values", List.of(row)), "the row", true);
        String range = res.get("updates") instanceof Map<?, ?> u && u.get("updatedRange") != null ? String.valueOf(u.get("updatedRange")) : null;
        Matcher m = range == null ? null : ROW_IN_RANGE.matcher(range);
        out.put("rowNumber", m != null && m.find() ? Long.parseLong(m.group(1)) : null);
        out.put("range", range);
        out.put("values", written);
        return out;
    }

    // The sheet's headers (row 1).
    private List<String> headers(String token, String spreadsheet, String sheet) throws Exception {
        Map<?, ?> res = get(token, "/spreadsheets/" + spreadsheet + "/values/" + enc(a1(sheet, "1:1")), "sheet \"" + sheet + "\"");
        List<String> headers = new ArrayList<>();
        if (res.get("values") instanceof List<?> rows && !rows.isEmpty() && rows.getFirst() instanceof List<?> first) {
            first.forEach(h -> headers.add(h == null ? "" : String.valueOf(h)));
        }
        if (headers.stream().allMatch(String::isBlank)) {
            throw new PermanentStepException("Sheet \"" + sheet + "\" has no column headers. Put them in row 1 (e.g. Name, Email), "
                    + "then use them as the keys in Values.");
        }
        return headers;
    }

    static int column(List<String> headers, String key, String sheet) throws PermanentStepException {
        for (int i = 0; i < headers.size(); i++) {
            if (headers.get(i).equals(key)) return i;
        }
        String wanted = squash(key);
        for (int i = 0; i < headers.size(); i++) {
            if (squash(headers.get(i)).equals(wanted)) return i;
        }
        throw new PermanentStepException("Sheet \"" + sheet + "\" has no column \"" + key + "\". Its columns: "
                + String.join(", ", headers.stream().filter(h -> !h.isBlank()).toList()));
    }

    private static String squash(String s) {
        return s.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }

    // A row with exactly these values among the sheet's last rows (as written, before Sheets turned
    // "42" into a number: compared as the text Sheets shows).
    private Long earlierRow(String token, String spreadsheet, String sheet, List<String> row) throws Exception {
        Map<?, ?> res = get(token, "/spreadsheets/" + spreadsheet + "/values/" + enc(a1(sheet, "A:" + columnLetters(row.size() - 1))),
                "sheet \"" + sheet + "\"");
        if (!(res.get("values") instanceof List<?> rows)) return null;
        for (int r = rows.size() - 1; r >= Math.max(1, rows.size() - RECENT_ROWS); r--) {
            if (rows.get(r) instanceof List<?> cells && sameRow(cells, row)) return (long) r + 1;
        }
        return null;
    }

    private static boolean sameRow(List<?> cells, List<String> row) {
        for (int i = 0; i < Math.max(cells.size(), row.size()); i++) {
            String have = i < cells.size() && cells.get(i) != null ? String.valueOf(cells.get(i)) : "";
            String want = i < row.size() ? row.get(i) : "";
            if (!have.equals(want)) return false;
        }
        return true;
    }

    // "A".."Z", "AA".. for a 0-based column.
    static String columnLetters(int index) {
        StringBuilder s = new StringBuilder();
        for (int n = index + 1; n > 0; n = (n - 1) / 26) s.insert(0, (char) ('A' + (n - 1) % 26));
        return s.toString();
    }

    // A1 notation with the sheet name quoted (it may have spaces or quotes).
    static String a1(String sheet, String cells) {
        return "'" + sheet.replace("'", "''") + "'!" + cells;
    }

    static String spreadsheetId(String given) throws PermanentStepException {
        Matcher link = LINK.matcher(given);
        if (link.matches()) return link.group(1);
        if (ID.matcher(given).matches()) return given;
        throw new PermanentStepException(given.isEmpty() ? "Set the spreadsheet: paste its link"
                : "\"" + abbreviate(given) + "\" isn't a Google Sheets link or ID");
    }

    private static long rowNumber(Object value) throws PermanentStepException {
        try {
            long row = new java.math.BigDecimal(text(value)).longValueExact();
            if (row < 2) {
                throw new PermanentStepException("Row number must be 2 or more (row 1 holds the column headers)");
            }
            return row;
        } catch (NumberFormatException | ArithmeticException e) {
            throw new PermanentStepException("Row number must be a whole number, not \"" + abbreviate(text(value)) + "\"");
        }
    }

    // What goes in a cell: text as is; numbers and true/false as Sheets would show them; anything
    // else (a list, an object) as JSON.
    private String cell(Object value) {
        if (value == null) return "";
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value instanceof Boolean b ? (b ? "TRUE" : "FALSE") : String.valueOf(value);
        }
        return jsonMapper.writeValueAsString(value);
    }

    private Map<?, ?> get(String token, String path, String what) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path)).GET(), token, what, false);
    }

    private Map<?, ?> post(String token, String path, Object body, String what, boolean creates) throws Exception {
        return send(HttpRequest.newBuilder(URI.create(apiBase + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonMapper.writeValueAsString(body))), token, what, creates);
    }

    private Map<?, ?> send(HttpRequest.Builder request, String token, String what, boolean creates) throws Exception {
        HttpResponse<String> response = AppCalls.send(http, request.timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token).build(), "Google Sheets", creates);
        int status = response.statusCode();
        if (status == 429) {
            throw new IllegalStateException("Google Sheets rate limit reached; will try again");
        }
        if (status >= 500) {
            if (creates) throw AppCalls.uncertainServerError("Google Sheets", status, what);
            throw new IllegalStateException("Google Sheets had a problem (HTTP " + status + "); will try again");
        }
        Map<?, ?> body;
        try {
            body = response.body() == null || response.body().isBlank() ? Map.of() : jsonMapper.readValue(response.body(), Map.class);
        } catch (RuntimeException notJson) {
            throw new IllegalStateException("Google Sheets answered HTTP " + status + " with something unexpected; will try again");
        }
        if (status >= 200 && status < 300) {
            return body;
        }
        Map<?, ?> error = body.get("error") instanceof Map<?, ?> e ? e : Map.of();
        String message = error.get("message") == null ? "HTTP " + status : String.valueOf(error.get("message"));
        if (status == 401) {
            throw new AccountRejectedException("Google no longer accepts this Sheets account (" + message + "). Reconnect it.");
        }
        if (status == 403) {
            throw new PermanentStepException("This Google account can't edit that spreadsheet (" + message
                    + "). Share it with the account, with Editor access.");
        }
        if (status == 404) {
            throw new PermanentStepException("Google Sheets couldn't find that spreadsheet. Check the link, and that it's shared "
                    + "with the connected account.");
        }
        if (message.startsWith("Unable to parse range")) {
            throw new PermanentStepException("The spreadsheet has no tab named " + what.replace("sheet ", "")
                    + ". Check Sheet (the tab's name at the bottom of the spreadsheet).");
        }
        throw new PermanentStepException("Google Sheets refused " + what + ": " + message);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String abbreviate(String s) {
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim();
    }
}
