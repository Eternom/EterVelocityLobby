package fr.eternom.eterVelocityLobby;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import fr.eternom.eterVelocityLobby.core.Config;
import fr.eternom.eterVelocityLobby.core.Lang;
import fr.eternom.eterVelocityLobby.helper.Messages;
import fr.eternom.eterVelocityLobby.listeners.Events;
import fr.eternom.eterVelocityLobby.module.lobby.Lobbies;
import fr.eternom.eterVelocityLobby.module.orchestrator.Orchestrator;
import org.slf4j.Logger;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * EterVelocityLobby : les lobbys du réseau, côté proxy. Arrivée sur le lobby le moins rempli, /lobby depuis
 * n'importe quel serveur, renvoi au lobby quand un serveur expulse ou s'arrête, annonce d'arrivée et de départ du
 * réseau. Les lobbys sont la liste « try » de velocity.toml, plus ceux créés sur Pterodactyl par l'orchestrateur
 * (facultatif). Indépendant d'EterLib (qui est pour Paper).
 */
@Plugin(id = "etervelocitylobby", name = "EterVelocityLobby", version = "1.1.0", authors = {"NadTum"},
        description = "Lobbys du réseau : répartition, /lobby, renvoi au lobby, arrivées et départs")
public final class EterVelocityLobby {

    private static final Duration PING_INTERVAL = Duration.ofSeconds(5);

    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDirectory;
    private Config config;
    private Messages messages;
    private Lobbies lobbies;
    private Orchestrator orchestrator;

    @Inject
    public EterVelocityLobby(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        this.proxy = proxy;
        this.logger = logger;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        try {
            config = new Config(dataDirectory);
            messages = new Messages(new Lang(dataDirectory, config.getString("default-language", "en_us"), logger), config);
        } catch (IOException | RuntimeException e) {
            // Sans le détail : une erreur YAML recopie la ligne fautive, qui peut être une clé du panel
            logger.error("config.yml ou lang/ illisible (vérifie la syntaxe YAML), EterVelocityLobby désactivé");
            return;
        }
        if (config.getBoolean("orchestrator.enabled", false)) {
            orchestrator = new Orchestrator(this, config, dataDirectory);
        }
        lobbies = new Lobbies(proxy, orchestrator);
        if (lobbies.names().isEmpty() && orchestrator == null) {
            logger.warn("Aucun lobby : la liste « try » de velocity.toml est vide");
        }
        proxy.getScheduler().buildTask(this, lobbies::ping).repeat(PING_INTERVAL).schedule();
        new Events(this);
        if (orchestrator != null) {
            orchestrator.start(dataDirectory.resolve("libs"));
        }
        logger.info("Lobbys : {}", String.join(", ", lobbies.names()));
    }

    @Subscribe
    public void onShutdown(ProxyShutdownEvent event) {
        if (orchestrator != null) {
            orchestrator.stop();
        }
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Config config() {
        return config;
    }

    public Messages messages() {
        return messages;
    }

    public Lobbies lobbies() {
        return lobbies;
    }

    /** null si l'orchestrateur est désactivé (orchestrator.enabled). */
    public Orchestrator orchestrator() {
        return orchestrator;
    }

    public Logger logger() {
        return logger;
    }
}
