package dev.foliate.command;

/**
 * Permission nodes checked by the plugin. Each one must also be declared in the
 * {@code bukkitPluginYaml} block of {@code build.gradle.kts}, which is what gives it a description
 * and a default; a test fails when the two drift apart.
 */
public final class Permissions {

    public static final String COMMAND_INFO = "foliate.command.info";
    public static final String COMMAND_RELOAD = "foliate.command.reload";

    private Permissions() {}
}
