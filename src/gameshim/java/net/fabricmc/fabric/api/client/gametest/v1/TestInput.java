/*
 * Copyright (c) 2016, 2017, 2018, 2019 FabricMC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Diorama's test shim: the signatures in this package are derived from Fabric API's
 * fabric-client-gametest-api-v1 for Minecraft 1.21.4, trimmed to what Diorama's client game tests
 * call, so the same tests run on Minecraft versions Fabric API doesn't ship that module for. The
 * implementation is in dev.diorama.gameshim. See NOTICE.
 */

package net.fabricmc.fabric.api.client.gametest.v1;

import net.minecraft.client.KeyMapping;
import net.minecraft.core.BlockPos;

/**
 * Simulated mouse and window input. The game ignores the real keyboard and mouse while the tests
 * run, so only this input reaches it.
 */
public interface TestInput {
	/** Presses a mouse button ({@code InputConstants.MOUSE_BUTTON_*}) and keeps it held. */
	void holdMouse(int button);

	/** Releases a mouse button held with {@link #holdMouse}. */
	void releaseMouse(int button);

	/** Presses and releases a mouse button, then waits a tick. */
	void pressMouse(int button);

	/** Presses and releases the key or mouse button bound to the mapping, then waits a tick. */
	void pressKey(KeyMapping keyMapping);

	/** Presses and releases a key ({@code InputConstants.KEY_*}), then waits a tick. */
	void pressKey(int key);

	/** Presses the key or mouse button bound to the mapping and keeps it held. */
	void holdKey(KeyMapping keyMapping);

	/** Releases a key or mouse button held with {@link #holdKey}. */
	void releaseKey(KeyMapping keyMapping);

	/** Presses the left Shift key and keeps it held: the game reads it as held, and gets its press. */
	void holdShift();

	/** Releases Shift held with {@link #holdShift}. */
	void releaseShift();

	/** Presses the left Ctrl key and keeps it held: the game reads it as held, and gets its press. */
	void holdControl();

	/** Releases Ctrl held with {@link #holdControl}. */
	void releaseControl();

	/** Types the characters, as the keyboard would send them to the focused text field. */
	void typeChars(String chars);

	/** Turns the player to look at the middle of a block. */
	void lookAt(BlockPos pos);

	/** Moves the cursor to a position in window pixels. */
	void setCursorPos(double x, double y);

	/** Moves the cursor by an offset in window pixels. */
	void moveCursor(double deltaX, double deltaY);

	/** Turns the mouse wheel, up for positive amounts, as Fabric API's TestInput does. */
	void scroll(double amount);

	/**
	 * Resizes the game's framebuffer, and the window as far as the screen allows. Screenshots
	 * come out at exactly this size.
	 */
	void resizeWindow(int width, int height);
}
