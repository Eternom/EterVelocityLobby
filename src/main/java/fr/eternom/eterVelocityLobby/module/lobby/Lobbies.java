package fr.eternom.eterVelocityLobby.module.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Les lobbys du réseau : la liste « try » de velocity.toml (les serveurs où arrive un joueur qui se connecte), rien à
 * configurer ici. Chacun est interrogé (ping) toutes les 5 s ; le meilleur lobby est celui qui répond et a le moins de
 * joueurs.
 */
public class Lobbies {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(3);

    private final ProxyServer proxy;
    /** Lobbys qui ont répondu au dernier ping. */
    private final Set<String> reachable = ConcurrentHashMap.newKeySet();

    public Lobbies(ProxyServer proxy) {
        this.proxy = proxy;
    }

    /** Noms des lobbys, dans l'ordre de « try ». */
    public List<String> names() {
        return proxy.getConfiguration().getAttemptConnectionOrder();
    }

    public boolean isLobby(String server) {
        return names().contains(server);
    }

    /** Lobby qui répond, le moins rempli, autre que except (peut être null). */
    public Optional<RegisteredServer> best(String except) {
        return names().stream()
                .filter(name -> !name.equals(except) && reachable.contains(name))
                .map(proxy::getServer)
                .flatMap(Optional::stream)
                .min(Comparator.comparingInt(server -> server.getPlayersConnected().size()));
    }

    /** Toutes les 5 s (tâche du proxy) : quels lobbys répondent. */
    public void ping() {
        for (String name : names()) {
            proxy.getServer(name).ifPresentOrElse(server -> server.ping()
                            .orTimeout(PING_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)
                            .whenComplete((result, error) -> {
                                if (error == null) {
                                    reachable.add(name);
                                } else {
                                    reachable.remove(name);
                                }
                            }),
                    () -> reachable.remove(name));
        }
    }
}
