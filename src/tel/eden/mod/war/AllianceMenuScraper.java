package tel.eden.mod.war;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import tel.eden.mod.EdenLogger;
import tel.eden.mod.EdenModClient;
import tel.eden.mod.net.BridgeWebSocketClient;

/**
 * Reads the guild's alliance roster from the in-game {@code Diplomacy} menu and relays it
 * to the backend, which shows it on the reward-storage message.
 *
 * <p>The menu is the game's own view of the roster, so a reading replaces whatever the
 * backend holds. Guild chat also announces alliances forming and being revoked (the
 * backend applies those as they arrive), but only this menu can correct a roster that has
 * drifted — a member who was offline for an alliance change never saw the chat line.
 *
 * <p>Parsing is all-or-nothing. A slot that doesn't look like an alliance entry means the
 * layout isn't what we expect, and a partial roster would silently drop real allies once
 * it replaced the stored one, so an unrecognised menu is discarded instead. It is only
 * sent when the roster actually changes, since the menu can sit open for a while.
 */
public final class AllianceMenuScraper {
	private AllianceMenuScraper() {
	}

	// Menu title, e.g. "Eden: Diplomacy".
	private static final Pattern TITLE = Pattern.compile("^[A-Za-z][A-Za-z ]*: Diplomacy$");
	// An allied-guild entry names the guild and its tag, e.g. "Sequoia [SEQ]" — the item is a
	// banner whose display name carries both. Tags are matched loosely: parsing is
	// all-or-nothing, so a tag shape we failed to anticipate would cost the entire roster
	// rather than one entry.
	private static final Pattern ALLY_ITEM = Pattern.compile("^(.+?) \\[([A-Za-z0-9]{2,5})]$");
	// The display name arrives styled ("§a§lSequoia [SEQ]"), and the codes are part of the
	// string rather than the component's style, so they have to come off before matching —
	// otherwise the guild name keeps them and matches nothing the backend knows.
	private static final Pattern COLOR_CODE = Pattern.compile("[§&][0-9a-fk-orA-FK-OR]");
	// The slots the alliance entries occupy; anything outside them is menu furniture.
	private static final int[] ALLY_SLOTS = {2, 3, 4, 5, 6, 7, 8};
	private static final int MAX_ALLIES = 16;
	// How long a reading has to stay identical before it counts as the settled roster.
	// Comfortably longer than the gap between the packets that fill the menu, and short
	// enough that it still lands while the menu is open.
	private static final long ROSTER_STABLE_MS = 750L;
	private static final int MAX_NAME_LENGTH = 64;
	// Hotbar + main inventory, appended to every container menu after its own slots.
	private static final int PLAYER_INVENTORY_SLOTS = 36;

	// Last roster relayed, so a menu left open doesn't re-send every tick. Kept across
	// closes as a dedup key only — reopening the menu re-reports regardless, so a roster
	// the backend somehow missed is never withheld for matching what we last sent.
	private static List<Ally> lastSent = List.of();
	private static boolean menuOpen;
	// The reading being waited on, and when it first appeared. A roster is only relayed
	// once it has read the same for ROSTER_STABLE_MS, which is what keeps a half-delivered
	// menu from replacing the stored roster with a shorter one.
	private static List<Ally> pendingRoster = List.of();
	private static long pendingSince;
	private static final EdenLogger LOGGER = EdenLogger.get();

	/** Client-thread tick: if the Diplomacy menu is open, read and relay the roster. */
	public static void onTick(Minecraft mc) {
		if (!(mc.screen instanceof AbstractContainerScreen<?> screen) || !isDiplomacyTitle(screen)) {
			menuOpen = false;
			return;
		}
		AbstractContainerMenu menu = screen.getMenu();
		// Every alliance slot has to fall inside the container's own slots, i.e. before the
		// player inventory appended after them. Anything smaller isn't the menu we parse,
		// and would leave hasContents scanning a negative range.
		if (menu.slots.size() <= PLAYER_INVENTORY_SLOTS + ALLY_SLOTS[ALLY_SLOTS.length - 1]) {
			return;
		}
		// The screen exists from the moment the open packet lands, but slot contents
		// arrive in a later packet, so the first ticks see an entirely empty menu. An
		// empty alliance section then reads as "no allies" and would wipe the stored
		// roster. The menu's own furniture (borders, back button) fills slots outside the
		// alliance range, so any occupied slot there means the contents have landed.
		if (!hasContents(menu)) {
			return;
		}
		List<Ally> allies = parseAllies(menu);
		boolean firstReadOfThisMenu = !menuOpen;
		// Null means the menu didn't parse cleanly; wait rather than relay a partial view.
		// Logged once per opening: every way this feature fails is silent by design, so
		// without a line here there is nothing to tell "never read it" from "read it and
		// the backend ignored it".
		if (allies == null) {
			if (firstReadOfThisMenu) {
				LOGGER.warn("Diplomacy menu did not parse; alliance roster not sent");
				menuOpen = true;
			}
			return;
		}
		menuOpen = true;
		// Wynncraft fills the alliance slots over more than one packet, and re-pushes them
		// while an ally is being added or removed. A slot that has not arrived yet reads
		// exactly like one whose ally was just removed, so a reading taken mid-update is a
		// short roster — and since a snapshot *replaces* what the backend holds, acting on
		// one deletes real allies. So a reading has to hold still before it is believed.
		if (!allies.equals(pendingRoster)) {
			pendingRoster = allies;
			pendingSince = System.currentTimeMillis();
			return;
		}
		if (System.currentTimeMillis() - pendingSince < ROSTER_STABLE_MS) {
			return;
		}
		if (!firstReadOfThisMenu && allies.equals(lastSent)) {
			return;
		}
		lastSent = allies;
		BridgeWebSocketClient socket = EdenModClient.instance().socket();
		if (socket == null) {
			LOGGER.warn("Read {} allies but the bridge is not connected: {}", allies.size(), allies);
			return;
		}
		LOGGER.info("Alliance roster read ({}): {}", allies.size(), allies);
		List<String> names = new ArrayList<>();
		List<String> tags = new ArrayList<>();
		for (Ally ally : allies) {
			names.add(ally.name());
			tags.add(ally.tag());
		}
		socket.sendGuildAlliances(names, tags);
	}

