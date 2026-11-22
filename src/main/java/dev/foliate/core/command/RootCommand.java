package dev.foliate.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.kyori.adventure.text.Component;
import org.jspecify.annotations.Nullable;

/**
 * The plugin's command, made of its {@link Subcommand}s. Adding a subcommand is writing its class
 * and handing it over, from {@code FoliatePlugin} or from a module's
 * {@link SubcommandProvider#subcommands()}: nothing in this class changes.
 *
 * <p>The command can also do something when typed alone, such as {@code /fly}: that is a
 * subcommand too, given apart ({@link SubcommandProvider#bareCommand()}), with its own permission
 * and checks. Its name is not typed and not used. Without one, the command typed alone is
 * incomplete, as for any command of the game.
 */
public final class RootCommand {

    private final String name;
    private final @Nullable Subcommand bare;
    private final List<Subcommand> subcommands;

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
     *     argument: an argument after the command alone would compete with the subcommands' names
     * @throws IllegalArgumentException when there is neither a subcommand nor a bare command, or
     *     two subcommands share a name: the second would silently take over the first one's handler
     */
    public RootCommand(String name, @Nullable Subcommand bare, List<Subcommand> subcommands) {
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
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name);
        Predicate<CommandSourceStack> mayRunAlone = source -> false;
        if (bare != null) {
            Subcommand alone = bare;
            LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(name);
            alone.build(node);
            Command<CommandSourceStack> action = node.getCommand();
            if (action == null || !node.getArguments().isEmpty()) {
                throw new IllegalStateException("What /" + name + " does alone must call executes() in its build(),"
                        + " and add no argument: make a subcommand of what takes one");
            }
            mayRunAlone = node.getRequirement().and(source -> allows(alone, source));
            Predicate<CommandSourceStack> allowed = mayRunAlone;
            // The root's requirement lets in whoever may use any subcommand: typed alone, the
            // command still runs only for those allowed to run it alone, and is incomplete for the
            // others, as it would be without a bare command.
            root.executes(context -> {
                if (!allowed.test(context.getSource())) {
                    context.getSource().getSender().sendMessage(Component.translatable("command.unknown.command"));
                    return 0;
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
            root.then(node);
        }
        return root.build();
    }

    private static boolean allows(Subcommand subcommand, CommandSourceStack source) {
        return source.getSender().hasPermission(subcommand.permission()) && subcommand.isAvailableTo(source);
    }
}
