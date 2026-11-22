package dev.foliate.core.command;

import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A module that adds subcommands to the plugin's command. {@code FoliatePlugin} asks the modules
 * that are enabled, so a feature's subcommands exist exactly when the feature runs, and removing a
 * feature removes them with it.
 */
public interface SubcommandProvider {

    /** Called once, after every module is enabled, to build the command. */
    List<Subcommand> subcommands();

    /**
     * What the plugin's command does when typed alone, such as {@code /fly}, or null when this
     * module gives nothing of the kind. Called once, with {@link #subcommands()}. At most one
     * enabled module may give one: the plugin refuses to start otherwise, rather than pick one
     * (see {@link #bareCommandOf}).
     * Named after the feature, {@code FlyCommand}, and checked like any subcommand: its
     * permission and {@link Subcommand#isAvailableTo}.
     */
    default @Nullable Subcommand bareCommand() {
        return null;
    }

    /**
     * The bare command the providers give, or null when none does.
     *
     * @throws IllegalStateException when two give one: which would win is up to the order of the
     *     modules, so neither is picked
     */
    static @Nullable Subcommand bareCommandOf(Collection<? extends SubcommandProvider> providers) {
        Subcommand found = null;
        for (SubcommandProvider provider : providers) {
            Subcommand bare = provider.bareCommand();
            if (bare != null && found != null) {
                throw new IllegalStateException("Two modules give what the command does alone: "
                        + found.getClass().getSimpleName() + " and " + bare.getClass().getSimpleName());
            }
            found = bare != null ? bare : found;
        }
        return found;
    }
}
