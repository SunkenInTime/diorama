package dev.diorama.test;

//? if fabric {
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/**
 * The part of a {@link SecondPlayer} that runs in its game: while the game was started with a
 * commands file, each line added to that file is sent as a command once the player is on a
 * server, but {@link #SNEAK} and {@link #STAND}, which hold the sneak key down and let it go, as the
 * player would, and a line that starts with {@link #SAY}, whose rest the player says in chat. In any other game, the tests' own included, it does nothing.
 */
public final class Puppet implements ClientModInitializer {
	static final String PROPERTY = "diorama.test.puppet";
	static final String SNEAK = "+sneak";
	static final String STAND = "-sneak";
	static final String SAY = "+say ";
	private static volatile boolean sneaking;

	@Override
	public void onInitializeClient() {
		String file = System.getProperty(PROPERTY);
		if (file == null) {
			return;
		}
		Path commands = Path.of(file);
		// Held each tick, before the player's input reads it: nothing on this game's screen presses keys.
		net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			if (sneaking) {
				mc.options.keyShift.setDown(true);
			}
		});
		Thread reader = new Thread(() -> {
			int sent = 0;
			while (true) {
				try {
					Thread.sleep(250);
					List<String> lines = Files.readAllLines(commands);
					Minecraft mc = Minecraft.getInstance();
					while (sent < lines.size() && mc.getConnection() != null && mc.player != null) {
						String command = lines.get(sent++);
						if (command.equals(SNEAK) || command.equals(STAND)) {
							sneaking = command.equals(SNEAK);
							mc.execute(() -> mc.options.keyShift.setDown(sneaking));
							continue;
						}
						mc.execute(() -> {
							if (mc.getConnection() == null) {
								return;
							}
							if (command.startsWith(SAY)) {
								mc.getConnection().sendChat(command.substring(SAY.length()));
							} else {
								mc.getConnection().sendCommand(command);
							}
						});
					}
				} catch (IOException e) {
					// Being written: read again.
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "diorama-test-puppet");
		reader.setDaemon(true);
		reader.start();
	}
}
//?} else {
/*final class Puppet {
	static final String PROPERTY = "diorama.test.puppet";
	static final String SNEAK = "+sneak";
	static final String STAND = "-sneak";
	static final String SAY = "+say ";
}
*///?}
