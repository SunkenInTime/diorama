package dev.diorama.gameshim;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;

import com.mojang.blaze3d.platform.InputConstants;

import dev.diorama.gameshim.mixin.KeyboardHandlerAccessor;
import dev.diorama.gameshim.mixin.MouseHandlerAccessor;
import net.minecraft.client.KeyMapping;
import net.fabricmc.fabric.api.client.gametest.v1.TestInput;
import net.minecraft.client.Minecraft;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
//? if >=1.21.9 {
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
//?}
//? if >=26.3 {
import org.lwjgl.sdl.SDLKeyboard;
//?}

/**
 * Simulated input, after Fabric API's TestInputImpl: events go straight into the game's keyboard
 * and mouse handlers, as if the window system had sent them. The real mouse and keyboard are cut
 * off (see the mixins).
 */
final class ShimInput implements TestInput {
	private final ShimContext context;
	private final Set<Integer> mouseDown = new LinkedHashSet<>();

	ShimInput(ShimContext context) {
		this.context = context;
	}

	@Override
	public void holdMouse(int button) {
		ShimThreading.checkOnTestThread("holdMouse");
		if (mouseDown.add(button)) {
			context.runOnClient(minecraft -> mouseButton(minecraft, button, InputConstants.PRESS));
		}
	}

	@Override
	public void releaseMouse(int button) {
		ShimThreading.checkOnTestThread("releaseMouse");
		if (mouseDown.remove(button)) {
			context.runOnClient(minecraft -> mouseButton(minecraft, button, InputConstants.RELEASE));
		}
	}

	@Override
	public void pressMouse(int button) {
		ShimThreading.checkOnTestThread("pressMouse");
		holdMouse(button);
		releaseMouse(button);
		context.waitTick();
	}

	@Override
	public void pressKey(KeyMapping keyMapping) {
		ShimThreading.checkOnTestThread("pressKey");
		// The key it is bound to, as the options file saves it.
		InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
		if (key.getType() == InputConstants.Type.MOUSE) {
			pressMouse(key.getValue());
			return;
		}
		context.runOnClient(minecraft -> {
			key(minecraft, key, InputConstants.PRESS);
			key(minecraft, key, InputConstants.RELEASE);
		});
		context.waitTick();
	}

	@Override
	public void pressKey(int key) {
		ShimThreading.checkOnTestThread("pressKey");
		InputConstants.Key pressed = keyboardKey(key);
		context.runOnClient(minecraft -> {
			key(minecraft, pressed, InputConstants.PRESS);
			key(minecraft, pressed, InputConstants.RELEASE);
		});
		context.waitTick();
	}

	@Override
	public void holdKey(KeyMapping keyMapping) {
		ShimThreading.checkOnTestThread("holdKey");
		InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
		if (key.getType() == InputConstants.Type.MOUSE) {
			holdMouse(key.getValue());
			return;
		}
		context.runOnClient(minecraft -> key(minecraft, key, InputConstants.PRESS));
	}

	@Override
	public void releaseKey(KeyMapping keyMapping) {
		ShimThreading.checkOnTestThread("releaseKey");
		InputConstants.Key key = InputConstants.getKey(keyMapping.saveString());
		if (key.getType() == InputConstants.Type.MOUSE) {
			releaseMouse(key.getValue());
			return;
		}
		context.runOnClient(minecraft -> key(minecraft, key, InputConstants.RELEASE));
	}

	@Override
	public void holdShift() {
		ShimThreading.checkOnTestThread("holdShift");
		if (HeldKeys.hold(InputConstants.KEY_LSHIFT)) {
			InputConstants.Key key = keyboardKey(InputConstants.KEY_LSHIFT);
			context.runOnClient(minecraft -> key(minecraft, key, InputConstants.PRESS));
		}
	}

	@Override
	public void releaseShift() {
		ShimThreading.checkOnTestThread("releaseShift");
		if (HeldKeys.release(InputConstants.KEY_LSHIFT)) {
			InputConstants.Key key = keyboardKey(InputConstants.KEY_LSHIFT);
			context.runOnClient(minecraft -> key(minecraft, key, InputConstants.RELEASE));
		}
	}

	@Override
	public void holdControl() {
		ShimThreading.checkOnTestThread("holdControl");
		if (HeldKeys.hold(InputConstants.KEY_LCONTROL)) {
			InputConstants.Key key = keyboardKey(InputConstants.KEY_LCONTROL);
			context.runOnClient(minecraft -> key(minecraft, key, InputConstants.PRESS));
		}
	}

	@Override
	public void releaseControl() {
		ShimThreading.checkOnTestThread("releaseControl");
		if (HeldKeys.release(InputConstants.KEY_LCONTROL)) {
			InputConstants.Key key = keyboardKey(InputConstants.KEY_LCONTROL);
			context.runOnClient(minecraft -> key(minecraft, key, InputConstants.RELEASE));
		}
	}

