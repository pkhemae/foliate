package dev.foliate.core.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.papermc.paper.command.brigadier.CommandSourceStack;

/**
 * One subcommand of the plugin's command, for example {@code reload} in {@code /foliate reload}.
 * Each subcommand is its own class; {@link RootCommand} assembles them.
 *
 * <p>On Folia a command runs on the region of whoever typed it (the global region for the
 * console). A handler may therefore only touch its own sender directly: anything else, such as
 * another player, a location elsewhere, or blocking I/O, has to go through the schedulers.
 */
public interface Subcommand {

    /** The word typed after the root command. */
    String name();

    /**
     * The permission node required to see and run it. Declare it next to the subcommand, as a
     * {@code PERMISSION} constant, and in the plugin.yml: a test checks that the two agree.
     */
    String permission();

    /**
     * A condition on top of the permission, for a subcommand that only makes sense for some
     * senders. The others can neither see nor run it. It is evaluated often, every time the server
     * works out which commands a sender has, and from any thread: keep it cheap and free of
     * side effects.
     *
     * <p>It sees the source as typed, BEFORE {@code /execute} changes it: Brigadier checks
     * requirements while it parses, and {@code /execute as} or {@code /execute at} only switch the
     * executor or the location when the command runs. So it must depend on the sender alone, never
     * on {@link CommandSourceStack#getExecutor()} or {@link CommandSourceStack#getLocation()}: it
     * would let {@code /execute as} get around a check, or refuse a command that would run as a
     * player. Check those inside {@code executes}, from {@code context.getSource()}, and answer
     * with a message when they do not fit. The same goes for any {@code requires} set in
     * {@link #build}.
     */
    default boolean isAvailableTo(CommandSourceStack source) {
        return true;
    }

    /**
     * Adds what the subcommand does to its node: at least {@code node.executes(...)}, and its
     * arguments if it takes any. The name is already set; the permission and
     * {@link #isAvailableTo} are enforced afterwards, in addition to anything required here.
     */
    void build(LiteralArgumentBuilder<CommandSourceStack> node);
}
