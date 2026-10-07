package fr.eternom.eterVelocityLobby.module.orchestrator;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;

/**
 * Dernière release GitHub d'un plugin (publiée par l'action de chaque dépôt), et son jar gardé dans cache/ : il n'est
 * téléchargé qu'une fois par version. Jeton GitHub facultatif (lecture seule) : sans lui, GitHub limite à 60 appels
 * par heure. Bloquant : seulement depuis le fil de l'orchestrateur.
 */
final class Releases {

    /** source = "Eternom/EterTab:EterTab-Paper" : dépôt, puis début du nom du jar (par défaut le nom du dépôt). */
    record Plugin(String repo, String assetPrefix) {

        static Plugin parse(String source) {
            String[] parts = source.trim().split(":", 2);
            String repo = parts[0];
            return new Plugin(repo, parts.length > 1 ? parts[1] : repo.substring(repo.indexOf('/') + 1));
        }
    }

    record Release(Plugin plugin, String tag, String assetName, String downloadUrl) {
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String token;
    private final Path cache;

    Releases(String token, Path cache) {
        this.token = token == null ? "" : token.trim();
        this.cache = cache;
    }

    Release latest(Plugin plugin) throws IOException {
        HttpResponse<String> response = send(request("https://api.github.com/repos/" + plugin.repo() + "/releases/latest", true)
                .header("Accept", "application/vnd.github+json").build(), HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("GitHub : release de " + plugin.repo() + " -> " + response.statusCode());
        }
        JsonObject release = JsonParser.parseString(response.body()).getAsJsonObject();
        String tag = release.get("tag_name").getAsString();
        for (JsonElement element : release.getAsJsonArray("assets")) {
            JsonObject asset = element.getAsJsonObject();
            String name = asset.get("name").getAsString();
            if (name.startsWith(plugin.assetPrefix() + "-") && name.endsWith(".jar")) {
                return new Release(plugin, tag, name, asset.get("browser_download_url").getAsString());
            }
        }
        throw new IOException("GitHub : pas de jar " + plugin.assetPrefix() + "-*.jar dans la release " + tag + " de " + plugin.repo());
    }

    /** Le jar de cette release, téléchargé si besoin. */
    Path jar(Release release) throws IOException {
        Path file = cache.resolve(release.assetName());
        if (Files.exists(file)) {
            return file;
        }
        Files.createDirectories(cache);
        Path temporary = cache.resolve(release.assetName() + ".part");
        HttpResponse<Path> response = send(request(release.downloadUrl(), false).header("Accept", "application/octet-stream").build(),
                HttpResponse.BodyHandlers.ofFile(temporary));
        if (response.statusCode() != 200) {
            Files.deleteIfExists(temporary);
            throw new IOException("GitHub : téléchargement de " + release.assetName() + " -> " + response.statusCode());
        }
        return Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** withToken : seulement pour l'API ; un jar est servi par un autre hébergeur (redirection) qui refuse le jeton. */
    private HttpRequest.Builder request(String target, boolean withToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(target)).timeout(Duration.ofMinutes(2));
        if (withToken && !token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) throws IOException {
        try {
            return http.send(request, handler);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Appel à GitHub interrompu", e);
        }
    }
}