	@Override
	public void typeChars(String chars) {
		ShimThreading.checkOnTestThread("typeChars");
		context.runOnClient(minecraft -> chars.codePoints().forEach(c -> {
			KeyboardHandlerAccessor keyboard = (KeyboardHandlerAccessor) minecraft.keyboardHandler;
			//? if >=26.1 {
			keyboard.diorama$charTyped(window(minecraft), new CharacterEvent(c));
			//?} else if >=1.21.9 {
			/*keyboard.diorama$charTyped(window(minecraft), new CharacterEvent(c, 0));
			*///?} else {
			/*keyboard.diorama$charTyped(window(minecraft), c, 0);
			*///?}
		}));
	}

	@Override
	public void lookAt(BlockPos pos) {
		ShimThreading.checkOnTestThread("lookAt");
		context.runOnClient(minecraft -> {
			if (minecraft.player == null) {
				throw new IllegalStateException("The player must be in a world to look");
			}
			minecraft.player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
		});
	}

	@Override
	public void setCursorPos(double x, double y) {
		ShimThreading.checkOnTestThread("setCursorPos");
		context.runOnClient(minecraft -> moveTo(minecraft, x, y));
	}

	@Override
	public void moveCursor(double deltaX, double deltaY) {
		ShimThreading.checkOnTestThread("moveCursor");
		context.runOnClient(minecraft -> moveTo(minecraft, minecraft.mouseHandler.xpos() + deltaX, minecraft.mouseHandler.ypos() + deltaY));
	}

	@Override
	public void scroll(double amount) {
		ShimThreading.checkOnTestThread("scroll");
		context.runOnClient(minecraft -> mouse(minecraft).diorama$onScroll(window(minecraft), 0, amount));
	}

	@Override
	public void resizeWindow(int width, int height) {
		ShimThreading.checkOnTestThread("resizeWindow");
		if (width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Window size must be positive: " + width + "x" + height);
		}
		context.runOnClient(minecraft -> ((WindowHooks) (Object) minecraft.getWindow()).diorama$resize(width, height));
	}

	/** After a test: releases held buttons and puts the cursor in the middle of the window. */
	void releaseAll() {
		for (int button : new ArrayList<>(mouseDown)) {
			releaseMouse(button);
		}
		releaseShift();
		context.runOnClient(minecraft -> moveTo(minecraft,
			minecraft.getWindow().getScreenWidth() * 0.5, minecraft.getWindow().getScreenHeight() * 0.5));
	}

	/** A key code ({@code InputConstants.KEY_*}) as a key. */
	private static InputConstants.Key keyboardKey(int key) {
		//? if >=26.3 {
		return InputConstants.Type.KEYBOARD.getOrCreate(key);
		//?} else {
		/*return InputConstants.Type.KEYSYM.getOrCreate(key);
		*///?}
	}

	private static void key(Minecraft minecraft, InputConstants.Key key, int action) {
		//? if >=26.3 {
		// Keys are scancodes, with the key code they make on this keyboard layout.
		KeyEvent event = new KeyEvent(key.getValue(), SDLKeyboard.SDL_GetKeyFromScancode(key.getValue(), (short) 0, false), 0);
		((KeyboardHandlerAccessor) minecraft.keyboardHandler).diorama$keyPress(window(minecraft), action, event);
		//?} else if >=1.21.9 {
		/*KeyEvent event = key.getType() == InputConstants.Type.SCANCODE
			? new KeyEvent(InputConstants.UNKNOWN.getValue(), key.getValue(), 0)
			: new KeyEvent(key.getValue(), 0, 0);
		((KeyboardHandlerAccessor) minecraft.keyboardHandler).diorama$keyPress(window(minecraft), action, event);
		*///?} else {
		/*if (key.getType() == InputConstants.Type.SCANCODE) {
			minecraft.keyboardHandler.keyPress(window(minecraft), InputConstants.UNKNOWN.getValue(), key.getValue(), action, 0);
		} else {
			minecraft.keyboardHandler.keyPress(window(minecraft), key.getValue(), 0, action, 0);
		}
		*///?}
	}

	private static void mouseButton(Minecraft minecraft, int button, int action) {
		//? if >=1.21.9 {
		mouse(minecraft).diorama$onButton(window(minecraft), new MouseButtonInfo(button, 0), action);
		//?} else {
		/*mouse(minecraft).diorama$onPress(window(minecraft), button, action, 0);
		*///?}
	}

	private static void moveTo(Minecraft minecraft, double x, double y) {
		//? if >=26.3 {
		mouse(minecraft).diorama$onMove(window(minecraft), x, y, x - minecraft.mouseHandler.xpos(), y - minecraft.mouseHandler.ypos());
		//?} else {
		/*mouse(minecraft).diorama$onMove(window(minecraft), x, y);
		*///?}
	}

	private static MouseHandlerAccessor mouse(Minecraft minecraft) {
		return (MouseHandlerAccessor) minecraft.mouseHandler;
	}

	private static long window(Minecraft minecraft) {
		//? if >=1.21.9 {
		return minecraft.getWindow().handle();
		//?} else {
		/*return minecraft.getWindow().getWindow();
		*///?}
	}
}
