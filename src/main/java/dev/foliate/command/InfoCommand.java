package dev.foliate.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;

/** {@code info}: the plugin version, the server it runs on and the modules that are enabled. */
public final class InfoCommand implements Subcommand {

    private final ModuleManager modules;
    private final MessageService messages;
    private final String version;
    private final String platform;

    public InfoCommand(ModuleManager modules, MessageService messages, String version, String platform) {
        this.modules = modules;
        this.messages = messages;
        this.version = version;
        this.platform = platform;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public String permission() {
        return Permissions.COMMAND_INFO;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
        node.executes(context -> {
            messages.send(
                    context.getSource().getSender(),
                    "command.info",
                    Placeholder.unparsed("version", version),
                    Placeholder.unparsed("platform", platform),
                    Placeholder.unparsed("modules", String.join(", ", modules.enabledNames())));
            return Command.SINGLE_SUCCESS;
        });
    }
}
