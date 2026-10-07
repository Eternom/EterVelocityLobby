package fr.eternom.eterVelocityLobby.module.orchestrator;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import fr.eternom.eterVelocityLobby.core.Config;
import fr.eternom.eterVelocityLobby.core.YamlFiles;
import fr.eternom.eterVelocityLobby.module.orchestrator.LobbyStore.Row;
import fr.eternom.eterVelocityLobby.module.orchestrator.LobbyStore.State;
import fr.eternom.eterVelocityLobby.module.orchestrator.Pterodactyl.Allocation;
import fr.eternom.eterVelocityLobby.module.orchestrator.Pterodactyl.Server;
import fr.eternom.eterVelocityLobby.module.orchestrator.Releases.Plugin;
import fr.eternom.eterVelocityLobby.module.orchestrator.Releases.Release;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Lobbys jetables sur Pterodactyl : créés à partir du modèle (template/lobby.zip ou lobby.tar.gz + dernières releases des plugins +
 * template/EterLib-config.yml), ajoutés à Velocity quand ils répondent, vidés puis supprimés quand ils ne servent plus.
 *
 * Règles (toutes les 30 s) : jamais moins de `minimum` lobbys à jour ; un de plus quand ils sont remplis à
 * `scale-up-at` ; un lobby en trop vide depuis `idle-minutes` est vidé ; un lobby d'une ancienne version (modèle ou
 * plugin) est vidé dès que les lobbys à jour suffisent ; un lobby qui ne répond plus depuis 5 min est vidé. « Vidé » :
 * plus aucun nouveau joueur, supprimé quand il est vide ou après `drain-timeout-minutes` (joueurs envoyés ailleurs).
 *
 * SÉCURITÉ (le panel héberge aussi les serveurs des clients) : un serveur n'est supprimé que s'il est dans la table
 * eterlobby_servers, appartient à l'utilisateur dédié ET porte l'identifiant externe eterlobby:<nom>. Au moindre doute :
 * erreur dans la console, rien n'est supprimé. En mode essai (dry-run), tout est seulement écrit dans la console.
 * Tout passe par un seul fil (worker) : jamais deux actions en même temps.
 */
public class Orchestrator {

    private static final String EXTERNAL_PREFIX = "eterlobby:";
    private static final Duration VERSION_REFRESH = Duration.ofMinutes(10);
    private static final Duration UNREACHABLE_LIMIT = Duration.ofMinutes(5);
    private static final Duration INSTALL_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration START_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration DRY_RUN_REPEAT = Duration.ofMinutes(10);

    /** Réglages de config.yml > orchestrator. */
    record Settings(boolean dryRun, String namePrefix, int minimum, int maximum, int capacity, double scaleUpAt,
                    Duration idle, Duration drainTimeout, int owner, int nest, int egg, String dockerImage,
                    String startup, Map<String, String> environment, int memory, int cpu, int disk,
                    int location, String portRange, String connectHost, List<Plugin> plugins) {
    }

