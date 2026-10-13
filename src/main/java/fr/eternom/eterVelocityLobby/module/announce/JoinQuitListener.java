package fr.eternom.eterVelocityLobby.module.announce;

import com.google.common.io.ByteArrayDataInput;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Arrivée et départ du RÉSEAU, annoncés une seule fois à tous les joueurs, dans la langue de chacun : pas à chaque
 * changement de serveur. Les messages de Minecraft sur les serveurs Paper sont coupés par EterLib
 * (vanilla-join-quit-messages). Un départ n'est annoncé que si l'arrivée l'a été (connexion ratée = silence).
 * Le staff invisible (vanish d'EterLib) n'est jamais annoncé : son serveur pose l'étiquette « vanished » (canal
 * eter:tab d'EterLib, celui de la liste Tab) juste après la connexion, d'où l'arrivée annoncée avec un court délai.
 * Le canal est déclaré ici aussi : on ne compte pas sur EterTab pour le faire.
 */
public class JoinQuitListener {

    public static final MinecraftChannelIdentifier TAGS = MinecraftChannelIdentifier.create("eter", "tab");
    private static final MinecraftChannelIdentifier REGISTER = MinecraftChannelIdentifier.create("minecraft", "register");
    private static final String VANISHED = "vanished";
    private static final long JOIN_DELAY_SECONDS = 2;

    private final EterVelocityLobby plugin;
    private final Set<UUID> announced = new HashSet<>();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public JoinQuitListener(EterVelocityLobby plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        // Paper n'envoie que sur un canal déclaré par l'autre bout : à chaque serveur rejoint
        player.getCurrentServer().ifPresent(server ->
                server.sendPluginMessage(REGISTER, TAGS.getId().getBytes(StandardCharsets.UTF_8)));
        if (event.getPreviousServer() != null) {
            return;
        }
        UUID uuid = player.getUniqueId();
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            // Verrou partagé avec onDisconnect : un joueur parti pendant le délai n'est ni annoncé ni gardé
            synchronized (announced) {
                if (player.isActive() && !vanished.contains(uuid) && announced.add(uuid)) {
                    broadcast("announce.join", player);
                }
            }
        }).delay(JOIN_DELAY_SECONDS, TimeUnit.SECONDS).schedule();
    }

    /** Lit seulement l'étiquette « vanished » (EterTab, s'il est là, lit les autres). */
    @Subscribe
    public void onTag(PluginMessageEvent event) {
        if (!TAGS.equals(event.getIdentifier()) || !(event.getSource() instanceof ServerConnection server)) {
            return;
        }
        event.setResult(PluginMessageEvent.ForwardResult.handled()); // jamais renvoyé au client
        try {
            ByteArrayDataInput in = event.dataAsDataStream();
            if (VANISHED.equals(in.readUTF())) {
                UUID uuid = server.getPlayer().getUniqueId();
                if (in.readUTF().isEmpty()) {
                    vanished.remove(uuid);
                } else {
                    vanished.add(uuid);
                }
            }
        } catch (RuntimeException ignored) {
            // message mal formé
        }
    }

    @Subscribe
    public void onDisconnect(DisconnectEvent event) {
        Player player = event.getPlayer();
        boolean hidden = vanished.remove(player.getUniqueId());
        synchronized (announced) {
            if (announced.remove(player.getUniqueId()) && !hidden) {
                broadcast("announce.quit", player);
            }
        }
    }

    private void broadcast(String key, Player player) {
        plugin.proxy().getAllPlayers().forEach(receiver ->
                receiver.sendMessage(plugin.messages().get(receiver, key, Placeholder.unparsed("player", player.getUsername()))));
    }
}
