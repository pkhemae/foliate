package dev.foliate.modules.scoreboard;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.core.scheduler.TaskHandle;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.util.Ticks;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/**
 * Shows each player a scoreboard whose title and lines come from {@code config.yml}.
 *
 * <p>Threading: a player's board is created, refreshed and removed only from that player's own
 * scheduler. Join and quit events already run there; reloads and enabling hop to it. The map of
 * boards is the only state shared between threads, with {@link #disable()}: when the plugin is
 * disabled while the server runs, a refresh or a join already under way on a player's thread may
 * still be running as the boards are removed. FastBoard locks each board, which keeps a draw from
 * interleaving with its removal and lets a late draw do nothing (see {@link FastBoardScoreboard}),
 * and a board created during {@code disable} is removed by whichever of the two comes last.
 *
 * <p>A board that cannot be drawn is left as it was: for instance a placeholder expansion built
 * against another version of its plugin, which fails with an error that MiniMessage lets through
 * (an exception only turns the tag into plain text), or the board library failing. Since every
 * refresh would fail the same way, the failure is logged once, not on every refresh for every
 * player: again only after a board has drawn successfully since, or after a reload.
 */
public final class ScoreboardModule implements Module, Listener {

    private final Plugin plugin;
    private final Schedulers schedulers;
    private final Supplier<ScoreboardSettings> settings;
    private final MessageService messages;
    private final Function<Player, ScoreboardDisplay> boardFactory;
    private final SupportCheck supportCheck;
    private final Logger logger;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();
    /** Whether a drawing failure has been logged and not followed by a successful draw or a reload. */
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    /** Between enable and disable: no board may be created outside. */
    private volatile boolean running;

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
        this.schedulers = schedulers;
        this.settings = settings;
        this.messages = messages;
        this.boardFactory = boardFactory;
        this.supportCheck = supportCheck;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "scoreboard";
    }

    @Override
    public void enable() throws ScoreboardUnavailableException {
        supportCheck.run();
        running = true;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        applyToAll();
    }

    @Override
    public void reload() {
        failureLogged.set(false);
        applyToAll();
    }

    @Override
    public void disable() {
        running = false;
        HandlerList.unregisterAll(this);
        // Nothing scheduled on the players' threads runs any more. Removing a board only sends
        // packets, which is safe from here. One at a time: a show still under way on a player's
        // thread may add a board meanwhile, and removes it itself once it sees running is false.
        for (UUID id : List.copyOf(entries.keySet())) {
            Entry entry = entries.remove(id);
            if (entry != null) {
                entry.remove();
            }
        }
    }

    /** Whether the configuration asks for boards at all. For the server test, which lives in this package. */
    boolean showsBoards() {
        return settings.get().enabled();
    }

    /** Whether a player has a board right now. For the server test, which lives in this package. */
    boolean hasBoard(UUID id) {
        return entries.containsKey(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        show(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        hide(event.getPlayer());
    }

    /** Applies the current settings to everyone online, each on their own scheduler. */
    private void applyToAll() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            try {
                schedulers.entity(player).executeOrRun(() -> apply(player));
            } catch (EntityRetiredException leaving) {
                // Still listed as online but already on the way out: the quit event removes the
                // board. Carry on with the other players.
            }
        }
    }

    /**
     * Brings a player's board in line with the settings. A board is only rebuilt when its refresh
     * interval changed: rebuilding it makes it flicker, and the title and lines are read on every
     * refresh anyway, so redrawing it is enough.
     */
    private void apply(Player player) {
        ScoreboardSettings settings = this.settings.get();
        Entry entry = entries.get(player.getUniqueId());
        if (!settings.enabled()) {
            hide(player);
        } else if (entry != null && entry.refreshTicks() == settings.refreshTicks()) {
            // A failure after a reload is worth logging again, whatever this board did before.
            entry.board().failing = false;
            draw(player, entry.board());
        } else {
            hide(player);
            show(player);
        }
    }

    private void show(Player player) {
        ScoreboardSettings settings = this.settings.get();
        if (!running || !settings.enabled() || !player.isOnline()) {
            return;
        }
        Board board = new Board(boardFactory.apply(player));
        Duration period = Ticks.duration(settings.refreshTicks());
        TaskHandle refresh;
        try {
            refresh = schedulers.entity(player).runTimer(period, period, () -> draw(player, board));
        } catch (RuntimeException e) {
            board.display().delete();
            if (!plugin.isEnabled()) {
                // Disabled since the check above: the server refuses timers from now on.
                return;
            }
            throw e;
        }
        if (refresh.isCancelled()) {
            // The player is being removed: no refresh will ever run, and no quit will remove it.
            board.display().delete();
            hide(player);
            return;
        }
        UUID id = player.getUniqueId();
        Entry entry = new Entry(board, refresh, settings.refreshTicks());
        Entry previous = entries.put(id, entry);
        if (previous != null) {
            previous.remove();
        }
        if (!running) {
            // disable() ran meanwhile and may have missed this board: whoever takes it out removes it.
            if (entries.remove(id, entry)) {
                entry.remove();
            }
            return;
        }
        // Already on the player's thread: drawn now, the board does not show up empty until the
        // first refresh.
        draw(player, board);
    }

    private void hide(Player player) {
        Entry entry = entries.remove(player.getUniqueId());
        if (entry != null) {
            entry.remove();
        }
    }

    /** Renders the title and the lines, then updates the board. Runs on the player's thread. */
    private void draw(Player player, Board board) {
        try {
            // Read on every refresh: a reload that changes the title or the lines needs no new board.
            ScoreboardSettings settings = this.settings.get();
            TagResolver[] values = {
                Placeholder.unparsed("player", player.getName()),
                Placeholder.unparsed("online", String.valueOf(plugin.getServer().getOnlinePlayers().size())),
            };
            Component title = messages.parse(player, settings.title(), values);
            List<Component> lines = settings.lines().stream()
                    .map(line -> messages.parse(player, line, values))
                    .toList();
            board.display().update(title, lines);
        } catch (RuntimeException | LinkageError e) {
            // Placeholders run other plugins' code, which can fail in any way. Logged once, with the
            // stack trace that points at the culprit, see the class description.
            if (!board.failing) {
                board.failing = true;
                if (failureLogged.compareAndSet(false, true)) {
                    logger.error(
                            "The scoreboard of {} could not be drawn and is left as it was. Further failures"
                                    + " are not logged until a scoreboard draws again or the plugin is reloaded",
                            player.getName(),
                            e);
                }
            }
            return;
        }
        if (board.failing) {
            board.failing = false;
            failureLogged.set(false);
        }
    }

    /** Fails when scoreboards cannot work on this server. */
    @FunctionalInterface
    interface SupportCheck {
        void run() throws ScoreboardUnavailableException;
    }

    /** A player's board, and whether its last draw failed. Only touched from the player's thread. */
    private static final class Board {

        private final ScoreboardDisplay display;
        private boolean failing;

        Board(ScoreboardDisplay display) {
            this.display = display;
        }

        ScoreboardDisplay display() {
            return display;
        }
    }

    private record Entry(Board board, TaskHandle refresh, int refreshTicks) {

        void remove() {
            refresh.cancel();
            board.display().delete();
        }
    }
}
