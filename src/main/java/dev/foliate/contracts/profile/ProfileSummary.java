package dev.foliate.contracts.profile;

/**
 * The profile of a player who is online, as a command or another feature may show it.
 *
 * @param firstSeen when the player first joined, in epoch milliseconds
 * @param onlineSince when the player joined this time, in epoch milliseconds
 */
public record ProfileSummary(String name, long firstSeen, long onlineSince, int joins) {}
