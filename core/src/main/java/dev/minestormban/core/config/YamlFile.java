package dev.minestormban.core.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Read-only dotted-path view over a parsed YAML document with a bundled-defaults fallback. */
public final class YamlFile {

    private final Map<String, Object> data;
    private final Map<String, Object> defaults;

    public YamlFile(Map<String, Object> data, Map<String, Object> defaults) {
        this.data = data == null ? Collections.emptyMap() : data;
        this.defaults = defaults == null ? Collections.emptyMap() : defaults;
    }

    public Object get(String path) {
        Object value = lookup(data, path);
        return value != null ? value : lookup(defaults, path);
    }

    public String getString(String path, String fallback) {
        Object value = get(path);
        return value == null ? fallback : String.valueOf(value);
    }

    public int getInt(String path, int fallback) {
        Object value = get(path);
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value).trim());
            } catch (NumberFormatException ignored) {
                // fall through to the fallback
            }
        }
        return fallback;
    }

    public boolean getBoolean(String path, boolean fallback) {
        Object value = get(path);
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value != null) {
            String s = String.valueOf(value).trim();
            if (s.equalsIgnoreCase("true")) {
                return true;
            }
            if (s.equalsIgnoreCase("false")) {
                return false;
            }
        }
        return fallback;
    }

    public List<String> getStringList(String path) {
        Object value = get(path);
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object o : list) {
                result.add(String.valueOf(o));
            }
            return result;
        }
        if (value instanceof String s) {
            return List.of(s);
        }
        return List.of();
    }

    @SuppressWarnings("unchecked")
    private static Object lookup(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = ((Map<String, Object>) map).get(part);
            if (current == null) {
                return null;
            }
        }
        return current;
    }
}
