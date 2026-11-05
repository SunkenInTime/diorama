package dev.diorama.structure;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.network.chat.Component;

/**
 * The kinds of structure the map can show, which is what the player turns on and off: a village is
 * one kind whatever its biome's variant. A structure the game adds that isn't listed here gets a
 * kind of its own the first time one is found, so nothing found is left off the map or the list.
 */
public final class StructureKinds {
	/** Where a kind is listed, and so which dimension's structures the player is choosing between. */
	public enum Group {
		OVERWORLD, NETHER, END
	}

	/** A kind: {@code id} is what the settings keep; its picture is {@link StructureIcons}. */
	public record Kind(String id, Group group) {
		public Component name() {
			return Component.translatableWithFallback("diorama.structure." + this.id, pretty(this.id));
		}
	}

	/** In drawing order: when icons crowd, the ones first here are kept and the later ones dropped. */
	private static final List<Kind> KNOWN = List.of(
		new Kind("stronghold", Group.OVERWORLD),
		new Kind("village", Group.OVERWORLD),
		new Kind("mansion", Group.OVERWORLD),
		new Kind("monument", Group.OVERWORLD),
		new Kind("pillager_outpost", Group.OVERWORLD),
		new Kind("ancient_city", Group.OVERWORLD),
		new Kind("trial_chambers", Group.OVERWORLD),
		new Kind("desert_pyramid", Group.OVERWORLD),
		new Kind("jungle_pyramid", Group.OVERWORLD),
		new Kind("igloo", Group.OVERWORLD),
		new Kind("swamp_hut", Group.OVERWORLD),
		new Kind("trail_ruins", Group.OVERWORLD),
		new Kind("ruined_portal", Group.OVERWORLD),
		new Kind("ocean_ruin", Group.OVERWORLD),
		new Kind("shipwreck", Group.OVERWORLD),
		new Kind("buried_treasure", Group.OVERWORLD),
		new Kind("abandoned_camp", Group.OVERWORLD),
		new Kind("mineshaft", Group.OVERWORLD),
		new Kind("fortress", Group.NETHER),
		new Kind("bastion", Group.NETHER),
		new Kind("nether_fossil", Group.NETHER),
		new Kind("end_ship", Group.END),
		new Kind("end_city", Group.END));

	private static final Map<String, Kind> BY_ID = new ConcurrentHashMap<>();
	/** Kinds made up for structures this list doesn't know, in the order they turned up. */
	private static final List<Kind> EXTRA = new ArrayList<>();

	static {
		for (Kind kind : KNOWN) {
			BY_ID.put(kind.id(), kind);
		}
	}

	private StructureKinds() {
	}

	/** The kind of a structure, by its registry name without the namespace: {@code village_plains} is a village. */
	public static Kind of(String structure) {
		String id = idOf(structure);
		Kind kind = BY_ID.get(id);
		if (kind == null) {
			synchronized (EXTRA) {
				kind = BY_ID.get(id);
				if (kind == null) {
					kind = new Kind(id, Group.OVERWORLD);
					BY_ID.put(id, kind);
					EXTRA.add(kind);
				}
			}
		}
		return kind;
	}

	private static String idOf(String structure) {
		for (String prefix : new String[] {"village", "ocean_ruin", "shipwreck", "mineshaft", "abandoned_camp", "ruined_portal"}) {
			if (structure.startsWith(prefix)) {
				return prefix;
			}
		}
		return structure.equals("bastion_remnant") ? "bastion" : structure;
	}

	/** Every kind, the ones the game comes with first in drawing order. */
	public static List<Kind> all() {
		List<Kind> all = new ArrayList<>(KNOWN);
		synchronized (EXTRA) {
			all.addAll(EXTRA);
		}
		return all;
	}

	/** The position of a kind in drawing order: lower is kept when icons crowd. */
	public static int priority(Kind kind) {
		int index = KNOWN.indexOf(kind);
		return index >= 0 ? index : KNOWN.size();
	}

	private static String pretty(String id) {
		StringBuilder out = new StringBuilder();
		for (String word : id.split("_")) {
			if (!word.isEmpty()) {
				out.append(out.isEmpty() ? "" : " ").append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
			}
		}
		return out.toString();
	}
}
