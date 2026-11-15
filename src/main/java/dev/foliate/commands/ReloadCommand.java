package dev.foliate.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.EntityRetiredException;
import dev.foliate.core.scheduler.Schedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.slf4j.Logger;

/** {@code reload}: reloads every module off the tick threads and reports what failed. */
public final class ReloadCommand implements Subcommand {

    private final ModuleManager modules;
    private final MessageService messages;
    private final Schedulers schedulers;
    private final Logger logger;

    public ReloadCommand(ModuleManager modules, MessageService messages, Schedulers schedulers, Logger logger) {
        this.modules = modules;
        this.messages = messages;
        this.schedulers = schedulers;
        this.logger = logger;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return Permissions.COMMAND_RELOAD;
    }

    @Override
    public void build(LiteralArgumentBuilder<CommandSourceStack> node) {
        node.executes(context -> {
            reload(context.getSource().getSender());
            return Command.SINGLE_SUCCESS;
        });
    }

    private void reload(CommandSender sender) {
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

    /** Runs a reply where its recipient expects it, see {@link Schedulers#sender}. */
    private void reply(CommandSender sender, Runnable reply) {
        try {
            schedulers.sender(sender).executeOrRun(reply);
        } catch (EntityRetiredException gone) {
            // The sender left before the reload finished: nobody to tell.
        }
    }
}
