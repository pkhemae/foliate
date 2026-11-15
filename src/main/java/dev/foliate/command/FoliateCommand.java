package dev.foliate.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.Schedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;

/**
 * The {@code /foliate} command.
 *
 * <p>On Folia a command runs on the region of whoever typed it (the global region for the
 * console). A handler may therefore only touch its own sender directly: anything else, such as
 * another player, a location elsewhere, or blocking I/O, has to go through {@link Schedulers}.
 */
public final class FoliateCommand {

    private final ModuleManager modules;
    private final MessageService messages;
    private final Schedulers schedulers;
    private final Logger logger;
    private final String version;
    private final String platform;

    public FoliateCommand(
            ModuleManager modules,
            MessageService messages,
            Schedulers schedulers,
            Logger logger,
            String version,
            String platform) {
        this.modules = modules;
        this.messages = messages;
        this.schedulers = schedulers;
        this.logger = logger;
        this.version = version;
        this.platform = platform;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        return Commands.literal("foliate")
                .requires(permission(Permissions.COMMAND_INFO).or(permission(Permissions.COMMAND_RELOAD)))
                .then(Commands.literal("info")
                        .requires(permission(Permissions.COMMAND_INFO))
                        .executes(this::info))
                .then(Commands.literal("reload")
                        .requires(permission(Permissions.COMMAND_RELOAD))
                        .executes(this::reload))
                .build();
    }

    private int info(CommandContext<CommandSourceStack> context) {
        messages.send(
                context.getSource().getSender(),
                "command.info",
                Placeholder.unparsed("version", version),
                Placeholder.unparsed("platform", platform),
                Placeholder.unparsed("modules", String.join(", ", modules.enabledNames())));
        return Command.SINGLE_SUCCESS;
    }

    private int reload(CommandContext<CommandSourceStack> context) {
        CommandSender sender = context.getSource().getSender();
        // Reloading reads files: it must not run on the tick thread the command was typed on.
        schedulers.async()
                .supply(modules::reloadAll)
                .thenAccept(failures -> {
                    if (failures.isEmpty()) {
                        messages.send(sender, "command.reload.success");
                        return;
                    }
                    failures.forEach((module, error) -> messages.send(
                            sender,
                            "command.reload.failure",
                            Placeholder.unparsed("module", module),
                            Placeholder.unparsed("error", String.valueOf(error.getMessage()))));
                })
                .exceptionally(error -> {
                    logger.error("Reload failed unexpectedly", error);
                    return null;
                });
        return Command.SINGLE_SUCCESS;
    }

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }
}
