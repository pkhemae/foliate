package dev.foliate.core.config;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;

/**
 * The content of {@code config.yml}. Adding a setting means adding a field here: the file is
 * regenerated from this class, comments included, and keys missing from an older file take the
 * field's initial value.
 *
 * <p>Instances are never modified after loading. Fields cannot be {@code final} because the
 * library sets them by reflection, so there are no setters: a reload publishes a new instance.
 * Getters of collection fields must return a copy ({@code List.copyOf}): the library fills them
 * with mutable collections, which must not be shared between threads.
 */
@Configuration
public final class PluginConfig {

    @Comment({"Internal version of this file, used to migrate it when the plugin updates.", "Do not edit."})
    @SuppressWarnings("unused") // Read and written by the library; the migrator works on the raw file.
    private int configVersion = ConfigMigrator.CURRENT_VERSION;

    @Comment("Logs extra details that help when reporting a problem.")
    private boolean debug = false;

    @Comment({
        "Language of the console, and of players whose game language has no file in the lang folder.",
        "Must be the name of a file in that folder, without the extension."
    })
    private String defaultLocale = "en";

    private PluginConfig() {}

    /** The configuration a fresh install starts with. */
    static PluginConfig defaults() {
        return new PluginConfig();
    }

    public boolean debug() {
        return debug;
    }

    public String defaultLocale() {
        return defaultLocale;
    }

    /**
     * Checks what the types alone cannot express. Runs before a loaded configuration is published
     * or written back, and its message is shown to the administrator: name the setting as it
     * appears in the file.
     *
     * @throws ConfigException with a message an administrator can act on
     */
    void validate() throws ConfigException {
        if (defaultLocale.isBlank()) {
            throw new ConfigException("default-locale must not be empty");
        }
    }
}
