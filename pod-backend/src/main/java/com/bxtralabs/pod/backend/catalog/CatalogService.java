package com.bxtralabs.pod.backend.catalog;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.regex.Pattern;

// The app catalog: which apps, triggers and actions exist and what settings each takes. Read
// from resources/catalog/apps.json at startup, so it changes with the code that runs it. A
// broken catalog stops the pod from starting instead of showing users half an app.
@Service
public class CatalogService {

    static final String RESOURCE = "catalog/apps.json";
    private static final Pattern KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");

    private final List<CatalogApp> apps;
    private final Map<String, CatalogApp.Trigger> triggers = new HashMap<>();
    private final Map<String, CatalogApp.Action> actions = new HashMap<>();
    private final Map<String, CatalogApp> appOfItem = new HashMap<>();

    @Autowired
    public CatalogService(JsonMapper jsonMapper) throws IOException {
        this(load(jsonMapper));
    }

    CatalogService(List<CatalogApp> apps) {
        this.apps = List.copyOf(apps);
        index();
    }

    private static List<CatalogApp> load(JsonMapper jsonMapper) throws IOException {
        try (InputStream in = new ClassPathResource(RESOURCE).getInputStream()) {
            return jsonMapper.readValue(in, CatalogFile.class).apps();
        }
    }

    private record CatalogFile(List<CatalogApp> apps) {
    }

    public List<CatalogApp> apps() {
        return apps;
    }

    public Optional<CatalogApp.Trigger> trigger(String id) {
        return Optional.ofNullable(triggers.get(id));
    }

    public Optional<CatalogApp.Action> action(String id) {
        return Optional.ofNullable(actions.get(id));
    }

    // The app a trigger or action belongs to.
    public Optional<CatalogApp> appOf(String itemId) {
        return Optional.ofNullable(appOfItem.get(itemId));
    }

    private void index() {
        if (apps.isEmpty()) {
            throw invalid("has no apps");
        }
        Set<String> appIds = new HashSet<>();
        for (CatalogApp app : apps) {
            require(app.id(), "an app without an id");
            require(app.name(), "app " + app.id() + " without a name");
            if (!appIds.add(app.id())) {
                throw invalid("has app " + app.id() + " twice");
            }
            if (!Set.of(CatalogApp.CONNECTION_NONE, CatalogApp.CONNECTION_OPTIONAL, CatalogApp.CONNECTION_REQUIRED)
                    .contains(app.connection())) {
                throw invalid("has app " + app.id() + " with unknown connection setting " + app.connection());
            }
            for (CatalogApp.Trigger t : nonNull(app.triggers())) {
                addItem(app, t.id(), t.name(), t.fields());
                checkOutputs(t.id(), t.outputs());
                triggers.put(t.id(), t);
            }
            for (CatalogApp.Action a : nonNull(app.actions())) {
                addItem(app, a.id(), a.name(), a.fields());
                checkOutputs(a.id(), a.outputs());
                require(a.handler(), "action " + a.id() + " without a handler");
                actions.put(a.id(), a);
            }
        }
    }

    private void addItem(CatalogApp app, String id, String name, List<CatalogField> fields) {
        require(id, "an item of " + app.id() + " without an id");
        require(name, id + " without a name");
        if (appOfItem.putIfAbsent(id, app) != null) {
            throw invalid("has item " + id + " twice");
        }
        Set<String> keys = new HashSet<>();
        for (CatalogField f : nonNull(fields)) {
            String where = id + "." + f.key();
            if (f.key() == null || !KEY.matcher(f.key()).matches()) {
                throw invalid("has a field of " + id + " with a bad key: " + f.key());
            }
            if (!keys.add(f.key())) {
                throw invalid("has field " + where + " twice");
            }
            require(f.label(), "field " + where + " without a label");
            if (!CatalogField.TYPES.contains(f.type())) {
                throw invalid("has field " + where + " of unknown type " + f.type());
            }
            boolean select = "select".equals(f.type());
            if (select == (f.options() == null || f.options().isEmpty())) {
                throw invalid(select ? "has select " + where + " without options" : "has options on non-select " + where);
            }
            if (f.secret() && !"text".equals(f.type()) && !"keyvalue".equals(f.type())) {
                throw invalid("marks " + where + " secret, which only text and keyvalue fields can be");
            }
            if (select && f.defaultValue() != null
                    && f.options().stream().noneMatch(o -> o.value().equals(f.defaultValue()))) {
                throw invalid("has select " + where + " whose default isn't one of its options");
            }
        }
    }

    private static void checkOutputs(String where, List<CatalogOutput> outputs) {
        Set<String> keys = new HashSet<>();
        for (CatalogOutput o : outputs) {
            String at = where + " output " + o.key();
            if (o.key() == null || !KEY.matcher(o.key()).matches()) {
                throw invalid("has an output of " + where + " with a bad key: " + o.key());
            }
            if (!keys.add(o.key())) {
                throw invalid("has " + at + " twice");
            }
            require(o.label(), at + " without a label");
            if (!CatalogOutput.TYPES.contains(o.type())) {
                throw invalid("has " + at + " of unknown type " + o.type());
            }
            if (!o.fields().isEmpty() && !"object".equals(o.type()) && !"list".equals(o.type())) {
                throw invalid("has fields on " + at + ", which only object and list outputs can have");
            }
            checkOutputs(where + " output " + o.key(), o.fields());
        }
    }

    private static <T> List<T> nonNull(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static void require(String value, String what) {
        if (value == null || value.isBlank()) {
            throw invalid("has " + what);
        }
    }

    private static IllegalStateException invalid(String problem) {
        return new IllegalStateException("App catalog (" + RESOURCE + ") " + problem);
    }
}
