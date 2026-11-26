package dev.foliate.core.message;

import java.io.Serial;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;

/**
 * What a player or an administrator asked cannot be done, and the message that says why: a key of
 * {@code messages.yml} and its arguments. The domain throws it; the command that asked catches it
 * and tells the sender, so that the reason is written once, next to the rule that refuses:
 *
 * <pre>{@code
 * try {
 *     game.start(settings);
 * } catch (RefusedException refused) {
 *     messages.send(sender, refused.messageKey(), refused.arguments());
 * }
 * }</pre>
 *
 * <p>Write the key inline, as a string literal: {@code MessageKeysTest} then checks that it is in
 * {@code messages.yml}. No stack trace is filled in: it is an answer, not a failure.
 */
public final class RefusedException extends Exception {

    @Serial
    private static final long serialVersionUID = 1L;

    private final String messageKey;
    private final transient TagResolver[] arguments;

    public RefusedException(String messageKey, TagResolver... arguments) {
        super(messageKey, null, false, false);
        this.messageKey = messageKey;
        this.arguments = arguments.clone();
    }

    /** The key of the message that says why, in {@code messages.yml}. */
    public String messageKey() {
        return messageKey;
    }

    /** What the message is given, such as {@code Placeholder.unparsed("player", name)}. */
    public TagResolver[] arguments() {
        return arguments.clone();
    }
}
