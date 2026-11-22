package dev.foliate.modules.scoreboard.listener;

import dev.foliate.modules.scoreboard.Scoreboards;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/** Shows a player their board when they join, and removes it when they leave. */
public final class ScoreboardListener implements Listener {

    private final Scoreboards scoreboards;

    public ScoreboardListener(Scoreboards scoreboards) {
        this.scoreboards = scoreboards;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        scoreboards.show(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scoreboards.hide(event.getPlayer());
    }
}
