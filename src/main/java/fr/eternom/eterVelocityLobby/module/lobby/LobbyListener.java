package fr.eternom.eterVelocityLobby.module.lobby;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.event.player.PlayerChooseInitialServerEvent;
import com.velocitypowered.api.proxy.Player;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/**
 * Arrivée sur le réseau : le lobby le moins rempli. Expulsé d'un serveur (arrêt, plantage, kick) : renvoyé sur un
 * lobby avec la raison, au lieu d'être déconnecté du réseau. Sans lobby joignable, Velocity fait comme d'habitude.
 */
public class LobbyListener {

    private final EterVelocityLobby plugin;

    public LobbyListener(EterVelocityLobby plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onChooseInitialServer(PlayerChooseInitialServerEvent event) {
        plugin.lobbies().best(null).ifPresent(event::setInitialServer);
    }

    @Subscribe
    public void onKicked(KickedFromServerEvent event) {
        Player player = event.getPlayer();
        // Échec d'un /server alors qu'il est déjà ailleurs : Velocity le prévient et le laisse où il est
        if (event.kickedDuringServerConnect() && player.getCurrentServer().isPresent()) {
            return;
        }
        String from = event.getServer().getServerInfo().getName();
        plugin.lobbies().best(from).ifPresent(lobby -> {
            Component reason = event.getServerKickReason().orElse(Component.empty());
            event.setResult(KickedFromServerEvent.RedirectPlayer.create(lobby,
                    plugin.messages().get(player, "lobby.kicked", Placeholder.component("reason", reason))));
        });
    }
}
