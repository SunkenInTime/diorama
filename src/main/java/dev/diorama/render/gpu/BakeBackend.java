package dev.diorama.render.gpu;

/**
 * Draws one {@link GpuTileBaker} job on the GPU and arranges for its pixels to come back. One
 * per version family: Minecraft's own GPU layer from 26.2, raw OpenGL before.
 */
interface BakeBackend {
	/**
	 * Records the bake on the render thread, outside any pass. Returns false if this GPU can't
	 * draw it, so the caller falls back to tracing; otherwise the job completes later through
	 * {@link GpuTileBaker.Job#done} or {@link GpuTileBaker.Job#fail}, and is freed either way.
	 */
	boolean draw(GpuTileBaker.Job job);

	/** The graphics card and driver, for the log and the saved check result. Render thread. */
	@org.jspecify.annotations.Nullable String device();

	/** Finishes bakes whose pixels have come back. Called on the render thread every frame. */
	default void poll() {
	}
}
