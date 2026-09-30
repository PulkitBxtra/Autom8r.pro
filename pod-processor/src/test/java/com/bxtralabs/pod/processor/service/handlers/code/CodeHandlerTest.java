package com.bxtralabs.pod.processor.service.handlers.code;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.handlers.PermanentStepException;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class CodeHandlerTest {

    private final CodeSandbox sandbox = mock(CodeSandbox.class);
    private final CodeHandler handler = new CodeHandler(sandbox);
    private final GraphNode node = new GraphNode("c", "action", "Code", "act_code_groovy", "Run Groovy", CodeHandler.TYPE,
            Map.of(), null, "app_code", null, null);

    private static final List<Map<String, Object>> OUTPUTS = List.of(
            Map.of("key", "total", "label", "Total", "type", "number"),
            Map.of("key", "when", "label", "When", "type", "datetime"),
            Map.of("key", "lines", "label", "Lines", "type", "list", "fields", List.of(
                    Map.of("key", "sku", "label", "SKU", "type", "text"))));

    private Map<String, Object> input(Object inputs, Object timeout) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("script", "[total: amount * 2]");
        if (inputs != null) in.put("inputs", inputs);
        in.put("outputs", OUTPUTS);
        if (timeout != null) in.put("timeoutSeconds", timeout);
        return in;
    }

    private void answers(Object result, String logs) throws Exception {
        when(sandbox.run(anyString(), anyMap(), anyInt())).thenReturn(new CodeSandbox.Result(true, result, logs, null, null, null));
    }

    @Test
    void inputsBecomeVariablesAndTheResultIsTheOutputWithWhatItPrinted() throws Exception {
        answers(Map.of("total", 25, "when", "2026-09-30T10:00:00Z", "lines", List.of(Map.of("sku", "A1")), "extra", true), "hi\n");

        Map<String, Object> out = handler.execute(node, input(Map.of("amount", 12.5, "customer", Map.of("name", "ada")), 45));

        verify(sandbox).run("[total: amount * 2]", Map.of("amount", 12.5, "customer", Map.of("name", "ada")), CodeHandler.MAX_TIMEOUT);
        assertEquals(25, out.get("total"));
        assertEquals(true, out.get("extra"), "undeclared outputs are kept");
        assertEquals("hi\n", out.get("logs"));
    }

    @Test
    void theTimeoutDefaultsToTenSeconds() throws Exception {
        answers(null, "");
        assertEquals(Map.of("logs", ""), handler.execute(node, input(null, null)));
        verify(sandbox).run(anyString(), eq(Map.of()), eq(10));
    }

    @Test
    void aResultThatDoesNotMatchTheOutputsFailsTheStepSayingWhich() throws Exception {
        answers(Map.of("total", "12", "when", "tomorrow", "lines", List.of(Map.of("sku", 7))), "");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> handler.execute(node, input(null, null)));
        assertTrue(e.getMessage().contains("Output total should be a number, but the script returned text (\"12\")"), e.getMessage());
        assertTrue(e.getMessage().contains("Output when should be a date"), e.getMessage());
        assertTrue(e.getMessage().contains("Output lines.0.sku should be text, but the script returned a number (7)"), e.getMessage());
    }

    @Test
    void theScriptMustReturnAMap() throws Exception {
        answers(List.of(1, 2), "");
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> handler.execute(node, input(null, null)));
        assertTrue(e.getMessage().startsWith("The script must return a map of outputs"), e.getMessage());

        answers(Map.of("logs", "mine"), "");
        assertThrows(PermanentStepException.class, () -> handler.execute(node, input(null, null)));
    }

    @Test
    void scriptErrorsFailForGoodWithTheLineAndWhatWasPrinted() throws Exception {
        when(sandbox.run(anyString(), anyMap(), anyInt()))
                .thenReturn(new CodeSandbox.Result(false, null, "step 1\n", "No such property: amout", "script", 3));
        PermanentStepException e = assertThrows(PermanentStepException.class, () -> handler.execute(node, input(null, null)));
        assertEquals("Line 3: No such property: amout\nPrinted before it stopped:\nstep 1", e.getMessage());
    }

    @Test
    void aRunnerThatCannotStartIsRetried() throws Exception {
        when(sandbox.run(anyString(), anyMap(), anyInt())).thenThrow(new CodeSandbox.SandboxUnavailableException("no java"));
        Exception e = assertThrows(Exception.class, () -> handler.execute(node, input(null, null)));
        assertFalse(e instanceof PermanentStepException);
    }

    @Test
    void inputNamesMustBeVariableNames() throws Exception {
        answers(Map.of(), "");
        for (String bad : List.of("2fast", "first name", "class", "out", "it")) {
            assertThrows(PermanentStepException.class, () -> handler.execute(node, input(Map.of(bad, 1), null)), bad);
        }
        verifyNoInteractions(sandbox);
        assertNull(CodeHandler.checkName("order_total2"));
    }
}
