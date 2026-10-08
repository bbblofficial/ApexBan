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
import java.util.Map;

/**
 * Loads config.yml, messages.yml and layout.yml from the plugin folder. Files are copied out of
 * the jar on first run; any key missing from a user's file falls back to the bundled default.
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
        return new YamlFile(user, defaults);
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
