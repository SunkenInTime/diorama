package dev.diorama;

import dev.diorama.render.Radar;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else if neoforge {
/*import net.neoforged.fml.ModList;
*///?} else {
/*import net.minecraftforge.fml.ModList;
*///?}
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.DefaultAttributes;
import org.jspecify.annotations.Nullable;

/**
 * The radar's categories, after Xaero's Minimap's entity categories: each has its own on/off, icon
 * size and names. Five are fixed, the kinds a player, mob or dropped item falls into by how it
 * behaves ({@link Radar.Kind}). Above them come the player's own, each a list of mobs; a mob in one
 * of those follows it and not its kind's, and the first that lists it wins, as the higher
 * sub-category does in Xaero's. Xaero's nests categories in a tree and has many more rules and
 * settings; this is the flat list of that idea.
 */
public final class RadarCategories {
	/** An icon size that follows the Radar tab's. */
	public static final int SAME_SIZE = 0;
	/** What {@link Category#builtIn} says for the category Diorama makes when a Pokémon mod is installed. */
	public static final String POKEMON = "pokemon";
	private static final List<String> POKEMON_MOBS = List.of("cobblemon:pokemon", "pixelmon:pixelmon");

	/** What the settings screens change, the same for a kind and for one of the player's categories. */
	public interface Entry {
		Component name();

		boolean shown();

		void shown(boolean shown);

		/** In percent, or {@link #SAME_SIZE}. */
		int iconScale();

		void iconScale(int percent);

		/** Whether names can show under this category's icons; dropped items have none. */
		boolean hasNames();

		Settings.NameTags names();

		void names(Settings.NameTags names);
	}

	/** One of the player's categories, as saved. */
	public static final class Category implements Entry {
		/** The name the player typed. */
		public @Nullable String name;
		/** Set on a category Diorama made, which is named in the player's language until they rename it. */
		public @Nullable String builtIn;
		/**
		 * The mobs in it: entity IDs like {@code minecraft:cow}, where {@code *} stands for anything, so
		 * {@code cobblemon:*} is every mob Cobblemon adds.
		 */
		public List<String> mobs = new ArrayList<>();
		public boolean shown = true;
		public int iconScale = SAME_SIZE;
		public Settings.NameTags names = Settings.NameTags.NEVER;

		@Override
		public Component name() {
			if (this.name != null && !this.name.isBlank()) {
				return Component.literal(this.name);
			}
			return Component.translatable(POKEMON.equals(this.builtIn) ? "diorama.radar_categories.pokemon" : "diorama.radar_categories.unnamed");
		}

		@Override
		public boolean shown() {
			return this.shown;
		}

		@Override
		public void shown(boolean shown) {
			this.shown = shown;
		}

		@Override
		public int iconScale() {
			return this.iconScale;
		}

		@Override
		public void iconScale(int percent) {
			this.iconScale = percent;
		}

		@Override
		public boolean hasNames() {
			return true;
		}

		@Override
		public Settings.NameTags names() {
			return this.names;
		}

		@Override
		public void names(Settings.NameTags names) {
			this.names = names;
		}

		public boolean has(String id) {
			for (String mob : this.mobs) {
				if (matches(mob, id)) {
					return true;
				}
			}
			return false;
		}
	}

	/** What a kind's category adds to its on/off, as saved under the kind's name. */
	public static final class KindLook {
		public int iconScale = SAME_SIZE;
		public Settings.NameTags names = Settings.NameTags.NEVER;
	}

	/**
	 * A kind's category. Its on/off is the setting the Radar tab has always had, and the players'
	 * names are the tab's Player names, so both screens change the same thing.
	 */
	private record KindEntry(Radar.Kind kind) implements Entry {
		@Override
		public Component name() {
			return Component.translatable("diorama.settings." + switch (this.kind) {
				case PLAYER -> "players";
				case HOSTILE -> "hostile";
				case NEUTRAL -> "neutral";
				case PASSIVE -> "passive";
				case ITEM -> "items";
			});
		}

		@Override
		public boolean shown() {
			Settings s = Settings.get();
			return switch (this.kind) {
				case PLAYER -> s.radarPlayers;
				case HOSTILE -> s.radarHostile;
				case NEUTRAL -> s.radarNeutral;
				case PASSIVE -> s.radarPassive;
				case ITEM -> s.radarItems;
			};
		}

		@Override
		public void shown(boolean shown) {
			Settings s = Settings.get();
			switch (this.kind) {
				case PLAYER -> s.radarPlayers = shown;
				case HOSTILE -> s.radarHostile = shown;
				case NEUTRAL -> s.radarNeutral = shown;
				case PASSIVE -> s.radarPassive = shown;
				case ITEM -> s.radarItems = shown;
			}
		}

		private @Nullable KindLook look() {
			return Settings.get().radarKinds.get(this.kind.name());
		}

		private KindLook lookToChange() {
			return Settings.get().radarKinds.computeIfAbsent(this.kind.name(), k -> new KindLook());
		}

		@Override
		public int iconScale() {
			KindLook look = this.look();
			return look == null ? SAME_SIZE : look.iconScale;
		}

		@Override
		public void iconScale(int percent) {
			this.lookToChange().iconScale = percent;
		}

		@Override
		public boolean hasNames() {
			return this.kind != Radar.Kind.ITEM;
		}

		@Override
		public Settings.NameTags names() {
			if (this.kind == Radar.Kind.PLAYER) {
				return Settings.get().playerNames;
			}
			KindLook look = this.look();
			return look == null ? Settings.NameTags.NEVER : look.names;
		}

		@Override
		public void names(Settings.NameTags names) {
			if (this.kind == Radar.Kind.PLAYER) {
				Settings.get().playerNames = names;
			} else {
				this.lookToChange().names = names;
			}
		}
	}

	private static final Entry[] KINDS = new Entry[Radar.Kind.values().length];
	/** The kinds as the screen lists them: players first, dropped items last. */
	private static final Radar.Kind[] LISTED = {Radar.Kind.PLAYER, Radar.Kind.HOSTILE, Radar.Kind.NEUTRAL, Radar.Kind.PASSIVE, Radar.Kind.ITEM};

	static {
		for (Radar.Kind kind : Radar.Kind.values()) {
			KINDS[kind.ordinal()] = new KindEntry(kind);
		}
	}

	/** Which of the player's categories each mob type is in; the list itself stands for "none". */
	private static final Map<EntityType<?>, Object> BY_TYPE = new IdentityHashMap<>();
	private static @Nullable List<Category> cachedFor;

	private RadarCategories() {
	}

	public static Entry kind(Radar.Kind kind) {
		return KINDS[kind.ordinal()];
	}

	/** Every category in the order the screen lists them, which is also the order a mob is matched in. */
	public static List<Entry> all() {
		List<Entry> all = new ArrayList<>(Settings.get().radarCategories);
		for (Radar.Kind kind : LISTED) {
			all.add(kind(kind));
		}
		return all;
	}

	/**
	 * The category an entity of this kind follows. Called on the render thread for every entity on
	 * the radar, so a mob type is looked up in the player's lists once.
	 */
	public static Entry of(Entity entity, Radar.Kind kind) {
		if (kind == Radar.Kind.PLAYER || kind == Radar.Kind.ITEM) {
			return kind(kind);
		}
		List<Category> categories = Settings.get().radarCategories;
		if (categories.isEmpty()) {
			return kind(kind);
		}
		if (cachedFor != categories) {
			BY_TYPE.clear();
			cachedFor = categories;
		}
		Object found = BY_TYPE.get(entity.getType());
		if (found == null) {
			String id = EntityType.getKey(entity.getType()).toString();
			found = categories;
			for (Category category : categories) {
				if (category.has(id)) {
					found = category;
					break;
				}
			}
			BY_TYPE.put(entity.getType(), found);
		}
		return found instanceof Category category ? category : kind(kind);
	}

	/** After a category's mobs, or the list of categories, changed. */
	public static void changed() {
		BY_TYPE.clear();
	}

	/** Whether an ID fits a list entry, where {@code *} in the entry stands for any run of characters. */
	public static boolean matches(String pattern, String id) {
		int star = pattern.indexOf('*');
		if (star < 0) {
			return pattern.equals(id);
		}
		if (!id.startsWith(pattern.substring(0, star))) {
			return false;
		}
		int at = star;
		int from = star;
		while (true) {
			int next = pattern.indexOf('*', at + 1);
			String piece = pattern.substring(at + 1, next < 0 ? pattern.length() : next);
			if (next < 0) {
				return id.length() - from >= piece.length() && id.endsWith(piece);
			}
			int found = id.indexOf(piece, from);
			if (found < 0) {
				return false;
			}
			from = found + piece.length();
			at = next;
		}
	}

	/** A hand-edited list entry made an ID: trimmed, lower case, and Minecraft's own if it names no mod. Null for a blank one. */
	static @Nullable String cleanMob(@Nullable String mob) {
		String trimmed = mob == null ? "" : mob.trim().toLowerCase(Locale.ROOT);
		if (trimmed.isEmpty()) {
			return null;
		}
		return trimmed.indexOf(':') < 0 ? "minecraft:" + trimmed : trimmed;
	}

	/**
	 * Mends what was read from the file, and adds the Pokémon category the first time Cobblemon or
	 * Pixelmon is installed: every Pokémon is the one entity type, so no kind's on/off can keep them
	 * and hide farm animals (#344). Deleted, it stays deleted.
	 */
	static void loaded(Settings settings) {
		List<Category> categories = new ArrayList<>();
		if (settings.radarCategories != null) {
			for (Category category : settings.radarCategories) {
				if (category == null) {
					continue;
				}
				List<String> mobs = new ArrayList<>();
				if (category.mobs != null) {
					for (String mob : category.mobs) {
						String clean = cleanMob(mob);
						if (clean != null && !mobs.contains(clean)) {
							mobs.add(clean);
						}
					}
				}
				category.mobs = mobs;
				category.iconScale = category.iconScale <= 0 ? SAME_SIZE : Settings.snapScale(category.iconScale);
				if (category.names == null) {
					category.names = Settings.NameTags.NEVER;
				}
				categories.add(category);
			}
		}
		settings.radarCategories = categories;
		Map<String, KindLook> kinds = new java.util.TreeMap<>();
		if (settings.radarKinds != null) {
			for (Radar.Kind kind : Radar.Kind.values()) {
				KindLook look = settings.radarKinds.get(kind.name());
				if (look != null) {
					look.iconScale = look.iconScale <= 0 ? SAME_SIZE : Settings.snapScale(look.iconScale);
					if (look.names == null) {
						look.names = Settings.NameTags.NEVER;
					}
					kinds.put(kind.name(), look);
				}
			}
		}
		settings.radarKinds = kinds;
		if (!settings.radarPokemonAdded && (installed("cobblemon") || installed("pixelmon"))) {
			settings.radarPokemonAdded = true;
			Category pokemon = new Category();
			pokemon.builtIn = POKEMON;
			pokemon.mobs = new ArrayList<>(POKEMON_MOBS);
			categories.add(0, pokemon);
		}
		changed();
	}

	/** A mob the player can put in a category. */
	public record Mob(String id, Component name) {
	}

	/**
	 * Every mob the game has, a mod's included, by name: the entity types that are alive, which are
	 * the ones with attributes. Players and armour stands are alive too, and aren't mobs.
	 */
	public static List<Mob> mobs() {
		List<Mob> mobs = new ArrayList<>();
		for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
			String id = EntityType.getKey(type).toString();
			if (!hasAttributes(type) || id.equals("minecraft:player") || id.equals("minecraft:armor_stand")) {
				continue;
			}
			mobs.add(new Mob(id, type.getDescription()));
		}
		mobs.sort(java.util.Comparator.comparing((Mob mob) -> mob.name().getString().toLowerCase(Locale.ROOT)).thenComparing(Mob::id));
		return mobs;
	}

	private static boolean hasAttributes(EntityType<?> type) {
		return DefaultAttributes.hasSupplier(type);
	}

	/** The mods that add mobs, by ID, for "every mob from". */
	public static Set<String> mobMods(List<Mob> mobs) {
		Set<String> mods = new TreeSet<>();
		for (Mob mob : mobs) {
			String namespace = mob.id().substring(0, mob.id().indexOf(':'));
			if (!namespace.equals("minecraft")) {
				mods.add(namespace);
			}
		}
		return mods;
	}

	/** A mod's name as its author wrote it, or its ID if the loader doesn't know it. */
	public static String modName(String id) {
		//? if fabric {
		return FabricLoader.getInstance().getModContainer(id).map(mod -> mod.getMetadata().getName()).orElse(id);
		//?} else {
		/*return ModList.get().getModContainerById(id).map(mod -> mod.getModInfo().getDisplayName()).orElse(id);
		*///?}
	}

	private static boolean installed(String mod) {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded(mod);
		//?} else {
		/*return ModList.get().isLoaded(mod);
		*///?}
	}
}
