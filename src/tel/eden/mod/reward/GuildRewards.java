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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
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
 * <p>Automated as: open {@code /gu man}, click slot 0 for member management, read the
 * rewards summary at slot 27, find the member's item (paging via slot 28), then
 * swap-hotbar it onto reward slot 0 (aspect) / 1 (tome) / 2 (emerald) once per unit.
 * Container interaction runs on the client thread; orchestration runs on a dedicated
 * background thread so the game is never blocked.
 *
 * <p>A batch shares one open menu across all members instead of reopening per member.
 * Paging can desync server-side (page doesn't advance, or the container silently
 * closes); {@link #findMemberSlot} and {@link #giveUnits} detect that and call
 * {@link #recoverSession} (close+reopen back to page 0) rather than trust the click.
 *
 * <p>{@link #waitUntil} polls for the server's actual ack (container state id bump, or
 * open/close) instead of a flat sleep, timed off ping — but a fast ack isn't proof the
 * server finished processing, so {@link #paceSend} still waits for it while never
 * letting a fast one shrink the gap below {@link #PACE_FLOOR_MS}. Ping alone doesn't
 * reflect a backlog we caused ourselves (a burst of clicks queueing up server-side), so
 * every adaptive wait also widens with {@link #stressLevel} — recent unacked clicks and
 * unconfirmed rewards raise it, recent successes lower it — and with
 * {@link #currentServerTickMs}, a real server-lag estimate derived from how fast
 * {@code level.getGameTime()} advances (Wynncraft publishes no TPS value, but this
 * doesn't need one).
 *
 * <p>An ack also isn't proof the reward was granted — only Wynncraft's own guild-chat
 * line ({@code "<giver> rewarded <reward> to <receiver>"}, fed in via {@link
 * #onConfirmedReward}) is authoritative. {@link #giveUnits} bursts sends first
 * ({@link #burstSend}), reconciles against the confirmation queue ({@link
 * #drainConfirmations}), and pays the full per-click wait ({@link #giveUnitsSequential})
 * only for the shortfall. A match also requires the confirmation to have arrived at or
 * after the gift operation started, so a leftover confirmation from an earlier gift to
 * the same player can't get claimed here instead.
 *
 * <p>A slow confirmation isn't proof a click failed — {@link #giveUnitsSequential}
 * still resends on timeout, which can occasionally produce a genuine duplicate grant.
 * That's an accepted, low-stakes risk; what keeps it rare is {@link #openRewardsMenu}
 * failing fast (see {@link #MENU_OPEN_TIMEOUT_MS}) instead of grinding through an
 * already-degraded connection.
 *
 * <p>{@link #run}/{@link #batchRun} also hold a server-side lock (see
 * {@link GiftLockGateway}) for the whole session, so two Chiefs can't drive the menu at
 * once and both read the same stock before either deducts — acquired before {@link
 * #openRewardsMenu}, released in a {@code finally} covering the whole run. The bridge
 * auto-expires an unreleased hold, and gifting fails closed if the lock can't be
 * confirmed at all (e.g. no bridge connection).
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
	// Slots 45+ are the Chief's own inventory, appended after the menu's own 45 slots —
	// scanning past this read personal items (accessories, pouches) as page content.
	private static final int MENU_SLOT_COUNT = 45;
	private static final int MAX_PAGES = 15;
	// Consecutive close+reopen recoveries with no progress in between, before giving up
	// (not a total — that was too easily exhausted by early hiccups). Resets on any
	// real progress (a clean page advance, or a confirmed unit given).
	private static final int MAX_RECOVERIES = 2;
	// Total recoveries across the whole call, which does NOT reset on progress — catches
	// a page turn broken at one specific spot, which would otherwise keep resetting
	// MAX_RECOVERIES' consecutive counter forever. Higher than MAX_RECOVERIES since it
	// has to tolerate a full scan's worth of non-repeating hiccups, not just a burst.
	private static final int MAX_TOTAL_RECOVERIES = 8;
	// Same-position/same-name matches between consecutive pages tolerated before treating
	// a page as stale leftovers. staleOverlapDetails() already excludes chrome columns;
	// this is just margin for rare legitimate coincidence.
	private static final int STALE_OVERLAP_THRESHOLD = 3;
	// waitUntil() polling interval, timeout floor/ceiling, ping multiplier, and the ping
	// assumed before getLatency() has a real value.
	private static final long POLL_INTERVAL_MS = 25L;
	private static final long MIN_TIMEOUT_MS = 150L;
	private static final long MAX_TIMEOUT_MS = 3000L;
	private static final int LATENCY_TIMEOUT_MULTIPLIER = 4;
	private static final int DEFAULT_LATENCY_MS = 150;
	// Flat (non-adaptive) budget for opening the member menu, doubling as the
	// reject-as-too-slow threshold. Ping doesn't predict this wait: healthy opens took
	// 322-559ms, degraded ones 1000-4532ms, regardless of reported ping.
	private static final long MENU_OPEN_TIMEOUT_MS = 1500L;
	// Pacing floor between repeated same-kind clicks (reward swaps, page turns). A flat
	// 600ms delay with no verification delivered 17/18 aspects; fully ping-scaled pacing
	// (shrinking to ~150ms on a good connection) only delivered 8/18 — a fast ack isn't
	// proof the server is done processing. 350ms splits the difference, since
	// giveUnitsSequential() now corrects a shortfall automatically.
	private static final long PACE_FLOOR_MS = 350L;
	private static final long PACE_MAX_MS = 4000L;
	private static final int PACE_MULTIPLIER = 6;
	// A page turn re-renders the whole grid, not one slot, so it's closer in cost to
	// MENU_OPEN_TIMEOUT_MS than to a single swap's ack — the generic ack-wait bounds
	// above were timing out page turns well before a healthy 250ms connection actually
	// finished them.
	private static final long PAGE_FLIP_MIN_TIMEOUT_MS = 1500L;
	private static final long PAGE_FLIP_MAX_TIMEOUT_MS = 6000L;
	private static final int PAGE_FLIP_MULTIPLIER = 8;
	// Confirmation-wait budget: much more patient than a raw ack, since it's the
	// server's game logic granting the item and broadcasting chat, not just a network
	// round trip — and ping under-predicts this badly under real congestion.
	private static final long MIN_CONFIRM_TIMEOUT_MS = 3000L;
	private static final long MAX_CONFIRM_TIMEOUT_MS = 12_000L;
	private static final int CONFIRM_TIMEOUT_MULTIPLIER = 10;
	// How long an unconsumed confirmation is kept before pruning, comfortably above
	// MAX_CONFIRM_TIMEOUT_MS so an active wait can never have its confirmation pruned
	// out from under it.
	private static final long CONFIRMATION_MEMORY_MS = 20_000L;
	// Ceiling on stressLevel — how many consecutive-failure "notches" widen every
	// adaptive wait above (see adaptiveTimeoutMs()). Ping alone doesn't reflect a
	// backlog we caused ourselves (e.g. a burst of clicks queueing up server-side),
	// so this widens on top of it when recent waits have actually been timing out.
	private static final int MAX_STRESS = 4;
	// Real server tick length, estimated from level.getGameTime() advancing against wall
	// clock (see observeServerTick()) — Wynncraft publishes no TPS value, but the time
	// packets driving that counter arrive at the server's actual tick rate, so a server
	// running behind schedule shows up here even when ping doesn't catch it.
	private static final int TPS_SAMPLE_SIZE = 20;
	private static final double BASELINE_TICK_MS = 50.0; // vanilla's 20 TPS
	private static final double MAX_TICK_FACTOR = 3.0; // cap how far a measured tick length can widen waits
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

	/**
	 * Coordinates exclusive use of the in-game gifting automation with the bridge, so
	 * two Chiefs' mods can never drive the guild-manage menu at the same time and risk
	 * both reading the same "available" stock before either deducts. Implemented by
	 * {@code EdenModClient}, which owns the actual bridge connection.
	 */
	public interface GiftLockGateway {
		/**
		 * Try to acquire the lock, or renew it if this player already holds it —
		 * blocking the calling thread until the bridge answers or a timeout elapses.
		 * Returns {@code null} when granted; otherwise a human-readable refusal reason
		 * (denied by another Chief, not connected to the bridge, or no reply in time).
		 */
		String acquire();

		/** Release the lock (best-effort, fire-and-forget — the server also auto-expires it). */
		void release();
	}

	private volatile RewardReporter reporter;
	private volatile StorageReporter storageReporter;
	private volatile DeductReporter deductReporter;
	private volatile GiftLockGateway giftLockGateway;
	// True while a gift run is driving the menu, so the passive tick-time reader in
	// EdenModClient doesn't relay a mid-gift (pre-swap) count; the run relays the exact
	// post-gift value itself.
	private volatile boolean giftInProgress;
	// 0..MAX_STRESS — widens every adaptive wait when recent clicks/confirmations have
	// been timing out, narrows again on success. Reset at the start of each run.
	private volatile int stressLevel;

	private record TimedReward(long atMs, GuildReward reward) {
	}

	private record TickSample(long worldTick, long realTimeNanos) {
	}

	// Confined to the client thread (only ever touched from observeServerTick()).
	private final Deque<TickSample> tickSamples = new ArrayDeque<>();
	private long lastObservedWorldTick = Long.MIN_VALUE;
	// Estimated ms per real server tick (50.0 == a healthy 20 TPS), read from any thread.
	private volatile double currentServerTickMs = BASELINE_TICK_MS;

	// Confirmed "<giver> rewarded <reward> to <receiver>" chat lines not yet claimed.
	// Fed by onConfirmedReward() (chat pipeline, network thread); drained by
	// consumeConfirmation() on the worker thread.
	private final Queue<TimedReward> confirmedRewards = new ConcurrentLinkedQueue<>();
	// Own account name, so a confirmation can be matched to a gift this class actually
	// sent, not another Chief's simultaneous one. Set from ensureFresh().
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

	/** Attach the gateway that coordinates exclusive use of the gifting automation with the bridge. */
	public void setGiftLockGateway(GiftLockGateway giftLockGateway) {
		this.giftLockGateway = giftLockGateway;
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
	 * deliberate gift run and from a passive per-tick poll ({@code EdenModClient}'s
	 * storage-check timer, which skips calling this during a gift run — the two never
	 * overlap); diagnostics below are gated on {@link #giftInProgress} so only the
	 * former logs, since only a real gift attempt reading 0 is suspicious.
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

	/** A member map keyed case-insensitively, while preserving each member's original spelling. */
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

	// The menu's own display order (owner first) — sorting a batch by this lets a scan
	// page strictly forward instead of wrapping back to page 0 partway through.
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
		stressLevel = 0;
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
			if (!acquireGiftLock()) {
				return;
			}
			try {
				if (!openRewardsMenu()) {
					chat("Couldn't open the guild manage menu — try again (this can happen when your connection is slow).", ChatFormatting.RED);
					return;
				}
				try {
					runSingle(name, type, requested, dump, false, true);
				} finally {
					onClientRun(this::closeMenu);
				}
			} finally {
				releaseGiftLock();
			}
		} catch (Exception e) {
			LOGGER.warn("Gift run failed", e);
			chat("Gift failed: " + e.getMessage(), ChatFormatting.RED);
		} finally {
			giftInProgress = false;
		}
	}

	/**
	 * Try to acquire the gift lock from the bridge (see {@link GiftLockGateway}),
	 * chatting a clear refusal and returning false if denied or the bridge can't be
	 * reached — gifting fails closed rather than risk two Chiefs' automations racing
	 * the same guild stock.
	 */
	private boolean acquireGiftLock() {
		GiftLockGateway gateway = giftLockGateway;
		if (gateway == null) {
			chat("Can't gift right now — not connected to the bridge.", ChatFormatting.RED);
			return false;
		}
		String denied = gateway.acquire();
		if (denied != null) {
			chat("Can't gift right now: " + denied, ChatFormatting.RED);
			return false;
		}
		return true;
	}

	/** Release the gift lock (best-effort — the server also auto-expires it if this never runs). */
	private void releaseGiftLock() {
		GiftLockGateway gateway = giftLockGateway;
		if (gateway == null) {
			return;
		}
		try {
			gateway.release();
		} catch (Exception e) {
			LOGGER.warn("Gift lock release failed", e);
		}
	}

	/**
	 * Drive one member's gift through an already-open guild-manage menu (caller has
	 * already validated chief/membership/eligibility and owns opening/closing it).
	 * Returns how many units were actually confirmed given, which may be less than
	 * {@code requested} — any shortfall has already been reported in chat, so callers
	 * only need the count, not a truthiness check. {@code autoDeduct} takes the handout
	 * off the member's pending backend total instead of just offering it as a command;
	 * {@code settlesPending} is false for a surplus giveaway that owes no one anything,
	 * skipping the deduction step entirely.
	 */
	private int runSingle(String name, RewardType type, int requested, boolean dump, boolean autoDeduct, boolean settlesPending) {
		// Index == RewardType.hotbar; the trio also becomes the post-gift snapshot below.
		long[] counts = readAllCountsSettled();
		if (counts == null) {
			counts = new long[]{0, 0, 0};
		}
		int available = (int) counts[type.hotbar];
		int availableItems = type == RewardType.EMERALD ? available / EMERALDS_PER_ITEM : available;
		// Never gift more than the guild actually has.
		int amount = dump ? availableItems : Math.min(requested, availableItems);
		if (amount <= 0) {
			chat("There aren't any " + type.label + " to gift!", ChatFormatting.YELLOW);
			return 0;
		}
		int slot = findMemberSlot(name);
		if (slot < 0) {
			chat("Couldn't find " + name + "'s item in the menu.", ChatFormatting.RED);
			return 0;
		}
		int total = type == RewardType.EMERALD ? amount * EMERALDS_PER_ITEM : amount;
		chat("Gifting " + name + " " + total + " " + type.label + "...", ChatFormatting.GREEN);
		if (!dump && amount < requested) {
			// Guild ran short — neither the deduction below nor /manage reset settles this right.
			chat("Only " + total + " of " + requested + " " + type.label + " were available for " + name + " — their pending total needs settling by hand.", ChatFormatting.YELLOW);
		}
		int given = giveUnits(name, slot, type, amount);
		if (given <= 0) {
			chat("Couldn't gift " + name + " — never got a confirmed handout back.", ChatFormatting.RED);
			return 0;
		}
		if (given < amount) {
			int shortTotal = type == RewardType.EMERALD ? given * EMERALDS_PER_ITEM : given;
			chat("Only " + shortTotal + " of " + total + " " + type.label + " to " + name + " were actually confirmed — their pending total needs settling by hand.", ChatFormatting.YELLOW);
		}
		// Report the exact count in case the server bunched reward announcements together.
		RewardReporter currentReporter = reporter;
		if (currentReporter != null) {
			currentReporter.report(name, type, given);
		}
		// Relay the authoritative storage left after this run.
		long[] finalCounts = counts.clone();
		finalCounts[type.hotbar] -= type == RewardType.EMERALD ? (long) given * EMERALDS_PER_ITEM : given;
		StorageReporter currentStorageReporter = storageReporter;
		if (currentStorageReporter != null) {
			currentStorageReporter.report((int) finalCounts[0], (int) finalCounts[1], finalCounts[2]);
		}
		// A dump isn't settling anyone's balance, so it never offers a deduction.
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
		return given;
	}

	/**
	 * Give {@code amount} units of {@code type} to {@code name}'s item at {@code slot} in
	 * two phases: {@link #burstSend} fires every click paced but not confirmation-gated
	 * (much faster than waiting out each confirmation in turn), then {@link
	 * #drainConfirmations} finds out how many actually landed. Any shortfall falls back
	 * to {@link #giveUnitsSequential} — bursting it again would likely just lose the
	 * same fraction a second time, and by now it's a small enough count to afford the
	 * slow, fully confirmation-gated path.
	 *
	 * <p>{@code since} (captured before the first click) excludes any confirmation left
	 * over from an earlier, unrelated gift to this player, so it can't be claimed here
	 * instead of the click that actually earned it.
	 */
	private int giveUnits(String name, int slot, RewardType type, int amount) {
		long since = System.currentTimeMillis();
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
		int confirmed = drainConfirmations(name, type, sent, since);
		if (confirmed >= amount) {
			return confirmed;
		}
		int shortfall = amount - confirmed;
		LOGGER.info("Gift: burst only confirmed {}/{} {} for {}; retrying the remaining {} one at a time", confirmed, amount, type.label, name, shortfall);
		return confirmed + giveUnitsSequential(name, slot, type, shortfall, since);
	}

	/**
	 * Fire up to {@code amount} swap clicks at {@code slot}, paced by {@link #paceSend}
	 * but not confirmation-gated. A single unacknowledged click just raises
	 * {@link #stressLevel} and slows the next one down — under a big burst the server
	 * can genuinely fall behind acking without being dead — so this only stops early
	 * once the container itself is confirmed closed. Returns how many were sent, not how
	 * many landed — that's for {@link #drainConfirmations} to find out.
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
			if (!paceSend(beforeState) && !Boolean.TRUE.equals(onClient(this::containerOpen))) {
				break;
			}
		}
		return sent;
	}

	/**
	 * The slow, reliable fallback: swap-hotbar {@code amount} units one at a time, each
	 * gated on both the container ack and Wynncraft's reward-confirmation chat line
	 * ({@link #waitForRewardConfirmation}) before the next is sent. A timeout on either
	 * calls {@link #recoverSession} and resends — a slow confirmation isn't proof the
	 * swap failed, so this can occasionally land a genuine duplicate grant. That risk is
	 * accepted rather than eliminated: {@link #openRewardsMenu} already refuses to start
	 * a session on a degraded connection ({@link #MENU_OPEN_TIMEOUT_MS}), which is what
	 * keeps duplicates rare instead of compounding on a sustained-bad one.
	 * {@link #MAX_RECOVERIES} bounds <em>consecutive</em> no-progress recoveries;
	 * {@link #MAX_TOTAL_RECOVERIES} bounds the whole call regardless of how progress is
	 * spread out (see {@link #findMemberSlot}'s doc). {@code since} excludes any
	 * confirmation from before this member's gift started (see {@link #giveUnits}).
	 * Returns how many units were actually confirmed given.
	 */
	private int giveUnitsSequential(String name, int slot, RewardType type, int amount, long since) {
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
			if (waitForStateChange(beforeState) && waitForRewardConfirmation(name, type, since)) {
				given++;
				consecutiveRecoveries = 0;
				continue;
			}
			// Unacknowledged or never-confirmed — either way, don't assume it landed.
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
	 * {@code paidUnits} is what the in-game handout actually came to, or -1 if unknown —
	 * reset zeroes the whole balance, so the hover warns when {@code paidUnits} doesn't
	 * cover it all, rather than letting a Chief wipe an underpaid member's remainder.
	 */
	public static Component manageResetFallbackLine(String resetKind, String player, int paidUnits) {
		String command = "/manage reset kind:" + resetKind + " player:" + player;
		String hover = paidUnits > 0 ? "Click to copy — this zeroes " + player + "'s whole pending balance, not just the " + paidUnits + " paid" : "Click to copy this command";
		return Component.literal(command).withStyle(Style.EMPTY.withColor(ChatFormatting.GREEN).withUnderlined(true).withClickEvent(new ClickEvent.CopyToClipboard(command)).withHoverEvent(new HoverEvent.ShowText(Component.literal(hover))));
	}

	/**
	 * Open {@code /gu man} and step into member management. True only if the member
	 * submenu itself is confirmed open — not just that some container is (the top-level
	 * {@code /gu man} menu counts too, and is smaller, so a slot 27 read there spills
	 * into the Chief's own inventory instead of the real "Guild Rewards" item). Both
	 * waits are capped at {@link #MENU_OPEN_TIMEOUT_MS}: on a degraded connection this
	 * fails fast rather than eventually succeeding too slowly to trust, so a slow
	 * connection just reads as "couldn't open" rather than needing separate handling.
	 */
	private boolean openRewardsMenu() {
		Minecraft mc = Minecraft.getInstance();
		onClientRun(() -> {
			if (mc.getConnection() != null) {
				mc.getConnection().sendCommand("gu man");
			}
		});
		long t0 = System.currentTimeMillis();
		boolean opened = waitUntil(this::containerOpen, MENU_OPEN_TIMEOUT_MS);
		LOGGER.info("Gift: openRewardsMenu — containerOpen={} after {}ms", opened, System.currentTimeMillis() - t0);
		onClientRun(() -> click(OPEN_MEMBERS_SLOT));
		long t1 = System.currentTimeMillis();
		boolean ready = waitUntil(this::isRewardsMenuOpen, MENU_OPEN_TIMEOUT_MS);
		LOGGER.info("Gift: openRewardsMenu — isRewardsMenuOpen={} after {}ms", ready, System.currentTimeMillis() - t1);
		return ready;
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
	 * Pay out aspects to several members in one go (off-thread). Checked against the
	 * guild's available aspects first — if it doesn't fit, nothing is distributed. A
	 * member short on the first pass gets one retry at just the missing amount before
	 * being reported as short (see {@link #batchRun}). With {@code autoDeduct}, each
	 * payout is also deducted from the member's pending backend total; otherwise the
	 * deduction is only offered.
	 */
	public void payoutAspects(List<PayoutTarget> targets, boolean autoDeduct) {
		List<PayoutTarget> copy = List.copyOf(targets);
		if (!copy.isEmpty()) {
			worker.submit(() -> batchRun(copy, autoDeduct, true));
		}
	}

	/**
	 * Flat-gift aspects to several members in one go (off-thread) — a bonus handout from
	 * bank surplus, not settling anyone's owed balance. Shares every safety check
	 * {@link #payoutAspects} has, but never touches the pending-balance bookkeeping.
	 */
	public void giveaway(List<PayoutTarget> targets) {
		List<PayoutTarget> copy = List.copyOf(targets);
		if (!copy.isEmpty()) {
			worker.submit(() -> batchRun(copy, false, false));
		}
	}

	private void batchRun(List<PayoutTarget> requested, boolean autoDeduct, boolean settlesPending) {
		giftInProgress = true;
		stressLevel = 0;
		try {
			if (!isChief()) {
				chat("Only guild Chiefs can pay out rewards.", ChatFormatting.RED);
				return;
			}
			if (members.isEmpty()) {
				chat("The guild member list hasn't loaded yet — try again in a moment.", ChatFormatting.RED);
				return;
			}
			// Validate every target up front, before any aspects move. An unknown name is
			// dropped rather than aborting the batch — the member list refreshes on its
			// own schedule, so it's usually just a stale snapshot. A too-new member is
			// different: the screen greys those out, so reaching us means a stale
			// selection, and paying the rest of the batch isn't obviously wanted.
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
			// Matches the menu's own ordering so a batch pages strictly forward.
			targets.sort(Comparator.comparingInt((PayoutTarget t) -> menuRankIndex(memberInfo(t.name()))).thenComparingLong(t -> -contributedXpOf(memberInfo(t.name()))));

			if (!acquireGiftLock()) {
				return;
			}
			try {
				if (!openRewardsMenu()) {
					chat("Couldn't open the guild manage menu — try again (this can happen when your connection is slow).", ChatFormatting.RED);
					return;
				}
				// The whole batch shares this one menu session.
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
					List<String> stillShort = new ArrayList<>();
					// Members runSingle() gave less than requested to get one more full attempt
					// (fresh recovery budget and confirmation cutoff) before being reported short.
					Map<String, Integer> shortfalls = new LinkedHashMap<>();
					int paidInFull = 0;
					int done = 0;
					try {
						for (PayoutTarget target : targets) {
							done++;
							int given = runSingle(target.name(), RewardType.ASPECT, target.aspects(), false, autoDeduct, settlesPending);
							if (given >= target.aspects()) {
								paidInFull++;
							} else if (given > 0) {
								shortfalls.put(target.name(), target.aspects() - given);
							} else {
								skipped.add(target.name());
							}
							// Renewed once per member (simpler than a timer, and guarantees a fresh
							// hold going into every member's swaps). A denial stops the batch
							// immediately rather than continuing without exclusive access.
							if (!acquireGiftLock()) {
								chat("Lost the gift lock mid-batch — stopping after " + done + " of " + targets.size() + " members.", ChatFormatting.RED);
								return;
							}
						}
						if (!shortfalls.isEmpty()) {
							chat("Retrying " + shortfalls.size() + " member(s) that came up short...", ChatFormatting.YELLOW);
							for (var entry : shortfalls.entrySet()) {
								int given = runSingle(entry.getKey(), RewardType.ASPECT, entry.getValue(), false, autoDeduct, settlesPending);
								int stillMissing = entry.getValue() - given;
								if (stillMissing <= 0) {
									paidInFull++;
								} else {
									stillShort.add(entry.getKey() + " (" + stillMissing + " short)");
								}
							}
						}
					} catch (Exception e) {
						LOGGER.warn("Batch payout interrupted", e);
						chat("Stopped after " + done + " of " + targets.size() + " members: " + e.getMessage(), ChatFormatting.RED);
						return;
					}
					chat((settlesPending ? "Payout" : "Giveaway") + " complete: " + paidInFull + "/" + targets.size() + " members paid in full.", ChatFormatting.GREEN);
					if (!stillShort.isEmpty()) {
						chat("Still short after retry: " + String.join(", ", stillShort), ChatFormatting.RED);
					}
					if (!skipped.isEmpty()) {
						chat("Skipped: " + String.join(", ", skipped), ChatFormatting.RED);
					}
				} finally {
					onClientRun(this::closeMenu);
				}
			} finally {
				releaseGiftLock();
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
	 * starting from whatever page it's currently on — a batch scans forward once rather
	 * than resetting to page 0 for every member. Next-page clicks are paced like reward
	 * swaps ({@link #paceSend}). Three signals mean the scroll desynced: the container
	 * closing, a click going unacknowledged, or the new page still carrying too many of
	 * the previous page's heads (see {@link #staleOverlapDetails}). Any of them calls
	 * {@link #recoverSession} and restarts the scan from page 0.
	 *
	 * <p>{@link #MAX_RECOVERIES} bounds <em>consecutive</em> no-progress recoveries and
	 * resets on a clean page — a flat total budget once burned out on two early hiccups
	 * and gave up on a member sitting in plain sight a few pages further on.
	 * {@link #MAX_TOTAL_RECOVERIES} does not reset, and bounds the whole call in case a
	 * page turn is broken at one specific spot that keeps resetting the consecutive
	 * counter without ever tripping it.
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
			boolean advanced = paceSend(beforeState, PAGE_FLIP_MIN_TIMEOUT_MS, PAGE_FLIP_MAX_TIMEOUT_MS, PAGE_FLIP_MULTIPLIER);
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
		// Not found — log what names we did see, to diagnose a rendering mismatch.
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
		// Stop where the menu ends — see MENU_SLOT_COUNT's doc.
		for (int i = 0; i < Math.min(menu.slots.size(), MENU_SLOT_COUNT); i++) {
			ItemStack stack = menu.getSlot(i).getItem();
			if (stack.isEmpty()) {
				continue;
			}
			String raw = stack.getHoverName().getString();
			seenByPosition.put(i, raw);
			// Compare on just the username characters — Wynncraft wraps names in glyphs/styling.
			if (normalizeName(raw).equals(wanted)) {
				return i;
			}
		}
		return -1;
	}

	/**
	 * Which slot positions in the member grid hold the exact same item in both
	 * {@code page} and {@code previousPage}. Rapid paging occasionally leaves the
	 * previous page's heads sitting in tail slots the new page didn't have enough
	 * members to overwrite. A clean page turn shares no member slots with the one
	 * before it, so a high match count means stale leftovers — see
	 * {@link #STALE_OVERLAP_THRESHOLD}. Chrome columns 0-1 are skipped since those are
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

	/**
	 * Reduce a rendered name to its bare username characters ([A-Za-z0-9_], lowercased).
	 * Wynncraft member-item names carry legacy {@code §}-formatting codes (e.g.
	 * {@code §f§lPlayerName}), so each {@code §} and the format char after it are
	 * skipped — otherwise they'd leak into the username and break the match.
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

	/**
	 * Sample {@link #currentServerTickMs} from how far {@code level.getGameTime()} has
	 * advanced since the last sample, against how much real time that took — called once
	 * per {@link #waitUntilDeadline} poll, so it stays fresh through every wait during a
	 * gift. Resets on leaving the world so a stale reading can't leak into the next join.
	 */
	private void observeServerTick() {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null) {
			tickSamples.clear();
			lastObservedWorldTick = Long.MIN_VALUE;
			return;
		}
		long worldTick = mc.level.getGameTime();
		if (worldTick == lastObservedWorldTick) {
			return;
		}
		lastObservedWorldTick = worldTick;
		tickSamples.addLast(new TickSample(worldTick, System.nanoTime()));
		while (tickSamples.size() > TPS_SAMPLE_SIZE) {
			tickSamples.removeFirst();
		}
		if (tickSamples.size() < 2) {
			return;
		}
		TickSample first = tickSamples.peekFirst();
		TickSample last = tickSamples.peekLast();
		long tickDelta = last.worldTick() - first.worldTick();
		long realDeltaNanos = last.realTimeNanos() - first.realTimeNanos();
		if (tickDelta <= 0L || realDeltaNanos <= 0L) {
			return;
		}
		currentServerTickMs = (realDeltaNanos / 1_000_000.0) / (double) tickDelta;
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

	/**
	 * {@code minMs}-to-{@code maxMs} timeout, scaled to {@code multiplier}× the current
	 * ping — then widened further by {@link #stressLevel} notches and by
	 * {@link #currentServerTickMs} running above {@link #BASELINE_TICK_MS}, both the
	 * target and the ceiling, so a session that's been genuinely timing out (or a server
	 * that's genuinely lagging) gets more patience than ping alone would predict.
	 */
	private long adaptiveTimeoutMs(long minMs, long maxMs, int multiplier) {
		Integer latency = onClient(this::currentLatencyMs);
		long pingMs = latency == null ? DEFAULT_LATENCY_MS : latency;
		int stress = 1 + stressLevel;
		double tickFactor = Math.max(1.0, Math.min(MAX_TICK_FACTOR, currentServerTickMs / BASELINE_TICK_MS));
		long scaledMax = (long) (maxMs * stress * tickFactor);
		long target = (long) (pingMs * multiplier * stress * tickFactor);
		return Math.max(minMs, Math.min(scaledMax, target));
	}

	/** Widen ({@code false}) or narrow ({@code true}) {@link #stressLevel}, clamped to [0, {@link #MAX_STRESS}]. */
	private void recordStress(boolean ok) {
		stressLevel = Math.max(0, Math.min(MAX_STRESS, stressLevel + (ok ? -1 : 1)));
	}

	/**
	 * Poll {@code condition} (evaluated on the client thread) until true, or an adaptive
	 * timeout elapses — {@link #LATENCY_TIMEOUT_MULTIPLIER}× the current
	 * {@link #currentLatencyMs() ping}, clamped to [{@link #MIN_TIMEOUT_MS}, {@link
	 * #MAX_TIMEOUT_MS}] — instead of a flat sleep.
	 */
	private boolean waitUntil(Supplier<Boolean> condition) {
		return waitUntil(condition, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, LATENCY_TIMEOUT_MULTIPLIER);
	}

	/** Same as {@link #waitUntil(Supplier)}, but with its own timeout bounds/multiplier instead of the click-pacing default. */
	private boolean waitUntil(Supplier<Boolean> condition, long minMs, long maxMs, int multiplier) {
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(minMs, maxMs, multiplier);
		return waitUntilDeadline(condition, deadline);
	}

	/** Same as {@link #waitUntil(Supplier)}, but a flat timeout with no ping-based scaling. */
	private boolean waitUntil(Supplier<Boolean> condition, long timeoutMs) {
		return waitUntilDeadline(condition, System.currentTimeMillis() + timeoutMs);
	}

	private boolean waitUntilDeadline(Supplier<Boolean> condition, long deadline) {
		while (true) {
			boolean met = Boolean.TRUE.equals(onClient(() -> {
				observeServerTick();
				return condition.get();
			}));
			if (met) {
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
	 * Right after opening the menu, slot 27 has been observed to briefly read back
	 * genuinely empty before the real "Guild Rewards" item populates, which has caused a
	 * batch to read the guild's stock as 0 and abort. Only ever seen while the Chief had
	 * an unclaimed personal reward pending (unconfirmed why).
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
	 * (so a dead connection is still detected), but never move on sooner than
	 * {@link #PACE_FLOOR_MS} — a fast ack isn't proof the server is actually done (see
	 * that constant's doc). Returns whether the click was acknowledged at all, which
	 * callers use as a "the menu may have died" signal.
	 */
	private boolean paceSend(int previousStateId) {
		return paceSend(previousStateId, MIN_TIMEOUT_MS, MAX_TIMEOUT_MS, LATENCY_TIMEOUT_MULTIPLIER);
	}

	/** Same as {@link #paceSend(int)}, but with its own ack-wait bounds/multiplier for a costlier click (e.g. a page turn). */
	private boolean paceSend(int previousStateId, long minMs, long maxMs, int multiplier) {
		long start = System.currentTimeMillis();
		boolean acked = waitUntil(() -> stateChanged(previousStateId), minMs, maxMs, multiplier);
		recordStress(acked);
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
	 * line to confirm this handout — the authoritative signal, since a container click
	 * can ack without anything actually being granted. Consumes the matching
	 * confirmation so it can't be reused for a later unit or member; {@code since}
	 * excludes anything that arrived before this gift started (see {@link #giveUnits}).
	 * Not dispatched via {@link #onClient}: {@link #confirmedRewards} is plain
	 * thread-safe Java state, so polling it directly isn't bounded by render-tick timing.
	 */
	private boolean waitForRewardConfirmation(String receiver, RewardType type, long since) {
		String wantedReward = type.unitReward();
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(MIN_CONFIRM_TIMEOUT_MS, MAX_CONFIRM_TIMEOUT_MS, CONFIRM_TIMEOUT_MULTIPLIER);
		while (true) {
			if (consumeConfirmation(receiver, wantedReward, since)) {
				recordStress(true);
				return true;
			}
			if (System.currentTimeMillis() >= deadline) {
				recordStress(false);
				return false;
			}
			sleep(POLL_INTERVAL_MS);
		}
	}

	/**
	 * After a burst of unconfirmed sends, wait a bit for straggler confirmations and
	 * claim up to {@code maxToClaim} of them for {@code receiver}/{@code type} that
	 * arrived at or after {@code since} (see {@link #waitForRewardConfirmation}). One
	 * confirm-timeout window is enough even for several units, since sends are already
	 * paced {@link #PACE_FLOOR_MS} apart. Returns how many were actually claimed, which
	 * may be less than {@code maxToClaim}.
	 */
	private int drainConfirmations(String receiver, RewardType type, int maxToClaim, long since) {
		String wantedReward = type.unitReward();
		long deadline = System.currentTimeMillis() + adaptiveTimeoutMs(MIN_CONFIRM_TIMEOUT_MS, MAX_CONFIRM_TIMEOUT_MS, CONFIRM_TIMEOUT_MULTIPLIER);
		int claimed = 0;
		while (claimed < maxToClaim) {
			if (consumeConfirmation(receiver, wantedReward, since)) {
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

	/**
	 * Find and remove one queued confirmation matching {@code receiver}/{@code
	 * wantedReward} (and our own name, if known) that arrived at or after {@code since}
	 * — a confirmation older than that belongs to whatever gift operation was already
	 * waiting on it when it arrived, not this one.
	 */
	private boolean consumeConfirmation(String receiver, String wantedReward, long since) {
		String giver = selfName;
		for (Iterator<TimedReward> it = confirmedRewards.iterator(); it.hasNext();) {
			TimedReward timed = it.next();
			if (timed.atMs() < since) {
				continue;
			}
			GuildReward candidate = timed.reward();
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
