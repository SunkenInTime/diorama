package dev.diorama.render.gpu;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.diorama.Diorama;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import org.jspecify.annotations.Nullable;

/**
 * What {@link GpuBakeCheck} remembers between sessions, in {@code .minecraft/diorama/gpu-bake.json}:
 * the graphics cards GPU baking turned itself off on, and the card whose first bakes were still
 * unproven when this was last written. A card here counts as its name and driver together, and a
 * result only holds for the Diorama version that found it, so a new driver or a new Diorama tries
 * again.
 */
final class GpuBakeRecord {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Why a card is off, by {@link GpuTileBaker#device}. */
	Map<String, Entry> off = new LinkedHashMap<>();
	/**
	 * The card set up for GPU baking and not proven yet. Still here at the next start, the game
	 * went down without closing during those first bakes: a driver crash leaves nothing else behind.
	 */
	@Nullable String starting;

	static final class Entry {
		GpuBakeCheck.Off result;
		String mod;

		Entry(GpuBakeCheck.Off result, String mod) {
			this.result = result;
			this.mod = mod;
		}
	}

	/** The saved result for a card, if it's from this version of Diorama. */
	GpuBakeCheck.@Nullable Off result(String device, String mod) {
		Entry entry = this.off.get(device);
		return entry != null && mod.equals(entry.mod) ? entry.result : null;
	}

	static GpuBakeRecord load() {
		Path file = file();
		if (Files.isRegularFile(file)) {
			try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
				GpuBakeRecord record = GSON.fromJson(reader, GpuBakeRecord.class);
				if (record != null) {
					// A hand-edited file can leave this out; an enum Gson can't name comes back null.
					record.off = record.off == null ? new LinkedHashMap<>() : record.off;
					record.off.values().removeIf(entry -> entry == null || entry.result == null);
					return record;
				}
			} catch (Exception e) {
				Diorama.LOGGER.error("Couldn't read {}; every graphics card gets checked again", file, e);
			}
		}
		return new GpuBakeRecord();
	}

	void save() {
		Path file = file();
		try {
			Files.createDirectories(file.getParent());
			Path temp = file.resolveSibling("gpu-bake.json.tmp");
			try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
				GSON.toJson(this, writer);
			}
			Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		} catch (Exception e) {
			Diorama.LOGGER.error("Couldn't save {}", file, e);
		}
	}

	private static Path file() {
		return Minecraft.getInstance().gameDirectory.toPath().resolve(Diorama.MOD_ID).resolve("gpu-bake.json");
	}
}
