package dev.foliate.modules.scoreboard;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.modules.scoreboard.listener.ScoreboardListener;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * The scoreboard feature: a board for each player, whose title and lines come from
 * {@code config.yml}. The boards themselves are {@link Scoreboards}; the joins and quits reach
 * them through {@link ScoreboardListener}. This class only puts them together and follows the
 * plugin's lifecycle.
 */
public final class ScoreboardModule implements Module {

    private final Plugin plugin;
    private final SupportCheck supportCheck;
    private final Scoreboards scoreboards;
    private final ScoreboardListener listener;

    public ScoreboardModule(Plugin plugin, Schedulers schedulers, Supplier<ScoreboardSettings> settings, MessageService messages) {
        this(
                plugin,
                schedulers,
                settings,
                messages,
                FastBoardScoreboard::new,
                FastBoardScoreboard::ensureSupported,
                plugin.getSLF4JLogger());
    }

    ScoreboardModule(
            Plugin plugin,
            Schedulers schedulers,
            Supplier<ScoreboardSettings> settings,
            MessageService messages,
            Function<Player, ScoreboardDisplay> boardFactory,
            SupportCheck supportCheck,
            Logger logger) {
        this.plugin = plugin;
        this.supportCheck = supportCheck;
        this.scoreboards = new Scoreboards(plugin, schedulers, settings, messages, boardFactory, logger);
        this.listener = new ScoreboardListener(scoreboards);
    }

    @Override
    public String name() {
        return "scoreboard";
    }

    @Override
    public void enable() throws ScoreboardUnavailableException {
        supportCheck.run();
        // Listening first: a player who joins before the boards start is skipped by the listener,
        // then found online by start(); one who joins after is shown by the listener.
        plugin.getServer().getPluginManager().registerEvents(listener, plugin);
        scoreboards.start();
    }

    @Override
    public void reload() {
        scoreboards.reload();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(listener);
        scoreboards.stop();
    }

    /** Whether the configuration asks for boards at all. For the server test, which lives in this package. */
    boolean showsBoards() {
        return scoreboards.showsBoards();
    }

    /** Whether a player has a board right now. For the server test, which lives in this package. */
    boolean hasBoard(UUID id) {
        return scoreboards.hasBoard(id);
    }

    /** The boards, for the tests, which live in this package. */
    Scoreboards scoreboards() {
        return scoreboards;
    }

    /** Fails when scoreboards cannot work on this server. */
    @FunctionalInterface
    interface SupportCheck {
        void run() throws ScoreboardUnavailableException;
    }
}
