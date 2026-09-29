package com.bxtralabs.pod.backend.catalog;

import java.util.List;
import java.util.Map;
import java.util.Set;

// One setting of a trigger or action, rendered as a form field in the step's Configure tab.
// Its value is saved under `key` in the step's parameters.
//   text, textarea  string (may contain {{...}} data from earlier steps)
//   number          number
//   boolean         true/false
//   select          one of options[].value
//   keyvalue        object of string -> string (headers, row values)
//   json            any JSON value
//   conditions      {"match": "all"|"any", "conditions": [{"left", "op", "right"}]} (Logic steps)
//   paths           [{"id", "name", "match", "conditions"}], each a named set of conditions (Logic steps)
// secret: never shown back once saved (see SecretMasker). On text the whole value; on keyvalue
// the values of sensitive-looking names (Authorization, X-Api-Key...).
// optionsFrom: a list pod-connector fills from the step's account (GET /connections/{id}/options/
// {source}), offered as choices on a text field. The value saved is the item's id; typing a value
// or inserting data still works.
public record CatalogField(
        String key,
        String label,
        String type,
        Boolean required,
        String placeholder,
        String help,
        List<Option> options,
        Object defaultValue,
        Boolean secret,
        String optionsFrom
) {
    // Most fields leave out "required", which means optional.
    public CatalogField {
        required = required != null && required;
        secret = secret != null && secret;
    }

    public static final Set<String> TYPES = Set.of("text", "textarea", "number", "boolean", "select", "keyvalue", "json",
            "conditions", "paths");

    // The lists pod-connector can fill, and the app whose accounts they come from.
    public static final Map<String, String> OPTION_SOURCES = Map.of(
            "slack.channels", "app_slack",
            "slack.users", "app_slack",
            "github.repos", "app_github",
            "notion.databases", "app_notion",
            "notion.pages", "app_notion",
            "notion.parents", "app_notion");

    public record Option(String value, String label) {
    }
}
