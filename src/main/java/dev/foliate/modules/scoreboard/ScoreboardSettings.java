package dev.foliate.modules.scoreboard;

import de.exlll.configlib.Comment;
import de.exlll.configlib.Configuration;
import dev.foliate.core.config.ConfigException;
import java.util.List;
import java.util.regex.Pattern;

/** The {@code scoreboard} section of {@code config.yml}. */
@Configuration
public final class ScoreboardSettings {

    /** The client cannot display more lines than this. */
    private static final int MAX_LINES = 15;

    private static final char LEGACY_CODE = '§';

    /** {@code <papi>} or {@code <papi:>}: the tag without the placeholder it needs. */
    private static final Pattern EMPTY_PAPI_TAG = Pattern.compile("<papi\\s*(:\\s*)?>", Pattern.CASE_INSENSITIVE);

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

    public ScoreboardSettings() {}

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
            throw new ConfigException("scoreboard.refresh-ticks must be at least 1");
        }
        if (lines.size() > MAX_LINES) {
            throw new ConfigException("scoreboard.lines cannot have more than " + MAX_LINES + " lines");
        }
        // MiniMessage refuses legacy codes outright: caught here, it is one clear message at
        // load time instead of an error on every refresh for every player.
        if (title.indexOf(LEGACY_CODE) >= 0 || lines.stream().anyMatch(line -> line.indexOf(LEGACY_CODE) >= 0)) {
            throw new ConfigException(
                    "scoreboard.title and scoreboard.lines use the MiniMessage format: write <red> instead of legacy colour codes");
        }
        // Like a legacy code, it would fail on every refresh for every player.
        if (EMPTY_PAPI_TAG.matcher(title).find() || lines.stream().anyMatch(line -> EMPTY_PAPI_TAG.matcher(line).find())) {
            throw new ConfigException(
                    "scoreboard.title and scoreboard.lines: the <papi> tag needs a placeholder, as in <papi:player_name>");
        }
    }
}
