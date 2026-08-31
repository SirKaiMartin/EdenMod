package tel.eden.mod.reward;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.chat.GuildReward;

/**
 * Gifts guild reward items (aspects/tomes/emeralds) to members by driving the
 * in-game guild-manage menu, and dumps all emeralds to a member. Chief/Owner only.
 *
 * <p>The menu is automated as follows: open
 * {@code /gu man}, click slot 0 to open member management, read the rewards summary
 * at slot 27, find the member's item (paging with slot 28), then swap-hotbar the
 * member item onto reward slot 0 (aspect) / 1 (tome) / 2 (emerald) once per unit.
 * All container interaction runs on the client thread; the orchestration runs on a
 * dedicated background thread so the game is never blocked.
 *
 * <p>A batch of gifts drives all of this through a single open menu instead of
 * reopening {@code /gu man} per member — reopening is the slow part, and staying in
 * one session is what makes skipping it worthwhile. The trade-off is that the
 * member-list paging occasionally desyncs server-side (the page just doesn't
 * advance, or the container silently closes); {@link #findMemberSlot} and
 * {@link #giveUnits} both watch for that and call {@link #recoverSession} — a
 * close+reopen back to page 0 — rather than trusting the click went through.
 *
 * <p>{@link #waitUntil} polls for the server's actual acknowledgement (the container's
 * {@link AbstractContainerMenu#getStateId()} bumping, or the container opening/closing)
 * instead of a flat sleep, up to a timeout scaled off the player's current {@link
 * PlayerInfo#getLatency() ping}. But a fast ack turned out not to mean the server was
 * actually done: an early version of this class paced sends purely off that ack and,
 * once ping was low enough to shrink the wait to ~150ms, only delivered 8 of 18
 * requested aspects, versus 17/18 from a flat, unverified 600ms delay. {@link
 * #paceSend} is the fix — it still waits for the ack (so a truly dead click is still
 * caught), but never lets a fast one shrink the gap below {@link #PACE_FLOOR_MS}.
 *
 * <p>Separately, that ack is <em>not</em> proof the reward was actually granted — the
 * state id can bump on a click the server otherwise drops. The only authoritative
 * signal is Wynncraft's own guild-chat line ({@code "<giver> rewarded <reward> to
 * <receiver>"}, parsed by {@code GuildRewardParser} as a {@link GuildReward} and fed in
 * via {@link #onConfirmedReward}) — the same ticker a human watches to confirm a gift
 * landed. Waiting out that confirmation after every single click is reliable but slow,
 * so {@link #giveUnits} instead bursts all the paced sends first ({@link #burstSend}),
 * reconciles against the confirmation queue ({@link #drainConfirmations}), and only
 * pays the full per-click confirmation wait ({@link #giveUnitsSequential}) for whatever
 * shortfall the burst didn't confirm.
 */
public final class GuildRewards {
	private static final EdenLogger LOGGER = EdenLogger.get();
	private static final long WEEK_MS = 604_800_000L;
	private static final long REFRESH_INTERVAL_MS = 600_000L; // 10 min, like the script
	// Member-management menu layout (a 5-row, 45-slot custom menu, index = row*9 + col,
	// both 0-indexed):
	//   col 0 (slots 0,9,18,27,36): invite member / — / back button / guild reward
	//     storage summary (REWARDS_SLOT) / kick members
	//   col 1 (slots 1,10,19,28,37): — / prev-page arrow (hidden on page 1) / — /
	//     next-page arrow (NEXT_PAGE_SLOT, hidden on the last page) / —
	//   cols 2-8, rows 0-4 (35 slots): the 7x5 grid of member-head items. The last page
	//     fills it left-to-right, top-to-bottom and may end with a few "Pending
	//     invitation" items after the last real member.
	// Columns 0-1 are menu chrome, not member data — staleOverlapDetails() below skips the
	// whole block so a held-static back/kick/storage/arrow item is never mistaken for a
	// carried-over head.
	private static final int REWARDS_SLOT = 27;
	private static final int OPEN_MEMBERS_SLOT = 0;
	private static final int NEXT_PAGE_SLOT = 28;
	private static final int CHROME_COLUMNS = 2; // columns 0-1, see layout note above
	// The menu itself is only 45 slots (rows 0-4 above); slot 45 onward is already the
	// viewing Chief's own inventory, appended in the same AbstractContainerMenu.slots
	// list the way any chest-style menu appends the player's inventory + hotbar after
	// its own slots. findSlotByName() was scanning past 45 and reading the Chief's own
	// items — accessories (Intensity, Obstinance, Precipitation, Malocchio), pouches,
	// weapons, "Character Info" — as if they were page content. Since none of that
	// changes between page turns, it was silently blowing past STALE_OVERLAP_THRESHOLD
	// on the very first next-page click of nearly every scan.
	private static final int MENU_SLOT_COUNT = 45;
	private static final int MAX_PAGES = 15;
	// How many consecutive close+reopen recoveries (with no progress in between) a
	// single find/gift will attempt before giving up — not a total across the whole
	// call, which was too easily exhausted by a couple of early hiccups long before a
	// scan reached a member several pages further on. Any real progress (a clean page
	// advance, or a confirmed unit given) resets this back to zero.
	private static final int MAX_RECOVERIES = 2;
	// A second, independent ceiling that does NOT reset on progress: a page turn that's
	// broken at one specific spot (say 2->3, with 0->1 and 1->2 both fine) would keep
	// resetting MAX_RECOVERIES' consecutive counter every cycle via that intervening
	// progress and never trip it, recovering over and over — bounded by MAX_PAGES so
	// not literally infinite, but potentially many real close+reopens against the actual
	// server before giving up. This catches that recurring-at-the-same-spot case
	// directly instead of grinding through the whole page/unit budget one reopen at a
	// time. Higher than MAX_RECOVERIES since it has to tolerate a full scan's worth of
	// legitimate, non-repeating hiccups, not just a burst of them.
	private static final int MAX_TOTAL_RECOVERIES = 8;
	// How many same-position/same-name matches between consecutive pages' member grids
	// are tolerated before treating a page as stale leftovers rather than genuinely new
	// content — rapid paging occasionally leaves some of the previous page's heads
	// sitting in slots the new page didn't have enough members to overwrite. staleOverlapDetails()
	// already excludes the chrome columns, so this is just a small margin for the rare
	// legitimate coincidence, not a stand-in for that exclusion.
	private static final int STALE_OVERLAP_THRESHOLD = 3;
	// waitUntil() polling: how often it re-checks, the timeout floor/ceiling regardless
	// of ping, the multiple of ping the timeout scales to, and the ping assumed when
	// getLatency() isn't available yet (fresh join — errs slow, not fast).
	private static final long POLL_INTERVAL_MS = 25L;
	private static final long MIN_TIMEOUT_MS = 150L;
	private static final long MAX_TIMEOUT_MS = 3000L;
	private static final int LATENCY_TIMEOUT_MULTIPLIER = 4;
	private static final int DEFAULT_LATENCY_MS = 150;
	// Pacing floor between repeated same-kind clicks (reward swaps, page turns) — see
	// paceSend(). A flat 600ms delay with zero verification reliably delivered 17/18
	// aspects; this class's own fully-dynamic pacing, once ping dropped low enough to
	// shrink the wait to ~150ms, only delivered 8/18. The container ack coming back
	// quickly does not mean the server is done processing the click, so the next send
	// is never paced faster than this floor regardless of how fast the ack arrives —
	// ping is only allowed to push the pacing slower, never faster than the floor.
	// 350ms splits the difference between those two data points: burstSend()'s
	// shortfall no longer has to be near-zero now that giveUnitsSequential() corrects
	// it automatically, so the floor only needs to avoid being so fast that corrections
	// end up costing more than the burst saved — not guarantee a clean first pass. In
	// practice a 6-member/18-aspect batch confirmed every unit on the burst alone, no
	// shortfall correction needed, but this is one data point, not a guarantee across
	// every connection.
	private static final long PACE_FLOOR_MS = 350L;
	private static final long PACE_MAX_MS = 4000L;
	private static final int PACE_MULTIPLIER = 6;
	// Waiting for the reward-confirmation chat line gets a much more patient budget than
	// a raw container ack: it's not just a network round trip but the server's own game
	// logic granting the item and broadcasting guild chat, which is slower and was the
	// actual source of the under-count this was built to fix.
	private static final long MIN_CONFIRM_TIMEOUT_MS = 800L;
	private static final long MAX_CONFIRM_TIMEOUT_MS = 6000L;
	private static final int CONFIRM_TIMEOUT_MULTIPLIER = 10;
	// How long an unconsumed confirmation is kept around before being pruned — covers a
	// reward chat line for a handout this class didn't end up waiting for (e.g. after a
	// timeout already moved on) without leaking memory if nothing ever consumes it.
	private static final long CONFIRMATION_MEMORY_MS = 15_000L;
	private static final int EMERALDS_PER_ITEM = 1024;
	// The backend tracks pending emeralds in 4096-emerald display units (one liquid
	// emerald), but the guild menu hands them out one 1024-emerald item at a time, so
	// four handouts make up one deductible unit.
	private static final int ITEMS_PER_DISPLAY_UNIT = 4096 / EMERALDS_PER_ITEM;
	private static final Pattern COUNT = Pattern.compile("(\\d+)\\s*/\\s*\\d+");

