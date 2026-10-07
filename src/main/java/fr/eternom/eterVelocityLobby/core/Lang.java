package fr.eternom.eterVelocityLobby.core;

import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Fichiers de langue : plugins/etervelocitylobby/lang/<locale>.yml (codes Minecraft : en_us, fr_fr...), comme dans EterLib.
 * Ceux du jar sont copiés au premier démarrage et servent de valeurs par défaut ; on ajoute une langue en déposant
 * un fichier. Recherche : langue exacte, même langue d'une autre région (fr_ca -> fr_fr), puis langue par défaut.
 */
public class Lang {

    private static final String[] BUNDLED = {"en_us", "fr_fr"};

    private final Map<String, Map<String, Object>> languages = new HashMap<>();
    private final String defaultLocale;

    public Lang(Path dataDirectory, String defaultLocale, Logger logger) throws IOException {
        this.defaultLocale = defaultLocale.toLowerCase(Locale.ROOT);
        for (String locale : BUNDLED) {
            YamlFiles.saveDefault(dataDirectory, "lang/" + locale + ".yml");
        }
        try (Stream<Path> files = Files.list(dataDirectory.resolve("lang"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".yml")).toList()) {
                String locale = file.getFileName().toString().replace(".yml", "").toLowerCase(Locale.ROOT);
                Map<String, Object> values = new HashMap<>(YamlFiles.loadResource("lang/" + locale + ".yml"));
                values.putAll(YamlFiles.load(file));
                languages.put(locale, values);
            }
        }
        if (!languages.containsKey(this.defaultLocale)) {
            throw new IOException("Langue par défaut introuvable : lang/" + this.defaultLocale + ".yml");
        }
        logger.info("Langues : {} (défaut : {})", String.join(", ", languages.keySet()), this.defaultLocale);
    }

    /** Texte brut (MiniMessage) ; une liste YAML est rendue en lignes séparées par \n. null si absent partout. */
    public String get(String locale, String key) {
        Object value = find(locale, key);
        if (value == null) {
            String language = locale.split("_")[0] + "_";
            value = languages.entrySet().stream()
                    .filter(entry -> entry.getKey().startsWith(language))
                    .map(entry -> entry.getValue().get(key))
                    .filter(found -> found != null)
                    .findFirst()
                    .orElse(null);
        }
        if (value == null) {
            value = find(defaultLocale, key);
        }
        if (value instanceof Iterable<?> lines) {
            StringBuilder joined = new StringBuilder();
            lines.forEach(line -> joined.append(joined.isEmpty() ? "" : "\n").append(line));
            return joined.toString();
        }
        return value == null ? null : value.toString();
    }

    public String getDefaultLocale() {
        return defaultLocale;
    }

    private Object find(String locale, String key) {
        Map<String, Object> values = languages.get(locale);
        return values == null ? null : values.get(key);
    }
}
