package dev.diorama.render;

import dev.diorama.Diorama;
import dev.diorama.compat.Cobblemon;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import org.jspecify.annotations.Nullable;
//? if <1.21.2 {
/*import com.mojang.blaze3d.pipeline.TextureTarget;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4f;
*///?}

/**
 * Radar pictures for Cobblemon's Pokémon and NPCs (#344). Cobblemon has no flat picture of a
 * Pokémon, only its model, so each kind's picture is the portrait Cobblemon's party list shows,
 * drawn once into a small image of its own and then used like any icon file's sprite. One picture
 * per species and look (shiny, form, gender, an NPC's outfit), made the first time one is on the
 * radar, a couple a frame, and made again after a resource reload. Until its picture is ready, and
 * if it can't be drawn, a Pokémon is what it was before: a dot.
 *
 * <p>Cobblemon's newest version is for Minecraft 1.21.1, so this only draws up to there.
 */
final class CobblemonPortraits {
	/** The portrait's box is 21 across in the party list; this many image pixels for each. */
	private static final int DETAIL = 4;
	private static final int BOX = 21;
	/** A picture is made between frames; more than this many at once would be felt. */
	private static final int PER_FRAME = 2;
	/** A Pokémon's look can change (it evolves, it's dyed), so who it is is read again this often. */
	private static final int REFRESH_TICKS = 40;

	private record Seen(Cobblemon.@Nullable Subject subject, int age) {
	}

	private static final Map<Entity, Seen> SEEN = new com.google.common.collect.MapMaker().weakKeys().makeMap();
	/** Empty for one that couldn't be drawn, so it's tried once per resource load, not every frame. */
	private static final Map<Cobblemon.Subject, Optional<XaeroRadarIcons.Sprite>> PORTRAITS = new HashMap<>();
	private static final ArrayDeque<Cobblemon.Subject> WAITING = new ArrayDeque<>();
	private static boolean scheduled;
	private static boolean warned;

	private CobblemonPortraits() {
	}

	/** This entity's picture, or null: not Cobblemon's, not drawn yet, or not drawable. Render thread only. */
	static XaeroRadarIcons.@Nullable Sprite of(Entity entity) {
		Cobblemon.Subject subject = subject(entity);
		if (subject == null) {
			return null;
		}
		Optional<XaeroRadarIcons.Sprite> portrait = PORTRAITS.get(subject);
		if (portrait != null) {
			return portrait.orElse(null);
		}
		if (!WAITING.contains(subject)) {
			WAITING.add(subject);
		}
		// Once a frame at most: the task runs before the next frame is drawn.
		if (!scheduled) {
			scheduled = true;
			// Between frames: the portrait is drawn through the game's own buffers, which hold the
			// screen's half-drawn batches while the radar is being drawn.
			Minecraft mc = Minecraft.getInstance();
			mc.schedule(CobblemonPortraits::drawWaiting);
		}
		return null;
	}

	/** Whether this entity is a shiny Pokémon. */
	static boolean shiny(Entity entity) {
		Cobblemon.Subject subject = subject(entity);
		return subject != null && subject.shiny();
	}

	private static Cobblemon.@Nullable Subject subject(Entity entity) {
		if (!Cobblemon.has(entity)) {
			return null;
		}
		Seen seen = SEEN.get(entity);
		int age = entity.tickCount;
		if (seen == null || age - seen.age() >= REFRESH_TICKS || age < seen.age()) {
			seen = new Seen(Cobblemon.subject(entity), age);
			SEEN.put(entity, seen);
		}
		return seen.subject();
	}

	/** Forgets every picture; they're drawn again as they're next needed. Resource packs can change a Pokémon's model and texture. */
	static void clear() {
		for (Optional<XaeroRadarIcons.Sprite> portrait : PORTRAITS.values()) {
			portrait.ifPresent(XaeroRadarIcons.Sprite::close);
		}
		PORTRAITS.clear();
		WAITING.clear();
	}

