package fr.eternom.eterVelocityLobby.module.announce;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Arrivée et départ du RÉSEAU, annoncés une seule fois à tous les joueurs, dans la langue de chacun : pas à chaque
 * changement de serveur. Les messages de Minecraft sur les serveurs Paper sont coupés par EterLib
 * (vanilla-join-quit-messages). Un départ n'est annoncé que si l'arrivée l'a été (connexion ratée = silence).
 */
public class JoinQuitListener {

    private final EterVelocityLobby plugin;
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();

    public JoinQuitListener(EterVelocityLobby plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (event.getPreviousServer() == null && announced.add(player.getUniqueId())) {
            broadcast("announce.join", player);
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        if (announced.remove(player.getUniqueId())) {
            broadcast("announce.quit", player);
        }
    }

    private void broadcast(String key, Player player) {
        plugin.proxy().getAllPlayers().forEach(receiver ->
                receiver.sendMessage(plugin.messages().get(receiver, key, Placeholder.unparsed("player", player.getUsername()))));
    }
}
