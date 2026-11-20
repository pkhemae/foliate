package dev.foliate.modules.profile;

import dev.foliate.core.player.PlayerStoreChecks;
import dev.foliate.selftest.PlayerCheck;
import java.sql.SQLException;
import java.util.UUID;
import org.bukkit.entity.Player;
import org.jspecify.annotations.Nullable;

/** The profile of a connected player is loaded, saved, and counts every visit. */
public final class ProfileCheck implements PlayerCheck {

    private final ProfileModule profiles;

    public ProfileCheck(ProfileModule profiles) {
        this.profiles = profiles;
    }

    @Override
    public @Nullable String problemWith(Player player, int visit) {
        String problem = PlayerStoreChecks.problemWith(profiles.store(), player);
        if (problem != null) {
            return problem;
        }
        try {
            // The profile is kept between sessions: a player who came back has been counted again.
            int joins = PlayerStoreChecks.stored(profiles.store(), player.getUniqueId())
                    .map(PlayerProfile::joins)
                    .orElse(0);
            if (joins < visit) {
                return "the profile of " + player.getName() + " counts " + joins + " joins after visit " + visit;
            }
        } catch (SQLException e) {
            return "the profile of " + player.getName() + " could not be read back: " + e.getMessage();
        }
        return null;
    }

    @Override
    public @Nullable String problemAfterQuit(UUID id, String name) {
        return PlayerStoreChecks.problemAfterQuit(profiles.store(), id, name);
    }
}
