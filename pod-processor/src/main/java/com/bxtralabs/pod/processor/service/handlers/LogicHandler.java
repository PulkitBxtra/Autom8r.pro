package com.bxtralabs.pod.processor.service.handlers;

import com.bxtralabs.pod.processor.model.graph.GraphNode;
import com.bxtralabs.pod.processor.service.logic.Conditions;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.*;

// The Logic app's steps. Each decides which of its outputs continue and says why:
//   logic.switch   paths in order, the first that matches, else "otherwise"
//   logic.paths    every path that matches (none: nothing after it runs)
//   logic.if_else  "if" when its conditions match, else "else"
//   logic.filter   "pass" when its conditions match (else nothing after it runs)
// output: {"paths": {outputId: taken?}, "matched": [names], "explain": [...]}. The orchestrator
// follows an edge out of a Logic step only if paths[edge.sourceHandle] is true (see
// ConditionEvaluator). No match isn't an error: the steps after it are skipped and the run
// still succeeds.
@Component
@Order(1)
public class LogicHandler implements ActionHandler {

    public static final String PREFIX = "logic.";
    public static final String OTHERWISE = "otherwise";

    public static boolean isLogic(String type) {
        return type != null && type.startsWith(PREFIX);
    }

    @Override
    public boolean supports(GraphNode node) {
        return isLogic(node.type());
    }

    @Override
    public Map<String, Object> execute(GraphNode node, Map<String, Object> input) throws PermanentStepException {
        Map<String, Boolean> taken = new LinkedHashMap<>();
        List<String> matched = new ArrayList<>();
        List<Map<String, Object>> explain = new ArrayList<>();

        switch (node.type()) {
            case "logic.switch", "logic.paths" -> {
                boolean firstOnly = node.type().equals("logic.switch");
                boolean any = false;
                for (Object p : input.get("paths") instanceof List<?> l ? l : List.of()) {
                    if (!(p instanceof Map<?, ?> path)) continue;
                    String id = String.valueOf(path.get("id"));
                    String name = String.valueOf(path.get("name"));
                    // A Switch stops at the first match; later paths aren't checked.
                    if (firstOnly && any) {
                        taken.put(id, false);
                        explain.add(Map.of("output", id, "name", name, "checked", false));
                        continue;
                    }
                    Conditions.Outcome o = Conditions.evaluate(path);
                    taken.put(id, o.matched());
                    explain.add(describe(id, name, o));
                    if (o.matched()) {
                        any = true;
                        matched.add(name);
                    }
                }
                if (firstOnly) {
                    taken.put(OTHERWISE, !any);
                    if (!any) matched.add("Otherwise");
                }
            }
            case "logic.if_else" -> {
                Conditions.Outcome o = Conditions.evaluate(input.get("conditions"));
                taken.put("if", o.matched());
                taken.put("else", !o.matched());
                matched.add(o.matched() ? "If" : "Else");
                explain.add(describe("if", "If", o));
            }
            case "logic.filter" -> {
                Conditions.Outcome o = Conditions.evaluate(input.get("conditions"));
                taken.put("pass", o.matched());
                if (o.matched()) matched.add("Continue");
                explain.add(describe("pass", "Continue", o));
            }
            default -> throw new PermanentStepException("Unknown Logic step: " + node.type());
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("paths", taken);
        output.put("matched", matched);
        output.put("explain", explain);
        return output;
    }

    private static Map<String, Object> describe(String id, String name, Conditions.Outcome o) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Conditions.Checked c : o.conditions()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("left", c.left());
            row.put("op", c.op());
            row.put("right", c.right());
            row.put("result", c.result());
            rows.add(row);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("output", id);
        out.put("name", name);
        out.put("checked", true);
        out.put("matched", o.matched());
        out.put("match", o.match());
        out.put("conditions", rows);
        return out;
    }
}
