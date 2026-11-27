package dev.diorama.render;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Deque;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
//? if >=1.21.6 {
import org.joml.Matrix3x2fStack;
//?} else {
/*import com.mojang.blaze3d.vertex.PoseStack;
*///?}
//? if <1.21.5 {
/*import com.mojang.blaze3d.systems.RenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL14;
*///?}

/**
 * Leaves a caller's GUI graphics as Diorama found them, for drawing into graphics that aren't
 * Diorama's (an addon's screen, {@link dev.diorama.addon.AddonView}), even when the drawing throws
 * partway. The map's drawing pushes scissors and poses as it goes and pops them as it finishes,
 * so a throw in between would leave them on the caller's stacks. Around such a draw, Diorama
 * draws on a pose stack of its own that starts where the caller's is, puts the caller's back
 * after, and pops the graphics' own scissor stack back down to the depth it had at the start.
 * Through 1.21.4, where the GUI draws straight away with OpenGL and each draw turns blending off
 * as it finishes, the caller's blending is put back too. One draw at a time: the view refuses to
 * draw inside its own drawing.
 */
public final class GuiStacks {
	/**
	 * The graphics' scissor stack and the list inside it, which the game keeps private on every
	 * version. Found by their types, not their names, which differ between loaders and versions:
	 * the field whose type is a class inside the graphics' class that takes a rectangle (its push)
	 * and holds a deque.
	 */
	private static final Field SCISSOR_STACK;
	private static final Field SCISSOR_LIST;
	/** The depth of the graphics' scissor stack when the draw started. */
	private static int scissorsFound;
	//? if >=1.21.6 {
	/** The pose stack Diorama draws on, emptied at the start of each draw, so a throw leaves nothing in it for the next. */
	private static final Matrix3x2fStack OWN = new Matrix3x2fStack(16);
	private static Matrix3x2fStack callerPose;
	//?} else {
	/*private static PoseStack callerPose;
	*///?}
	//? if <1.21.5 {
	/*private static boolean blend;
	private static int srcRgb;
	private static int dstRgb;
	private static int srcAlpha;
	private static int dstAlpha;
	*///?}

	static {
		Field stack = null;
		Field list = null;
		for (Field field : GuiGraphicsExtractor.class.getDeclaredFields()) {
			if (field.getType().getEnclosingClass() != GuiGraphicsExtractor.class || !pushesRectangles(field.getType())) {
				continue;
			}
			for (Field inside : field.getType().getDeclaredFields()) {
				if (Deque.class.isAssignableFrom(inside.getType())) {
					stack = field;
					list = inside;
					break;
				}
			}
			if (stack != null) {
				break;
			}
		}
		if (stack == null) {
			throw new IllegalStateException("Diorama didn't find the GUI graphics' scissor stack");
		}
		stack.setAccessible(true);
		list.setAccessible(true);
		SCISSOR_STACK = stack;
		SCISSOR_LIST = list;
	}

	private GuiStacks() {
	}

	private static boolean pushesRectangles(Class<?> type) {
		for (Method method : type.getDeclaredMethods()) {
			if (method.getParameterCount() == 1 && method.getParameterTypes()[0] == ScreenRectangle.class) {
				return true;
			}
		}
		return false;
	}

	/** Lets the graphics' pose stack be swapped: GuiGraphicsMixin. */
	public interface Poses {
		//? if >=1.21.6 {
		void diorama$setPose(Matrix3x2fStack pose);
		//?} else {
		/*void diorama$setPose(PoseStack pose);
		*///?}
	}

	/** How many scissors are pushed on these graphics now. */
	public static int scissorDepth(GuiGraphicsExtractor graphics) {
		try {
			return ((Deque<?>) SCISSOR_LIST.get(SCISSOR_STACK.get(graphics))).size();
		} catch (IllegalAccessException e) {
			throw new IllegalStateException(e);
		}
	}

	/** Starts drawing on a pose stack of Diorama's own, at the caller's transform. */
	public static void enter(GuiGraphicsExtractor graphics) {
		scissorsFound = scissorDepth(graphics);
		//? if >=1.21.6 {
		callerPose = graphics.pose();
		OWN.clear();
		OWN.set(callerPose);
		((Poses) graphics).diorama$setPose(OWN);
		//?} else {
		/*callerPose = graphics.pose();
		PoseStack own = new PoseStack();
		own.last().pose().set(callerPose.last().pose());
		own.last().normal().set(callerPose.last().normal());
		((Poses) graphics).diorama$setPose(own);
		*///?}
		//? if <1.21.5 {
		/*blend = GL11.glIsEnabled(GL11.GL_BLEND);
		srcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
		dstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
		srcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
		dstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
		*///?}
	}

	/**
	 * Pops the graphics' scissors back down to the depth {@link #enter} found and puts back the
	 * caller's pose stack (and, through 1.21.4, blending). The pose stack and blending are put back
	 * even if a pop throws.
	 */
	public static void leave(GuiGraphicsExtractor graphics) {
		try {
			for (int extra = scissorDepth(graphics) - scissorsFound; extra > 0; extra--) {
				graphics.disableScissor();
			}
		} finally {
			((Poses) graphics).diorama$setPose(callerPose);
			//? if <1.21.5 {
			/*if (blend) {
				RenderSystem.enableBlend();
			} else {
				RenderSystem.disableBlend();
			}
			RenderSystem.blendFuncSeparate(srcRgb, dstRgb, srcAlpha, dstAlpha);
			*///?}
		}
	}
}
