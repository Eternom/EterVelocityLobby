package fr.eternom.eterVelocityLobby.helper;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import fr.eternom.eterVelocityLobby.core.Config;
import fr.eternom.eterVelocityLobby.core.Lang;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.Tag;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MiniMessage avec la palette du réseau (<primary>, <accent>, <info>, <error>, <success>), comme EterLib côté Paper
 * (copie de celle d'EterTab-Velocity : EterLib ne tourne pas sur le proxy).
 * Les couleurs sont dans config.yml (colors), à garder identiques à EterLib/config.yml.
 */
public class Messages {

    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();

    private final Lang lang;
    private final TagResolver palette;
    private final Component prefix;

    public Messages(Lang lang, Config config) {
        this.lang = lang;
        List<TagResolver> colors = new ArrayList<>();
        for (String name : config.getKeys("colors")) {
            TextColor color = parseColor(config.getString("colors." + name, "white"));
            if (color != null) {
                colors.add(TagResolver.resolver(name, Tag.styling(color)));
            }
        }
        this.palette = TagResolver.resolver(colors);
        this.prefix = render(config.getString("prefix", ""), TagResolver.empty());
    }

    /**
     * Texte de key dans la langue du destinataire, mis en forme. Langue par défaut pour la console, et pour un joueur
     * qui vient d'arriver : son client n'a pas encore envoyé sa langue (getEffectiveLocale() vaut alors null).
     */
    public Component get(CommandSource source, String key, TagResolver tags) {
        Locale clientLocale = source instanceof Player player ? player.getEffectiveLocale() : null;
        String locale = clientLocale == null ? lang.getDefaultLocale() : clientLocale.toString().toLowerCase(Locale.ROOT);
        String raw = lang.get(locale, key);
        return raw == null ? Component.text(key, NamedTextColor.RED) : render(raw, tags);
    }

    /** Message de chat, précédé du préfixe commun (config.yml > prefix, le même que dans EterLib). */
    public void send(CommandSource source, String key, TagResolver tags) {
        source.sendMessage(prefix.append(get(source, key, tags)));
    }

    /** Texte MiniMessage mis en forme avec la palette. */
    public Component render(String raw, TagResolver tags) {
        return MINI_MESSAGE.deserialize(raw, palette, tags);
    }

    private static TextColor parseColor(String value) {
        return value.startsWith("#") ? TextColor.fromHexString(value) : NamedTextColor.NAMES.value(value.toLowerCase(Locale.ROOT));
    }
}
