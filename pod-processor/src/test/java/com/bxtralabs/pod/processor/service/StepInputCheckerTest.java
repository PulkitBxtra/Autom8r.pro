package com.bxtralabs.pod.processor.service;

import com.bxtralabs.pod.processor.model.graph.FieldSpec;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class StepInputCheckerTest {

    private final StepInputChecker checker = new StepInputChecker();

    private static final List<FieldSpec> FIELDS = List.of(
            new FieldSpec("url", "URL", "text", true, null, false),
            new FieldSpec("timeoutSeconds", "Timeout (seconds)", "number", false, null, false),
            new FieldSpec("send", "Send", "boolean", false, null, false),
            new FieldSpec("method", "Method", "select", true, List.of("GET", "POST"), false));

    private static GraphNode node(Map<String, Object> parameters, List<FieldSpec> fields) {
        return new GraphNode("a", "action", "HTTP", "act_http_request", "Make a Request", "http_request",
                parameters, null, "app_http", null, fields);
    }

    private static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void textFromTemplatesBecomesNumbersAndBooleans() throws Exception {
        GraphNode n = node(map("url", "x", "timeoutSeconds", "{{trigger.body.t}}", "send", "{{trigger.body.s}}", "method", "GET"), FIELDS);
        Map<String, Object> out = checker.check(n, map("url", "https://x", "timeoutSeconds", " 15 ", "send", "TRUE", "method", "GET"));
        assertEquals(15L, out.get("timeoutSeconds"));
        assertEquals(true, out.get("send"));
        assertEquals(2.5, checker.check(n, map("url", "u", "timeoutSeconds", "2.5", "method", "GET")).get("timeoutSeconds"));
    }

    @Test
    void anEmptyRequiredValueNamesTheTemplateItCameFrom() {
        GraphNode n = node(map("url", "{{trigger.body.link}}", "method", "GET"), FIELDS);
        Exception e = assertThrows(PermanentStepException.class, () -> checker.check(n, map("url", "", "method", "GET")));
        assertEquals("URL is empty (from {{trigger.body.link}})", e.getMessage());
    }

    @Test
    void wrongTypesFailWithWhatArrived() {
        GraphNode n = node(map("url", "u", "timeoutSeconds", "{{trigger.body.t}}", "method", "GET"), FIELDS);
        Exception e = assertThrows(PermanentStepException.class,
                () -> checker.check(n, map("url", "u", "timeoutSeconds", "soon", "method", "GET")));
        assertEquals("Timeout (seconds) must be a number, but got \"soon\" (from {{trigger.body.t}})", e.getMessage());

        assertThrows(PermanentStepException.class, () -> checker.check(n, map("url", "u", "send", "maybe", "method", "GET")));
        assertThrows(PermanentStepException.class, () -> checker.check(n, map("url", "u", "method", "TRACE")));
    }

    @Test
    void graphsSavedBeforeRulesAreLeftAlone() throws Exception {
        Map<String, Object> input = map("timeoutSeconds", "soon");
        assertSame(input, checker.check(node(map(), null), input));
    }
}
