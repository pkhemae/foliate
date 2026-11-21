package dev.foliate.core.command;

import java.util.List;

/**
 * A module that adds subcommands to the plugin's command. {@code FoliatePlugin} asks the modules
 * that are enabled, so a feature's subcommands exist exactly when the feature runs, and removing a
 * feature removes them with it.
 */
public interface SubcommandProvider {

    /** Called once, after every module is enabled, to build the command. */
    List<Subcommand> subcommands();
}
