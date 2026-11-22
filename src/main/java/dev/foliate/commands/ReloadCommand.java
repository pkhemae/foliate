package dev.foliate.commands;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.foliate.core.command.Subcommand;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.core.scheduler.Schedulers;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.slf4j.Logger;

/** {@code reload}: reloads every module off the tick threads and reports what failed. */
public final class ReloadCommand implements Subcommand {

    /** Declared in the plugin.yml with the other nodes. */
    static final String PERMISSION = "foliate.command.reload";

    private final Plugin plugin;
    private final ModuleManager modules;
    private final MessageService messages;
    private final Schedulers schedulers;
    private final Logger logger;

    public ReloadCommand(
            Plugin plugin, ModuleManager modules, MessageService messages, Schedulers schedulers, Logger logger) {
        this.plugin = plugin;
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
        return PERMISSION;
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
        // Its outcome is handled in the callback, which reports any error: nothing waits on it.
        CompletableFuture<?> _ = schedulers.async().supply(modules::reloadAll).whenComplete((failures, error) -> reply(sender, () -> {
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

    /**
     * Runs a reply where its recipient expects it, see {@link Schedulers#sender}. Skipped when the
     * plugin was disabled while the reload ran, which interrupts it (see {@link ModuleManager}):
     * the reply could not be scheduled or would never run, and what failed then is only the
     * shutdown cutting the reload short.
     */
    private void reply(CommandSender sender, Runnable reply) {
        if (!plugin.isEnabled()) {
            return;
        }
        // Dropped when the sender left before the reload finished, or the plugin was disabled
        // between the check above and now: nobody to tell.
        schedulers.sender(sender).executeOrDrop(reply);
    }
}