	/** A reward kind and how it maps onto the guild-manage menu. */
	public enum RewardType {
		ASPECT(0, "Aspects:", "aspects", "aspects"), TOME(1, "Guild Tomes:", "tomes", null), EMERALD(2, "Emeralds:", "emeralds", "emeralds");

		private final int hotbar;
		private final String loreKey;
		private final String label;
		// The /manage reset kind for this reward (null = no reward-balance reset, e.g. tomes).
		private final String resetKind;

		RewardType(int hotbar, String loreKey, String label, String resetKind) {
			this.hotbar = hotbar;
			this.loreKey = loreKey;
			this.label = label;
			this.resetKind = resetKind;
		}

		/** The per-handout unit label the backend parses (matches the chat wording). */
		public String unitReward() {
			return switch (this) {
				case ASPECT -> "an Aspect";
				case TOME -> "a Guild Tome";
				case EMERALD -> EMERALDS_PER_ITEM + " Emeralds";
			};
		}
	}

	/**
	 * A guild member's join time, role bucket ("chief", "recruit", ...), and lifetime
	 * contributed XP from the API.
	 */
	public record MemberInfo(long joinedEpochMillis, String rank, long contributedXp) {
	}

	/** One member's share of a batch payout. */
	public record PayoutTarget(String name, int aspects) {
	}

	/** Notified once per completed gift run with the exact number of handouts. */
	public interface RewardReporter {
		void report(String receiver, RewardType type, int count);
	}

	/** Notified with the guild's current reward storage (aspects/tomes/emeralds). */
	public interface StorageReporter {
		void report(int aspects, int tomes, long emeralds);
	}

	/**
	 * Notified after each handout of a reward kind that has a pending balance on the
	 * backend ("aspects"/"emeralds"), so the payout can be deducted there instead of
	 * being reset by hand on Discord.
	 *
	 * <p>{@code displayUnits} is the handout in the backend's display units, or -1 when
	 * the amount handed out doesn't convert to a whole number of them.
	 *
	 * <p>{@code autoDeduct} asks for the deduction to happen straight away — a payout
	 * with the screen's auto-update option on, where the Chief picked the amounts off
	 * the pending list itself. Otherwise it is only offered as a clickable command,
	 * which is what single gifts do, since a gift needn't be settling what is owed.
	 */
	public interface DeductReporter {
		void report(String receiver, String rewardKind, int displayUnits, boolean autoDeduct);
	}

	private volatile RewardReporter reporter;
	private volatile StorageReporter storageReporter;
	private volatile DeductReporter deductReporter;
	// True while a gift run is driving the menu, so the passive tick-time reader in
	// EdenModClient doesn't relay a mid-gift (pre-swap) count; the run relays the exact
	// post-gift value itself.
	private volatile boolean giftInProgress;

	private record TimedReward(long atMs, GuildReward reward) {
	}

	// Confirmed "<giver> rewarded <reward> to <receiver>" chat lines not yet claimed by
	// a giveUnits() call, timestamped so onConfirmedReward() can prune ones nothing ever
	// consumed. Fed by onConfirmedReward() from the chat pipeline (network thread — see
	// EdenModClient.handleSystemChat); drained by consumeConfirmation() on the worker
	// thread, via drainConfirmations() (the burst path) and waitForRewardConfirmation()
	// (the slow, one-at-a-time fallback path).
	private final Queue<TimedReward> confirmedRewards = new ConcurrentLinkedQueue<>();
	// The linked player's own account name, so a confirmation can be matched to a gift
	// this class actually sent rather than one some other Chief happened to send to the
	// same receiver/reward at the same time. Set from ensureFresh(); null (no filtering)
	// until the first refresh lands.
	private volatile String selfName;

	/** Attach the reporter used to send authoritative reward counts to the backend. */
	public void setReporter(RewardReporter reporter) {
		this.reporter = reporter;
	}

	/** Attach the reporter used to relay the guild's live reward storage counts. */
	public void setStorageReporter(StorageReporter storageReporter) {
		this.storageReporter = storageReporter;
	}

	/** Attach the reporter that deducts a handout from the backend's pending balance. */
	public void setDeductReporter(DeductReporter deductReporter) {
		this.deductReporter = deductReporter;
	}

	/** Whether a gift run is currently driving the guild-manage menu. */
	public boolean isGiftInProgress() {
		return giftInProgress;
	}

