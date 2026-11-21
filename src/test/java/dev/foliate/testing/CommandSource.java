package dev.foliate.testing;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/**
 * A minimal {@link CommandSourceStack} for running a command through a Brigadier dispatcher in a
 * test: MockBukkit does not provide one.
 *
 * <p>To run a command as someone else, type {@code execute as <player> run ...} through a
 * dispatcher given {@link TestCommands#registerExecuteAs}, rather than building a source with that
 * executor: on the server, Brigadier checks the requirements while it parses, with the source as
 * typed, and {@code /execute as} only swaps the executor (through {@link #withExecutor}) when the
 * command runs. A source with the executor already set would pass checks the server does not.
 *
 * @param executor the entity the command runs as: the sender itself for a player, none for the
 *     console or a command block
 */
public record CommandSource(CommandSender sender, @Nullable Entity executor) implements CommandSourceStack {

    /** A command typed by the sender: for a player, the executor is that player. */
    public CommandSource(CommandSender sender) {
        this(sender, sender instanceof Entity entity ? entity : null);
    }

    @Override
    public Location getLocation() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CommandSender getSender() {
        return sender;
    }

    @Override
    public @Nullable Entity getExecutor() {
        return executor;
    }

    @Override
    public Player getPlayerOrThrow() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Entity getEntityOrThrow() {
        throw new UnsupportedOperationException();
    }

    @Override
    public CommandSourceStack withLocation(Location location) {
        return this;
    }

    @Override
    public CommandSourceStack withExecutor(Entity executor) {
        return new CommandSource(sender, executor);
    }
}
