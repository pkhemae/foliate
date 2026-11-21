package dev.foliate.testing;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.LiteralMessage;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import dev.foliate.core.command.RootCommand;
import dev.foliate.core.command.Subcommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** Runs subcommands through Brigadier as the server would, without starting the plugin. */
public final class TestCommands {

    private static final String TARGET = "target";

    private TestCommands() {}

    /** A dispatcher that knows {@code /foliate}, made of these subcommands. */
    public static CommandDispatcher<CommandSourceStack> foliate(Subcommand... subcommands) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new RootCommand("foliate", List.of(subcommands)).build());
        return dispatcher;
    }

    /**
     * Adds a minimal {@code /execute as <player name> run <command>} to the dispatcher, built the
     * way the server's is: {@code run} redirects to the root, and the executor is only swapped by
     * the redirect's modifier, when the command runs. While Brigadier parses the rest, it checks
     * every requirement with the source as typed, like the server does.
     *
     * @return the same dispatcher
     */
    public static CommandDispatcher<CommandSourceStack> registerExecuteAs(
            CommandDispatcher<CommandSourceStack> dispatcher, Server server) {
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("execute")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("as")
                        .then(RequiredArgumentBuilder.<CommandSourceStack, String>argument(
                                        TARGET, StringArgumentType.word())
                                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("run")
                                        .redirect(dispatcher.getRoot(), context -> {
                                            String name = StringArgumentType.getString(context, TARGET);
                                            Player target = server.getPlayerExact(name);
                                            if (target == null) {
                                                throw new SimpleCommandExceptionType(
                                                                new LiteralMessage(name + " is not online"))
                                                        .create();
                                            }
                                            return context.getSource().withExecutor(target);
                                        })))));
        return dispatcher;
    }

    /** A message as the player reads it, failing when none was sent. */
    public static String plain(@Nullable Component component) {
        assertNotNull(component, "no message was sent");
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
