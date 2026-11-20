package dev.foliate.contracts.profile;

import java.util.Optional;
import java.util.UUID;

/**
 * Reads the profile of a player who is online. The rest of the plugin depends on this interface
 * only, the way it does on {@code Menus}: a command can show a profile without importing the
 * feature that keeps it.
 */
public interface ProfileLookup {

    /** Safe from any thread and never blocks: it reads what is cached. Empty when there is none. */
    Optional<ProfileSummary> lookup(UUID id);
}
