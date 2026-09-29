package tel.eden.mod.util;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.world.entity.Entity;

/** Registers client-side entities for the thick screen-space outline effect. */
public final class OutlineManager {
	public static final int MAX_THICKNESS = 127;
	public static final int MAX_STYLE_SLOTS = 16;
	private final Map<Entity, Style> styles = Collections.synchronizedMap(new WeakHashMap<>());
	private volatile StyleSnapshot styleSnapshot = new StyleSnapshot(Map.of(), List.of());

	/**
	 * Registers an entity style, returning false when the entity is null or adding it
	 * would exceed the shader's distinct-style limit.
	 */
	public boolean add(Entity entity, int argb, int thickness) {
		if (entity == null) {
			return false;
		}
		Style style = new Style(argb, Math.clamp(thickness, 1, MAX_THICKNESS));
		synchronized (styles) {
			Set<Style> otherStyles = new HashSet<>();
			for (Map.Entry<Entity, Style> entry : styles.entrySet()) {
				if (entry.getKey() != entity) {
					otherStyles.add(entry.getValue());
				}
			}
			if (!otherStyles.contains(style) && otherStyles.size() >= MAX_STYLE_SLOTS) {
				return false;
			}
			styles.put(entity, style);
			rebuildStyleSnapshot();
		}
		return true;
	}

	public void remove(Entity entity) {
		synchronized (styles) {
			styles.remove(entity);
			rebuildStyleSnapshot();
		}
	}

	public void clear() {
		synchronized (styles) {
			styles.clear();
			styleSnapshot = new StyleSnapshot(Map.of(), List.of());
		}
	}

	int styleSlot(Entity entity) {
		Style style = styles.get(entity);
		return style == null ? -1 : styleSnapshot.slots().getOrDefault(style, -1);
	}

	List<Style> styleTable() {
		return styleSnapshot.styles();
	}

	private void rebuildStyleSnapshot() {
		Map<Style, Integer> newSlots = new LinkedHashMap<>();
		for (Style style : styles.values()) {
			if (!newSlots.containsKey(style)) {
				newSlots.put(style, newSlots.size());
			}
		}
		styleSnapshot = new StyleSnapshot(Map.copyOf(newSlots), List.copyOf(newSlots.keySet()));
	}

	public record Style(int argb, int thickness) {
	}

	private record StyleSnapshot(Map<Style, Integer> slots, List<Style> styles) {
	}
}
