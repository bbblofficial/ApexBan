package dev.minestormban.core.config;

import dev.minestormban.core.platform.MineStormLogger;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/**
 * Loads config.yml, messages.yml and layout.yml from the plugin folder. Files are copied out of
 * the jar on first run. Any key missing from a user's file is added to that file automatically
 * (see {@link YamlMerger}) and also falls back to the bundled default.
 */
public final class ConfigManager {

    private final Path folder;
    private final MineStormLogger logger;

    private volatile YamlFile config;
    private volatile YamlFile messages;
    private volatile YamlFile layout;

    public ConfigManager(Path folder, MineStormLogger logger) {
        this.folder = folder;
        this.logger = logger;
    }

    public void load() throws IOException {
        Files.createDirectories(folder);
        YamlFile newConfig = loadFile("config.yml");
        YamlFile newMessages = loadFile("messages.yml");
        YamlFile newLayout = loadFile("layout.yml");
        this.config = newConfig;
        this.messages = newMessages;
        this.layout = newLayout;
    }

    public YamlFile config() {
        return config;
    }

    public YamlFile messages() {
        return messages;
    }

    public YamlFile layout() {
        return layout;
    }

    private YamlFile loadFile(String name) throws IOException {
        Path target = folder.resolve(name);
        if (Files.notExists(target)) {
            try (InputStream in = open(name)) {
                Files.copy(in, target);
            }
        }
        Map<String, Object> defaults = parseResource(name);
        Map<String, Object> user;
        try (Reader reader = Files.newBufferedReader(target, StandardCharsets.UTF_8)) {
            user = parse(reader);
        } catch (RuntimeException ex) {
            logger.error("Could not parse " + name + "; falling back to bundled defaults", ex);
            user = null;
        }
        if (user != null && defaults != null) {
            user = completeMissing(name, target, user, defaults);
        }
        return new YamlFile(user, defaults);
    }

    /**
     * Auto-missing for config files: every key that exists in the bundled file but not in the
     * server's copy is written into the server's file. Existing values are never changed and the
     * previous file is saved as {@code <name>.bak}.
     */
    private Map<String, Object> completeMissing(String name, Path target, Map<String, Object> user,
                                                Map<String, Object> defaults) {
        List<String> missing = YamlMerger.missingPaths(user, defaults);
        if (missing.isEmpty()) {
            return user;
        }
        Map<String, Object> merged = YamlMerger.merge(user, defaults);
        try {
            String userText = Files.readString(target, StandardCharsets.UTF_8);
            String defaultText = readResourceText(name);

            String newText = null;
            boolean onlyTopLevel = missing.stream().noneMatch(path -> path.contains("."));
            if (onlyTopLevel) {
                // Keeps comments and formatting: the bundled text of the missing sections is appended.
                newText = YamlMerger.appendTopLevelBlocks(userText, defaultText, missing).orElse(null);
            }
            if (newText == null) {
                newText = YamlMerger.dump(merged, userText);
            }

            // Never write something we cannot read back.
            try (Reader check = new java.io.StringReader(newText)) {
                if (parse(check) == null) {
                    throw new IllegalStateException("result is empty");
                }
            }
            Files.copy(target, folder.resolve(name + ".bak"), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(target, newText, StandardCharsets.UTF_8);
            logger.info("[auto-missing] " + name + ": added " + missing.size() + " missing key(s): "
                    + String.join(", ", missing) + " (backup: " + name + ".bak)");
        } catch (IOException | RuntimeException ex) {
            logger.warn("[auto-missing] could not update " + name + " on disk (" + ex.getMessage()
                    + "); the missing keys are used from memory for now");
        }
        return merged;
    }

    private String readResourceText(String name) throws IOException {
        try (InputStream in = open(name)) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private Map<String, Object> parseResource(String name) throws IOException {
        try (InputStream in = open(name);
             Reader reader = new java.io.InputStreamReader(in, StandardCharsets.UTF_8)) {
            return parse(reader);
        }
    }

    private InputStream open(String name) throws IOException {
        InputStream in = ConfigManager.class.getResourceAsStream("/" + name);
        if (in == null) {
            throw new IOException("Bundled resource missing: " + name);
        }
        return in;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parse(Reader reader) {
        Yaml yaml = new Yaml(new SafeConstructor(new LoaderOptions()));
        Object loaded = yaml.load(reader);
        if (loaded instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }
}
