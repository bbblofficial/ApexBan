package dev.apexban.core.util;

import java.util.LinkedHashMap;
import java.util.Map;

/** Simple {@code %key%} placeholder replacement. */
public final class Placeholders {

    private Placeholders() {
    }

    public static String apply(String template, Map<String, String> values) {
        if (template == null || template.isEmpty() || values.isEmpty()) {
            return template == null ? "" : template;
        }
        String result = template;
        for (Map.Entry<String, String> e : values.entrySet()) {
            result = result.replace('%' + e.getKey() + '%', e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }

    /** Builds a map from alternating key/value arguments. */
    public static Map<String, String> of(String... kv) {
        if (kv.length % 2 != 0) {
            throw new IllegalArgumentException("Placeholders.of requires key/value pairs");
        }
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(kv[i], kv[i + 1]);
        }
        return map;
    }
}
