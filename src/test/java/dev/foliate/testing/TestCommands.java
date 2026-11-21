package dev.foliate.testing;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.mojang.brigadier.CommandDispatcher;
import dev.foliate.core.command.RootCommand;
import dev.foliate.core.command.Subcommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.jspecify.annotations.Nullable;

/** Runs subcommands through Brigadier as the server would, without starting the plugin. */
public final class TestCommands {

    private TestCommands() {}

    /** A dispatcher that knows {@code /foliate}, made of these subcommands. */
    public static CommandDispatcher<CommandSourceStack> foliate(Subcommand... subcommands) {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.getRoot().addChild(new RootCommand("foliate", List.of(subcommands)).build());
        return dispatcher;
    }

    /** A message as the player reads it, failing when none was sent. */
    public static String plain(@Nullable Component component) {
        assertNotNull(component, "no message was sent");
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
