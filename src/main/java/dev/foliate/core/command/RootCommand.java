package dev.foliate.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.builder.ArgumentBuilder;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.tree.CommandNode;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.command.CommandSender;
import org.jspecify.annotations.Nullable;

/**
 * The plugin's command, made of its {@link Subcommand}s. Adding a subcommand is writing its class
 * and handing it over, from {@code FoliatePlugin} or from a module's
 * {@link SubcommandProvider#subcommands()}: nothing in this class changes.
 *
 * <p>The command can also do something when typed alone, such as {@code /fly}: that is a
 * subcommand too, given apart ({@link SubcommandProvider#bareCommand()}), with its own permission
 * and checks. Its name is not typed and not used. Without one, the command typed alone is
 * incomplete, as for any command of the game. With one, the players who may only use subcommands
 * still get that error when they type it alone, but their client no longer marks it incomplete
 * while they type: Brigadier cannot make a node runnable for some sources only.
 *
 * <p>Given a {@link Usage}, a subcommand typed without all its arguments, such as
 * {@code /foliate pay} or {@code /foliate pay Steve}, answers with what may follow instead of the game's
 * "unknown or incomplete command". The usage is read from the subcommand's own tree, so it never
 * goes stale, and shows only what the sender may use. It is written from the command's own name,
 * however it was typed ({@code /foliate:foliate pay}, or an alias). A node reached through a
 * redirect inside a subcommand shows none: the redirect still points at the tree as it was built.
 *
 * <p>Mistakes in the subcommands' {@code build} are thrown by {@link #build()}: call it once while
 * the plugin starts, so that they stop it there.
 */
public final class RootCommand {

    /** Shows a sender the usage of a subcommand they typed without all its arguments. */
    @FunctionalInterface
    public interface Usage {

        /**
         * Shows the lines, on the sender's own thread: the command runs there.
         *
         * @param lines one per way to complete the command, each written in full from the root, as
         *     Brigadier writes usages: {@code /foliate pay <player> <amount>}, {@code [optional]},
         *     {@code (a|b)} for a choice
         */
        void show(CommandSender sender, List<String> lines);
    }

    /** Only for its usages, which need no root: {@link CommandDispatcher#getSmartUsage} reads the node given. */
    private static final CommandDispatcher<CommandSourceStack> USAGES = new CommandDispatcher<>();

    private final String name;
    private final @Nullable Subcommand bare;
    private final List<Subcommand> subcommands;
    private final @Nullable Usage usage;

    /**
     * @throws IllegalArgumentException when there is no subcommand, or two share a name: the
     *     second would silently take over the first one's handler
     */
    public RootCommand(String name, List<Subcommand> subcommands) {
        this(name, null, subcommands);
    }

    /**
     * @param bare what the command does when typed alone, or null when it needs a subcommand. Its
     *     {@code build} is given a node of its own, on which it calls {@code executes} and adds no
     *     argument, redirect or fork: an argument after the command alone, a player's name say,
     *     would compete with the subcommands' names
     * @throws IllegalArgumentException when there is neither a subcommand nor a bare command, or
     *     two subcommands share a name: the second would silently take over the first one's handler
     */
    public RootCommand(String name, @Nullable Subcommand bare, List<Subcommand> subcommands) {
        this(name, bare, subcommands, null);
    }

