package fr.eternom.eterVelocityLobby.module.lobby;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ServerConnection;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/** /lobby (/hub, /l) : retour au lobby le moins rempli, depuis n'importe quel serveur du réseau. */
public class LobbyCommand implements SimpleCommand {

    private final EterVelocityLobby plugin;

    public LobbyCommand(EterVelocityLobby plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(Invocation invocation) {
        if (!(invocation.source() instanceof Player player)) {
            plugin.messages().send(invocation.source(), "command.players-only", TagResolver.empty());
            return;
        }
        String current = player.getCurrentServer().map(ServerConnection::getServerInfo).map(info -> info.getName()).orElse(null);
        if (current != null && plugin.lobbies().isLobby(current)) {
            plugin.messages().send(player, "lobby.already", TagResolver.empty());
            return;
        }
        plugin.lobbies().best(current).ifPresentOrElse(lobby -> {
            plugin.messages().send(player, "lobby.sending", TagResolver.empty());
            player.createConnectionRequest(lobby).fireAndForget();
        }, () -> plugin.messages().send(player, "lobby.none", TagResolver.empty()));
    }
}
