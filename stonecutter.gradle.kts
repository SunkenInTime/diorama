plugins {
	id("dev.kikugie.stonecutter")
	id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT" apply false
	id("net.fabricmc.fabric-loom-remap") version "1.18-SNAPSHOT" apply false
	id("net.neoforged.moddev") version "2.0.148" apply false
	id("net.neoforged.moddev.legacyforge") version "2.0.148" apply false
}

// The version the source files are written for. Keep it at the newest version: the renames below
// only translate newest names to older ones, so the source can't be switched to an older version
// and back. Version-specific code that isn't a rename goes in //? if blocks.
stonecutter active "26.3"

// Game tests bake with the tracer unless a run asks otherwise with DIORAMA_GPU_BAKE: it's what they
// were written against, and what boat.dev's graphics-free VMs can do at speed. 0 is the tracer, 1
// forces the GPU, and any other value, such as auto, leaves the choice to the game, as for a player.
subprojects {
	tasks.matching { it.name.contains("ClientGameTest") }.configureEach {
		if (this is ProcessForkOptions && System.getenv("DIORAMA_GPU_BAKE") == null) {
			environment("DIORAMA_GPU_BAKE", "0")
		}
	}
}

stonecutter parameters {
	// The mod loader the jar is for, as //? if fabric, //? if neoforge and //? if forge conditions.
	// Versions named <minecraft>-neoforge are NeoForge, <minecraft>-forge Forge (settings.gradle.kts);
	// the rest are Fabric.
	val loader = when {
		current.project.endsWith("-neoforge") -> "neoforge"
		current.project.endsWith("-forge") -> "forge"
		else -> "fabric"
	}
	constants.match(loader, "fabric", "neoforge", "forge")

	// Names that changed without changing behaviour, as regexes from the newer spelling to the
	// older one, applied to every version before the rename. One-way: the reverse pattern never
	// matches. Renamed in 26.1:
	val before26 = listOf(
		// The GUI drawing context and its text methods.
		"\\bGuiGraphicsExtractor\\b" to "GuiGraphics",
		"\\bgraphics\\.text\\(" to "graphics.drawString(",
		"\\bgraphics\\.centeredText\\(" to "graphics.drawCenteredString(",
		// Screens and HUD elements render instead of extracting render state.
		"\\bextractRenderState\\(" to "render(",
		"\\bextractBackground\\(" to "renderBackground(",
		"import net\\.minecraft\\.client\\.renderer\\.state\\.gui\\.GuiElementRenderState;" to "import net.minecraft.client.gui.render.state.GuiElementRenderState;",
		"\\.addGuiElement\\(" to ".submitGuiElement(",
		// ChunkPos was a class with fields.
		"getPos\\(\\)\\.([xz])\\(\\)" to "getPos().$1",
		// Fabric API's key binding helper.
		"import net\\.fabricmc\\.fabric\\.api\\.client\\.keymapping\\.v1\\.KeyMappingHelper;" to "import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;",
		"\\bKeyMappingHelper\\.registerKeyMapping\\(" to "KeyBindingHelper.registerKeyBinding(",
		// Fabric API's payload type registries.
		"\\bPayloadTypeRegistry\\.clientboundPlay\\(" to "PayloadTypeRegistry.playS2C(",
		"\\bPayloadTypeRegistry\\.serverboundPlay\\(" to "PayloadTypeRegistry.playC2S(",
		// The day's clock was the level's.
		"\\.getOverworldClockTime\\(\\)" to ".getDayTime()",
		// Chat lines moved into the multiplayer chat package.
		"net\\.minecraft\\.client\\.multiplayer\\.chat\\.GuiMessage\\b" to "net.minecraft.client.GuiMessage",
	)
	// Renamed in 26.2.
	val before262 = listOf(
		// The open screen, the loading overlay and the chat lived on Minecraft and Gui directly.
		"\\.gui\\.setScreen\\(" to ".setScreen(",
		"\\.gui\\.screen\\(\\)" to ".screen",
		"\\.gui\\.overlay\\(\\)" to ".getOverlay()",
		"\\.gui\\.hud\\." to ".gui.",
		// Entity type constants lived on EntityType.
		"\\bEntityTypes\\.([A-Z][A-Z0-9_]*)" to "EntityType.$1",
		"import net\\.minecraft\\.world\\.entity\\.EntityTypes;" to "import net.minecraft.world.entity.EntityType;",
		// The camera had a getter.
		"\\.mainCamera\\(\\)" to ".getMainCamera()",
		// The client game test API: a dedicated server's connection had no type of its own.
		"\\bTestDedicatedServerConnection\\b" to "TestServerConnection",
	)
	// Renamed in 26.3: the GPU API moved from com.mojang.blaze3d to com.mojang.renderpearl.api.
	val before263 = listOf(
		"\\bcom\\.mojang\\.renderpearl\\.api\\.GpuFormat\\b" to "com.mojang.blaze3d.GpuFormat",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.device\\.GpuDevice\\b" to "com.mojang.blaze3d.systems.GpuDevice",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.PrimitiveTopology\\b" to "com.mojang.blaze3d.PrimitiveTopology",
		// The GPU tile baker's names that didn't stay together; these come before the general rules.
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.IndexType\\b" to "com.mojang.blaze3d.IndexType",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.CompareOp\\b" to "com.mojang.blaze3d.platform.CompareOp",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.BlendFactor\\b" to "com.mojang.blaze3d.platform.BlendFactor",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\.UniformType\\b" to "com.mojang.blaze3d.shaders.UniformType",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.commands\\." to "com.mojang.blaze3d.systems.",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.buffers\\." to "com.mojang.blaze3d.buffers.",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.vertex\\." to "com.mojang.blaze3d.vertex.",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.pipeline\\." to "com.mojang.blaze3d.pipeline.",
		"\\bcom\\.mojang\\.renderpearl\\.api\\.textures\\." to "com.mojang.blaze3d.textures.",
	)
	for ((renamedIn, renames) in listOf("26.1" to before26, "26.2" to before262, "26.3" to before263)) {
		for ((pattern, older) in renames) {
			replacements.regex(current.parsed < renamedIn) {
				replace(pattern, older, "(?!)", "never")
			}
		}
	}

	// From the 1.21.11 spelling to the 1.21.10 one. Versions older than 1.21.11 get both lists, so
	// keep the patterns of one list from matching the other's output.
	val before1_21_11 = listOf(
		// Resource ids were ResourceLocations, and a ResourceKey's id was its location.
		"\\bIdentifier\\b" to "ResourceLocation",
		"\\b(read|write)Identifier\\b" to "$1ResourceLocation",
		"\\.identifier\\(\\)" to ".location()",
		// Cows had no package of their own.
		"net\\.minecraft\\.world\\.entity\\.animal\\.cow\\." to "net.minecraft.world.entity.animal.",
		// Game rules had camel-case names (the tests set them by command).
		"\\bgamerule show_death_messages\\b" to "gamerule showDeathMessages",
		"\\bgamerule immediate_respawn\\b" to "gamerule doImmediateRespawn",
		"\\bgamerule random_tick_speed\\b" to "gamerule randomTickSpeed",
		"\\bgamerule advance_time\\b" to "gamerule doDaylightCycle",
		// The F3 screen's on/off switch was named after the key.
		"\\bdebugEntries\\.isOverlayVisible\\(\\)" to "debugEntries.isF3Visible()",
		"\\bdebugEntries\\.toggleDebugOverlay\\(\\)" to "debugEntries.toggleF3Visible()",
	)
	for ((pattern, older) in before1_21_11) {
		replacements.regex(current.parsed < "1.21.11") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// Up to 1.21.8 the game asks only for OpenGL 3.2, so the fog shaders must be GLSL 1.50. This
	// can't be a //? block: #version has to be the first thing in the file.
	replacements.regex(current.parsed < "1.21.9") {
		replace("^#version 330$", "#version 150", "(?!)", "never")
	}

	// From the 1.21.6 spelling to the 1.21.5 one. Each call is a whole statement on one line.
	val before1_21_6 = listOf(
		// The GUI pose was a 3D PoseStack instead of a 2D matrix stack.
		"\\bgraphics\\.pose\\(\\)\\.pushMatrix\\(\\);" to "graphics.pose().pushPose();",
		"\\bgraphics\\.pose\\(\\)\\.popMatrix\\(\\);" to "graphics.pose().popPose();",
		"\\bgraphics\\.pose\\(\\)\\.translate\\((.*)\\);" to "graphics.pose().translate($1, 0);",
		"\\bgraphics\\.pose\\(\\)\\.scale\\((.*)\\);" to "graphics.pose().scale($1, $1, 1);",
		// A screen's tooltip was set on the screen, which draws it after everything else.
		"\\bgraphics\\.setTooltipForNextFrame\\(this\\.font, (.*), mouseX, mouseY\\);" to "this.setTooltipForNextRenderPass($1);",
	)
	for ((pattern, older) in before1_21_6) {
		replacements.regex(current.parsed < "1.21.6") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// HUD elements implement Fabric API's interface, or the mod's own where Fabric API has none
	// (before 1.21.6, where they draw from a callback) or isn't there at all (NeoForge and Forge,
	// GUI layers).
	replacements.regex(current.parsed < "1.21.6" || loader != "fabric") {
		replace("import net\\.fabricmc\\.fabric\\.api\\.client\\.rendering\\.v1\\.hud\\.HudElement;", "import dev.diorama.compat.HudElement;", "(?!)", "never")
	}

	// From the 1.21.5 spelling to the 1.21.4 one.
	val before1_21_5 = listOf(
		// Baked quads and models had getters.
		"\\bquad\\.sprite\\(\\)" to "quad.getSprite()",
		"\\bquad\\.tintIndex\\(\\)" to "quad.getTintIndex()",
		"\\bquad\\.direction\\(\\)" to "quad.getDirection()",
		"\\bmodel\\.particleIcon\\(\\)" to "model.getParticleIcon()",
		"\\.getMissingBlockStateModel\\(\\)" to ".getMissingModel()",
		// Chat click and hover events were one class each, with an action.
		"\\bnew ClickEvent\\.RunCommand\\(" to "new ClickEvent(ClickEvent.Action.RUN_COMMAND, ",
		"\\bnew ClickEvent\\.OpenFile\\(" to "new ClickEvent(ClickEvent.Action.OPEN_FILE, ",
		"\\bnew HoverEvent\\.ShowText\\(" to "new HoverEvent(HoverEvent.Action.SHOW_TEXT, ",
		// Placing an entity (the tests do) was moving it.
		"\\.snapTo\\(" to ".moveTo(",
		// Whether it's dark enough to sleep was named after the night.
		"\\.isDarkOutside\\(\\)" to ".isNight()",
	)
	for ((pattern, older) in before1_21_5) {
		replacements.regex(current.parsed < "1.21.5") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// From the 1.21.2 spelling to the 1.21.1 one.
	val before1_21_2 = listOf(
		// Colour helpers lived in FastColor.
		"import net\\.minecraft\\.util\\.ARGB;" to "import net.minecraft.util.FastColor;",
		"\\bARGB\\.(red|green|blue|color)\\(" to "FastColor.ARGB32.$1(",
		// World heights: the bottom had another name, and the top was exclusive.
		"\\.getMinY\\(\\)" to ".getMinBuildHeight()",
		"\\.getMinSectionY\\(\\)" to ".getMinSection()",
		"([\\w.]+)\\.getMaxY\\(\\)" to "($1.getMaxBuildHeight() - 1)",
		// Running a task on the client thread later was telling the client.
		"\\bmc\\.schedule\\(" to "mc.tell(",
	)
	for ((pattern, older) in before1_21_2) {
		replacements.regex(current.parsed < "1.21.2") {
			replace(pattern, older, "(?!)", "never")
		}
	}

	// From the 1.21 spelling to the 1.20.1 one (changed somewhere from 1.20.2 to 1.21; 1.20.1 is
	// the only version before 1.21).
	val before1_21 = listOf(
		// Resource ids were built with constructors. Either spelling: the Identifier rename above
		// may run first.
		"\\b(?:net\\.minecraft\\.resources\\.)?(?:Identifier|ResourceLocation)\\.(?:fromNamespaceAndPath|withDefaultNamespace|parse)\\(" to "new net.minecraft.resources.ResourceLocation(",
		// Chunk statuses had no package of their own.
		"import net\\.minecraft\\.world\\.level\\.chunk\\.status\\.ChunkStatus;" to "import net.minecraft.world.level.chunk.ChunkStatus;",
		"\\.getPersistedStatus\\(\\)" to ".getStatus()",
		// The death packet had a getter for whose death it was.
		"\\bpacket\\.playerId\\(\\)" to "packet.getPlayerId()",
		// A built mesh was a RenderedBuffer, nested in BufferBuilder, which built it with end().
		"import com\\.mojang\\.blaze3d\\.vertex\\.MeshData;" to "import com.mojang.blaze3d.vertex.BufferBuilder.RenderedBuffer;",
		"\\bMeshData\\b" to "RenderedBuffer",
		"\\.buildOrThrow\\(\\)" to ".end()",
		// A player's skin texture had a getter of its own.
		"\\.getSkin\\(\\)\\.texture\\(\\)" to ".getSkinTextureLocation()",
		// A status effect instance held its effect itself, not a registry holder of it.
		"\\.getEffect\\(\\)\\.value\\(\\)" to ".getEffect()",
		// The test stand-in's names: the "saving world" screen had its dirt background in its name,
		// and the renderer counted chunks, not sections.
		"\\bGenericMessageScreen\\b" to "GenericDirtMessageScreen",
		"\\.hasRenderedAllSections\\(\\)" to ".hasRenderedAllChunks()",
		// Leaving a world was clearing the client's level.
		"\\b(minecraft|mc)\\.disconnect\\(" to "$1.clearLevel(",
		// The tests' checks: chat's allowed characters were in SharedConstants, and widgets had no
		// right or bottom edge getters.
		"\\bnet\\.minecraft\\.util\\.StringUtil\\.isAllowedChatCharacter\\(" to "net.minecraft.SharedConstants.isAllowedChatCharacter(",
		"\\b(\\w+)\\.getRight\\(\\)" to "($1.getX() + $1.getWidth())",
		"\\b(\\w+)\\.getBottom\\(\\)" to "($1.getY() + $1.getHeight())",
		// Not a Minecraft change: the 1.20.1 jar is built for Java 17, which has no Math.clamp.
		// Minecraft's own clamp has the int, float and double forms.
		"\\bMath\\.clamp\\(" to "net.minecraft.util.Mth.clamp(",
		// The tests' scenes: short grass was named plain grass.
		"\"short_grass\"" to "\"grass\"",
	)
	for ((pattern, older) in before1_21) {
		replacements.regex(current.parsed < "1.21") {
			replace(pattern, older, "(?!)", "never")
		}
	}
	// NeoForge forked from Forge in 1.20.1, so the NeoForge code in the else branches of //? if fabric
	// blocks often works on Forge too, under Forge's package names.
	val forgePackages = listOf(
		"\\bnet\\.neoforged\\.fml\\." to "net.minecraftforge.fml.",
		"\\bnet\\.neoforged\\.neoforge\\.client\\." to "net.minecraftforge.client.",
	)
	for ((pattern, forge) in forgePackages) {
		replacements.regex(loader == "forge") {
			replace(pattern, forge, "(?!)", "never")
		}
	}
}

// gradle.properties turns on configure-on-demand, but Stonecutter's switch and refresh tasks read
// every version's source sets, which each version's own script adds; without this they skip
// src/gametest and src/gameshim. Configure every version first. (At the end of this script:
// earlier, the versions' scripts run before Stonecutter has set them up.)
if (gradle.startParameter.taskNames.any { "active project" in it }) evaluationDependsOnChildren()