	/** Forget the last relayed roster (world change / disconnect). */
	public static void reset() {
		lastSent = List.of();
		menuOpen = false;
		pendingRoster = List.of();
		pendingSince = 0;
	}

	/**
	 * Whether the container's contents have been delivered, judged by any slot outside the
	 * alliance range being occupied. Those slots hold the menu's own decoration, so they
	 * are filled for every Diplomacy menu — including one with no allies at all, which is
	 * why the alliance slots themselves can't be used as the signal.
	 */
	private static boolean hasContents(AbstractContainerMenu menu) {
		// The player's own inventory is appended to every container menu and is populated
		// client-side from the start, so only the container's own slots say anything about
		// whether the server's contents have arrived.
		int containerSlots = menu.slots.size() - PLAYER_INVENTORY_SLOTS;
		int firstAllySlot = ALLY_SLOTS[0];
		int lastAllySlot = ALLY_SLOTS[ALLY_SLOTS.length - 1];
		for (int slot = 0; slot < containerSlots; slot++) {
			if (slot >= firstAllySlot && slot <= lastAllySlot) {
				continue;
			}
			if (!menu.getSlot(slot).getItem().isEmpty()) {
				return true;
			}
		}
		return false;
	}

	private static boolean isDiplomacyTitle(AbstractContainerScreen<?> screen) {
		return TITLE.matcher(stripCodes(screen.getTitle().getString()).trim()).matches();
	}

	private static String stripCodes(String text) {
		return COLOR_CODE.matcher(text).replaceAll("");
	}

	/**
	 * Allied guilds in slot order, or null when the menu doesn't parse. Empty slots are
	 * alliance slots the guild hasn't filled, which is a valid (empty) roster.
	 */
	private static List<Ally> parseAllies(AbstractContainerMenu menu) {
		Map<String, Ally> unique = new LinkedHashMap<>();
		for (int slot : ALLY_SLOTS) {
			ItemStack stack = menu.getSlot(slot).getItem();
			// An unfilled alliance slot is simply empty, so anything present here is meant
			// to be an ally and has to read like one. Item type is deliberately not part of
			// that test: the entries are guild banners, but tags are server-synced and
			// Wynncraft's banner tag does not arrive complete — checking against it dropped
			// every black-bannered guild while keeping the others, silently.
			if (stack.isEmpty()) {
				continue;
			}
			String displayed = stripCodes(stack.getHoverName().getString()).trim();
			Matcher matcher = ALLY_ITEM.matcher(displayed);
			if (!matcher.matches()) {
				LOGGER.warn("Diplomacy slot {} holds {} named \"{}\", which is not an ally entry", slot, stack.getItem(), displayed);
				return null;
			}
			String name = matcher.group(1).trim();
			// A colon would mean we captured a chat line or a label, not a guild name.
			if (name.isEmpty() || name.length() > MAX_NAME_LENGTH || name.contains(":")) {
				LOGGER.warn("Diplomacy slot {} yielded an implausible guild name: \"{}\"", slot, name);
				return null;
			}
			unique.putIfAbsent(name.toLowerCase(Locale.ROOT), new Ally(name, matcher.group(2).trim()));
			if (unique.size() > MAX_ALLIES) {
				return null;
			}
		}
		return List.copyOf(new ArrayList<>(unique.values()));
	}

	/** One allied guild as the Diplomacy menu shows it: its name and its tag. */
	public record Ally(String name, String tag) {
	}
}
