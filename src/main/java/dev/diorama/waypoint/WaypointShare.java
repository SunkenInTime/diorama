package dev.diorama.waypoint;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import net.minecraft.network.chat.TextColor;
import com.mojang.brigadier.CommandDispatcher;
import org.jspecify.annotations.Nullable;

/**
 * Sharing waypoints through chat, in the format Xaero's Minimap uses, so players of either mod
 * can pick up each other's waypoints: {@code xaero-waypoint:Name:N:x:y:z:colour:false:0:Internal-overworld-waypoints}.
 * A chat line carrying one gets an "[Add]" link on the end that opens the waypoint editor filled
 * in. Chat can carry anything, so everything read from it is checked.
 */
public final class WaypointShare {
	private static final Pattern SHARED = Pattern.compile(
		"xaero-waypoint:([^:]{1,160}):([^:]{0,20}):(-?\\d{1,9}):(~|-?\\d{1,5}):(-?\\d{1,9}):(\\d{1,2}):(?:true|false):-?\\d{1,5}:(Internal-[^\\s]{1,200}-waypoints)"
	);
	/** Further out than the world border, or outside any world's height, isn't a real place. */
	private static final int MAX_HORIZONTAL = 30_000_000;
	private static final int MAX_VERTICAL = 4096;
	/** The longest chat message a client may send. */
	private static final int MAX_CHAT = 256;
	/** The waypoint editor's limits. */
	private static final int MAX_NAME = 32;
	private static final Pattern DIMENSION_ID = Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");
	/** How the "Add" link's command starts. */
	private static final String ADD_COMMAND = "/diorama waypoint add ";

	/** A waypoint someone shared. {@code y} is null when the sender didn't know the height. */
	public record Shared(String name, String initials, int x, Integer y, int z, int colorIndex, String dimension) {
	}

	private WaypointShare() {
	}

	/**
	 * The chat text for a waypoint, or empty if it can't fit in chat's 256 characters. Names are
	 * escaped the way Xaero's Minimap does, since colons separate the fields, and anything chat
	 * refuses (formatting codes, control characters) is dropped.
	 */
	public static Optional<String> format(Waypoint waypoint) {
		String initials = encode(chatSafe(waypoint.label()));
		String tail = ":" + initials + ":" + waypoint.x + ":" + waypoint.y + ":" + waypoint.z + ":"
			+ colorIndex(waypoint.color) + ":false:0:Internal-" + xaeroDimension(waypoint.dimension) + "-waypoints";
		String name = chatSafe(waypoint.shownName()).trim();
		if (name.isEmpty()) {
			name = "Waypoint";
		}
		// Cut the name, not an escape, until the line fits.
		String encoded = encode(name);
		while ("xaero-waypoint:".length() + encoded.length() + tail.length() > MAX_CHAT && name.length() > 1) {
			name = cut(name, name.length() - 1);
			encoded = encode(name);
		}
		String line = "xaero-waypoint:" + encoded + tail;
		return line.length() > MAX_CHAT ? Optional.empty() : Optional.of(line);
	}

	/**
	 * Posts a waypoint in chat for others to add. False if it can't fit in a chat line, which
	 * chat is told about instead.
	 */
	public static boolean send(Waypoint waypoint) {
		Minecraft mc = Minecraft.getInstance();
		Optional<String> text = format(waypoint);
		if (text.isEmpty()) {
			//? if >=26.1 {
			mc.gui.hud.getChat().addClientSystemMessage(Component.translatable("diorama.share.too_long"));
			//?} else {
			/*mc.gui.getChat().addMessage(Component.translatable("diorama.share.too_long"));
			*///?}
			return false;
		}
		if (mc.player != null) {
			mc.player.connection.sendChat(text.get());
		}
		return true;
	}

	/** Xaero's escapes for characters that mean something in the share line. */
	static String encode(String text) {
		return text.replace("-", "^min^").replace("_", "-").replace(":", "^col^").replace("*", "^ast^");
	}

	static String decode(String text) {
		return text.replace("^ast^", "*").replace("^col^", ":").replace("-", "_").replace("^min^", "-");
	}

	static String chatSafe(String text) {
		StringBuilder out = new StringBuilder();
		text.codePoints().filter(c -> c >= ' ' && c != '§' && c != 127).forEach(out::appendCodePoint);
		return out.toString();
	}

