package dev.foliate.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.tree.LiteralCommandNode;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.List;

/**
 * The plugin's command, made of its {@link Subcommand}s. Adding a subcommand is writing its class
 * and adding it to the list given here: nothing in this class changes.
 */
public final class RootCommand {

    private final String name;
    private final List<Subcommand> subcommands;

    public RootCommand(String name, List<Subcommand> subcommands) {
        this.name = name;
        this.subcommands = List.copyOf(subcommands);
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(name)
                // The command itself is shown to whoever may use at least one subcommand.
                .requires(source -> subcommands.stream().anyMatch(subcommand -> allows(subcommand, source)));
        for (Subcommand subcommand : subcommands) {
            LiteralArgumentBuilder<CommandSourceStack> node =
                    Commands.literal(subcommand.name()).requires(source -> allows(subcommand, source));
            subcommand.build(node);
            root.then(node);
        }
        return root.build();
    }

    private static boolean allows(Subcommand subcommand, CommandSourceStack source) {
        return source.getSender().hasPermission(subcommand.permission()) && subcommand.isAvailableTo(source);
    }
}
