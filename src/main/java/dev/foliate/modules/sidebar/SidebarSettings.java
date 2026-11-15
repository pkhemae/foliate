package dev.foliate.modules.sidebar;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import dev.foliate.core.config.ConfigException;
import java.util.List;

/** The {@code sidebar} section of {@code config.yml}. */
@Configuration
public final class SidebarSettings {

    /** The client cannot display more lines than this. */
    private static final int MAX_LINES = 15;

    private static final char LEGACY_CODE = '§';

    private boolean enabled = true;

    @Comment("Ticks between two refreshes (20 ticks = 1 second).")
    private int refreshTicks = 20;

    @Comment({
        "Title and lines use the MiniMessage format.",
        "Available tags: <player>, <online>, and <papi:placeholder> when PlaceholderAPI is installed,",
        "for example <papi:foliate_joins>."
    })
    private String title = "<aqua><bold>Foliate";

    private List<String> lines = List.of("", "<gray>Player: <white><player>", "<gray>Online: <white><online>", "");

    public SidebarSettings() {}

    public boolean enabled() {
        return enabled;
    }

    public int refreshTicks() {
        return refreshTicks;
    }

    public String title() {
        return title;
    }

    public List<String> lines() {
        return List.copyOf(lines);
    }

    /**
     * Checks what the types alone cannot express.
     *
     * @throws ConfigException with a message an administrator can act on
     */
    public void validate() throws ConfigException {
        if (refreshTicks < 1) {
            throw new ConfigException("sidebar.refresh-ticks must be at least 1");
        }
        if (lines.size() > MAX_LINES) {
            throw new ConfigException("sidebar.lines cannot have more than " + MAX_LINES + " lines");
        }
        // MiniMessage refuses legacy codes outright: caught here, it is one clear message at
        // load time instead of an error on every refresh for every player.
        if (title.indexOf(LEGACY_CODE) >= 0 || lines.stream().anyMatch(line -> line.indexOf(LEGACY_CODE) >= 0)) {
            throw new ConfigException(
                    "sidebar.title and sidebar.lines use the MiniMessage format: write <red> instead of legacy colour codes");
        }
    }
}
