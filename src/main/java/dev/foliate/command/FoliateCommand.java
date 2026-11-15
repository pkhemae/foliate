package dev.foliate.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import dev.foliate.core.menu.Menus;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import java.util.function.Predicate;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;
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
    private final @Nullable Menus menus;

    /**
     * @param menus null when no menu plugin is installed; the menu subcommand is then left out
     */
    public FoliateCommand(
            ModuleManager modules,
            MessageService messages,
            Schedulers schedulers,
            Logger logger,
            String version,
            String platform,
            @Nullable Menus menus) {
        this.modules = modules;
        this.messages = messages;
        this.schedulers = schedulers;
        this.logger = logger;
        this.version = version;
        this.platform = platform;
        this.menus = menus;
    }

    public LiteralCommandNode<CommandSourceStack> build() {
        Predicate<CommandSourceStack> anySubcommand =
                permission(Permissions.COMMAND_INFO).or(permission(Permissions.COMMAND_RELOAD));
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("foliate")
                .then(Commands.literal("info")
                        .requires(permission(Permissions.COMMAND_INFO))
                        .executes(this::info))
                .then(Commands.literal("reload")
                        .requires(permission(Permissions.COMMAND_RELOAD))
                        .executes(this::reload));

        Menus menus = this.menus;
        if (menus != null) {
            Predicate<CommandSourceStack> canOpenMenu = permission(Permissions.COMMAND_MENU)
                    .and(source -> source.getExecutor() instanceof Player);
            anySubcommand = anySubcommand.or(canOpenMenu);
            root.then(Commands.literal("menu").requires(canOpenMenu).executes(context -> {
                // The executor is the player the command runs as, which differs from the sender
                // under /execute as. Menus#open hops to that player's region by itself.
                if (context.getSource().getExecutor() instanceof Player player) {
                    menus.open(player, "example");
                }
                return Command.SINGLE_SUCCESS;
            }));
        }
        return root.requires(anySubcommand).build();
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
        // Said before returning: senders such as RCON only keep what they receive while the
        // command runs, and the result below comes later.
        messages.send(sender, "command.reload.started");
        // Reloading reads files: it must not run on the tick thread the command was typed on.
        schedulers.async().supply(modules::reloadAll).whenComplete((failures, error) -> reply(sender, () -> {
            if (error != null) {
                logger.error("Reload failed unexpectedly", error);
                messages.send(sender, "command.reload.error");
            } else if (failures.isEmpty()) {
                messages.send(sender, "command.reload.success");
            } else {
                failures.forEach((module, failure) -> reportFailure(sender, module, failure));
            }
        }));
        return Command.SINGLE_SUCCESS;
    }

    private void reportFailure(CommandSender sender, String module, Throwable failure) {
        // A checked exception is a problem the module anticipated and worded for an administrator:
        // its first line is shown. Anything else is a bug whose details belong in the console.
        if (failure instanceof RuntimeException || failure instanceof Error || failure.getMessage() == null) {
            messages.send(sender, "command.reload.unexpected", Placeholder.unparsed("module", module));
            return;
        }
        String reason = failure.getMessage().lines().findFirst().orElse("");
        messages.send(
                sender,
                "command.reload.failure",
                Placeholder.unparsed("module", module),
                Placeholder.unparsed("error", reason));
    }

    /**
     * Runs a reply where its recipient expects it: the sender's own region for an entity, the
     * global region for the console and other non-entity senders. Some senders reject messages
     * coming from another thread.
     */
    private void reply(CommandSender sender, Runnable reply) {
        try {
            if (sender instanceof Entity entity) {
                schedulers.entity(entity).executeOrRun(reply);
            } else {
                schedulers.global().executeOrRun(reply);
            }
        } catch (EntityRetiredException gone) {
            // The sender left before the reload finished: nobody to tell.
        }
    }

    private static Predicate<CommandSourceStack> permission(String node) {
        return source -> source.getSender().hasPermission(node);
    }
}
