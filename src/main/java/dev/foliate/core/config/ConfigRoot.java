package dev.foliate.core.config;

/**
 * The root of a configuration file: the class {@link ConfigService} maps the file to.
 *
 * <p>It must also declare a {@code configVersion} field that starts at the number of the current
 * format (the number of migration steps plus one), so that the version is written to the file.
 */
public interface ConfigRoot {

    /** The value of the {@code config-version} setting this object holds. */
    int configVersion();

    /**
     * Checks what the types alone cannot express. Runs before a loaded configuration is published
     * or written back, and its message is shown to the administrator: name the setting as it
     * appears in the file.
     *
     * @throws ConfigException with a message an administrator can act on
     */
    void validate() throws ConfigException;
}