	private static void drawWaiting() {
		scheduled = false;
		for (int i = 0; i < PER_FRAME && !WAITING.isEmpty(); i++) {
			Cobblemon.Subject subject = WAITING.poll();
			XaeroRadarIcons.Sprite sprite = null;
			try {
				sprite = draw(subject);
			} catch (ReflectiveOperationException | RuntimeException | LinkageError e) {
				if (!warned) {
					warned = true;
					Diorama.LOGGER.warn("Couldn't draw Cobblemon's portrait of {}; it will be a dot on the radar", subject.id(), e);
				}
			}
			PORTRAITS.put(subject, Optional.ofNullable(sprite));
		}
		// The rest wait for the radar to ask again next frame. Asking for another turn from here
		// wouldn't wait: the game runs its tasks until none are left, so all would be drawn now.
	}

	/** The picture's name in logs and tests: {@code cobblemon:gengar/male.shiny}. */
	private static Identifier name(Cobblemon.Subject subject) {
		StringBuilder path = new StringBuilder(subject.id().getPath());
		char separator = '/';
		for (String aspect : subject.aspects()) {
			path.append(separator).append(aspect.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_.-]", "_"));
			separator = '.';
		}
		Identifier name = Identifier.tryBuild(subject.id().getNamespace(), path.toString());
		return name == null ? subject.id() : name;
	}

	//? if >=1.21.2 {
	private static XaeroRadarIcons.@Nullable Sprite draw(Cobblemon.Subject subject) throws ReflectiveOperationException {
		return null;
	}
	//?} else {
	/*private static XaeroRadarIcons.@Nullable Sprite draw(Cobblemon.Subject subject) throws ReflectiveOperationException {
		Minecraft mc = Minecraft.getInstance();
		int size = BOX * DETAIL;
		TextureTarget target = new TextureTarget(size, size, true, Minecraft.ON_OSX);
		Matrix4f projection = RenderSystem.getProjectionMatrix();
		VertexSorting sorting = RenderSystem.getVertexSorting();
		float fogStart = RenderSystem.getShaderFogStart();
		try {
			target.setClearColor(0, 0, 0, 0);
			target.clear(Minecraft.ON_OSX);
			target.bindWrite(true);
			// The party list's box, filling the image, seen the way the screen is: y down, no fog.
			RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0, BOX, BOX, 0, -1000, 1000), VertexSorting.ORTHOGRAPHIC_Z);
			RenderSystem.setShaderFogStart(Float.MAX_VALUE);
			RenderSystem.setShaderColor(1, 1, 1, 1);
			pushModelView();
			boolean drawn;
			try {
				drawn = Cobblemon.drawPortrait(subject, new PoseStack());
			} finally {
				mc.renderBuffers().bufferSource().endBatch();
				popModelView();
			}
			if (!drawn) {
				return null;
			}
			try (NativeImage image = new NativeImage(size, size, false)) {
				RenderSystem.bindTexture(target.getColorTextureId());
				image.downloadTexture(0, false);
				image.flipY();
				return XaeroRadarIcons.Sprite.cropped(name(subject), image, true, false);
			}
		} finally {
			// Cobblemon lights the portrait its own way and puts the lights back at the end; a
			// portrait that failed partway wouldn't have.
			com.mojang.blaze3d.platform.Lighting.setupFor3DItems();
			RenderSystem.setShaderFogStart(fogStart);
			RenderSystem.setProjectionMatrix(projection, sorting);
			target.destroyBuffers();
			mc.getMainRenderTarget().bindWrite(true);
		}
	}
	*///?}

	//? if >=1.20.5 && <1.21.2 {
	/*private static void pushModelView() {
		RenderSystem.getModelViewStack().pushMatrix().identity();
		RenderSystem.applyModelViewMatrix();
	}

	private static void popModelView() {
		RenderSystem.getModelViewStack().popMatrix();
		RenderSystem.applyModelViewMatrix();
	}
	*///?}
	//? if <1.20.5 {
	/*private static void pushModelView() {
		RenderSystem.getModelViewStack().pushPose();
		RenderSystem.getModelViewStack().setIdentity();
		RenderSystem.applyModelViewMatrix();
	}

	private static void popModelView() {
		RenderSystem.getModelViewStack().popPose();
		RenderSystem.applyModelViewMatrix();
	}
	*///?}
}
