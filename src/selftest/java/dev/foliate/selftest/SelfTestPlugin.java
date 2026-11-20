package dev.foliate.selftest;

import dev.foliate.FoliatePlugin;
import dev.foliate.core.message.MessageCheck;
import dev.foliate.core.message.MessageService;
import dev.foliate.core.module.ModuleManager;
import dev.foliate.modules.profile.ProfileCheck;
import dev.foliate.modules.profile.ProfileModule;
import dev.foliate.modules.scoreboard.ScoreboardCheck;
import dev.foliate.modules.scoreboard.ScoreboardModule;
import java.util.ArrayList;
import java.util.List;

/**
 * The plugin as the smoke test runs it: the real one, which then tests itself and stops the
 * server. The jar built by {@code selfTestJar} names this class in its plugin.yml; the plugin jar
 * never contains it.
 */
public final class SelfTestPlugin extends FoliatePlugin {

    @Override
    protected void started(Parts parts) {
        new SelfTest(this, parts.schedulers(), parts.modules(), parts.database(), checks(parts.modules())).start();
    }

    @Override
    protected void failedToStart(String reason) {
        SelfTest.abort(this, reason);
    }

    /** One check per enabled module that has one. A new module with a check is added here. */
    private static List<PlayerCheck> checks(ModuleManager modules) {
        List<PlayerCheck> checks = new ArrayList<>();
        modules.enabledOf(MessageService.class).forEach(messages -> checks.add(new MessageCheck(messages)));
        modules.enabledOf(ProfileModule.class).forEach(profiles -> checks.add(new ProfileCheck(profiles)));
        modules.enabledOf(ScoreboardModule.class).forEach(scoreboard -> checks.add(new ScoreboardCheck(scoreboard)));
        return List.copyOf(checks);
    }
}
