package dev.foliate.modules.scoreboard;

import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.Module;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import dev.foliate.core.scheduler.TaskHandle;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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

/**
 * Shows each player a scoreboard whose title and lines come from {@code config.yml}.
 *
 * <p>Threading: a player's board is created, refreshed and removed only from that player's own
 * scheduler. Join and quit events already run there; reloads and enabling hop to it. The map of
 * boards is the only state shared between threads.
 */
public final class ScoreboardModule implements Module, Listener {

    private final Plugin plugin;
    private final Schedulers schedulers;
    private final Supplier<ScoreboardSettings> settings;
    private final MessageService messages;
    private final Function<Player, ScoreboardDisplay> boardFactory;
    private final SupportCheck supportCheck;
    private final Map<UUID, Entry> entries = new ConcurrentHashMap<>();

    public ScoreboardModule(Plugin plugin, Schedulers schedulers, Supplier<ScoreboardSettings> settings, MessageService messages) {
        this(plugin, schedulers, settings, messages, FastBoardScoreboard::new, FastBoardScoreboard::ensureSupported);
    }

    ScoreboardModule(
            Plugin plugin,
            Schedulers schedulers,
            Supplier<ScoreboardSettings> settings,
            MessageService messages,
            Function<Player, ScoreboardDisplay> boardFactory,
            SupportCheck supportCheck) {
        this.plugin = plugin;
        this.schedulers = schedulers;
        this.settings = settings;
        this.messages = messages;
        this.boardFactory = boardFactory;
        this.supportCheck = supportCheck;
    }

    @Override
    public String name() {
        return "scoreboard";
    }

    @Override
    public void enable() throws ScoreboardUnavailableException {
        supportCheck.run();
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        applyToAll();
    }

    @Override
    public void reload() {
        applyToAll();
    }

    @Override
    public void disable() {
        HandlerList.unregisterAll(this);
        // No region work is possible during shutdown. Removing a board only sends packets, which
        // is safe from here.
        entries.values().forEach(Entry::remove);
        entries.clear();
    }

    /** Whether the configuration asks for boards at all. For the self-test, which lives in this package. */
    boolean showsBoards() {
        return settings.get().enabled();
    }

    /** Whether a player has a board right now. For the self-test, which lives in this package. */
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
            draw(player, entry.board());
        } else {
            hide(player);
            show(player);
        }
    }

    private void show(Player player) {
        ScoreboardSettings settings = this.settings.get();
        if (!settings.enabled() || !player.isOnline()) {
            return;
        }
        ScoreboardDisplay board = boardFactory.apply(player);
        TaskHandle refresh = schedulers.entity(player)
                .runTimer(Ticks.duration(1), Ticks.duration(settings.refreshTicks()), () -> draw(player, board));
        Entry previous = entries.put(player.getUniqueId(), new Entry(board, refresh, settings.refreshTicks()));
        if (previous != null) {
            previous.remove();
        }
    }

    private void hide(Player player) {
        Entry entry = entries.remove(player.getUniqueId());
        if (entry != null) {
            entry.remove();
        }
    }

    private void draw(Player player, ScoreboardDisplay board) {
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
        board.update(title, lines);
    }

    /** Fails when scoreboards cannot work on this server. */
    @FunctionalInterface
    interface SupportCheck {
        void run() throws ScoreboardUnavailableException;
    }

    private record Entry(ScoreboardDisplay board, TaskHandle refresh, int refreshTicks) {

        void remove() {
            refresh.cancel();
            board.delete();
        }
    }
}
