package com.bxtralabs.pod.connector.triggers;

import com.bxtralabs.pod.connector.model.TriggerSubscription;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Google Sheets triggers, polled (TriggerPoller) by reading the sheet:
//   trg_sheets_new_row      rows added below the last one seen
//   trg_sheets_updated_row  rows whose values changed (each row's fingerprint is kept; sheets of up
//                           to MAX_ROWS rows)
// Row 1 holds the column headers; each row becomes {header: value} plus its row number. Turning one
// on records the sheet as it is, so existing rows start nothing. Deleting or sorting rows shifts row
// numbers: New Row then only counts rows past the previous end, and Updated Row may see moved rows as
// changed.
@Component
public class SheetsTriggers implements PollingTriggers {

    public static final String NEW_ROW = "trg_sheets_new_row";
    public static final String UPDATED_ROW = "trg_sheets_updated_row";
    static final int MAX_ROWS = 2000;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{20,}");
    private static final Pattern LINK = Pattern.compile("https?://docs\\.google\\.com/spreadsheets/d/([A-Za-z0-9_-]+).*");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final JsonMapper jsonMapper;
    private final String apiBase;

    public SheetsTriggers(JsonMapper jsonMapper, @Value("${connectors.sheets.api-base:https://sheets.googleapis.com/v4}") String apiBase) {
        this.jsonMapper = jsonMapper;
        this.apiBase = apiBase.replaceAll("/+$", "");
    }

    @Override
    public boolean supports(String appId) {
        return "app_sheets".equals(appId);
    }

    @Override
    public Registration register(TriggerSubscription s, Map<String, String> credentials, String hookUrl, String unused)
            throws TriggerSetupException {
        List<List<String>> rows = rows(s, credentials.get("access_token"));
        if (rows.isEmpty() || rows.getFirst().stream().allMatch(String::isBlank)) {
            throw new TriggerSetupException("The sheet has no column headers. Put them in row 1 (e.g. Name, Email), then turn the workflow on again.");
        }
        if (UPDATED_ROW.equals(s.getTriggerId()) && rows.size() > MAX_ROWS) {
            throw new TriggerSetupException("Updated Row watches sheets of up to " + MAX_ROWS + " rows; this one has " + rows.size());
        }
        s.setMeta(meta(s, rows));
        return new Registration(null, null);
    }

    @Override
    public Poll poll(TriggerSubscription s, Map<String, String> credentials) throws TriggerSetupException {
        List<List<String>> rows = rows(s, credentials.get("access_token"));
        Map<String, Object> meta = s.getMeta() == null ? Map.of() : s.getMeta();
        List<String> headers = rows.isEmpty() ? List.of() : rows.getFirst();
        List<Event> events = new ArrayList<>();
        if (NEW_ROW.equals(s.getTriggerId())) {
            int seen = meta.get("rowCount") instanceof Number n ? n.intValue() : rows.size();
            for (int r = seen; r < rows.size(); r++) {
                List<String> row = rows.get(r);
                if (row.stream().allMatch(String::isBlank)) continue;
                events.add(new Event("sheets:" + (r + 1) + ":" + hash(row), body(s, headers, row, r + 1)));
            }
        } else {
            if (rows.size() > MAX_ROWS) {
                throw new TriggerSetupException("Updated Row watches sheets of up to " + MAX_ROWS + " rows; this one now has " + rows.size());
            }
            List<?> before = meta.get("hashes") instanceof List<?> l ? l : List.of();
            for (int r = 1; r < Math.min(before.size(), rows.size()); r++) {
                String now = hash(rows.get(r));
                if (!now.equals(before.get(r))) {
                    // Keyed on the change, so a row going back to an earlier value still counts.
                    events.add(new Event("sheets:" + (r + 1) + ":" + before.get(r) + ">" + now, body(s, headers, rows.get(r), r + 1)));
                }
            }
        }
        return new Poll(events, meta(s, rows));
    }