	/**
	 * Whether the rewards summary is on screen right now (slot 27 carries the reward
	 * lore). Cheap enough to poll each client tick. Must run on the client thread.
	 */
	public boolean isRewardsMenuOpen() {
		AbstractContainerMenu menu = menu();
		if (menu == null || REWARDS_SLOT >= menu.slots.size()) {
			return false;
		}
		ItemLore lore = menu.getSlot(REWARDS_SLOT).getItem().get(DataComponents.LORE);
		if (lore == null) {
			return false;
		}
		for (Component line : lore.lines()) {
			if (line.getString().contains(RewardType.EMERALD.loreKey)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Read the current aspect/tome/emerald storage from the open rewards summary, or
	 * {@code null} if it isn't open. Must run on the client thread. Called both from a
	 * deliberate gift run ({@link #giftInProgress} true) and from a passive per-tick
	 * poll (see {@code EdenModClient}'s storage-check timer, which explicitly skips
	 * calling this while a gift is running) that runs whenever a Chief has any container
	 * open at all — so the two call sites never overlap, and diagnostics below are gated
	 * on {@code giftInProgress} to fire only for the former: a batch has aborted citing
	 * 0 aspects in guild storage right after opening the menu, with nothing else
	 * suggesting the guild was actually empty, so it's worth seeing the raw lore behind
	 * a read that comes back empty/zero specifically during a real gift attempt.
	 */
	public long[] readAllCounts() {
		if (!isRewardsMenuOpen()) {
			if (giftInProgress) {
				logRewardsSlotDiagnostics("isRewardsMenuOpen() was false");
			}
			return null;
		}
		long[] counts = new long[]{readRewardCount(RewardType.ASPECT.loreKey), readRewardCount(RewardType.TOME.loreKey), readRewardCount(RewardType.EMERALD.loreKey)};
		if (giftInProgress && counts[0] == 0 && counts[1] == 0 && counts[2] == 0) {
			logRewardsSlotDiagnostics("all three reward counts parsed as zero");
		}
		return counts;
	}

	/** Dump slot 27's raw item name + lore lines to the log — see {@link #readAllCounts} for why. */
	private void logRewardsSlotDiagnostics(String reason) {
		AbstractContainerMenu menu = menu();
		if (menu == null || REWARDS_SLOT >= menu.slots.size()) {
			LOGGER.info("Gift: rewards-slot diagnostic ({}) — no container, or slot {} out of range", reason, REWARDS_SLOT);
			return;
		}
		ItemStack stack = menu.getSlot(REWARDS_SLOT).getItem();
		ItemLore lore = stack.get(DataComponents.LORE);
		List<String> lines = lore == null ? List.of() : lore.lines().stream().map(Component::getString).toList();
		LOGGER.info("Gift: rewards-slot diagnostic ({}) — item='{}' empty={} lore={}", reason, stack.getHoverName().getString(), stack.isEmpty(), lines);
	}

	private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "eden-guild-rewards");
		t.setDaemon(true);
		return t;
	});

	private volatile String rank = "";
	private volatile Map<String, MemberInfo> members = emptyMembers();
	private volatile long lastRefresh = 0L;
	private volatile boolean refreshing;

	/** Whether the linked player is a Chief or Owner (only they may gift). */
	public boolean isChief() {
		return rank.equalsIgnoreCase("chief") || rank.equalsIgnoreCase("owner");
	}

	/** Current known member usernames (for command tab-completion). */
	public List<String> memberNames() {
		return new ArrayList<>(members.keySet());
	}

	/** Whether {@code name} is a current member of the player's guild (case-insensitive). */
	public boolean isMember(String name) {
		if (name == null || name.isBlank()) {
			return false;
		}
		for (String member : members.keySet()) {
			if (member.equalsIgnoreCase(name)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * A member map that matches names case-insensitively while keeping each member's
	 * original spelling as the key. Making that a property of the map itself means no
	 * lookup site has to remember to normalise (and can't trip over locale-dependent
	 * {@code toLowerCase} in the process).
	 */
	private static Map<String, MemberInfo> emptyMembers() {
		return new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
	}

	/** Look up a member's cached info by name (case-insensitive), or {@code null}. */
	private MemberInfo memberInfo(String name) {
		if (name == null || name.isBlank()) {
			return null;
		}
		return members.get(name);
	}

	/** Display rank ("Chief", "Recruit", ...) for a member, or {@code null} if unknown. */
	public String memberRank(String name) {
		MemberInfo info = memberInfo(name);
		if (info == null || info.rank() == null || info.rank().isBlank()) {
			return null;
		}
		String rankName = info.rank();
		return Character.toUpperCase(rankName.charAt(0)) + rankName.substring(1).toLowerCase();
	}

	// The member-management menu's own display order (Owner first, Recruit last) — the
	// reverse of AspectGiveawayScreen's RANK_ORDER, which runs recruit-to-owner for its
	// rank-range filter UI. Used to sort a batch before paging through the menu, so it
	// scans strictly forward instead of running off the last page and needing a full
	// reopen-and-wrap back to page 0.
	private static final List<String> MENU_RANK_ORDER = List.of("owner", "chief", "strategist", "captain", "recruiter", "recruit");

	/** Where {@code info}'s rank falls in {@link #MENU_RANK_ORDER}, or last if unknown. */
	private static int menuRankIndex(MemberInfo info) {
		if (info == null || info.rank() == null) {
			return MENU_RANK_ORDER.size();
		}
		int idx = MENU_RANK_ORDER.indexOf(info.rank().toLowerCase());
		return idx < 0 ? MENU_RANK_ORDER.size() : idx;
	}

	/** {@code info}'s contributed XP, or 0 if unknown — the menu's tiebreaker within a rank (highest first). */
	private static long contributedXpOf(MemberInfo info) {
		return info == null ? 0L : info.contributedXp();
	}

	/** When a member joined the guild, or {@code null} if unknown. */
	public Long memberJoined(String name) {
		MemberInfo info = memberInfo(name);
		return info == null ? null : info.joinedEpochMillis();
	}

	/** Refresh the rank + member list from the API if stale, off-thread (non-blocking). */
	public void ensureFresh(String playerName) {
		if (playerName != null && !playerName.isBlank()) {
			selfName = playerName;
		}
		long now = System.currentTimeMillis();
		if (refreshing || (now - lastRefresh < REFRESH_INTERVAL_MS && !members.isEmpty())) {
			return;
		}
		if (playerName == null || playerName.isBlank()) {
			return;
		}
		refreshing = true;
		worker.submit(() -> {
			try {
				refresh(playerName);
			} catch (Exception e) {
				LOGGER.warn("Guild rewards refresh failed: {}", e.toString());
			} finally {
				refreshing = false;
			}
		});
	}

	private void refresh(String playerName) throws Exception {
		JsonObject player = getJson("https://api.wynncraft.com/v3/player/" + URLEncoder.encode(playerName, StandardCharsets.UTF_8));
		if (player == null || !player.has("guild") || player.get("guild").isJsonNull()) {
			rank = "";
			members = emptyMembers();
			return;
		}
		JsonObject guild = player.getAsJsonObject("guild");
		rank = guild.has("rank") ? guild.get("rank").getAsString() : "";
		String guildName = guild.get("name").getAsString();
		JsonObject g = getJson("https://api.wynncraft.com/v3/guild/" + URLEncoder.encode(guildName, StandardCharsets.UTF_8));
		members = parseMembers(g);
		lastRefresh = System.currentTimeMillis();
	}

	private static Map<String, MemberInfo> parseMembers(JsonObject guild) {
		Map<String, MemberInfo> out = emptyMembers();
		if (guild == null || !guild.has("members") || !guild.get("members").isJsonObject()) {
			return out;
		}
		for (var role : guild.getAsJsonObject("members").entrySet()) {
			if (role.getKey().equals("total") || !role.getValue().isJsonObject()) {
				continue;
			}
			for (var member : role.getValue().getAsJsonObject().entrySet()) {
				if (!member.getValue().isJsonObject()) {
					continue;
				}
				JsonObject data = member.getValue().getAsJsonObject();
				long joined = data.has("joined") ? Instant.parse(data.get("joined").getAsString()).toEpochMilli() : 0L;
				long contributed = data.has("contributed") ? data.get("contributed").getAsLong() : 0L;
				// The bucket key is the member's rank (owner/chief/strategist/...).
				out.put(member.getKey(), new MemberInfo(joined, role.getKey(), contributed));
			}
		}
		return out;
	}

	private JsonObject getJson(String url) throws Exception {
		HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(), HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() != 200) {
			throw new IllegalStateException(url + " returned " + response.statusCode());
		}
		return JsonParser.parseString(response.body()).getAsJsonObject();
	}

	/** Gift {@code amount} of {@code type} to {@code name} (one menu run, off-thread). */
	public void gift(String name, RewardType type, int amount) {
		worker.submit(() -> run(name, type, amount, false));
	}

	/** Gift all available emeralds to {@code name}. */
	public void dumpEmeralds(String name) {
		worker.submit(() -> run(name, RewardType.EMERALD, 0, true));
	}

	private void run(String name, RewardType type, int requested, boolean dump) {
		giftInProgress = true;
		try {
			if (!isChief()) {
				chat("Only guild Chiefs can gift rewards.", ChatFormatting.RED);
				return;
			}
			MemberInfo info = memberInfo(name);
			if (info == null) {
				chat("Unknown member: " + name, ChatFormatting.RED);
				return;
			}
			if (System.currentTimeMillis() - info.joinedEpochMillis() < WEEK_MS) {
				chat(name + " has not been in the guild for a week, and is not eligible " + "for rewards.", ChatFormatting.YELLOW);
				return;
			}
			if (!openRewardsMenu()) {
				chat("Couldn't open the guild manage menu — try again.", ChatFormatting.RED);
				return;
			}
			try {
				runSingle(name, type, requested, dump, false, true);
			} finally {
				onClientRun(this::closeMenu);
			}
		} catch (Exception e) {
			LOGGER.warn("Gift run failed", e);
			chat("Gift failed: " + e.getMessage(), ChatFormatting.RED);
		} finally {
			giftInProgress = false;
		}
	}

	/**
	 * Drive one member's gift through an already-open guild-manage menu. Assumes the
	 * caller has already validated chief/membership/eligibility, owns the {@code
	 * giftInProgress} flag, and will open/close the menu itself — this only finds the
	 * member and swaps units onto their item, recovering the session if paging or a
	 * swap desyncs along the way. Returns true when at least one unit was handed out; a
	 * false return means a soft failure (nothing to gift, member item never found even
	 * after recovery) that has already been reported in chat. Client-thread timeouts
	 * propagate as exceptions.
	 *
	 * <p>{@code autoDeduct} takes the handout off the member's pending total on the
	 * backend instead of only offering the deduction as a clickable command.
	 *
	 * <p>{@code settlesPending} is false for a handout that isn't settling anyone's
	 * owed balance at all (a giveaway from bank surplus) — it skips the
	 * deduction/fallback-command step entirely rather than offering one, since there
	 * is nothing pending to settle.
	 */
	private boolean runSingle(String name, RewardType type, int requested, boolean dump, boolean autoDeduct, boolean settlesPending) {
		// Read all three counts up front (index == RewardType.hotbar): the gifted
		// type gives us `available`, and the trio becomes the post-gift snapshot.
		long[] counts = readAllCountsSettled();
		if (counts == null) {
			counts = new long[]{0, 0, 0};
		}
		int available = (int) counts[type.hotbar];
		int availableItems = type == RewardType.EMERALD ? available / EMERALDS_PER_ITEM : available;
		// Never attempt to gift more than the guild actually has; this both avoids
		// wasted clicks and keeps the reported handout count exact.
		int amount = dump ? availableItems : Math.min(requested, availableItems);
		if (amount <= 0) {
			chat("There aren't any " + type.label + " to gift!", ChatFormatting.YELLOW);
			return false;
		}
		int slot = findMemberSlot(name);
		if (slot < 0) {
			chat("Couldn't find " + name + "'s item in the menu.", ChatFormatting.RED);
			return false;
		}
		int total = type == RewardType.EMERALD ? amount * EMERALDS_PER_ITEM : amount;
		chat("Gifting " + name + " " + total + " " + type.label + "...", ChatFormatting.GREEN);
		if (!dump && amount < requested) {
			// The guild ran short: what is about to be handed out no longer matches what
			// the pending list said was owed, so neither the deduction below nor a
			// /manage reset settles this member correctly.
			chat("Only " + total + " of " + requested + " " + type.label + " were available for " + name + " — their pending total needs settling by hand.", ChatFormatting.YELLOW);
		}
		int given = giveUnits(name, slot, type, amount);
		if (given <= 0) {
			chat("Couldn't gift " + name + " — never got a confirmed handout back.", ChatFormatting.RED);
			return false;
		}
		if (given < amount) {
			int shortTotal = type == RewardType.EMERALD ? given * EMERALDS_PER_ITEM : given;
			chat("Only " + shortTotal + " of " + total + " " + type.label + " to " + name + " were actually confirmed — their pending total needs settling by hand.", ChatFormatting.YELLOW);
		}
		// Report the exact handout count so the backend logs the right total even
		// if the server bunched some identical reward announcements together.
		RewardReporter currentReporter = reporter;
		if (currentReporter != null) {
			currentReporter.report(name, type, given);
		}
		// Relay the exact storage left after this run (authoritative final value):
		// decrement the gifted type by what we just handed out.
		long[] finalCounts = counts.clone();
		finalCounts[type.hotbar] -= type == RewardType.EMERALD ? (long) given * EMERALDS_PER_ITEM : given;
		StorageReporter currentStorageReporter = storageReporter;
		if (currentStorageReporter != null) {
			currentStorageReporter.report((int) finalCounts[0], (int) finalCounts[1], finalCounts[2]);
		}
		// A dump empties the guild bank into one member and isn't settling what anyone
		// is owed, so it never offers (or performs) a pending-balance deduction.
		if (type.resetKind != null && !dump && settlesPending && given == amount) {
			DeductReporter currentDeductReporter = deductReporter;
			if (currentDeductReporter != null) {
				currentDeductReporter.report(name, type.resetKind, displayUnits(type, given), autoDeduct);
			} else {
				chatComponent(manageResetFallbackLine(type.resetKind, name, displayUnits(type, given)));
			}
		} else if (given == amount) {
			chat("Done — gifted " + name + " " + total + " " + type.label + ".", ChatFormatting.GREEN);
		}
		return true;
	}

	/**
	 * Give {@code amount} units of {@code type} to {@code name}'s item at {@code slot} in
	 * two phases. First, {@link #burstSend} fires every click paced but not
	 * confirmation-gated — sending only one at a time and waiting out the full
	 * confirmation between each was reliable but far slower than necessary, since the
	 * confirmation is bookkeeping, not something the next click needs to wait for.
	 * {@link #drainConfirmations} then finds out how many of those actually landed. Any
	 * shortfall falls back to {@link #giveUnitsSequential} — the slow, fully
	 * confirmation-gated path — since bursting the shortfall again would likely just
	 * lose the same fraction a second time, and by now it's a small enough count that
	 * the slow path's cost is bounded.
	 */
	private int giveUnits(String name, int slot, RewardType type, int amount) {
		if (!Boolean.TRUE.equals(onClient(this::containerOpen))) {
			if (!recoverSession()) {
				return 0;
			}
			slot = findMemberSlot(name);
			if (slot < 0) {
				return 0;
			}
		}
		int sent = burstSend(slot, type, amount);
		int confirmed = drainConfirmations(name, type, sent);
		if (confirmed >= amount) {
			return confirmed;
		}
		int shortfall = amount - confirmed;
		LOGGER.info("Gift: burst only confirmed {}/{} {} for {}; retrying the remaining {} one at a time", confirmed, amount, type.label, name, shortfall);
		return confirmed + giveUnitsSequential(name, slot, type, shortfall);
	}

	/**
	 * Fire up to {@code amount} swap clicks at {@code slot}, paced by {@link #paceSend}
	 * but not confirmation-gated. Stops early if the container closes or a click goes
	 * fully unacknowledged (either suggests the menu died, so further sends would just
	 * be wasted). Returns how many clicks were actually sent — not how many landed;
	 * that's for the caller to find out via {@link #drainConfirmations}.
	 */
	private int burstSend(int slot, RewardType type, int amount) {
		int sent = 0;
		while (sent < amount) {
			if (!Boolean.TRUE.equals(onClient(this::containerOpen))) {
				break;
			}
			int beforeState = onClient(this::currentStateId);
			final int target = slot;
			onClientRun(() -> swapHotbar(target, type.hotbar));
			sent++;
			if (!paceSend(beforeState)) {
				break;
			}
		}
		return sent;
	}

	/**
	 * The slow, reliable fallback: swap-hotbar {@code amount} units one at a time, each
	 * gated on both the container ack and Wynncraft's reward-confirmation chat line
	 * ({@link #waitForRewardConfirmation}) before the next is sent. If the container
	 * closes, a click goes unacknowledged, or a click acks but is never confirmed, this
	 * calls {@link #recoverSession} and re-locates the member (their slot can shift
	 * after a reopen) before continuing. {@link #MAX_RECOVERIES} bounds <em>consecutive</em>
	 * recoveries with no unit actually given in between, not the total across the whole
	 * call — see {@link #findMemberSlot}'s doc for why a flat total budget was too
	 * easily exhausted by a couple of early hiccups, and why {@link #MAX_TOTAL_RECOVERIES}
	 * bounds the whole call on top of that regardless of how progress is spread out (an
	 * alternating give-then-fail pattern would otherwise never trip the consecutive
	 * cap, and for a large {@code amount} — e.g. dumping the whole bank's emeralds —
	 * that isn't a tight bound on its own). Returns how many units were actually
	 * confirmed given, which may be less than {@code amount} if recovery keeps failing.
	 */
	private int giveUnitsSequential(String name, int slot, RewardType type, int amount) {
		int given = 0;
		int consecutiveRecoveries = 0;
		int totalRecoveries = 0;
		while (given < amount) {
			if (!Boolean.TRUE.equals(onClient(this::containerOpen))) {
				if (consecutiveRecoveries >= MAX_RECOVERIES || totalRecoveries >= MAX_TOTAL_RECOVERIES || !recoverSession()) {
					break;
				}
				consecutiveRecoveries++;
				totalRecoveries++;
				slot = findMemberSlot(name);
				if (slot < 0) {
					break;
				}
				continue;
			}
			int beforeState = onClient(this::currentStateId);
			final int target = slot;
			onClientRun(() -> swapHotbar(target, type.hotbar));
			if (waitForStateChange(beforeState) && waitForRewardConfirmation(name, type)) {
				given++;
				consecutiveRecoveries = 0;
				continue;
			}
			// Either the click went unacknowledged, or it acked but was never confirmed —
			// both mean the reward may not actually have landed, so recover the same way:
			// reopen, re-find the member (their slot may have moved), and keep going
			// rather than assuming the swap silently succeeded.
			if (consecutiveRecoveries >= MAX_RECOVERIES || totalRecoveries >= MAX_TOTAL_RECOVERIES || !recoverSession()) {
				break;
			}
			consecutiveRecoveries++;
			totalRecoveries++;
			slot = findMemberSlot(name);
			if (slot < 0) {
				break;
			}
		}
		return given;
	}

	/**
	 * How many of the backend's display units a handout of {@code menuAmount} items is
	 * worth, or -1 when it doesn't divide into whole units. Aspects map one-to-one;
	 * emeralds only line up every {@link #ITEMS_PER_DISPLAY_UNIT} items, and the backend
	 * has no way to take a fraction of a unit.
	 */
	public static int displayUnits(RewardType type, int menuAmount) {
		if (type != RewardType.EMERALD) {
			return menuAmount;
		}
		return menuAmount % ITEMS_PER_DISPLAY_UNIT == 0 ? menuAmount / ITEMS_PER_DISPLAY_UNIT : -1;
	}

	/**
	 * The matching {@code /manage reset} command, clickable to copy, so the pending
	 * balance can still be zeroed by hand on Discord when the bridge can't do it.
	 *
	 * <p>{@code paidUnits} is what the in-game handout actually came to, or -1 when the
	 * caller doesn't know. Reset zeroes the whole balance, so the two only agree when
	 * the payout covered all of it — the hover says so rather than leaving a Chief to
	 * discover it after wiping the remainder of a partially-paid member's total.
	 */
	public static Component manageResetFallbackLine(String resetKind, String player, int paidUnits) {
		String command = "/manage reset kind:" + resetKind + " player:" + player;
		String hover = paidUnits > 0 ? "Click to copy — this zeroes " + player + "'s whole pending balance, not just the " + paidUnits + " paid" : "Click to copy this command";
		return Component.literal(command).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(new ClickEvent.CopyToClipboard(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/** Open {@code /gu man} and step into member management. True if the menu came up. */
	private boolean openRewardsMenu() {
		Minecraft mc = Minecraft.getInstance();
		onClientRun(() -> {
			if (mc.getConnection() != null) {
				mc.getConnection().sendCommand("gu man");
			}
		});
		waitUntil(this::containerOpen);
		onClientRun(() -> click(OPEN_MEMBERS_SLOT));
		waitUntil(this::isRewardsMenuOpen);
		return Boolean.TRUE.equals(onClient(this::containerOpen));
	}

	/**
	 * Close and reopen the guild-manage menu (back on page 0), for use mid-session when
	 * paging or a swap goes unacknowledged — the same symptom a scroll desync or a
	 * server-side hiccup produces. Waits for the close to actually take before sending
	 * {@code /gu man} again, rather than racing the two.
	 */
	private boolean recoverSession() {
		LOGGER.info("Guild rewards menu desynced — reopening");
		onClientRun(this::closeMenu);
		waitUntil(() -> !containerOpen());
		return openRewardsMenu();
	}

	/**
	 * Pay out aspects to several members in one go (off-thread). The whole batch is
	 * checked against the guild's available aspects first: if it doesn't fit, nothing
	 * is distributed at all.
	 *
	 * <p>With {@code autoDeduct}, each member's payout is also deducted from their
	 * pending total on the backend; otherwise the deduction is only offered.
	 */
	public void payoutAspects(List<PayoutTarget> targets, boolean autoDeduct) {
		List<PayoutTarget> copy = List.copyOf(targets);
		if (!copy.isEmpty()) {
			worker.submit(() -> batchRun(copy, autoDeduct, true));
		}
	}

	/**
	 * Flat-gift aspects to several members in one go (off-thread) — a bonus handout
	 * from bank surplus, not settling anyone's owed balance. Shares every safety check
	 * {@link #payoutAspects} has (Chief/member-list/cooldown validation, the live guild-stock
	 * pre-flight check) but never touches the backend's pending-balance bookkeeping.
	 */
	public void giveaway(List<PayoutTarget> targets) {
		List<PayoutTarget> copy = List.copyOf(targets);
		if (!copy.isEmpty()) {
			worker.submit(() -> batchRun(copy, false, false));
		}
	}

	private void batchRun(List<PayoutTarget> requested, boolean autoDeduct, boolean settlesPending) {
		giftInProgress = true;
		try {
			if (!isChief()) {
				chat("Only guild Chiefs can pay out rewards.", ChatFormatting.RED);
				return;
			}
			if (members.isEmpty()) {
				chat("The guild member list hasn't loaded yet — try again in a moment.", ChatFormatting.RED);
				return;
			}
			// Validate every target up front, before any aspects move. A member the
			// member list doesn't know is dropped from the batch rather than aborting it:
			// the member list refreshes on its own schedule, so an unknown name usually
			// means a stale snapshot, and one such name shouldn't block everyone else.
			// A positively-too-new member is a different matter — the screen greys
			// those rows out, so one reaching us means the selection raced a refresh,
			// and paying the rest of a batch the user picked under stale information
			// isn't obviously what they wanted.
			List<String> tooNew = new ArrayList<>();
			List<String> unknown = new ArrayList<>();
			List<PayoutTarget> targets = new ArrayList<>();
			int total = 0;
			for (PayoutTarget target : requested) {
				MemberInfo info = memberInfo(target.name());
				if (info == null) {
					unknown.add(target.name());
					continue;
				}
				if (System.currentTimeMillis() - info.joinedEpochMillis() < WEEK_MS) {
					tooNew.add(target.name());
					continue;
				}
				targets.add(target);
				total += Math.max(0, target.aspects());
			}
			if (!tooNew.isEmpty()) {
				chat("Nothing was distributed — these members joined less than a week ago: " + String.join(", ", tooNew), ChatFormatting.RED);
				return;
			}
			if (!unknown.isEmpty()) {
				chat("Skipping (not a guild member): " + String.join(", ", unknown), ChatFormatting.YELLOW);
			}
			if (total <= 0) {
				chat("Nothing to pay out.", ChatFormatting.YELLOW);
				return;
			}
			// Matches the menu's own ordering: rank tier first, then contributed XP
			// descending within a tier — so a batch pages strictly forward with no
			// same-rank misses forcing a reopen either.
			targets.sort(Comparator.comparingInt((PayoutTarget t) -> menuRankIndex(memberInfo(t.name()))).thenComparingLong(t -> -contributedXpOf(memberInfo(t.name()))));

			if (!openRewardsMenu()) {
				chat("Couldn't open the guild manage menu — try again.", ChatFormatting.RED);
				return;
			}
			// The whole batch shares this one menu session — see the class doc for why,
			// and findMemberSlot/giveUnits for how a mid-session desync recovers.
			try {
				long[] counts = readAllCountsSettled();
				int available = counts == null ? 0 : (int) counts[RewardType.ASPECT.hotbar];
				if (total > available) {
					chat("Not enough aspects: selected " + total + " but the guild only has " + available + " — nothing was distributed.", ChatFormatting.RED);
					return;
				}

				String verb = settlesPending ? "Paying out" : "Gifting";
				chat(verb + " " + total + " aspects to " + targets.size() + " members...", ChatFormatting.GREEN);
				List<String> skipped = new ArrayList<>();
				int paid = 0;
				int done = 0;
				try {
					for (PayoutTarget target : targets) {
						done++;
						if (runSingle(target.name(), RewardType.ASPECT, target.aspects(), false, autoDeduct, settlesPending)) {
							paid++;
						} else {
							skipped.add(target.name());
						}
					}
				} catch (Exception e) {
					LOGGER.warn("Batch payout interrupted", e);
					chat("Stopped after " + done + " of " + targets.size() + " members: " + e.getMessage(), ChatFormatting.RED);
					return;
				}
				chat((settlesPending ? "Payout" : "Giveaway") + " complete: " + paid + "/" + targets.size() + " members paid.", ChatFormatting.GREEN);
				if (!skipped.isEmpty()) {
					chat("Skipped: " + String.join(", ", skipped), ChatFormatting.RED);
				}
			} finally {
				onClientRun(this::closeMenu);
			}
		} catch (Exception e) {
			LOGGER.warn("Batch payout failed", e);
			chat("Payout failed: " + e.getMessage(), ChatFormatting.RED);
		} finally {
			giftInProgress = false;
		}
	}

	/**
	 * Page through the open member-management menu looking for {@code name}'s item,
	 * starting from whatever page it's currently on — a batch scans forward through the
	 * roster once rather than resetting to page 0 for every member. Next-page clicks are
	 * paced the same way reward swaps are ({@link #paceSend} — never faster than {@link
	 * #PACE_FLOOR_MS} regardless of ack speed), since this is the same "clicked faster
	 * than the server actually finished" failure mode. Three independent signals mean
	 * the scroll desynced anyway: the container closing outright, a click going
	 * unacknowledged even after the paced wait, or the new page still carrying too many
	 * of the previous page's heads in the same slots (see {@link #staleOverlapDetails} —
	 * the "5 real heads plus a page full of leftovers" case). Any of them calls {@link
	 * #recoverSession} and restarts the scan from page 0.
	 *
	 * <p>{@link #MAX_RECOVERIES} bounds <em>consecutive</em> recoveries with no progress
	 * in between, not total recoveries for the whole call — a scan that's found a few
	 * clean pages resets the counter. A flat total budget once burned out after two
	 * back-to-back hiccups right at the start of a scan, giving up on a member who was
	 * sitting in plain sight three pages further on, simply because the scan never got
	 * the chance to advance that far. But a page turn broken at one specific spot (say
	 * page 2->3, with every other transition fine) would keep resetting that consecutive
	 * counter and never trip it either, so {@link #MAX_TOTAL_RECOVERIES} — which does
	 * <em>not</em> reset — bounds the whole call regardless of how progress is spread
	 * out, rather than relying on {@link #MAX_PAGES} alone to eventually end a scan
	 * that's really just recovering from the same spot over and over.
	 */
	private int findMemberSlot(String name) {
		Set<String> seenOverall = new LinkedHashSet<>();
		Map<Integer, String> lastPage = null;
		int pagesScanned = 0;
		int consecutiveRecoveries = 0;
		int totalRecoveries = 0;
		while (pagesScanned < MAX_PAGES) {
			if (!Boolean.TRUE.equals(onClient(this::containerOpen))) {
				if (consecutiveRecoveries >= MAX_RECOVERIES || totalRecoveries >= MAX_TOTAL_RECOVERIES || !recoverSession()) {
					break;
				}
				LOGGER.info("Gift: findMemberSlot('{}') recovering ({}/{} consecutive, {}/{} total) — container was closed", name, consecutiveRecoveries + 1, MAX_RECOVERIES, totalRecoveries + 1, MAX_TOTAL_RECOVERIES);
				consecutiveRecoveries++;
				totalRecoveries++;
				lastPage = null;
				continue;
			}
			Map<Integer, String> seen = new LinkedHashMap<>();
			Integer found = onClient(() -> findSlotByName(name, seen));
			seenOverall.addAll(seen.values());
			if (found != null && found >= 0) {
				return found;
			}
			int beforeState = onClient(this::currentStateId);
			onClientRun(() -> click(NEXT_PAGE_SLOT));
			boolean advanced = paceSend(beforeState);
			Map<Integer, String> overlap = lastPage == null ? Map.of() : staleOverlapDetails(seen, lastPage);
			if (!advanced || overlap.size() > STALE_OVERLAP_THRESHOLD) {
				String reason = advanced ? "stale overlap " + overlap.size() + " " + overlap : "click unacknowledged";
				if (consecutiveRecoveries >= MAX_RECOVERIES || totalRecoveries >= MAX_TOTAL_RECOVERIES || !recoverSession()) {
					break;
				}
				LOGGER.info("Gift: findMemberSlot('{}') recovering ({}/{} consecutive, {}/{} total) — {}", name, consecutiveRecoveries + 1, MAX_RECOVERIES, totalRecoveries + 1, MAX_TOTAL_RECOVERIES, reason);
				consecutiveRecoveries++;
				totalRecoveries++;
				lastPage = null;
				continue;
			}
			lastPage = seen;
			pagesScanned++;
			consecutiveRecoveries = 0;
		}
		// Not found across every page — log what names we did see so a mismatch in
		// how Wynncraft renders member-item names is easy to diagnose from the log.
		LOGGER.info("Gift: member '{}' not found after {} pages ({} total recoveries used). Item names seen: {}", name, pagesScanned, totalRecoveries, seenOverall);
		return -1;
	}

	// -- client-thread operations (must run on the render thread) ---------------

	private boolean containerOpen() {
		Minecraft mc = Minecraft.getInstance();
		return mc.player != null && mc.player.containerMenu != mc.player.inventoryMenu;
	}

	/** {@code seenByPosition} is filled slot-index → raw hover name, for {@link #staleOverlapDetails}. */
	private int findSlotByName(String name, Map<Integer, String> seenByPosition) {
		AbstractContainerMenu menu = menu();
		if (menu == null) {
			return -1;
		}
		String wanted = normalizeName(name);
		// Stop where the menu ends — see MENU_SLOT_COUNT's doc for why scanning further
		// (into the Chief's own appended inventory) corrupts both the name search and
		// the stale-page detection.
		for (int i = 0; i < Math.min(menu.slots.size(), MENU_SLOT_COUNT); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String raw = stack.getHoverName().getString();
			seenByPosition.put(i, raw);
			// Wynncraft wraps item names in private-use font glyphs and styling, so
			// compare on just the username characters (case-insensitive).
			if (normalizeName(raw).equals(wanted)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Which slot positions in the member grid (columns 2-8; see the layout note by
	 * {@link #REWARDS_SLOT}) hold the exact same item in both {@code page} and {@code
	 * previousPage}. Rapid paging occasionally leaves the previous page's heads sitting
	 * in the tail slots the new page didn't have enough members to overwrite — visually
	 * "5 real heads plus a page full of leftovers." A clean page turn shares no member
	 * slots with the one before it (each member appears on exactly one page); a page
	 * mostly full of carried-over heads means a much larger match count, which is what
	 * {@link #STALE_OVERLAP_THRESHOLD} catches (on {@code .size()}). Columns 0-1
	 * (invite/back/storage/kick/page-arrow chrome) are skipped entirely — those are
	 * *supposed* to repeat every page.
	 */
	private static Map<Integer, String> staleOverlapDetails(Map<Integer, String> page, Map<Integer, String> previousPage) {
		Map<Integer, String> matches = new LinkedHashMap<>();
		for (var entry : page.entrySet()) {
			int slot = entry.getKey();
			if (slot % 9 < CHROME_COLUMNS) {
				continue;
			}
			String previousValue = previousPage.get(slot);
			if (entry.getValue().equals(previousValue)) {
				matches.put(slot, entry.getValue());
			}
		}
		return matches;
	}

	/** Reduce a rendered name to its bare username characters ([A-Za-z0-9_], lowercased).
	 *
	 * <p>Wynncraft member-item names carry literal legacy {@code §}-formatting codes
	 * (e.g. {@code §f§lPlayerName}) and font glyphs, so a {@code §} and the format char
	 * after it are skipped wholesale — otherwise the {@code f}/{@code l} from {@code §f§l}
	 * would leak into the username and break the match.
	 */
	private static String normalizeName(String raw) {
		StringBuilder out = new StringBuilder(raw.length());
		for (int i = 0; i < raw.length(); i++) {
			char c = raw.charAt(i);
			if (c == '§') { // section sign: skip it and the following format code
				i++;
				continue;
			}
			if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
				out.append(c);
			} else if (c >= 'A' && c <= 'Z') {
				out.append((char) (c + ('a' - 'A')));
			}
		}
		return out.toString();
	}

	private int readRewardCount(String loreKey) {
		AbstractContainerMenu menu = menu();
		if (menu == null || REWARDS_SLOT >= menu.slots.size()) {
			return 0;
		}
		ItemStack stack = menu.getSlot(REWARDS_SLOT).getItem();
		ItemLore lore = stack.get(DataComponents.LORE);
		if (lore == null) {
			return 0;
		}
		for (Component line : lore.lines()) {
			String text = line.getString();
			if (text.contains(loreKey)) {
				Matcher matcher = COUNT.matcher(text);
				if (matcher.find()) {
					return Integer.parseInt(matcher.group(1));
				}
			}
		}
		return 0;
	}

	private void click(int slot) {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerMenu menu = menu();
		if (mc.gameMode != null && mc.player != null && menu != null) {
			mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.PICKUP, mc.player);
		}
	}

	private void swapHotbar(int slot, int hotbar) {
		Minecraft mc = Minecraft.getInstance();
		AbstractContainerMenu menu = menu();
		if (mc.gameMode != null && mc.player != null && menu != null) {
			mc.gameMode.handleInventoryMouseClick(menu.containerId, slot, hotbar, ClickType.SWAP, mc.player);
		}
	}

	private void closeMenu() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player != null) {
			player.closeContainer();
		}
	}

	private static AbstractContainerMenu menu() {
		LocalPlayer player = Minecraft.getInstance().player;
		if (player == null || player.containerMenu == player.inventoryMenu) {
			return null;
		}
		return player.containerMenu;
	}

	/** The open container's ack counter, or -1 with nothing open — a snapshot to diff against after a click. */
	private int currentStateId() {
		AbstractContainerMenu menu = menu();
		return menu == null ? -1 : menu.getStateId();
	}

	/** Whether the container's state id has moved past {@code previous} — the server has acknowledged something since. */
	private boolean stateChanged(int previous) {
		AbstractContainerMenu menu = menu();
		return menu != null && menu.getStateId() != previous;
	}

	/** Current round-trip ping, or {@link #DEFAULT_LATENCY_MS} if unknown (e.g. fresh join). */
	private int currentLatencyMs() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.getConnection() == null) {
			return DEFAULT_LATENCY_MS;
		}
		PlayerInfo info = mc.getConnection().getPlayerInfo(mc.player.getUUID());
		return info == null ? DEFAULT_LATENCY_MS : Math.max(0, info.getLatency());
	}

	// -- threading helpers ------------------------------------------------------

	private static <T> T onClient(Supplier<T> action) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.isSameThread()) {
			return action.get();
		}
		CompletableFuture<T> future = new CompletableFuture<>();
		mc.execute(() -> {
			try {
				future.complete(action.get());
			} catch (Throwable t) {
				future.completeExceptionally(t);
			}
		});
		try {
			return future.get(5, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException("client action failed", e);
		}
	}

	private static void onClientRun(Runnable action) {
		onClient(() -> {
			action.run();
			return null;
		});
	}

	/** {@code minMs}-to-{@code maxMs} timeout, scaled to {@code multiplier}× the current ping. */
	private long adaptiveTimeoutMs(long minMs, long maxMs, int multiplier) {
		Integer latency = onClient(this::currentLatencyMs);
		return Math.max(minMs, Math.min(maxMs, (latency == null ? DEFAULT_LATENCY_MS : latency) * (long) multiplier));
	}

	/**
	 * Poll {@code condition} (evaluated on the client thread) until it becomes true, or
	 * an adaptive timeout elapses — {@link #LATENCY_TIMEOUT_MULTIPLIER}× the current
	 * {@link #currentLatencyMs() ping}, clamped to [{@link #MIN_TIMEOUT_MS}, {@link
	 * #MAX_TIMEOUT_MS}] — instead of a flat sleep. A good connection stops waiting as
	 * soon as the server acks; a bad one still gets enough time rather than firing the
	 * next click before the previous one landed. Returns whether the condition was true
	 * by the time this returns.
	 */
	private boolean waitUntil(Supplier<Boolean> condition) {
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, LATENCY_TIMEOUT_MULTIPLIER);
		while (true) {
			if (Boolean.TRUE.equals(onClient(condition))) {
				return true;
			}
			if (System.currentTimeMillis() >= deadline) {
				return false;
			}
			sleep(POLL_INTERVAL_MS);
		}
	}

	/**
	 * {@link #readAllCounts} retried for a bit instead of trusted on the first read.
	 * Right after opening the menu, slot 27 has been observed to briefly read back as
	 * genuinely empty (Air, no lore at all) before the real "Guild Rewards" item
	 * populates — a batch has read the guild's stock as 0 and aborted entirely because
	 * of a read landing on exactly that frame. Not confirmed, but it's only ever been
	 * seen while the Chief had an unclaimed personal reward pending, which is the same
	 * state that grows slot 27's lore with a "You've received a reward!" prompt.
	 */
	private long[] readAllCountsSettled() {
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, LATENCY_TIMEOUT_MULTIPLIER);
		while (true) {
			long[] counts = onClient(this::readAllCounts);
			if (counts != null) {
				return counts;
			}
			if (System.currentTimeMillis() >= deadline) {
				return null;
			}
			sleep(POLL_INTERVAL_MS);
		}
	}

	/** Whether a click made after snapshotting {@code previousStateId} was acknowledged before the adaptive timeout. */
	private boolean waitForStateChange(int previousStateId) {
		return waitUntil(() -> stateChanged(previousStateId));
	}

	/**
	 * Pace the next click after one made at {@code previousStateId}: wait for its ack
	 * (as {@link #waitForStateChange} would, so a totally dead connection is still
	 * detected), but never move on sooner than {@link #PACE_FLOOR_MS} regardless of how
	 * quickly the ack arrives — see the field's doc for why a fast ack turned out not to
	 * mean the server was actually done. Returns whether the click was acknowledged at
	 * all, which callers use as a "the menu may have died" signal distinct from a
	 * confirmation ever arriving.
	 */
	private boolean paceSend(int previousStateId) {
		long start = System.currentTimeMillis();
		boolean acked = waitForStateChange(previousStateId);
		long elapsed = System.currentTimeMillis() - start;
		long floor = adaptiveTimeoutMs(PACE_FLOOR_MS, PACE_MAX_MS, PACE_MULTIPLIER);
		if (elapsed < floor) {
			sleep(floor - elapsed);
		}
		return acked;
	}

	/** Record a confirmed handout from the chat pipeline, for {@link #waitForRewardConfirmation} to claim. */
	public void onConfirmedReward(GuildReward reward) {
		confirmedRewards.add(new TimedReward(System.currentTimeMillis(), reward));
		long cutoff = System.currentTimeMillis() - CONFIRMATION_MEMORY_MS;
		confirmedRewards.removeIf(t -> t.atMs() < cutoff);
	}

	/**
	 * Wait for Wynncraft's own "{@code <giver> rewarded <reward> to <receiver>}" chat
	 * line to confirm this handout, up to a generously long adaptive timeout (see
	 * {@link #MIN_CONFIRM_TIMEOUT_MS}) — the authoritative signal, since a container
	 * click can ack without the server actually granting anything. Consumes the matching
	 * confirmation so it can't be reused for a later unit or member. Not dispatched via
	 * {@link #onClient}: {@link #confirmedRewards} is plain thread-safe Java state, not
	 * Minecraft client state, so polling it directly keeps the interval accurate instead
	 * of being bounded by render-tick timing.
	 */
	private boolean waitForRewardConfirmation(String receiver, RewardType type) {
		String wantedReward = type.unitReward();
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(MIN_CONFIRM_TIMEOUT_MS, MAX_CONFIRM_TIMEOUT_MS, CONFIRM_TIMEOUT_MULTIPLIER);
		while (true) {
			if (consumeConfirmation(receiver, wantedReward)) {
				return true;
			}
			if (System.currentTimeMillis() >= deadline) {
				return false;
			}
			sleep(POLL_INTERVAL_MS);
		}
	}

	/**
	 * After a burst of unconfirmed sends, wait a bit for straggler confirmations and
	 * claim up to {@code maxToClaim} of them for {@code receiver}/{@code type}. One
	 * confirm-timeout window is enough even for several units: sends are already paced
	 * {@link #PACE_FLOOR_MS} apart, so by the time the last one goes out, confirmations
	 * for the earlier ones have generally already arrived. Returns how many were
	 * actually claimed, which may be less than {@code maxToClaim}.
	 */
	private int drainConfirmations(String receiver, RewardType type, int maxToClaim) {
		String wantedReward = type.unitReward();
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(MIN_CONFIRM_TIMEOUT_MS, MAX_CONFIRM_TIMEOUT_MS, CONFIRM_TIMEOUT_MULTIPLIER);
		int claimed = 0;
		while (claimed < maxToClaim) {
			if (consumeConfirmation(receiver, wantedReward)) {
				claimed++;
				continue;
			}
			if (System.currentTimeMillis() >= deadline) {
				break;
			}
			sleep(POLL_INTERVAL_MS);
		}
		return claimed;
	}

	/** Find and remove one queued confirmation matching {@code receiver}/{@code wantedReward} (and our own name, if known). */
	private boolean consumeConfirmation(String receiver, String wantedReward) {
		String giver = selfName;
		for (Iterator<TimedReward> it = confirmedRewards.iterator(); it.hasNext();) {
			GuildReward candidate = it.next().reward();
			if (!candidate.receiver().equalsIgnoreCase(receiver) || !candidate.reward().equalsIgnoreCase(wantedReward)) {
				continue;
			}
			if (giver != null && !candidate.giver().equalsIgnoreCase(giver)) {
				continue;
			}
			it.remove();
			return true;
		}
		return false;
	}

	private static void chat(String message, ChatFormatting color) {
		chatComponent(Component.literal(message).withStyle(color));
	}

	private static void chatComponent(Component message) {
		Minecraft mc = Minecraft.getInstance();
		mc.execute(() -> {
			if (mc.player != null) {
				mc.player.displayClientMessage(message, false);
			}
		});
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	/** Shut down the worker thread (on mod disconnect/close). */
	public void shutdown() {
		worker.shutdownNow();
	}
}
