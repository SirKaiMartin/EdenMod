package tel.eden.mod.net;

/**
 * One current guild member's stats for the aspect-giveaway screen, from the bot's
 * reply to the in-game aspect-giveaway request.
 *
 * @param name              the member's username
 * @param contributedXp     lifetime guild XP contribution
 * @param rank              guild rank ("Recruit", "Chief", ...)
 * @param lastSeenEpochMs   last time seen online, or 0 if never
 * @param aspectsBlocked    whether the member has opted out of aspects
 */
public record GiveawayCandidate(String name, long contributedXp, String rank, long lastSeenEpochMs, boolean aspectsBlocked) {
}
