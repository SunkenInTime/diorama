//? if <1.21.5 {
/*package dev.diorama.render.gpu;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.TextureUtil;
import com.mojang.blaze3d.systems.RenderSystem;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

// A MapTexture before 1.21.5: a raw OpenGL texture, set up the way vanilla's own textures are.
final class GlIdTexture implements LegacyTexture {
	private static final int TEXTURE_2D = 3553;
	private final int id;
	// A framebuffer drawing into the texture, made the first time FogLayer draws into it.
	private int framebuffer = -1;

	GlIdTexture(int size, int levels) {
		this.id = TextureUtil.generateTextureId();
		// Allocates every mip level and limits sampling to them.
		TextureUtil.prepareImage(NativeImage.InternalGlFormat.RGBA, this.id, levels - 1, size, size);
	}

	@Override
	public void write(int level, int x, int y, int edge, int[] abgr) {
		ByteBuffer buffer = MemoryUtil.memAlloc(edge * edge * 4).order(ByteOrder.LITTLE_ENDIAN);
		try {
			buffer.asIntBuffer().put(abgr, 0, edge * edge);
			GlStateManager._bindTexture(this.id);
			// Rows packed tightly from the start of the buffer.
			GlStateManager._pixelStore(3314, 0);
			GlStateManager._pixelStore(3316, 0);
			GlStateManager._pixelStore(3315, 0);
			GlStateManager._pixelStore(3317, 4);
			// GL_RGBA, GL_UNSIGNED_BYTE: the ints' bytes in memory are R, G, B, A.
			GlStateManager._texSubImage2D(TEXTURE_2D, level, x, y, edge, edge, 6408, 5121, MemoryUtil.memAddress(buffer));
		} finally {
			MemoryUtil.memFree(buffer);
		}
	}

	@Override
	public void bind(int slot, Sampling sampling) {
		GlStateManager._bindTexture(this.id);
		// Minify: LINEAR_MIPMAP_LINEAR or LINEAR. Magnify: NEAREST or LINEAR.
		GlStateManager._texParameter(TEXTURE_2D, 10241, sampling.mipmapped() ? 9987 : 9729);
		GlStateManager._texParameter(TEXTURE_2D, 10240, sampling.crisp() ? 9728 : 9729);
		// Wrap S and T: REPEAT or CLAMP_TO_EDGE.
		int wrap = sampling.repeats() ? 10497 : 33071;
		GlStateManager._texParameter(TEXTURE_2D, 10242, wrap);
		GlStateManager._texParameter(TEXTURE_2D, 10243, wrap);
		RenderSystem.setShaderTexture(slot, this.id);
	}

	// The framebuffer that draws into this texture: GL_FRAMEBUFFER, its first colour attachment.
	// Made with what was bound before bound again, so the caller reads its own binding after.
	int framebuffer() {
		if (this.framebuffer < 0) {
			int bound = org.lwjgl.opengl.GL11.glGetInteger(org.lwjgl.opengl.GL30.GL_DRAW_FRAMEBUFFER_BINDING);
			this.framebuffer = GlStateManager.glGenFramebuffers();
			GlStateManager._glBindFramebuffer(36160, this.framebuffer);
			GlStateManager._glFramebufferTexture2D(36160, 36064, TEXTURE_2D, this.id, 0);
			GlStateManager._glBindFramebuffer(36160, bound);
		}
		return this.framebuffer;
	}

	@Override
	public void close() {
		if (this.framebuffer >= 0) {
			GlStateManager._glDeleteFramebuffers(this.framebuffer);
		}
		TextureUtil.releaseTextureId(this.id);
	}
}
*///?}
