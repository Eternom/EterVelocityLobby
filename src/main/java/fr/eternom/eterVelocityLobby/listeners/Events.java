package fr.eternom.eterVelocityLobby.listeners;

import com.velocitypowered.api.command.CommandManager;
import fr.eternom.eterVelocityLobby.EterVelocityLobby;
import fr.eternom.eterVelocityLobby.module.announce.JoinQuitListener;
import fr.eternom.eterVelocityLobby.module.lobby.LobbyCommand;
import fr.eternom.eterVelocityLobby.module.lobby.LobbyListener;

public class Events {

    public Events(EterVelocityLobby plugin) {
        plugin.proxy().getEventManager().register(plugin, new LobbyListener(plugin));
        if (plugin.config().getBoolean("announce.enabled", true)) {
            plugin.proxy().getEventManager().register(plugin, new JoinQuitListener(plugin));
        }
        CommandManager commands = plugin.proxy().getCommandManager();
        commands.register(commands.metaBuilder("lobby").aliases("hub", "l").plugin(plugin).build(), new LobbyCommand(plugin));
    }
}
