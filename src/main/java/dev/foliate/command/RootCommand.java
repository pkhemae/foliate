package dev.foliate.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The plugin's command, made of its {@link Subcommand}s. Adding a subcommand is writing its class
 * and adding it to the list given here: nothing in this class changes.
 */
public final class RootCommand {

    private final String name;
    private final List<Subcommand> subcommands;

    /**
     * @throws IllegalArgumentException when there is no subcommand, or two share a name: the
     *     second would silently take over the first one's handler
     */
    public RootCommand(String name, List<Subcommand> subcommands) {
        if (subcommands.isEmpty()) {
            throw new IllegalArgumentException("/" + name + " needs at least one subcommand");
        }
        Set<String> names = new HashSet<>();
        for (Subcommand subcommand : subcommands) {
            if (!names.add(subcommand.name())) {
                throw new IllegalArgumentException("Two subcommands are named '" + subcommand.name() + "'");
            }
        }
        this.name = name;
        this.subcommands = List.copyOf(subcommands);
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                // The command itself is shown to whoever may use at least one subcommand.
                .requires(source -> subcommands.stream().anyMatch(subcommand -> allows(subcommand, source)));
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
