package dev.foliate.testing;

import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/**
 * A minimal {@link CommandSourceStack} for running a command through a Brigadier dispatcher in a
 * test: MockBukkit does not provide one. Give it the sender, and an executor when the command is
 * run as someone else (what {@code /execute as} does).
 */
public record CommandSource(CommandSender sender, Entity executor) implements CommandSourceStack {

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
    public Entity getExecutor() {
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
        return this;
    }
}
