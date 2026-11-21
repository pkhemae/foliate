package dev.foliate.core.scheduler;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;

/**
 * The only way this plugin schedules work. Folia has no main thread: pick the context that owns
 * what the task touches.
 *
 * <ul>
 *   <li>{@link #entity(Entity)}: anything about one entity or player. Follows it across regions,
 *       and only runs while the entity is loaded.
 *   <li>{@link #region(Location)}: blocks and chunks at a location. Not for entities.
 *   <li>{@link #global()}: world-wide state such as time, weather, game rules.
 *   <li>{@link #async()}: blocking I/O. Never touches the world.
 * </ul>
 *
 * <p>{@code BukkitScheduler} must never be used: it does not work on Folia.
 *
 * <p>Nothing can be scheduled once the plugin is disabling: the server rejects global and async
 * tasks, as well as delayed and repeating region and entity ones, and silently drops region and
 * entity tasks given to {@code execute}. Region and entity tasks already scheduled no longer
 * start. Work left for {@code onDisable} has to run synchronously, on the thread that calls it.
 *
 * <p>An interface so that code depending on it can be tested with a deterministic fake instead of
 * a server.
 */
public interface Schedulers {

    TaskContext async();

    TaskContext global();

    TaskContext region(Location location);

    TaskContext entity(Entity entity);

    /**
     * The context a command sender expects to hear back on: its own region for a player or any
     * other entity, the global region for the console and the like. Some senders reject messages
     * that come from another thread.
     */
    default TaskContext sender(CommandSender sender) {
        return sender instanceof Entity entity ? entity(entity) : global();
    }

    /**
     * Teleports an entity from any thread, on Folia and on Paper alike. {@code Entity#teleport} is
     * broken on Folia, and Paper only accepts {@code teleportAsync} from the thread ticking the
     * entity: this hops to the entity's scheduler first when needed.
     *
     * <p>Completes with {@code false} when the teleport was refused or the entity no longer
     * exists. Never call {@code join()} or {@code get()} on the result from a tick thread.
     */
    CompletableFuture<Boolean> teleport(Entity entity, Location destination);
}
