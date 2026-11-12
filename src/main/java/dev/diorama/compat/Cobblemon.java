package dev.diorama.compat;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.diorama.Diorama;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.Set;
import java.util.TreeSet;
//? if fabric {
import net.fabricmc.loader.api.FabricLoader;
//?} else {
/*import net.neoforged.fml.ModList;
*///?}
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;

/**
 * What Diorama reads from Cobblemon: whether the player is in a battle, which Pokémon or NPC an
 * entity is, and the portrait Cobblemon draws of it, for the radar.
 *
 * <p>Diorama doesn't build against Cobblemon. Each part looks its classes up once, and if they
 * aren't there, logs once and stays off.
 */
public final class Cobblemon {
	private static boolean started;
	private static Object client;
	private static Method battle;

	private Cobblemon() {
	}

	/**
	 * Whether the player is in a Cobblemon battle. Its battle screen fills the bottom of the screen,
	 * and the battlers' tiles sit in both top corners for the whole battle, also once Esc has closed
	 * the screen to look around, so the minimap stays out of the way until the battle ends (#147).
	 * {@code CobblemonClient.INSTANCE.getBattle()} is the client's battle, spectated ones included,
	 * or null; it's the same from Cobblemon 1.5 (1.20.1) to 1.8 (1.21.1). Called on the render
	 * thread, where Cobblemon starts and ends battles.
	 */
	public static boolean inBattle() {
		if (!started) {
			started = true;
			if (installed()) {
				try {
					Class<?> type = Class.forName("com.cobblemon.mod.common.client.CobblemonClient");
					client = type.getField("INSTANCE").get(null);
					battle = type.getMethod("getBattle");
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					Diorama.LOGGER.warn("Cobblemon is installed, but its battles couldn't be found; the minimap won't step aside for them", e);
					battle = null;
				}
			}
		}
		if (battle == null) {
			return false;
		}
		try {
			return battle.invoke(client) != null;
		} catch (ReflectiveOperationException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read Cobblemon's battle; the minimap won't step aside for battles", e);
			battle = null;
			return false;
		}
	}

	/**
	 * One of Cobblemon's creatures as it's drawn: the species of a Pokémon or the class of an NPC, and
	 * its aspects (shiny, a form, a gender, an NPC's outfit), which pick its model and texture.
	 */
	public record Subject(Identifier id, Set<String> aspects, boolean npc) {
		public boolean shiny() {
			return !this.npc && this.aspects.contains("shiny");
		}
	}

	private static boolean subjectsStarted;
	private static @Nullable Class<?> pokemonEntity;
	private static Method pokemonOf;
	private static Method speciesOf;
	private static Method speciesId;
	private static Method pokemonAspects;
	private static @Nullable Class<?> npcEntity;
	private static Method npcId;
	private static Method npcAspects;

	/**
	 * Which Pokémon or NPC this entity is, or null for anything else, and when Cobblemon isn't
	 * installed. Every Pokémon is the same entity type, and every NPC another, so this is what tells
	 * them apart (#344). The same from Cobblemon 1.5 to 1.8; NPCs came with 1.6.
	 */
	public static @Nullable Subject subject(Entity entity) {
		if (!has(entity)) {
			return null;
		}
		try {
			if (pokemonEntity != null && pokemonEntity.isInstance(entity)) {
				return subject(speciesId.invoke(speciesOf.invoke(pokemonOf.invoke(entity))), pokemonAspects.invoke(entity), false);
			}
			return subject(npcId.invoke(entity), npcAspects.invoke(entity), true);
		} catch (ReflectiveOperationException | RuntimeException e) {
			Diorama.LOGGER.warn("Couldn't read which Pokémon or NPC an entity is; Cobblemon's will be dots on the radar", e);
			pokemonEntity = null;
			npcEntity = null;
			return null;
		}
	}

