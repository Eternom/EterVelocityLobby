package fr.eternom.eterVelocityLobby.module.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import fr.eternom.eterVelocityLobby.module.orchestrator.Orchestrator;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Les lobbys du réseau : la liste « try » de velocity.toml, plus ceux créés par l'orchestrateur (s'il est activé) qui
 * reçoivent des joueurs. Chacun est interrogé (ping) toutes les 5 s ; le meilleur lobby est celui qui répond et a le
 * moins de joueurs. Un lobby de l'orchestrateur en cours de création ou de vidange n'en fait pas partie.
 */
public class Lobbies {

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(3);

    private final ProxyServer proxy;
    private final Orchestrator orchestrator; // null s'il est désactivé
    /** Lobbys qui ont répondu au dernier ping. */
    private final Set<String> reachable = ConcurrentHashMap.newKeySet();

    public Lobbies(ProxyServer proxy, Orchestrator orchestrator) {
        this.proxy = proxy;
        this.orchestrator = orchestrator;
    }

    /** Noms des lobbys qui reçoivent des joueurs : ceux de « try », puis ceux de l'orchestrateur. */
    public List<String> names() {
        List<String> names = new ArrayList<>(proxy.getConfiguration().getAttemptConnectionOrder());
        if (orchestrator != null) {
            orchestrator.activeNames().stream().filter(name -> !names.contains(name)).forEach(names::add);
        }
        return names;
    }

    /** Un lobby, même en création ou en vidange (« tu es déjà au lobby »). */
    public boolean isLobby(String server) {
        return names().contains(server) || (orchestrator != null && orchestrator.manages(server));
    }

    public boolean isReachable(String server) {
        return reachable.contains(server);
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
