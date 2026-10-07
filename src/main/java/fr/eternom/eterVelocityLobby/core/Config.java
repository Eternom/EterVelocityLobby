package fr.eternom.eterVelocityLobby.core;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** config.yml du plugin ; une clé absente reprend la valeur du fichier fourni dans le jar. */
public class Config {

    private final Map<String, Object> values = new HashMap<>();

    public Config(Path dataDirectory) throws IOException {
        YamlFiles.saveDefault(dataDirectory, "config.yml");
        values.putAll(YamlFiles.loadResource("config.yml"));
        values.putAll(YamlFiles.load(dataDirectory.resolve("config.yml")));
    }

    public String getString(String key, String fallback) {
        Object value = values.get(key);
        return value == null ? fallback : value.toString();
    }

    public boolean getBoolean(String key, boolean fallback) {
        return values.get(key) instanceof Boolean bool ? bool : fallback;
    }

    public int getInt(String key, int fallback) {
        return values.get(key) instanceof Number number ? number.intValue() : fallback;
    }

    public double getDouble(String key, double fallback) {
        return values.get(key) instanceof Number number ? number.doubleValue() : fallback;
    }

    public List<String> getStringList(String key) {
        return values.get(key) instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    /** Clés directement sous section : "animations" -> [logo, dots]. */
    public List<String> getKeys(String section) {
        String prefix = section + ".";
        return values.keySet().stream()
                .filter(key -> key.startsWith(prefix))
                .map(key -> key.substring(prefix.length()).split("\\.")[0])
                .distinct()
                .toList();
    }
}
