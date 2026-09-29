package com.bxtralabs.pod.backend.catalog;

import java.util.List;
import java.util.Set;

// One piece of data a trigger starts with or an action returns, listed in the data picker so
// later steps can use it without knowing the path by heart ({{steps.<id>.output.url}}).
//   text, number, boolean, datetime   plain values (datetime is ISO 8601 text)
//   object                            fields: what it holds
//   list                              fields: what each item holds, if items are objects;
//                                     none for a list of plain values
//   any                               whatever the other side sent (an HTTP response body)
// Declaring outputs doesn't limit what a step may read: a path it doesn't list still resolves.
public record CatalogOutput(
        String key,
        String label,
        String type,
        List<CatalogOutput> fields
) {
    public CatalogOutput {
        fields = fields == null ? List.of() : List.copyOf(fields);
    }

    public static final Set<String> TYPES = Set.of("text", "number", "boolean", "datetime", "object", "list", "any");
}