    /**
     * @param usage what shows the usage of a subcommand typed without all its arguments, or null to
     *     leave that to the game's "unknown or incomplete command"
     * @see #RootCommand(String, Subcommand, List)
     */
    public RootCommand(String name, @Nullable Subcommand bare, List<Subcommand> subcommands, @Nullable Usage usage) {
        if (subcommands.isEmpty() && bare == null) {
            throw new IllegalArgumentException("/" + name + " needs at least one subcommand");
        }
        Set<String> names = new HashSet<>();
        for (Subcommand subcommand : subcommands) {
            if (!names.add(subcommand.name())) {
                throw new IllegalArgumentException("Two subcommands are named '" + subcommand.name() + "'");
            }
        }
        this.name = name;
        this.bare = bare;
        this.subcommands = List.copyOf(subcommands);
        this.usage = usage;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);
        Predicate<CommandSourceStack> mayRunAlone = source -> false;
        if (bare != null) {
            Subcommand alone = bare;
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name);
            alone.build(node);
            Command<CommandSourceStack> action = node.getCommand();
            if (action == null || !node.getArguments().isEmpty() || node.getRedirect() != null) {
                throw new IllegalStateException("What /" + name + " does alone must call executes() in its build(),"
                        + " and add no argument nor redirect: make a subcommand of what takes one");
            }
            mayRunAlone = node.getRequirement().and(source -> allows(alone, source));
            Predicate<CommandSourceStack> allowed = mayRunAlone;
            // The root's requirement lets in whoever may use any subcommand: typed alone, the
            // command still runs only for those allowed to run it alone, and fails for the others
            // with the game's own error, as it would without a bare command.
            root.executes(context -> {
                if (!allowed.test(context.getSource())) {
                    throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().create();
                }
                return action.run(context);
            });
        }
        Predicate<CommandSourceStack> mayRunAloneOrUse = mayRunAlone;
        // The command itself is shown to whoever may run it alone or use at least one subcommand.
        root.requires(source -> mayRunAloneOrUse.test(source)
                || subcommands.stream().anyMatch(subcommand -> allows(subcommand, source)));
        for (Subcommand subcommand : subcommands) {
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(subcommand.name());
            subcommand.build(node);
            if (node.getCommand() == null && node.getArguments().isEmpty()) {
                throw new IllegalStateException(
                        "Subcommand '" + subcommand.name() + "' does nothing: its build() must call executes()");
            }
            // Applied after build(), and on top of whatever it required: Brigadier's requires()
            // replaces the previous condition, so a subcommand could otherwise drop its own
            // permission check without noticing.
            node.requires(node.getRequirement().and(source -> allows(subcommand, source)));
            LiteralCommandNode<CommandSourceStack> built = node.build();
            root.then(usage == null ? built : withUsage(built, "/" + name + " " + subcommand.name(), usage));
        }
        return root.build();
    }

    /**
     * A copy of the node and of what lies under it, where a node that does not run by itself but
     * leads further shows the usage of what may follow. Requirements, suggestions and redirects are
     * kept: {@link CommandNode#createBuilder} copies them.
     *
     * @param typed the command up to this node, as its usage reads: {@code /foliate pay <player>}
     */
    private static CommandNode<CommandSourceStack> withUsage(
            CommandNode<CommandSourceStack> node, String typed, Usage usage) {
        ArgumentBuilder<CommandSourceStack, ?> copy = node.createBuilder();
        if (copy.getCommand() == null && copy.getRedirect() == null && !node.getChildren().isEmpty()) {
            copy.executes(context -> showUsage(context, node, typed, usage));
        }
        for (CommandNode<CommandSourceStack> child : node.getChildren()) {
            copy.then(withUsage(child, typed + " " + child.getUsageText(), usage));
        }
        return copy.build();
    }

    /**
     * Shows what may follow the node. Read from the original node: whether a part is optional
     * depends on whether its node runs by itself, which the copies that show a usage all do.
     */
    private static int showUsage(
            CommandContext<CommandSourceStack> context, CommandNode<CommandSourceStack> node, String typed, Usage usage)
            throws CommandSyntaxException {
        List<String> lines = USAGES.getSmartUsage(node, context.getSource()).values().stream()
                .map(rest -> typed + " " + rest)
                .sorted()
                .toList();
        if (lines.isEmpty()) {
            // Nothing that may follow is allowed to this sender: the command is as incomplete as
            // before, and the game shows where it stops, as it would.
            StringReader reader = new StringReader(context.getInput());
            reader.setCursor(context.getRange().getEnd());
            throw CommandSyntaxException.BUILT_IN_EXCEPTIONS.dispatcherUnknownCommand().createWithContext(reader);
        }
        usage.show(context.getSource().getSender(), lines);
        return 0;
    }

    private static boolean allows(Subcommand subcommand, CommandSourceStack source) {
        return source.getSender().hasPermission(subcommand.permission()) && subcommand.isAvailableTo(source);
    }
}