	/** The first shared waypoint in a chat message, if there's a well-formed one. */
	public static Optional<Shared> parse(String text) {
		Matcher m = SHARED.matcher(text);
		if (!m.find()) {
			return Optional.empty();
		}
		String name = cut(chatSafe(decode(m.group(1))).trim(), MAX_NAME).trim();
		StringBuilder initials = new StringBuilder();
		decode(m.group(2)).codePoints().filter(Character::isLetterOrDigit).limit(Waypoint.MAX_INITIALS).forEach(initials::appendCodePoint);
		int x = Integer.parseInt(m.group(3));
		int z = Integer.parseInt(m.group(5));
		Integer y = "~".equals(m.group(4)) ? null : Integer.parseInt(m.group(4));
		int color = Integer.parseInt(m.group(6));
		String xaero = m.group(7).substring("Internal-".length(), m.group(7).length() - "-waypoints".length());
		String dimension = ourDimension(xaero);
		if (name.isEmpty() || Math.abs(x) > MAX_HORIZONTAL || Math.abs(z) > MAX_HORIZONTAL || (y != null && Math.abs(y) > MAX_VERTICAL)
			|| color > 15 || dimension == null) {
			return Optional.empty();
		}
		return Optional.of(new Shared(name, initials.toString(), x, y, z, color, dimension));
	}

	/** At most {@code max} characters of {@code text}, never splitting a character in two. */
	static String cut(String text, int max) {
		if (text.length() <= max) {
			return text;
		}
		int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
		return text.substring(0, end);
	}

	/**
	 * The chat line, with an "[Add]" link on the end if it carries a shared waypoint. Called for
	 * every line as it arrives and again as chat adds it, so a line that has its link is left alone.
	 */
	public static Component withAddLink(Component message) {
		Optional<Shared> shared = parse(message.getString());
		if (shared.isEmpty() || hasAddLink(message)) {
			return message;
		}
		Minecraft mc = Minecraft.getInstance();
		int y = shared.get().y() != null ? shared.get().y() : mc.player != null ? mc.player.getBlockY() : 64;
		return message.copy().append(" ").append(addLink(shared.get(), y));
	}

	/** Whether part of the line runs the "Add" link's command when clicked. */
	private static boolean hasAddLink(Component message) {
		return message.visit((style, text) -> {
			//? if >=1.21.5 {
			String command = style.getClickEvent() instanceof ClickEvent.RunCommand run ? run.command() : null;
			//?} else {
			/*ClickEvent click = style.getClickEvent();
			String command = click != null && click.getAction() == ClickEvent.Action.RUN_COMMAND ? click.getValue() : null;
			*///?}
			return command != null && command.startsWith(ADD_COMMAND) ? Optional.of(true) : Optional.empty();
		}, Style.EMPTY).isPresent();
	}

	/** The command the "Add" link runs for a shared waypoint. */
	public static String addCommand(Shared shared, int y) {
		return ADD_COMMAND + shared.x() + " " + y + " " + shared.z() + " " + shared.colorIndex()
			+ " \"" + shared.dimension() + "\" \"" + shared.initials() + "\" " + shared.name();
	}

	private static Component addLink(Shared shared, int y) {
		String command = addCommand(shared, y);
		return Component.translatable("diorama.share.add").withStyle(style -> style
			.withColor(ChatFormatting.GREEN)
			.withClickEvent(new ClickEvent.RunCommand(command))
			.withHoverEvent(new HoverEvent.ShowText(Component.translatable("diorama.share.add_hover", shared.name(), shared.x(), y, shared.z()))));
	}

	/**
	 * {@code /diorama waypoint add <x> <y> <z> <colour> "<dimension>" "<initials>" <name>}, which the
	 * "Add" link runs, and {@code /diorama waypoint list}, which the Xaero's import line's link runs.
	 * Built with Brigadier alone, so they go in either loader's client commands.
	 */
	public static <S> void register(CommandDispatcher<S> dispatcher) {
		dispatcher.register(LiteralArgumentBuilder.<S>literal("diorama")
			.then(LiteralArgumentBuilder.<S>literal("waypoint")
				.then(LiteralArgumentBuilder.<S>literal("list")
					.executes(WaypointShare::list))
				.then(LiteralArgumentBuilder.<S>literal("add")
					.then(RequiredArgumentBuilder.<S, Integer>argument("x", IntegerArgumentType.integer())
						.then(RequiredArgumentBuilder.<S, Integer>argument("y", IntegerArgumentType.integer())
							.then(RequiredArgumentBuilder.<S, Integer>argument("z", IntegerArgumentType.integer())
								.then(RequiredArgumentBuilder.<S, Integer>argument("colour", IntegerArgumentType.integer(0, 15))
									.then(RequiredArgumentBuilder.<S, String>argument("dimension", StringArgumentType.string())
										.then(RequiredArgumentBuilder.<S, String>argument("initials", StringArgumentType.string())
											.then(RequiredArgumentBuilder.<S, String>argument("name", StringArgumentType.greedyString())
												.executes(WaypointShare::add)))))))))));
	}

