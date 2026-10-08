package dev.minestormban.core.config;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.representer.Representer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Auto-missing" for YAML files: finds keys that exist in the bundled defaults but not in the
 * server owner's file, and adds them without touching any value the owner already set.
 */
final class YamlMerger {

    private YamlMerger() {
    }

    /**
     * Dotted paths of every key present in {@code defaults} but absent from {@code user}. When a
     * whole section is missing only the section itself is reported (e.g. {@code scope}), not its children.
     */
    @SuppressWarnings("unchecked")
    static List<String> missingPaths(Map<String, Object> user, Map<String, Object> defaults) {
        List<String> missing = new ArrayList<>();
        collect("", user, defaults, missing);
        return missing;
    }

    @SuppressWarnings("unchecked")
    private static void collect(String prefix, Map<String, Object> user, Map<String, Object> defaults,
                                List<String> out) {
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (!user.containsKey(key) || user.get(key) == null) {
                out.add(path);
            } else if (entry.getValue() instanceof Map<?, ?> defaultChild
                    && user.get(key) instanceof Map<?, ?> userChild) {
                collect(path, (Map<String, Object>) userChild, (Map<String, Object>) defaultChild, out);
            }
        }
    }

    /** Deep merge: every user value is kept, missing defaults are appended. Input maps are not modified. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> merge(Map<String, Object> user, Map<String, Object> defaults) {
        Map<String, Object> result = new LinkedHashMap<>(user);
        for (Map.Entry<String, Object> entry : defaults.entrySet()) {
            String key = entry.getKey();
            Object current = result.get(key);
            if (current == null) {
                result.put(key, entry.getValue());
            } else if (current instanceof Map<?, ?> userChild && entry.getValue() instanceof Map<?, ?> defaultChild) {
                result.put(key, merge((Map<String, Object>) userChild, (Map<String, Object>) defaultChild));
            }
        }
        return result;
    }

    /**
     * Appends the bundled text block of each missing top-level key to the user's file, which keeps
     * every comment and every custom value exactly as it was.
     *
     * @return empty when a block could not be located in the bundled text
     */
    static Optional<String> appendTopLevelBlocks(String userText, String defaultText, List<String> topLevelKeys) {
        String[] lines = defaultText.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder(userText.replace("\r\n", "\n"));
        if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
            out.append('\n');
        }
        for (String key : topLevelKeys) {
            int start = -1;
            for (int i = 0; i < lines.length; i++) {
                if (lines[i].startsWith(key + ":")) {
                    start = i;
                    break;
                }
            }
            if (start < 0) {
                return Optional.empty();
            }
            // Include the comment lines directly above the key.
            int from = start;
            while (from > 0 && lines[from - 1].startsWith("#")) {
                from--;
            }
            int end = start + 1;
            while (end < lines.length) {
                String line = lines[end];
                boolean topLevel = !line.isEmpty() && !Character.isWhitespace(line.charAt(0))
                        && !line.startsWith("#") && !line.startsWith("-");
                if (topLevel) {
                    break;
                }
                end++;
            }
            // Trailing comment/blank lines belong to the next key; trim them off.
            while (end > start + 1 && (lines[end - 1].isBlank() || lines[end - 1].startsWith("#"))) {
                end--;
            }
            out.append('\n');
            for (int i = from; i < end; i++) {
                out.append(lines[i]).append('\n');
            }
        }
        return Optional.of(out.toString());
    }

    /** Serialises a merged document, keeping the user's leading comment block (if any). */
    static String dump(Map<String, Object> merged, String originalText) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setIndent(2);
        options.setSplitLines(false);
        options.setAllowUnicode(true);
        Yaml yaml = new Yaml(new Representer(options), options);

        StringBuilder header = new StringBuilder();
        for (String line : originalText.replace("\r\n", "\n").split("\n", -1)) {
            if (line.startsWith("#") || (line.isBlank() && header.length() > 0)) {
                header.append(line).append('\n');
            } else {
                break;
            }
        }
        return header + yaml.dump(merged);
    }
}
