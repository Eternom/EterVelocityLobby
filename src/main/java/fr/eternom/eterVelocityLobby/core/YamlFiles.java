package fr.eternom.eterVelocityLobby.core;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lecture des fichiers YAML du plugin (config, langues) avec SnakeYAML, fourni par Velocity.
 * Les clés imbriquées se lisent avec des points : "announce.join".
 */
public final class YamlFiles {

    private YamlFiles() {
    }

    /** Copie le fichier du jar dans le dossier du plugin s'il n'y est pas encore (jamais d'écrasement). */
    public static void saveDefault(Path dataDirectory, String resource) throws IOException {
        Path target = dataDirectory.resolve(resource);
        if (Files.exists(target)) {
            return;
        }
        Files.createDirectories(target.getParent());
        try (InputStream in = YamlFiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IOException("Fichier absent du jar : " + resource);
            }
            Files.copy(in, target);
        }
    }

    public static Map<String, Object> load(Path file) throws IOException {
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return flatten(new Yaml().load(reader));
        }
    }

    /** Fichier fourni dans le jar (valeurs par défaut), vide s'il n'existe pas. */
    public static Map<String, Object> loadResource(String resource) throws IOException {
        try (InputStream in = YamlFiles.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                return Map.of();
            }
            return flatten(new Yaml().load(new InputStreamReader(in, StandardCharsets.UTF_8)));
        }
    }

    /** { announce: { join: "..." } } -> { "announce.join": "..." } ; les listes restent des listes. */
    private static Map<String, Object> flatten(Object root) {
        Map<String, Object> flat = new HashMap<>();
        if (root instanceof Map<?, ?> map) {
            flatten("", map, flat);
        }
        return flat;
    }

    private static void flatten(String prefix, Map<?, ?> map, Map<String, Object> flat) {
        map.forEach((key, value) -> {
            String path = prefix + key;
            if (value instanceof Map<?, ?> child) {
                flatten(path + ".", child, flat);
            } else {
                flat.put(path, value instanceof List<?> list ? List.copyOf(list) : value);
            }
        });
    }
}
