package dev.diorama.test;

//? if fabric {
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

/**
 * Puts the last run's diorama/config.json back before any mod starts, for a test of the game
 * starting again with what it saved ({@code addons-restart}). Fabric API's game tests empty the
 * game folder before each run, after sandbox.sh has put the file there, so on Fabric it's copied
 * again here, from DIORAMA_KEPT_CONFIG (set by sandbox.sh with DIORAMA_KEEP_CONFIG=1). NeoForge and
 * Forge keep the folder as sandbox.sh leaves it.
 */
public final class KeptConfig implements PreLaunchEntrypoint {
	@Override
	public void onPreLaunch() {
		String kept = System.getenv("DIORAMA_KEPT_CONFIG");
		if (kept == null || kept.isEmpty()) {
			return;
		}
		Path target = FabricLoader.getInstance().getGameDir().resolve("diorama").resolve("config.json");
		try {
			Files.createDirectories(target.getParent());
			Files.copy(Path.of(kept), target, StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		System.out.println("Diorama test: diorama/config.json kept from the last run");
	}
}
//?}
