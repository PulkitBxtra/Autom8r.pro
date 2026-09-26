package com.bxtralabs.pod.processor.service.template;

import com.bxtralabs.pod.processor.model.StepRun;
import com.bxtralabs.pod.processor.model.StepStatus;
import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.model.graph.WorkflowGraph;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class TemplateResolverTest {

    private final TemplateResolver resolver = new TemplateResolver(JsonMapper.builder().build());

    private static StepRun step(String nodeId, StepStatus status, Map<String, Object> output) {
        StepRun s = new StepRun("exn_1", nodeId, 0);
        s.setStatus(status);
        s.setOutput(output);
        return s;
    }

    private final Map<String, Object> context = TemplateResolver.context(
            new WorkflowGraph(List.of(
                    new GraphNode("t", "trigger", "Gmail", "x", null, null, null, null),
                    new GraphNode("action-1", "action", "HTTP", "y", null, "http_request", null, null)), List.of()),
            List.of(
                    step("t", StepStatus.SUCCEEDED, Map.of("body", Map.of(
                            "order", Map.of("id", 42, "paid", true, "total", 19.5),
                            "email", "a@b.co",
                            "items", List.of(Map.of("name", "pen"), Map.of("name", "ink"))))),
                    step("action-1", StepStatus.SUCCEEDED, Map.of("status", 201, "body", Map.of("ticket", "T-9")))));

    @Test
    void wholeTemplateKeepsTheValuesType() {
        assertEquals(42, resolver.resolve("{{trigger.body.order.id}}", context));
        assertEquals(true, resolver.resolve("{{trigger.body.order.paid}}", context));
        assertEquals(Map.of("id", 42, "paid", true, "total", 19.5), resolver.resolve("{{trigger.body.order}}", context));
    }

    @Test
    void templateInsideTextIsInterpolated() {
        assertEquals("Order 42 for a@b.co", resolver.resolve("Order {{trigger.body.order.id}} for {{trigger.body.email}}", context));
    }

    @Test
    void objectsInsideTextBecomeJson() {
        assertEquals("items: [{\"name\":\"pen\"},{\"name\":\"ink\"}]", resolver.resolve("items: {{trigger.body.items}}", context));
    }

    @Test
    void readsEarlierStepOutputsIncludingHyphenatedIds() {
        assertEquals("T-9", resolver.resolve("{{steps.action-1.output.body.ticket}}", context));
        assertEquals(201, resolver.resolve("{{steps.action-1.output.status}}", context));
        assertEquals("SUCCEEDED", resolver.resolve("{{steps.action-1.status}}", context));
    }

    @Test
    void listIndexes() {
        assertEquals("ink", resolver.resolve("{{trigger.body.items.1.name}}", context));
        assertNull(resolver.resolve("{{trigger.body.items.5.name}}", context));
    }

    @Test
    void whitespaceInsideBracesIsIgnored() {
        assertEquals(42, resolver.resolve("{{   trigger.body.order.id  }}", context));
    }

    @Test
    void missingPathsResolveToNullOrEmptyInsteadOfFailing() {
        assertNull(resolver.resolve("{{trigger.body.nope}}", context));
        assertNull(resolver.resolve("{{steps.ghost.output.x}}", context));
        assertEquals("hi !", resolver.resolve("hi {{trigger.body.nope}}!", context));
    }

    @Test
    void resolvesInsideNestedMapsAndListsWithoutMutatingTheOriginal() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("to", "{{trigger.body.email}}");
        params.put("blocks", List.of(Map.of("text", "Order {{trigger.body.order.id}}"), "{{trigger.body.order.total}}"));
        params.put("count", 3);

        Map<String, Object> resolved = resolver.resolveParameters(params, context);

        assertEquals("a@b.co", resolved.get("to"));
        assertEquals(List.of(Map.of("text", "Order 42"), 19.5), resolved.get("blocks"));
        assertEquals(3, resolved.get("count"));
        assertEquals("{{trigger.body.email}}", params.get("to"), "original parameters untouched");
    }

    @Test
    void textWithoutTemplatesAndDollarSignsIsUnchanged() {
        assertEquals("costs $5 {not a template}", resolver.resolve("costs $5 {not a template}", context));
        assertEquals("$1 for 42", resolver.resolve("$1 for {{trigger.body.order.id}}", context));
    }

    @Test
    void nullParametersBecomeEmptyInput() {
        assertEquals(Map.of(), resolver.resolveParameters(null, context));
    }
}
