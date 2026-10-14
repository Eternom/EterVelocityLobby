package fr.eternom.eterVelocityLobby.module.lobby;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arrivée sur le réseau : le lobby le moins rempli. Expulsé d'un serveur (arrêt, plantage, kick) : renvoyé sur un
 * lobby avec la raison, au lieu d'être déconnecté du réseau. Sans lobby joignable, Velocity fait comme d'habitude.
 *
 * Refusé par un lobby pendant son arrivée (version du client non prise en charge, serveur plein...) : un autre lobby
 * est essayé, jamais deux fois le même. Quand tous l'ont refusé, il est déconnecté avec la raison du serveur, au lieu
 * de rebondir d'un lobby à l'autre jusqu'à l'expiration de la connexion.
 */
public class LobbyListener {

    private final EterVelocityLobby plugin;
    /** Lobbys qui ont refusé chaque joueur pendant son arrivée (vidé dès qu'il est sur un serveur, ou parti). */
    private final Map<UUID, Set<String>> refused = new ConcurrentHashMap<>();

    public LobbyListener(EterVelocityLobby plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        plugin.lobbies().best((String) null).ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        // Échec d'un /server alors qu'il est déjà ailleurs : Velocity le prévient et le laisse où il est
        if (event.kickedDuringServerConnect() && player.getCurrentServer().isPresent()) {
            return;
        }
        String from = event.getServer().getServerInfo().getName();
        Component reason = event.getServerKickReason().orElse(Component.empty());
        if (event.kickedDuringServerConnect()) {
            // Arrivée sur le réseau : n'essayer que les lobbys qui ne l'ont pas encore refusé
            Set<String> tried = refused.computeIfAbsent(player.getUniqueId(), uuid -> ConcurrentHashMap.newKeySet());
            tried.add(from);
            plugin.lobbies().best(tried).ifPresentOrElse(
                    lobby -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby, Component.empty())),
                    () -> event.setResult(KickedFromServerEvent.DisconnectPlayer.create(reason)));
            return;
        }
        plugin.lobbies().best(from).ifPresent(lobby -> event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby,
                plugin.messages().get(player, "lobby.kicked", Placeholder.component("reason", reason)))));
    }

    @Subscribe
    public void onConnected(ServerPostConnectEvent event) {
        refused.remove(event.getPlayer().getUniqueId());
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        refused.remove(event.getPlayer().getUniqueId());
    }
}
