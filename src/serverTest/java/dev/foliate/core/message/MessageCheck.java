package dev.foliate.core.message;

import dev.foliate.servertest.PlayerCheck;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A player is spoken to in the language their client reports. When that language has its own text
 * for a message and it differs from the default language's, the text rendered for the player must
 * be the one of their language: getting the default language's text means the language could not
 * be read from the player.
 */
public final class MessageCheck implements PlayerCheck {

    /** A message every bundled language translates. */
    static final String KEY = "command.reload.success";

    private final MessageService messages;

    public MessageCheck(MessageService messages) {
        this.messages = messages;
    }

    @Override
    public @Nullable String problemWith(Player player, int visit) {
        Locale locale = player.locale();
        Locale fallback = messages.defaultLocale();
        List<String> own = messages.ownLines(locale, KEY);
        if (own == null || own.equals(messages.ownLines(fallback, KEY))) {
            return null; // nothing that would tell the two languages apart
        }
        PlainTextComponentSerializer plain = PlainTextComponentSerializer.plainText();
        String forPlayer = plain.serialize(messages.render(player, KEY));
        String defaultText = plain.serialize(messages.render(fallback, KEY));
        return forPlayer.equals(defaultText)
                ? player.getName() + " is not spoken to in " + locale + ": got the default language's \"" + forPlayer + "\""
                : null;
    }
}
