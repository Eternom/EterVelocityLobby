package fr.eternom.eterVelocityLobby.module.orchestrator;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import fr.eternom.eterVelocityLobby.helper.Messages;
import fr.eternom.eterVelocityLobby.module.orchestrator.LobbyStore.Row;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * /eterlobby (etervelocitylobby.admin) : list (lobbys gérés, état, joueurs, à jour ou non), status (réglages et
 * version voulue), create (un lobby de plus tout de suite), drain <lobby> (le vider puis le supprimer).
 */
public class OrchestratorCommand implements SimpleCommand {

    public static final String PERMISSION = "etervelocitylobby.admin";

    private final Orchestrator orchestrator;
    private final Messages messages;

    public OrchestratorCommand(Orchestrator orchestrator, Messages messages) {
        this.orchestrator = orchestrator;
        this.messages = messages;
    }

    @Override
    public void execute(Invocation invocation) {
        CommandSource source = invocation.source();
        String[] args = invocation.arguments();
        String action = args.length > 0 ? args[0].toLowerCase(Locale.ROOT) : "";
        if (!orchestrator.isReady() && !action.equals("status")) {
            messages.send(source, "orchestrator.not-ready", TagResolver.empty());
            return;
        }
        switch (action) {
            case "list" -> list(source);
            case "status" -> status(source);
            case "create" -> {
                orchestrator.requestCreate();
                messages.send(source, "orchestrator.creating", TagResolver.empty());
            }
            case "drain" -> {
                String name = args.length > 1 ? args[1] : "";
                messages.send(source, orchestrator.requestDrain(name) ? "orchestrator.draining" : "orchestrator.unknown",
                        Placeholder.unparsed("lobby", name));
            }
            default -> messages.send(source, "orchestrator.usage", TagResolver.empty());
        }
    }

    private void list(CommandSource source) {
        List<Row> rows = orchestrator.rows();
        messages.send(source, rows.isEmpty() ? "orchestrator.list-empty" : "orchestrator.list-header",
                Placeholder.unparsed("count", String.valueOf(rows.size())));
        for (Row row : rows) {
            source.sendMessage(messages.get(source, "orchestrator.list-line", TagResolver.resolver(
                    Placeholder.unparsed("lobby", row.name()),
                    Placeholder.unparsed("state", row.state().name().toLowerCase(Locale.ROOT)),
                    Placeholder.unparsed("players", String.valueOf(orchestrator.players(row.name()))),
                    Placeholder.unparsed("port", String.valueOf(row.port())),
                    Placeholder.component("version", messages.get(source, row.version().equals(orchestrator.version())
                            ? "orchestrator.up-to-date" : "orchestrator.outdated", TagResolver.empty())))));
        }
    }

    private void status(CommandSource source) {
        Orchestrator.Settings settings = orchestrator.settings();
        messages.send(source, "orchestrator.status", TagResolver.resolver(
                Placeholder.unparsed("mode", settings.dryRun() ? "essai" : "réel"),
                Placeholder.unparsed("ready", String.valueOf(orchestrator.isReady())),
                Placeholder.unparsed("minimum", String.valueOf(settings.minimum())),
                Placeholder.unparsed("maximum", String.valueOf(settings.maximum())),
                Placeholder.unparsed("capacity", String.valueOf(settings.capacity())),
                Placeholder.unparsed("version", String.valueOf(orchestrator.version()))));
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        String[] args = invocation.arguments();
        if (args.length <= 1) {
            String start = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            return Stream.of("list", "status", "create", "drain").filter(action -> action.startsWith(start)).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("drain")) {
            return orchestrator.activeNames().stream().filter(name -> name.startsWith(args[1])).toList();
        }
        return List.of();
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(PERMISSION);
    }
}
