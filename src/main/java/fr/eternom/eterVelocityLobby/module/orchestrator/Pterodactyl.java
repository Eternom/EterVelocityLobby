package fr.eternom.eterVelocityLobby.module.orchestrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Appels au panel Pterodactyl. Deux clés :
 * - Application (ptla_) : créer, lire, supprimer des serveurs, lire les ports d'un nœud et l'œuf ;
 * - Client (ptlc_, celle de l'utilisateur dédié aux lobbys) : fichiers et démarrage d'un serveur.
 * Bloquant : seulement depuis le fil de l'orchestrateur.
 */
final class Pterodactyl {

    record Allocation(int id, String ip, String alias, int port, boolean assigned) {
    }

    /** status : null = installé et prêt ; "installing", "install_failed", "suspended"... */
    record Server(int id, String identifier, String externalId, int owner, String status) {
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final String url;
    private final String applicationKey;
    private final String clientKey;

    Pterodactyl(String url, String applicationKey, String clientKey) {
        this.url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
        this.applicationKey = applicationKey;
        this.clientKey = clientKey;
    }

    // ---------- API Application ----------

    /** Ports du nœud (toutes les pages). */
    List<Allocation> allocations(int node) throws IOException {
        List<Allocation> all = new ArrayList<>();
        for (int page = 1; ; page++) {
            JsonObject result = application("GET", "/nodes/" + node + "/allocations?per_page=100&page=" + page, null);
            for (JsonElement element : result.getAsJsonArray("data")) {
                JsonObject a = element.getAsJsonObject().getAsJsonObject("attributes");
                all.add(new Allocation(a.get("id").getAsInt(), a.get("ip").getAsString(), text(a, "alias"),
                        a.get("port").getAsInt(), a.get("assigned").getAsBoolean()));
            }
            JsonObject pagination = result.getAsJsonObject("meta").getAsJsonObject("pagination");
            if (page >= pagination.get("total_pages").getAsInt()) {
                return all;
            }
        }
    }

    /** L'œuf, avec ses variables (valeurs par défaut des variables d'environnement). */
    JsonObject egg(int nest, int egg) throws IOException {
        return application("GET", "/nests/" + nest + "/eggs/" + egg + "?include=variables", null).getAsJsonObject("attributes");
    }

    Server create(JsonObject body) throws IOException {
        return toServer(application("POST", "/servers", body).getAsJsonObject("attributes"));
    }

    Optional<Server> server(int id) throws IOException {
        JsonObject result = applicationOrNull("GET", "/servers/" + id);
        return result == null ? Optional.empty() : Optional.of(toServer(result.getAsJsonObject("attributes")));
    }

    Optional<Server> serverByExternalId(String externalId) throws IOException {
        JsonObject result = applicationOrNull("GET", "/servers/external/" + URLEncoder.encode(externalId, StandardCharsets.UTF_8));
        return result == null ? Optional.empty() : Optional.of(toServer(result.getAsJsonObject("attributes")));
    }

    /** Serveurs dont cet utilisateur est propriétaire. */
    List<Server> serversOf(int user) throws IOException {
        JsonObject attributes = application("GET", "/users/" + user + "?include=servers", null).getAsJsonObject("attributes");
        List<Server> servers = new ArrayList<>();
        JsonArray data = attributes.getAsJsonObject("relationships").getAsJsonObject("servers").getAsJsonArray("data");
        data.forEach(element -> servers.add(toServer(element.getAsJsonObject().getAsJsonObject("attributes"))));
        return servers;
    }

    void delete(int id) throws IOException {
        try {
            application("DELETE", "/servers/" + id, null);
        } catch (IOException e) {
            application("DELETE", "/servers/" + id + "/force", null); // serveur injoignable : suppression forcée
        }
    }

    // ---------- API Client ----------

    /** Envoie des fichiers dans un dossier du serveur (URL signée du panel, envoi multipart). */
    void upload(String identifier, String directory, List<Path> files) throws IOException {
        String signed = client("GET", identifier, "/files/upload", null).getAsJsonObject("attributes").get("url").getAsString();
        String boundary = "EterLobby" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        for (Path file : files) {
            body.writeBytes(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\""
                    + file.getFileName() + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            body.writeBytes(Files.readAllBytes(file));
            body.writeBytes("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        body.writeBytes(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(signed + "&directory=" + URLEncoder.encode(directory, StandardCharsets.UTF_8)))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()))
                .build();
        check(send(request), "envoi de fichiers");
    }

    void decompress(String identifier, String file) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("root", "/");
        body.addProperty("file", file);
        client("POST", identifier, "/files/decompress", body);
    }

    void deleteFile(String identifier, String file) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("root", "/");
        JsonArray files = new JsonArray();
        files.add(file);
        body.add("files", files);
        client("POST", identifier, "/files/delete", body);
    }

    /** Écrit un fichier texte (les dossiers manquants sont créés par le panel). */
    void write(String identifier, String path, String content) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/client/servers/" + identifier
                        + "/files/write?file=" + URLEncoder.encode(path, StandardCharsets.UTF_8)))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + clientKey)
                .header("Accept", "application/json")
                .header("Content-Type", "text/plain")
                .POST(HttpRequest.BodyPublishers.ofString(content))
                .build();
        check(send(request), "écriture de " + path);
    }

    void power(String identifier, String signal) throws IOException {
        JsonObject body = new JsonObject();
        body.addProperty("signal", signal);
        client("POST", identifier, "/power", body);
    }

    // ---------- Outils ----------

    private JsonObject application(String method, String path, JsonObject body) throws IOException {
        return call(method, url + "/api/application" + path, applicationKey, body, false);
    }

    /** null si le serveur n'existe pas (404). */
    private JsonObject applicationOrNull(String method, String path) throws IOException {
        return call(method, url + "/api/application" + path, applicationKey, null, true);
    }

    private JsonObject client(String method, String identifier, String path, JsonObject body) throws IOException {
        return call(method, url + "/api/client/servers/" + identifier + path, clientKey, body, false);
    }

    private JsonObject call(String method, String target, String key, JsonObject body, boolean notFoundIsNull) throws IOException {
        HttpRequest request = HttpRequest.newBuilder(URI.create(target))
                .timeout(TIMEOUT)
                .header("Authorization", "Bearer " + key)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> response = send(request);
        if (notFoundIsNull && response.statusCode() == 404) {
            return null;
        }
        check(response, method + " " + target.substring(url.length()));
        String text = response.body();
        return text == null || text.isBlank() ? new JsonObject() : JsonParser.parseString(text).getAsJsonObject();
    }

    private HttpResponse<String> send(HttpRequest request) throws IOException {
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Appel au panel interrompu", e);
        }
    }

    private static void check(HttpResponse<String> response, String action) throws IOException {
        if (response.statusCode() / 100 != 2) {
            String body = response.body() == null ? "" : response.body();
            throw new IOException("Panel : " + action + " -> " + response.statusCode() + " " + body.substring(0, Math.min(300, body.length())));
        }
    }

    private static Server toServer(JsonObject a) {
        return new Server(a.get("id").getAsInt(), a.get("identifier").getAsString(), text(a, "external_id"),
                a.get("user").getAsInt(), text(a, "status"));
    }

    private static String text(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }
}
