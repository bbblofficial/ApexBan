package dev.minestormban.core.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlMergerTest {

    @SuppressWarnings("unchecked")
    private static Map<String, Object> load(String yaml) {
        return (Map<String, Object>) new Yaml().load(yaml);
    }

    @Test
    void reportsMissingSectionsAndNestedKeys() {
        Map<String, Object> defaults = load("a: 1\nscope:\n  ban: server\ndb:\n  type: sqlite\n  auto-repair: true\n");
        Map<String, Object> user = load("a: 5\ndb:\n  type: mysql\n");
        List<String> missing = YamlMerger.missingPaths(user, defaults);
        assertEquals(List.of("scope", "db.auto-repair"), missing);
    }

    @Test
    void mergeKeepsUserValues() {
        Map<String, Object> defaults = load("a: 1\ndb:\n  type: sqlite\n  auto-repair: true\n");
        Map<String, Object> user = load("a: 5\ndb:\n  type: mysql\n");
        Map<String, Object> merged = YamlMerger.merge(user, defaults);
        assertEquals(5, merged.get("a"));
        @SuppressWarnings("unchecked")
        Map<String, Object> db = (Map<String, Object>) merged.get("db");
        assertEquals("mysql", db.get("type"));
        assertEquals(true, db.get("auto-repair"));
    }

    @Test
    void appendsTopLevelBlocksWithComments() {
        String defaults = "# header\nname: x\n\n# about scope\nscope:\n  ban: server\n  mute: server\n\n# next\nother: 1\n";
        String user = "name: custom\nother: 2\n";
        Optional<String> result = YamlMerger.appendTopLevelBlocks(user, defaults, List.of("scope"));
        assertTrue(result.isPresent());
        String text = result.get();
        assertTrue(text.contains("# about scope\nscope:\n  ban: server\n  mute: server\n"));
        assertTrue(text.startsWith("name: custom\nother: 2\n"));
        assertEquals(Map.of("ban", "server", "mute", "server"), load(text).get("scope"));
        assertEquals("custom", load(text).get("name"));
    }
}
