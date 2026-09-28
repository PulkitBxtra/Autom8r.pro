package com.bxtralabs.pod.processor.service.logic;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static com.bxtralabs.pod.processor.service.logic.Conditions.check;
import static org.junit.jupiter.api.Assertions.*;

class ConditionsTest {

    @Test
    void textComparesIgnoringCaseAndSpaces() {
        assertTrue(check(" Paid ", "equals", "paid"));
        assertTrue(check("Refunded", "not_equals", "paid"));
        assertTrue(check("Order #42 shipped", "contains", "SHIPPED"));
        assertTrue(check("hello", "not_contains", "bye"));
        assertTrue(check("INV-001", "starts_with", "inv"));
        assertTrue(check("report.pdf", "ends_with", ".PDF"));
        assertTrue(check("DE", "in_list", "fr, de, it"));
        assertFalse(check("US", "in_list", "fr, de, it"));
    }

    @Test
    void numbersCompareAsNumbersWhenBothSidesAreNumbers() {
        assertTrue(check(1500, "gt", "1000"));
        assertTrue(check("1500", "gt", 1000));
        assertTrue(check("10", "gt", "9"), "numerically, not as text where \"10\" < \"9\"");
        assertTrue(check(5, "equals", "5.0"));
        assertTrue(check(3, "lte", 3));
        assertFalse(check(2, "gte", 3));
        assertTrue(check("b", "gt", "a"), "text falls back to alphabetical order");
    }

    @Test
    void emptinessAndBooleans() {
        assertTrue(check(null, "is_empty", null));
        assertTrue(check("  ", "is_empty", null));
        assertTrue(check(List.of(), "is_empty", null));
        assertTrue(check(Map.of("a", 1), "is_not_empty", null));
        assertTrue(check(true, "is_true", null));
        assertTrue(check("Yes", "is_true", null));
        assertTrue(check("false", "is_false", null));
        assertTrue(check(null, "is_false", null));
        assertTrue(check(List.of("vip", "eu"), "contains", "VIP"), "a list contains an equal item");
    }

    @Test
    void allNeedsEveryConditionAnyNeedsOne() {
        List<Map<String, Object>> rows = List.of(
                Map.of("left", 1500, "op", "gt", "right", "1000"),
                Map.of("left", "US", "op", "in_list", "right", "DE, FR"));
        assertFalse(Conditions.evaluate(Map.of("match", "all", "conditions", rows)).matched());
        Conditions.Outcome any = Conditions.evaluate(Map.of("match", "any", "conditions", rows));
        assertTrue(any.matched());
        assertEquals(List.of(true, false), any.conditions().stream().map(Conditions.Checked::result).toList());
        assertFalse(Conditions.evaluate(Map.of("match", "any", "conditions", List.of())).matched(), "no conditions never match");
    }
}
