package fr.eternom.eterVelocityLobby.listeners;

import com.velocitypowered.api.command.CommandManager;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import fr.eternom.eterVelocityLobby.module.announce.JoinQuitListener;
import fr.eternom.eterVelocityLobby.module.lobby.LobbyCommand;
import fr.eternom.eterVelocityLobby.module.lobby.LobbyListener;
import fr.eternom.eterVelocityLobby.module.lobby.LobbyRequests;
import fr.eternom.eterVelocityLib.orchestrator.PoolCommand;

public class Events {

    public Events(EterVelocityLobby plugin) {
        plugin.proxy().getEventManager().register(plugin, new LobbyListener(plugin));
        if (plugin.config().getBoolean("announce.enabled", true)) {
            plugin.proxy().getEventManager().register(plugin, new JoinQuitListener(plugin));
        }
        CommandManager commands = plugin.proxy().getCommandManager();
        LobbyCommand lobby = new LobbyCommand(plugin);
        commands.register(commands.metaBuilder("lobby").aliases("hub", "l").plugin(plugin).build(), lobby);
        // Le même retour au lobby, demandé par un serveur (bouton d'un menu)
        plugin.proxy().getChannelRegistrar().register(LobbyRequests.CHANNEL);
        plugin.proxy().getEventManager().register(plugin, new LobbyRequests(lobby));
        if (plugin.orchestrator() != null) {
            commands.register(commands.metaBuilder("eterlobby").plugin(plugin).build(),
                    new PoolCommand(plugin.orchestrator(), plugin.messages(), "eterlobby", "etervelocitylobby.admin"));
        }
    }
}