    private final EterVelocityLobby plugin;
    private final Logger logger;
    private final Settings settings;
    private final Pterodactyl panel;
    private final String panelUrl;
    private final boolean hasApplicationKey;
    private final boolean hasClientKey;
    private final Releases releases;
    private final Path template;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "EterLobby-orchestrateur");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<String, Row> rows = new ConcurrentHashMap<>();
    private final Map<String, Long> emptySince = new ConcurrentHashMap<>();
    private final Map<String, Long> unreachableSince = new ConcurrentHashMap<>();
    private final Map<String, Long> dryRunLogged = new ConcurrentHashMap<>();
    private LobbyStore store;
    private volatile boolean ready;
    private volatile String version;
    private volatile List<Release> versionReleases = List.of();
    private long versionCheckedAt;
    private long templateModified;

    public Orchestrator(EterVelocityLobby plugin, Config config, Path dataDirectory) {
        this.plugin = plugin;
        this.logger = plugin.logger();
        this.settings = settings(config);
        this.panelUrl = config.getString("orchestrator.panel.url", "").trim();
        String applicationKey = config.getString("orchestrator.panel.application-key", "").trim();
        String clientKey = config.getString("orchestrator.panel.client-key", "").trim();
        this.hasApplicationKey = !applicationKey.isEmpty();
        this.hasClientKey = !clientKey.isEmpty();
        // Les clés ne vont qu'au client du panel : jamais gardées ailleurs, jamais écrites dans la console
        this.panel = new Pterodactyl(panelUrl, applicationKey, clientKey);
        this.releases = new Releases(config.getString("orchestrator.github-token", ""), dataDirectory.resolve("cache"));
        this.template = dataDirectory.resolve("template");
    }

    /** Démarrage : base, puis remise en ordre (lobbys déjà là, créations interrompues), puis les règles toutes les 30 s. */
    public void start(Path libs) {
        worker.execute(() -> {
            try {
                checkSettings();
                Map<String, Object> eterLib = readEterLibTemplate();
                store = new LobbyStore(libs, String.valueOf(eterLib.getOrDefault("database.host", "localhost")),
                        Integer.parseInt(String.valueOf(eterLib.getOrDefault("database.port", 3306))),
                        String.valueOf(eterLib.getOrDefault("database.name", "eternom")),
                        String.valueOf(eterLib.getOrDefault("database.username", "root")),
                        String.valueOf(eterLib.getOrDefault("database.password", "")),
                        jar -> plugin.proxy().getPluginManager().addToClasspath(plugin, jar));
                archive(); // modèle présent ?
                reconcile();
                ready = true;
                logger.info("Orchestrateur des lobbys actif{} : {} à {} lobbys de {} joueurs", settings.dryRun()
                        ? " en MODE ESSAI (rien n'est créé ni supprimé)" : "", settings.minimum(), settings.maximum(), settings.capacity());
            } catch (Exception e) {
                // Le message seul : une trace complète pourrait recopier une ligne de config (mot de passe compris)
                logger.error("Orchestrateur des lobbys désactivé : {}", e.getMessage());
            }
        });
        plugin.proxy().getScheduler().buildTask(plugin, () -> worker.execute(this::tick)).repeat(Duration.ofSeconds(30)).schedule();
    }

    public void stop() {
        worker.shutdownNow();
    }

    // ---------- Pour Lobbies et la commande ----------

    /** Lobbys gérés qui reçoivent des joueurs. */
    public List<String> activeNames() {
        return rows.values().stream().filter(row -> row.state() == State.ACTIVE).map(Row::name).sorted().toList();
    }

    public boolean manages(String server) {
        return rows.containsKey(server);
    }

    List<Row> rows() {
        return rows.values().stream().sorted(Comparator.comparing(Row::name)).toList();
    }

    Settings settings() {
        return settings;
    }

    boolean isReady() {
        return ready;
    }

    String version() {
        return version;
    }

    int players(String server) {
        return plugin.proxy().getServer(server).map(found -> found.getPlayersConnected().size()).orElse(0);
    }

    /** /eterlobby create : un lobby de plus, tout de suite (dans la limite de sécurité). */
    void requestCreate() {
        worker.execute(() -> {
            refreshVersion();
            create();
        });
    }

    /** /eterlobby drain <lobby>. */
    boolean requestDrain(String name) {
        Row row = rows.get(name);
        if (row == null || row.state() != State.ACTIVE) {
            return false;
        }
        worker.execute(() -> drain(rows.get(name), "demandé par le staff"));
        return true;
    }

    // ---------- Règles ----------

    private void tick() {
        if (!ready) {
            return;
        }
        try {
            refreshVersion();
            long now = System.currentTimeMillis();
            List<Row> active = byState(State.ACTIVE);
            for (Row row : active) {
                trackHealth(row, now);
            }
            for (Row row : byState(State.DRAINING)) {
                if (players(row.name()) == 0 || now - row.stateSince() > settings.drainTimeout().toMillis()) {
                    safeDelete(row);
                }
            }
            active = byState(State.ACTIVE);
            int creating = byState(State.CREATING).size();
            int players = active.stream().mapToInt(row -> players(row.name())).sum();
            int target = Math.clamp((long) Math.ceil(players / (settings.capacity() * settings.scaleUpAt())),
                    settings.minimum(), settings.maximum());
            List<Row> upToDate = active.stream().filter(row -> row.version().equals(version)).toList();

            if (version != null && creating == 0 && upToDate.size() < target && rows.size() < settings.maximum() + settings.minimum()) {
                create();
            } else if (upToDate.size() >= target) {
                // Assez de lobbys à jour : on vide une ancienne version, sinon un lobby en trop vide depuis longtemps
                Optional<Row> outdated = active.stream().filter(row -> !row.version().equals(version)).findFirst();
                if (outdated.isPresent() && version != null) {
                    drain(outdated.get(), "nouvelle version");
                } else if (upToDate.size() > target) {
                    upToDate.stream()
                            .filter(row -> now - emptySince.getOrDefault(row.name(), now) >= settings.idle().toMillis())
                            .findFirst()
                            .ifPresent(row -> drain(row, "inutile (vide)"));
                }
            }
        } catch (Exception e) {
            logger.warn("Orchestrateur : {}", e.getMessage());
        }
    }

    /** Vide depuis quand, ne répond plus depuis quand (un lobby planté est vidé au bout de 5 min). */
    private void trackHealth(Row row, long now) {
        if (players(row.name()) == 0) {
            emptySince.putIfAbsent(row.name(), now);
        } else {
            emptySince.remove(row.name());
        }
        if (plugin.lobbies().isReachable(row.name())) {
            unreachableSince.remove(row.name());
        } else if (now - unreachableSince.computeIfAbsent(row.name(), name -> now) > UNREACHABLE_LIMIT.toMillis()) {
            drain(row, "ne répond plus");
        }
    }

    // ---------- Création ----------

    private void create() {
        if (version == null) {
            logger.warn("Orchestrateur : version des plugins inconnue (GitHub injoignable ?), création reportée");
            return;
        }
        try {
            String name = nextName();
            if (settings.dryRun()) {
                dryRun("créerait " + name + " dans la location " + settings.location() + " (version " + version + ")");
                return;
            }
            // Adresse encore inconnue : c'est le panel qui choisit le nœud et le port (déploiement automatique)
            Row row = new Row(name, null, null, EXTERNAL_PREFIX + name, version, State.CREATING, "", 0, System.currentTimeMillis());
            save(row); // AVANT le panel : une création interrompue reste retrouvable (et supprimable) au redémarrage
            logger.info("Création du lobby {}...", name);
            try {
                Server server = panel.create(serverBody(row));
                Allocation allocation = panel.allocationOf(server.id());
                String host = !settings.connectHost().isBlank() ? settings.connectHost()
                        : allocation.alias() != null && !allocation.alias().isBlank() ? allocation.alias() : allocation.ip();
                row = row.withPanel(server.id(), server.identifier(), host, allocation.port());
                save(row);
                logger.info("Lobby {} déployé par le panel sur {}:{}", name, host, allocation.port());
                install(row);
                register(row);
                waitReachable(row.name());
                row = row.with(State.ACTIVE);
                save(row);
                logger.info("Lobby {} prêt", name);
            } catch (Exception e) {
                logger.error("Création du lobby {} ratée : {} (suppression)", name, e.getMessage());
                safeDelete(row);
            }
        } catch (Exception e) {
            logger.error("Création d'un lobby impossible : {}", e.getMessage());
        }
    }

    /** Attend l'installation de Paper par le panel, puis dépose modèle, plugins et config d'EterLib, et démarre. */
    private void install(Row row) throws Exception {
        long deadline = System.currentTimeMillis() + INSTALL_TIMEOUT.toMillis();
        while (true) {
            Server server = panel.server(row.panelId()).orElseThrow(() -> new IOException("serveur disparu du panel"));
            if (server.status() == null) {
                break;
            }
            if ("install_failed".equals(server.status()) || System.currentTimeMillis() > deadline) {
                throw new IOException("installation : " + server.status());
            }
            TimeUnit.SECONDS.sleep(10);
        }
        String id = row.identifier();
        Path archive = archive();
        String archiveName = archive.getFileName().toString();
        panel.upload(id, "/", List.of(archive));
        panel.decompress(id, archiveName);
        panel.deleteFile(id, archiveName);
        List<Path> jars = new ArrayList<>();
        for (Release release : versionReleases) {
            jars.add(releases.jar(release));
        }
        panel.upload(id, "/plugins", jars);
        panel.write(id, "/plugins/EterLib/config.yml", Files.readString(eterLibTemplate(), StandardCharsets.UTF_8)
                .replace("%server%", row.name()).replace("%display%", displayName(row.name())));
        panel.power(id, "start");
    }

    private JsonObject serverBody(Row row) throws IOException {
        JsonObject egg = panel.egg(settings.nest(), settings.egg());
        JsonObject environment = new JsonObject();
        for (JsonElement variable : egg.getAsJsonObject("relationships").getAsJsonObject("variables").getAsJsonArray("data")) {
            JsonObject attributes = variable.getAsJsonObject().getAsJsonObject("attributes");
            JsonElement value = attributes.get("default_value");
            environment.addProperty(attributes.get("env_variable").getAsString(), value == null || value.isJsonNull() ? "" : value.getAsString());
        }
        settings.environment().forEach(environment::addProperty);

        JsonObject body = new JsonObject();
        body.addProperty("name", row.name());
        body.addProperty("description", "Lobby créé et géré par EterVelocityLobby : ne pas modifier ni supprimer à la main.");
        body.addProperty("external_id", row.externalId());
        body.addProperty("user", settings.owner());
        body.addProperty("egg", settings.egg());
        body.addProperty("docker_image", !settings.dockerImage().isBlank() ? settings.dockerImage() : eggImage(egg));
        body.addProperty("startup", !settings.startup().isBlank() ? settings.startup() : egg.get("startup").getAsString());
        body.add("environment", environment);
        JsonObject limits = new JsonObject();
        limits.addProperty("memory", settings.memory());
        limits.addProperty("swap", 0);
        limits.addProperty("disk", settings.disk());
        limits.addProperty("io", 500);
        limits.addProperty("cpu", settings.cpu());
        body.add("limits", limits);
        JsonObject features = new JsonObject();
        features.addProperty("databases", 0);
        features.addProperty("allocations", 0);
        features.addProperty("backups", 0);
        body.add("feature_limits", features);
        // Déploiement automatique : le panel choisit un nœud de la location et un port libre (dans port-range s'il est réglé)
        JsonObject deploy = new JsonObject();
        JsonArray locations = new JsonArray();
        locations.add(settings.location());
        deploy.add("locations", locations);
        deploy.addProperty("dedicated_ip", false);
        JsonArray portRange = new JsonArray();
        if (!settings.portRange().isBlank()) {
            portRange.add(settings.portRange());
        }
        deploy.add("port_range", portRange);
        body.add("deploy", deploy);
        body.addProperty("start_on_completion", false);
        return body;
    }

    private static String eggImage(JsonObject egg) {
        JsonElement images = egg.get("docker_images");
        if (images != null && images.isJsonObject() && !images.getAsJsonObject().isEmpty()) {
            return images.getAsJsonObject().entrySet().iterator().next().getValue().getAsString();
        }
        return egg.get("docker_image").getAsString();
    }

    private void waitReachable(String name) throws Exception {
        RegisteredServer server = plugin.proxy().getServer(name).orElseThrow();
        long deadline = System.currentTimeMillis() + START_TIMEOUT.toMillis();
        while (System.currentTimeMillis() < deadline) {
            try {
                server.ping().get(3, TimeUnit.SECONDS);
                return;
            } catch (Exception notYet) {
                TimeUnit.SECONDS.sleep(5);
            }
        }
        throw new IOException("ne répond pas après " + START_TIMEOUT.toMinutes() + " min");
    }

    // ---------- Vidange et suppression ----------

    private void drain(Row row, String reason) {
        if (row == null || row.state() != State.ACTIVE) {
            return;
        }
        if (settings.dryRun()) {
            dryRun("viderait " + row.name() + " (" + reason + ")");
            return;
        }
        try {
            save(row.with(State.DRAINING));
            logger.info("Lobby {} vidé ({}) : plus de nouveaux joueurs, supprimé une fois vide", row.name(), reason);
        } catch (Exception e) {
            logger.warn("Vidange de {} : {}", row.name(), e.getMessage());
        }
    }

    /**
     * Supprime le serveur, SEULEMENT s'il est bien à nous : ligne en base (on vient de la lire), propriétaire = utilisateur
     * dédié, identifiant externe = eterlobby:<nom>. Sinon : erreur dans la console et rien n'est touché.
     */
    private void safeDelete(Row row) {
        try {
            Optional<Server> found = row.panelId() != null ? panel.server(row.panelId()) : panel.serverByExternalId(row.externalId());
            if (found.isPresent()) {
                Server server = found.get();
                if (server.owner() != settings.owner() || !row.externalId().equals(server.externalId())) {
                    logger.error("REFUS de supprimer le serveur {} du panel (lobby {}) : propriétaire {} / identifiant externe {}"
                                    + " ne correspondent pas. Vérifie à la main ; rien n'a été supprimé.",
                            server.id(), row.name(), server.owner(), server.externalId());
                    return;
                }
            }
            if (settings.dryRun()) {
                dryRun("supprimerait " + row.name() + found.map(server -> " (serveur " + server.id() + " du panel)").orElse(" (déjà absent du panel)"));
                return;
            }
            sendPlayersAway(row.name());
            unregister(row.name());
            if (found.isPresent()) {
                panel.delete(found.get().id());
            }
            store.delete(row.name());
            rows.remove(row.name());
            emptySince.remove(row.name());
            unreachableSince.remove(row.name());
            logger.info("Lobby {} supprimé", row.name());
        } catch (Exception e) {
            logger.warn("Suppression de {} : {} (nouvel essai au prochain passage)", row.name(), e.getMessage());
        }
    }

    private void sendPlayersAway(String name) {
        plugin.proxy().getServer(name).ifPresent(server -> {
            for (Player player : server.getPlayersConnected()) {
                plugin.lobbies().best(name).ifPresent(lobby -> player.createConnectionRequest(lobby).fireAndForget());
            }
        });
    }

    // ---------- Remise en ordre au démarrage ----------

    private void reconcile() throws Exception {
        for (Row row : store.all()) {
            rows.put(row.name(), row);
        }
        for (Row row : List.copyOf(rows.values())) {
            if (row.state() == State.CREATING) {
                logger.warn("Lobby {} : création interrompue (redémarrage du proxy), suppression", row.name());
                safeDelete(row);
            } else {
                register(row);
            }
        }
        // Serveurs « à nous » sur le panel mais absents de la table : jamais supprimés, seulement signalés
        for (Server server : panel.serversOf(settings.owner())) {
            String externalId = server.externalId();
            if (externalId != null && externalId.startsWith(EXTERNAL_PREFIX)
                    && !rows.containsKey(externalId.substring(EXTERNAL_PREFIX.length()))) {
                logger.warn("Serveur {} du panel ({}) inconnu de la table eterlobby_servers : laissé tel quel, à vérifier à la main",
                        server.id(), externalId);
            }
        }
    }

    // ---------- Outils ----------

    /**
     * Réglages indispensables, et panel en HTTPS : les clés partent dans chaque appel, et la config d'EterLib (avec les
     * accès à la base) est envoyée aux lobbys ; en HTTP elles circuleraient en clair (sauf panel sur cette machine).
     */
    private void checkSettings() throws IOException {
        List<String> missing = new ArrayList<>();
        if (panelUrl.isBlank()) missing.add("orchestrator.panel.url");
        if (!hasApplicationKey) missing.add("orchestrator.panel.application-key");
        if (!hasClientKey) missing.add("orchestrator.panel.client-key");
        if (settings.owner() <= 0) missing.add("orchestrator.panel.owner-user-id");
        if (settings.location() <= 0) missing.add("orchestrator.panel.location-id");
        if (settings.plugins().isEmpty()) missing.add("orchestrator.plugins");
        if (!Files.exists(eterLibTemplate())) missing.add("template/EterLib-config.yml");
        if (!missing.isEmpty()) {
            throw new IOException("à régler : " + String.join(", ", missing));
        }
        URI uri = URI.create(panelUrl);
        boolean local = "localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
        if (!"https".equalsIgnoreCase(uri.getScheme()) && !local) {
            throw new IOException("le panel doit être en https:// (les clés et les accès à la base passeraient en clair)");
        }
    }

    /**
     * Version voulue : empreinte du modèle (archive ET config d'EterLib) + dernière release de chaque plugin ; relue
     * toutes les 10 min ou dès qu'un fichier du modèle change. Une version différente = lobbys remplacés.
     */
    private void refreshVersion() {
        try {
            Path zip = archive();
            long modified = Files.getLastModifiedTime(zip).toMillis() + Files.getLastModifiedTime(eterLibTemplate()).toMillis();
            if (version != null && modified == templateModified
                    && System.currentTimeMillis() - versionCheckedAt < VERSION_REFRESH.toMillis()) {
                return;
            }
            List<Release> latest = new ArrayList<>();
            for (Plugin source : settings.plugins()) {
                latest.add(releases.latest(source));
            }
            StringBuilder next = new StringBuilder(hash(zip, eterLibTemplate()));
            latest.forEach(release -> next.append(' ').append(release.plugin().assetPrefix()).append('@').append(release.tag()));
            if (!next.toString().equals(version)) {
                logger.info("Version des lobbys : {}", next);
            }
            versionReleases = List.copyOf(latest);
            version = next.toString();
            versionCheckedAt = System.currentTimeMillis();
            templateModified = modified;
        } catch (Exception e) {
            logger.warn("Version des lobbys non relue : {}", e.getMessage());
        }
    }

    /** lobby-1, lobby-2... : le plus petit numéro libre. */
    private String nextName() {
        for (int n = 1; ; n++) {
            String name = settings.namePrefix() + "-" + n;
            if (!rows.containsKey(name)) {
                return name;
            }
        }
    }

    private String displayName(String name) {
        return "Lobby " + name.substring(name.lastIndexOf('-') + 1);
    }

    private void register(Row row) {
        if (plugin.proxy().getServer(row.name()).isEmpty()) {
            plugin.proxy().registerServer(new ServerInfo(row.name(), new InetSocketAddress(row.host(), row.port())));
        }
    }

    private void unregister(String name) {
        plugin.proxy().getServer(name).ifPresent(server -> plugin.proxy().unregisterServer(server.getServerInfo()));
    }

    private void save(Row row) throws Exception {
        store.save(row);
        rows.put(row.name(), row);
    }

    private List<Row> byState(State state) {
        return rows.values().stream().filter(row -> row.state() == state).sorted(Comparator.comparing(Row::name)).toList();
    }

    /** Mode essai : ce qui serait fait, écrit au plus une fois toutes les 10 min par action. */
    private void dryRun(String action) {
        long now = System.currentTimeMillis();
        Long last = dryRunLogged.get(action);
        if (last == null || now - last > DRY_RUN_REPEAT.toMillis()) {
            dryRunLogged.put(action, now);
            logger.info("[ESSAI] L'orchestrateur {}", action);
        }
    }

    /**
     * Le modèle de config d'EterLib (accès à la base). Une erreur YAML recopie la ligne fautive : jamais transmise,
     * pour qu'un mot de passe ne finisse pas dans la console.
     */
    private Map<String, Object> readEterLibTemplate() throws IOException {
        try {
            return YamlFiles.load(eterLibTemplate());
        } catch (RuntimeException invalidYaml) {
            throw new IOException("template/EterLib-config.yml illisible (YAML invalide)");
        }
    }

    /** Le modèle : lobby.zip, ou lobby.tar.gz (format des archives du panel). */
    private Path archive() throws IOException {
        for (String name : List.of("lobby.zip", "lobby.tar.gz")) {
            Path archive = template.resolve(name);
            if (Files.exists(archive)) {
                return archive;
            }
        }
        throw new IOException("modèle absent : template/lobby.zip ou template/lobby.tar.gz");
    }

    private Path eterLibTemplate() {
        return template.resolve("EterLib-config.yml");
    }

    private static String hash(Path... files) throws IOException, NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[65536];
        for (Path file : files) {
            try (InputStream in = Files.newInputStream(file)) {
                for (int read; (read = in.read(buffer)) > 0; ) {
                    digest.update(buffer, 0, read);
                }
            }
        }
        return "modele-" + HexFormat.of().formatHex(digest.digest()).substring(0, 8);
    }

    private static Settings settings(Config config) {
        Map<String, String> environment = new ConcurrentHashMap<>();
        for (String key : config.getKeys("orchestrator.panel.environment")) {
            environment.put(key, config.getString("orchestrator.panel.environment." + key, ""));
        }
        return new Settings(
                config.getBoolean("orchestrator.dry-run", true),
                config.getString("orchestrator.lobbies.name-prefix", "lobby"),
                Math.max(1, config.getInt("orchestrator.lobbies.minimum", 3)),
                Math.max(1, config.getInt("orchestrator.lobbies.maximum", 8)),
                Math.max(1, config.getInt("orchestrator.lobbies.capacity", 20)),
                Math.clamp(config.getDouble("orchestrator.lobbies.scale-up-at", 0.7), 0.1, 1),
                Duration.ofMinutes(Math.max(1, config.getInt("orchestrator.lobbies.idle-minutes", 10))),
                Duration.ofMinutes(Math.max(1, config.getInt("orchestrator.lobbies.drain-timeout-minutes", 30))),
                config.getInt("orchestrator.panel.owner-user-id", 0),
                config.getInt("orchestrator.panel.nest-id", 1),
                config.getInt("orchestrator.panel.egg-id", 2),
                config.getString("orchestrator.panel.docker-image", ""),
                config.getString("orchestrator.panel.startup", ""),
                Map.copyOf(environment),
                config.getInt("orchestrator.panel.memory-mb", 4096),
                config.getInt("orchestrator.panel.cpu-percent", 200),
                config.getInt("orchestrator.panel.disk-mb", 10240),
                config.getInt("orchestrator.panel.location-id", 0),
                config.getString("orchestrator.panel.port-range", "").trim(),
                config.getString("orchestrator.panel.connect-host", ""),
                config.getStringList("orchestrator.plugins").stream().map(Plugin::parse).toList());
    }
}
