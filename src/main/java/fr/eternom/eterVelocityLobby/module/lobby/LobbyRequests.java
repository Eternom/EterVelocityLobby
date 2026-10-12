package fr.eternom.eterVelocityLobby.module.lobby;

import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

import java.nio.charset.StandardCharsets;

/**
 * Retour au lobby demandé par un serveur Paper (canal eter:lobby, ex : le bouton du /menu d'EterEssential) : un menu
 * ne peut pas lancer /lobby, qui est une commande du proxy. Même effet que /lobby. Seul un serveur peut le demander,
 * et seulement pour le joueur qui y est connecté : un message venant d'un joueur (client modifié) est ignoré.
 */
public class LobbyRequests {

    public static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("eter", "lobby");
    private static final MinecraftChannelIdentifier REGISTER = MinecraftChannelIdentifier.create("minecraft", "register");

    private final LobbyCommand lobby;

    public LobbyRequests(LobbyCommand lobby) {
        this.lobby = lobby;
    }

    /** Paper n'envoie que sur un canal déclaré par l'autre bout : on le déclare au serveur à chaque connexion. */
    @Subscribe
    public void onConnected(ServerPostConnectEvent event) {
        event.getPlayer().getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(REGISTER, CHANNEL.getId().getBytes(StandardCharsets.UTF_8)));
    }

    @Subscribe
    public void onMessage(PluginMessageEvent event) {
        if (!CHANNEL.equals(event.getIdentifier())) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled());
        if (event.getSource() instanceof ServerConnection server) {
            lobby.send(server.getPlayer());
        }
    }
}
