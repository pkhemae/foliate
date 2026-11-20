package dev.foliate.core.profile;

/**
 * A player's profile as a command or another feature may show it.
 *
 * @param firstSeen epoch milliseconds
 * @param lastSeen epoch milliseconds
 */
public record ProfileSummary(String name, long firstSeen, long lastSeen, int joins) {}