    private Map<String, Object> meta(TriggerSubscription s, List<List<String>> rows) {
        Map<String, Object> meta = new LinkedHashMap<>();
        if (NEW_ROW.equals(s.getTriggerId())) {
            meta.put("rowCount", rows.size());
        } else {
            meta.put("hashes", rows.stream().map(SheetsTriggers::hash).toList());
        }
        return meta;
    }

    private static Map<String, Object> body(TriggerSubscription s, List<String> headers, List<String> row, int rowNumber) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < Math.max(headers.size(), row.size()); i++) {
            String header = i < headers.size() && !headers.get(i).isBlank() ? headers.get(i) : "Column " + (i + 1);
            values.put(header, i < row.size() ? row.get(i) : "");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("rowNumber", rowNumber);
        out.put("values", values);
        out.put("spreadsheetId", spreadsheet(s));
        out.put("sheet", sheet(s));
        return out;
    }

    // Each row's cells, as shown in the sheet (trailing empty cells left out by Sheets).
    private List<List<String>> rows(TriggerSubscription s, String token) throws TriggerSetupException {
        String id;
        try {
            id = spreadsheet(s);
        } catch (IllegalArgumentException e) {
            throw new TriggerSetupException(e.getMessage());
        }
        String range = URLEncoder.encode("'" + sheet(s).replace("'", "''") + "'", StandardCharsets.UTF_8).replace("+", "%20");
        HttpResponse<String> response;
        try {
            response = http.send(HttpRequest.newBuilder(URI.create(apiBase + "/spreadsheets/" + id + "/values/" + range))
                    .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new TriggerSetupException("Couldn't reach Google Sheets. Will try again.");
        }
        int status = response.statusCode();
        String body = response.body() == null ? "" : response.body();
        if (status == 401) throw new TriggerSetupException("Google no longer accepts this Sheets account. Reconnect it.");
        if (status == 403) throw new TriggerSetupException("This Google account can't open that spreadsheet. Share it with the account.");
        if (status == 404) throw new TriggerSetupException("Google Sheets couldn't find that spreadsheet. Check the link, and that it's shared with the account.");
        if (status == 400 && body.contains("Unable to parse range")) {
            throw new TriggerSetupException("The spreadsheet has no tab named \"" + sheet(s) + "\"");
        }
        if (status != 200) throw new TriggerSetupException("Google Sheets had a problem (HTTP " + status + "). Will try again.");
        List<List<String>> rows = new ArrayList<>();
        if (jsonMapper.readValue(body, Map.class).get("values") instanceof List<?> list) {
            for (Object r : list) {
                List<String> row = new ArrayList<>();
                if (r instanceof List<?> cells) cells.forEach(c -> row.add(c == null ? "" : String.valueOf(c)));
                rows.add(row);
            }
        }
        return rows;
    }

    static String spreadsheet(TriggerSubscription s) {
        String given = s.getConfig() == null || s.getConfig().get("spreadsheetId") == null ? "" : String.valueOf(s.getConfig().get("spreadsheetId")).trim();
        Matcher link = LINK.matcher(given);
        if (link.matches()) return link.group(1);
        if (ID.matcher(given).matches()) return given;
        throw new IllegalArgumentException(given.isEmpty() ? "Set the spreadsheet: paste its link" : "\"" + given + "\" isn't a Google Sheets link or ID");
    }

    private static String sheet(TriggerSubscription s) {
        Object v = s.getConfig() == null ? null : s.getConfig().get("sheet");
        return v == null || String.valueOf(v).isBlank() ? "Sheet1" : String.valueOf(v).trim();
    }

    // A short fingerprint of a row's values (trailing empty cells don't count).
    static String hash(List<String> row) {
        int end = row.size();
        while (end > 0 && row.get(end - 1).isEmpty()) end--;
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(String.join("\u0000", row.subList(0, end)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d, 0, 8);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
