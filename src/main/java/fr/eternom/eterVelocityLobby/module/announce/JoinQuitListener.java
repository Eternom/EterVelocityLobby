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

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Arrivée et départ du RÉSEAU, annoncés une seule fois à tous les joueurs, dans la langue de chacun : pas à chaque
 * changement de serveur. Les messages de Minecraft sur les serveurs Paper sont coupés par EterLib
 * (vanilla-join-quit-messages). Un départ n'est annoncé que si l'arrivée l'a été (connexion ratée = silence).
 * Le staff invisible (vanish d'EterLib) n'est jamais annoncé : son serveur pose l'étiquette « vanished » (canal
 * eter:tab, déclaré par EterTab) juste après la connexion, d'où l'arrivée annoncée avec un court délai.
 */
public class JoinQuitListener {

    private static final MinecraftChannelIdentifier TAGS = MinecraftChannelIdentifier.create("eter", "tab");
    private static final String VANISHED = "vanished";
    private static final long JOIN_DELAY_SECONDS = 2;

    private final EterVelocityLobby plugin;
    private final Set<UUID> announced = ConcurrentHashMap.newKeySet();
    private final Set<UUID> vanished = ConcurrentHashMap.newKeySet();

    public JoinQuitListener(EterVelocityLobby plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onConnected(ServerPostConnectEvent event) {
        Player player = event.getPlayer();
        if (event.getPreviousServer() != null) {
            return;
        }
        plugin.proxy().getScheduler().buildTask(plugin, () -> {
            if (player.isActive() && !vanished.contains(player.getUniqueId()) && announced.add(player.getUniqueId())) {
                broadcast("announce.join", player);
            }
        }).delay(JOIN_DELAY_SECONDS, TimeUnit.SECONDS).schedule();
    }

    /** Lit seulement l'étiquette « vanished » ; le message reste à EterTab (résultat inchangé). */
    @Subscribe
    public void onTag(PluginMessageEvent event) {
        if (!TAGS.equals(event.getIdentifier()) || !(event.getSource() instanceof ServerConnection server)) {
            return;
        }
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
        if (announced.remove(player.getUniqueId()) && !hidden) {
            broadcast("announce.quit", player);
        }
    }

    private void broadcast(String key, Player player) {
        plugin.proxy().getAllPlayers().forEach(receiver ->
                receiver.sendMessage(plugin.messages().get(receiver, key, Placeholder.unparsed("player", player.getUsername()))));
    }
}
