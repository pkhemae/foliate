package dev.foliate.example;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.foliate.command.Permissions;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.command.brigadier.argument.ArgumentTypes;
import io.papermc.paper.command.brigadier.argument.resolvers.BlockPositionResolver;
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CompletionException;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.slf4j.Logger;

/**
 * {@code /example}: four subcommands, one for each kind of thing a command cannot touch directly
 * on Folia.
 *
 * <p>A command runs on the region of whoever typed it. That thread owns the sender and nothing
 * else: another player may be in a region ticking on another thread, a block far away belongs to
 * another region, the world's time belongs to the global region, and a file must not be written
 * from any tick thread at all. Each handler below hops to the right context, does its work there,
 * and comes back to the sender to report.
 */
public final class ExampleCommand {

    private final Schedulers schedulers;
    private final MessageService messages;
    private static final int MAX_NOTE_LENGTH = 200;

    private final Path notes;
    private final Logger logger;

    public ExampleCommand(Schedulers schedulers, MessageService messages, Path dataDirectory, Logger logger) {
        this.schedulers = schedulers;
        this.messages = messages;
        this.logger = logger;
        this.notes = dataDirectory.resolve("example-notes.txt");
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("example")
                .requires(source -> source.getSender().hasPermission(Permissions.COMMAND_EXAMPLE))
                .then(Commands.literal("feed")
                        .then(Commands.argument("player", ArgumentTypes.player()).executes(context -> {
                            Player target = context.getArgument("player", PlayerSelectorArgumentResolver.class)
                                    .resolve(context.getSource())
                                    .getFirst();
                            feed(context.getSource().getSender(), target);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("block")
                        .then(Commands.argument("position", ArgumentTypes.blockPosition()).executes(context -> {
                            World world = context.getSource().getLocation().getWorld();
                            Location location = context.getArgument("position", BlockPositionResolver.class)
                                    .resolve(context.getSource())
                                    .toLocation(world);
                            block(context.getSource().getSender(), location);
                            return Command.SINGLE_SUCCESS;
                        })))
                .then(Commands.literal("time").executes(context -> {
                    time(context.getSource().getSender(), context.getSource().getLocation().getWorld());
                    return Command.SINGLE_SUCCESS;
                }))
                .then(Commands.literal("note")
                        .then(Commands.argument("text", StringArgumentType.greedyString()).executes(context -> {
                            note(context.getSource().getSender(), StringArgumentType.getString(context, "text"));
                            return Command.SINGLE_SUCCESS;
                        })))
                .build();
    }

    /**
     * Another player. They may be in a region that another thread is ticking right now, so their
     * state is changed from their own scheduler, which follows them wherever they are. If they log
     * out first, the future fails with {@link EntityRetiredException} and the sender is told.
     */
    void feed(CommandSender sender, Player target) {
        // Read while still on a thread that may: an entity's name is not safe to ask for later.
        String name = target.getName();
        schedulers.entity(target)
                .run(() -> {
                    target.setFoodLevel(20);
                    target.setSaturation(20);
                })
                .whenComplete((done, error) -> {
                    if (error == null) {
                        reply(sender, "example.feed.done", Placeholder.unparsed("player", name));
                    } else if (cause(error) instanceof EntityRetiredException) {
                        reply(sender, "example.feed.gone", Placeholder.unparsed("player", name));
                    } else {
                        failed(sender, "feed " + name, error);
                    }
                });
    }

    /**
     * A location. It belongs to whichever region contains it, and the region scheduler runs the
     * task on the thread that owns it: even reading a block must happen there.
     *
     * <p>The scheduler does not load the chunk, and this does not either. Asking for a block in a
     * chunk that is not loaded would load it on the spot, blocking the region, and generate it if
     * nobody ever went there: a command that did so would let anyone generate terrain at will.
     */
    void block(CommandSender sender, Location location) {
        TagResolver[] position = {
            Placeholder.unparsed("x", String.valueOf(location.getBlockX())),
            Placeholder.unparsed("y", String.valueOf(location.getBlockY())),
            Placeholder.unparsed("z", String.valueOf(location.getBlockZ())),
        };
        schedulers.region(location)
                .supply(() -> location.isChunkLoaded()
                        ? Optional.of(location.getBlock().getType())
                        : Optional.<Material>empty())
                .whenComplete((type, error) -> {
                    if (error != null) {
                        failed(sender, "block", error);
                    } else if (type.isEmpty()) {
                        reply(sender, "example.block.unloaded", position);
                    } else {
                        TagResolver[] found = Arrays.copyOf(position, position.length + 1);
                        found[position.length] = Placeholder.unparsed("type", type.get().getKey().asMinimalString());
                        reply(sender, "example.block.found", found);
                    }
                });
    }

    /** World-wide state. Time, weather and game rules belong to the global region. */
    void time(CommandSender sender, World world) {
        schedulers.global().supply(world::getFullTime).whenComplete((time, error) -> {
            if (error != null) {
                failed(sender, "time", error);
            } else {
                reply(sender, "example.time", Placeholder.unparsed("time", String.valueOf(time)));
            }
        });
    }

    /** Blocking I/O. Never on a tick thread: a slow disk would freeze every player of the region. */
    void note(CommandSender sender, String text) {
        String author = sender.getName();
        // Bounded: whatever a sender types ends up on disk.
        String line = author + ": " + (text.length() > MAX_NOTE_LENGTH ? text.substring(0, MAX_NOTE_LENGTH) : text);
        schedulers.async()
                .run(() -> {
                    try {
                        Files.writeString(
                                notes,
                                line + System.lineSeparator(),
                                StandardOpenOption.CREATE,
                                StandardOpenOption.APPEND);
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                })
                .whenComplete((done, error) -> {
                    if (error == null) {
                        reply(sender, "example.note.saved");
                    } else {
                        failed(sender, "note", error);
                    }
                });
    }

    /**
     * {@code run} and {@code supply} hand failures to the future and log nothing: whoever chains on
     * them has to. A failure nobody handles here would vanish, and the sender would wait forever.
     */
    private void failed(CommandSender sender, String what, Throwable error) {
        logger.error("/example {} failed", what, cause(error));
        reply(sender, "example.failed");
    }

    private static Throwable cause(Throwable error) {
        return error instanceof CompletionException && error.getCause() != null ? error.getCause() : error;
    }

    /** Back to the sender, on the context the sender lives on. Nobody to tell if they have left. */
    private void reply(CommandSender sender, String key, TagResolver... arguments) {
        try {
            schedulers.sender(sender).executeOrRun(() -> messages.send(sender, key, arguments));
        } catch (EntityRetiredException gone) {
            // The sender logged out while the work was in progress.
        }
    }
}
