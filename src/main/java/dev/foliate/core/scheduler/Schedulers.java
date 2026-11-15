package dev.foliate.core.scheduler;

import java.util.concurrent.CompletableFuture;
import org.bukkit.Location;
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
 * tasks and silently drops region and entity ones. Work left for {@code onDisable} has to run
 * synchronously, on the thread that calls it.
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
     * Teleports an entity from any thread, on Folia and on Paper alike. {@code Entity#teleport} is
     * broken on Folia, and Paper only accepts {@code teleportAsync} from the thread ticking the
     * entity: this hops to the entity's scheduler first when needed.
     *
     * <p>Completes with {@code false} when the teleport was refused or the entity no longer
     * exists. Never call {@code join()} or {@code get()} on the result from a tick thread.
     */
    CompletableFuture<Boolean> teleport(Entity entity, Location destination);
}
