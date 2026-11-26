package dev.diorama.test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * A second player with the same mods as the test's own game, for servers a vanilla client can't
 * stay on (FTB's mods turn one away) and for a second player who has to type commands, as joining
 * a party takes. It's this game started again, as it was started, under another name and in a game
 * folder of its own, joining the server straight away and without the tests.
 *
 * <p>It does what it's told through a file: each line added is a command it sends as its player,
 * as if typed with a slash ({@link #command}). {@link Puppet} is the part of it that runs in that
 * game.
 */
final class SecondPlayer implements AutoCloseable {
	/** Options that take a value, and are this player's own. */
	private static final Set<String> OWN = Set.of("--username", "--gameDir", "--quickPlayMultiplayer", "--quickPlaySingleplayer", "--quickPlayPath", "--uuid");

	private final String name;
	private final Process process;
	private final Path commands;
	private final Path folder;

	private SecondPlayer(String name, Process process, Path commands, Path folder) {
		this.name = name;
		this.process = process;
		this.commands = commands;
		this.folder = folder;
	}

	/** Starts the game as {@code name} and waits until the server says they joined. */
	static SecondPlayer join(String name, RealServer server) {
		String program = ProcessHandle.current().info().command().orElseThrow(() -> new AssertionError("Can't tell how this game was started"));
		String[] started;
		try {
			// Whole, where ProcessHandle's copy of a long command is cut short. Its first part is the program.
			String[] all = Files.readString(Path.of("/proc/self/cmdline")).split(String.valueOf((char) 0));
			started = java.util.Arrays.copyOfRange(all, 1, all.length);
		} catch (IOException e) {
			throw new AssertionError("Can't tell how this game was started", e);
		}
		Path folder;
		Path commands;
		try {
			folder = Files.createTempDirectory("second-player-" + name);
			commands = folder.resolve("commands.txt");
			Files.writeString(commands, "");
			Files.writeString(folder.resolve("options.txt"), "onboardAccessibility:false\nsoundCategory_master:0.0\npauseOnLostFocus:false\n");
			// A game folder that holds the mods, on a loader that takes them from there.
			Path mods = Path.of("mods");
			if (Files.isDirectory(mods)) {
				Files.createDirectories(folder.resolve("mods"));
				try (Stream<Path> jars = Files.list(mods)) {
					for (Path jar : (Iterable<Path>) jars::iterator) {
						if (Files.isRegularFile(jar)) {
							Files.copy(jar, folder.resolve("mods").resolve(jar.getFileName()));
						}
					}
				}
			}
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		List<String> command = new ArrayList<>();
		command.add(program);
		boolean jvm = true;
		for (int i = 0; i < started.length; i++) {
			String argument = started[i];
			if (argument.startsWith("-Dfabric.client.gametest") || argument.startsWith("-Ddiorama.gameshim") || argument.startsWith("-Xmx") || argument.startsWith("-Xms")) {
				continue;
			}
			if (OWN.contains(argument)) {
				i++;
				continue;
			}
			if (jvm && !argument.startsWith("-") && (i == 0 || !Set.of("-cp", "-classpath", "-p", "--module-path", "--add-modules", "--add-opens", "--add-exports").contains(started[i - 1]))) {
				// The main class: this player's own settings go in before it.
				jvm = false;
				command.add("-Xmx2G");
				command.add("-D" + Puppet.PROPERTY + "=" + commands.toAbsolutePath());
			}
			command.add(argument);
		}
		command.addAll(List.of("--username", name, "--gameDir", folder.toAbsolutePath().toString(), "--quickPlayMultiplayer", "127.0.0.1:" + server.port));
		long joined = joins(server, name);
		Process process;
		try {
			process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(folder.resolve("game.log").toFile()).start();
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		SecondPlayer player = new SecondPlayer(name, process, commands, folder);
		long deadline = System.currentTimeMillis() + 900_000;
		while (joins(server, name) <= joined) {
			if (!process.isAlive() || System.currentTimeMillis() > deadline) {
				String log = player.log();
				player.close();
				throw new AssertionError("The second player " + name + " never joined. Started with " + command + "\n" + log);
			}
			pause(1000);
		}
		return player;
	}

	private static long joins(RealServer server, String name) {
		return server.console().lines().filter(line -> line.contains(name + " joined the game")).count();
	}

	/** The player sends a command, as if typed after a slash. */
	void command(String command) {
		try {
			Files.writeString(this.commands, command + "\n", StandardCharsets.UTF_8, StandardOpenOption.APPEND);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** The player says a line in chat. */
	void say(String line) {
		this.command(Puppet.SAY + line);
	}

	/** The player holds the sneak key down, or lets it go. */
	void sneak(boolean down) {
		this.command(down ? Puppet.SNEAK : Puppet.STAND);
	}

	/** Whether the game is still running: a player turned away by the server is back at the title screen, still running. */
	boolean running() {
		return this.process.isAlive();
	}

	/** The end of what the game printed. */
	String log() {
		try {
			List<String> lines = Files.readAllLines(this.folder.resolve("game.log"));
			return String.join("\n", lines.subList(Math.max(0, lines.size() - 40), lines.size()));
		} catch (IOException e) {
			return "(no log: " + e + ")";
		}
	}

	/** The player leaves: the game is stopped. */
	@Override
	public void close() {
		this.process.destroy();
		try {
			if (!this.process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)) {
				this.process.destroyForcibly();
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			this.process.destroyForcibly();
		}
	}

	@Override
	public String toString() {
		return this.name;
	}

	private static void pause(long ms) {
		try {
			Thread.sleep(ms);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new AssertionError(e);
		}
	}
}