	/** Whether this entity is one of Cobblemon's Pokémon or NPCs. Cheap: asked for every entity on the radar. */
	public static boolean has(Entity entity) {
		if (!subjectsStarted) {
			subjectsStarted = true;
			if (installed()) {
				try {
					Class<?> type = Class.forName("com.cobblemon.mod.common.entity.pokemon.PokemonEntity");
					pokemonOf = type.getMethod("getPokemon");
					speciesOf = pokemonOf.getReturnType().getMethod("getSpecies");
					speciesId = speciesOf.getReturnType().getMethod("getResourceIdentifier");
					pokemonAspects = type.getMethod("getAspects");
					pokemonEntity = type;
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					Diorama.LOGGER.warn("Cobblemon is installed, but its Pokémon couldn't be read; they will be dots on the radar", e);
				}
				try {
					Class<?> type = Class.forName("com.cobblemon.mod.common.entity.npc.NPCEntity");
					npcId = type.getMethod("getResourceIdentifier");
					npcAspects = type.getMethod("getAspects");
					npcEntity = type;
				} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
					// Cobblemon before 1.6 has no NPCs.
				}
			}
		}
		return pokemonEntity != null && pokemonEntity.isInstance(entity) || npcEntity != null && npcEntity.isInstance(entity);
	}

	private static @Nullable Subject subject(Object id, Object aspects, boolean npc) {
		if (!(id instanceof Identifier identifier) || !(aspects instanceof Set<?> set)) {
			return null;
		}
		Set<String> names = new TreeSet<>();
		for (Object aspect : set) {
			names.add(String.valueOf(aspect));
		}
		return new Subject(identifier, Collections.unmodifiableSet(names), npc);
	}

	private static boolean portraitsStarted;
	/** Cobblemon 1.6 and later: {@code drawPosablePortrait(id, pose, state, partialTicks)}, and the state it poses. */
	private static @Nullable Method posablePortrait;
	private static Constructor<?> floatingState;
	private static Method setAspects;
	/** Cobblemon 1.5: {@code drawPortraitPokemon(species, aspects, pose, scale, reversed, state, partialTicks)}. */
	private static @Nullable Method pokemonPortrait;
	private static Object allSpecies;
	private static Method speciesById;

	/**
	 * Draws the portrait Cobblemon's own party list shows for this Pokémon or NPC: its model's head
	 * and shoulders, posed and framed as each model's author set it. Cobblemon ships no flat
	 * pictures of its Pokémon, so this is the one picture every species has. It's drawn where the
	 * party list draws it, into a box 21 across whose top left is at (0, 0) of {@code pose}; what
	 * sticks out of the box is for the caller to cut off. False if this Cobblemon has no portrait
	 * Diorama can draw. Render thread only; it draws through the game's own buffers and ends their
	 * batch.
	 */
	public static boolean drawPortrait(Subject subject, PoseStack pose) throws ReflectiveOperationException {
		if (!portraitsStarted) {
			portraitsStarted = true;
			try {
				Class<?> gui = Class.forName("com.cobblemon.mod.common.api.gui.GuiUtilsKt");
				Class<?> state = stateClass();
				if (state != null) {
					floatingState = Class.forName("com.cobblemon.mod.common.client.render.models.blockbench.FloatingState").getConstructor();
					setAspects = state.getMethod("setCurrentAspects", Set.class);
					posablePortrait = gui.getMethod("drawPosablePortrait", Identifier.class, PoseStack.class, state, float.class);
				} else {
					Class<?> species = Class.forName("com.cobblemon.mod.common.pokemon.Species");
					Class<?> all = Class.forName("com.cobblemon.mod.common.api.pokemon.PokemonSpecies");
					allSpecies = all.getField("INSTANCE").get(null);
					speciesById = all.getMethod("getByIdentifier", Identifier.class);
					pokemonPortrait = gui.getMethod("drawPortraitPokemon", species, Set.class, PoseStack.class, float.class, boolean.class,
						Class.forName("com.cobblemon.mod.common.client.render.models.blockbench.PoseableEntityState"), float.class);
				}
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				Diorama.LOGGER.warn("Cobblemon is installed, but its portraits couldn't be found; Pokémon will be dots on the radar", e);
				posablePortrait = null;
				pokemonPortrait = null;
			}
		}
		// The party list's own numbers: it starts the portrait here in its 21-wide box.
		pose.translate(9.5F, -12.0F, 0.0F);
		if (posablePortrait != null) {
			Object state = floatingState.newInstance();
			setAspects.invoke(state, subject.aspects());
			posablePortrait.invoke(null, subject.id(), pose, state, 0.0F);
			return true;
		}
		if (pokemonPortrait != null && !subject.npc()) {
			Object species = speciesById.invoke(allSpecies, subject.id());
			if (species == null) {
				return false;
			}
			// 13 is the scale the party list leaves at its default; no state draws the model at rest.
			pokemonPortrait.invoke(null, species, subject.aspects(), pose, 13.0F, false, null, 0.0F);
			return true;
		}
		return false;
	}

	/** The state Cobblemon 1.6 and later pose a model with, or null on 1.5. */
	private static @Nullable Class<?> stateClass() {
		try {
			return Class.forName("com.cobblemon.mod.common.client.render.models.blockbench.PosableState");
		} catch (ClassNotFoundException e) {
			return null;
		}
	}

	private static boolean installed() {
		//? if fabric {
		return FabricLoader.getInstance().isModLoaded("cobblemon");
		//?} else {
		/*return ModList.get().isLoaded("cobblemon");
		*///?}
	}
}