	private static <S> int list(CommandContext<S> context) {
		Minecraft mc = Minecraft.getInstance();
		// Queued for later: a chat click puts the chat screen back right after running this.
		mc.schedule(() -> mc.gui.setScreen(new WaypointListScreen(null)));
		return 1;
	}

	private static <S> int add(CommandContext<S> context) {
		String name = StringArgumentType.getString(context, "name");
		Waypoint waypoint = new Waypoint(
			cut(name, MAX_NAME),
			IntegerArgumentType.getInteger(context, "x"),
			IntegerArgumentType.getInteger(context, "y"),
			IntegerArgumentType.getInteger(context, "z"),
			StringArgumentType.getString(context, "dimension"),
			paletteColor(IntegerArgumentType.getInteger(context, "colour"))
		);
		String initials = StringArgumentType.getString(context, "initials");
		if (!initials.isEmpty()) {
			waypoint.initials = cut(initials, Waypoint.MAX_INITIALS);
		}
		Minecraft mc = Minecraft.getInstance();
		// Queued for later: a chat click puts the chat screen back right after running this.
		mc.schedule(() -> mc.gui.setScreen(new WaypointEditScreen(null, waypoint, true)));
		return 1;
	}
	/** Xaero's colours are the 16 chat colours, the first 16 {@link ChatFormatting} values; the nearest one to a waypoint colour. */
	static int colorIndex(int argb) {
		int best = 15;
		long bestDistance = Long.MAX_VALUE;
		for (int i = 0; i < 16; i++) {
			int chat = TextColor.fromLegacyFormat(ChatFormatting.values()[i]).getValue();
			long dr = ((argb >> 16) & 0xFF) - ((chat >> 16) & 0xFF);
			long dg = ((argb >> 8) & 0xFF) - ((chat >> 8) & 0xFF);
			long db = (argb & 0xFF) - (chat & 0xFF);
			long distance = dr * dr + dg * dg + db * db;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = i;
			}
		}
		return best;
	}

	/** The palette colour nearest a chat colour. */
	static int paletteColor(int index) {
		return nearestPaletteColor(chatColor(index));
	}

	/** The RGB of one of the 16 chat colours. */
	static int chatColor(int index) {
		return TextColor.fromLegacyFormat(ChatFormatting.values()[Math.max(0, Math.min(15, index))]).getValue();
	}

	/** The palette colour nearest an RGB colour. */
	static int nearestPaletteColor(int rgb) {
		int best = Waypoint.PALETTE[0];
		long bestDistance = Long.MAX_VALUE;
		for (int color : Waypoint.PALETTE) {
			long dr = ((color >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
			long dg = ((color >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
			long db = (color & 0xFF) - (rgb & 0xFF);
			long distance = dr * dr + dg * dg + db * db;
			if (distance < bestDistance) {
				bestDistance = distance;
				best = color;
			}
		}
		return best;
	}

	/** Xaero's name for a dimension: {@code overworld}, {@code the-nether}, {@code the-end}, or {@code dim%namespace$path}. */
	static String xaeroDimension(String id) {
		return switch (id) {
			case "minecraft:overworld" -> "overworld";
			case "minecraft:the_nether" -> "the-nether";
			case "minecraft:the_end" -> "the-end";
			default -> "dim%" + id.replace(':', '$').replace('/', '%');
		};
	}

	/** Our dimension id for Xaero's name, or null if it isn't one we can read. */
	static @Nullable String ourDimension(String xaero) {
		String id = switch (xaero.toLowerCase(Locale.ROOT)) {
			case "overworld" -> "minecraft:overworld";
			case "the-nether" -> "minecraft:the_nether";
			case "the-end" -> "minecraft:the_end";
			default -> xaero.startsWith("dim%") ? xaero.substring(4).replace('$', ':').replace('%', '/') : null;
		};
		return id != null && DIMENSION_ID.matcher(id).matches() ? id : null;
	}
}
