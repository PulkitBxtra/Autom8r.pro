package com.bxtralabs.pod.processor.service.logic;

import java.math.BigDecimal;
import java.util.*;

// Evaluates a Logic step's conditions: rows of [left] [op] [right] joined by "all" (AND) or
// "any" (OR). left/right arrive with templates already filled in. Comparing text ignores case
// and surrounding spaces; gt/gte/lt/lte compare as numbers when both sides are numbers,
// otherwise as text. Each result carries what was compared, so the run can explain a decision.
public final class Conditions {

    public static final Set<String> OPERATORS = Set.of(
            "equals", "not_equals", "contains", "not_contains", "starts_with", "ends_with",
            "gt", "gte", "lt", "lte", "is_empty", "is_not_empty", "is_true", "is_false", "in_list");
    // Operators that don't use the right-hand value.
    public static final Set<String> UNARY = Set.of("is_empty", "is_not_empty", "is_true", "is_false");

    private Conditions() {
    }

    public record Checked(Object left, String op, Object right, boolean result) {
    }

    public record Outcome(boolean matched, String match, List<Checked> conditions) {
    }

    // group: {"match": "all" | "any", "conditions": [{"left", "op", "right"}]}
    public static Outcome evaluate(Object group) {
        Map<?, ?> g = group instanceof Map<?, ?> m ? m : Map.of();
        String match = "any".equals(g.get("match")) ? "any" : "all";
        List<Checked> checked = new ArrayList<>();
        if (g.get("conditions") instanceof List<?> rows) {
            for (Object row : rows) {
                if (row instanceof Map<?, ?> c) {
                    String op = String.valueOf(c.get("op"));
                    Object left = c.get("left");
                    Object right = UNARY.contains(op) ? null : c.get("right");
                    checked.add(new Checked(left, op, right, check(left, op, right)));
                }
            }
        }
        boolean matched = !checked.isEmpty() && ("any".equals(match)
                ? checked.stream().anyMatch(Checked::result)
                : checked.stream().allMatch(Checked::result));
        return new Outcome(matched, match, checked);
    }

    static boolean check(Object left, String op, Object right) {
        String l = text(left);
        String r = text(right);
        return switch (op) {
            case "equals" -> equal(left, right);
            case "not_equals" -> !equal(left, right);
            case "contains" -> contains(left, r);
            case "not_contains" -> !contains(left, r);
            case "starts_with" -> l.startsWith(r);
            case "ends_with" -> l.endsWith(r);
            case "gt" -> compare(left, right) > 0;
            case "gte" -> compare(left, right) >= 0;
            case "lt" -> compare(left, right) < 0;
            case "lte" -> compare(left, right) <= 0;
            case "is_empty" -> isEmpty(left);
            case "is_not_empty" -> !isEmpty(left);
            case "is_true" -> Set.of("true", "yes", "1", "on").contains(l);
            case "is_false" -> Set.of("false", "no", "0", "off", "").contains(l);
            case "in_list" -> Arrays.stream(r.split(",")).map(String::trim).anyMatch(l::equals);
            default -> false;
        };
    }

    private static boolean equal(Object left, Object right) {
        BigDecimal a = number(left), b = number(right);
        if (a != null && b != null) {
            return a.compareTo(b) == 0;
        }
        return text(left).equals(text(right));
    }

    // A list contains an item equal to the value; text contains the value as a substring.
    private static boolean contains(Object left, String right) {
        if (left instanceof Collection<?> list) {
            return list.stream().anyMatch(item -> text(item).equals(right));
        }
        return text(left).contains(right);
    }

    private static int compare(Object left, Object right) {
        BigDecimal a = number(left), b = number(right);
        if (a != null && b != null) {
            return a.compareTo(b);
        }
        return text(left).compareTo(text(right));
    }

    private static boolean isEmpty(Object v) {
        return v == null
                || (v instanceof String s && s.isBlank())
                || (v instanceof Collection<?> c && c.isEmpty())
                || (v instanceof Map<?, ?> m && m.isEmpty());
    }

    private static BigDecimal number(Object v) {
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof String s && !s.isBlank()) {
            try {
                return new BigDecimal(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private static String text(Object v) {
        return v == null ? "" : String.valueOf(v).trim().toLowerCase(Locale.ROOT);
    }
}
