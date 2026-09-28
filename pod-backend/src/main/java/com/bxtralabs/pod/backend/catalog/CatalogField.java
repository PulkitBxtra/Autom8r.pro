package com.bxtralabs.pod.backend.catalog;

import java.util.List;
import java.util.Set;

// One setting of a trigger or action, rendered as a form field in the step's Configure tab.
// Its value is saved under `key` in the step's parameters.
//   text, textarea  string (may contain {{...}} data from earlier steps)
//   number          number
//   boolean         true/false
//   select          one of options[].value
//   keyvalue        object of string -> string (headers, row values)
//   json            any JSON value
public record CatalogField(
        String key,
        String label,
        String type,
        Boolean required,
        String placeholder,
        String help,
        List<Option> options,
        Object defaultValue
) {
    // Most fields leave out "required", which means optional.
    public CatalogField {
        required = required != null && required;
    }

    public static final Set<String> TYPES = Set.of("text", "textarea", "number", "boolean", "select", "keyvalue", "json");

    public record Option(String value, String label) {
    }
}
