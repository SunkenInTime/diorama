package dev.diorama.web;

import dev.diorama.Projection;
import dev.diorama.web.webp.Vp8lDecoder;
import dev.diorama.web.webp.Vp8lEncoder;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageOutputStream;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/**
 * The web map's pictures on disk: one folder per dimension, one per zoom level, a picture per tile
 * in the {@link TileFormat} the settings pick. Zoom 0 is the map's own tiles, 512 pixels for 128
 * blocks across; each level out halves that, one tile made from the four under it. A tile with
 * nothing drawn has no file.
 */
final class WebTiles {
	/** JPEG quality: about 59 KB a full tile with colour at full resolution (Hermitcraft's average). */
	static final float QUALITY = 0.85F;
	/** What the page shows where nothing is drawn; tiles blend their edges into it. */
	static final int BACKGROUND = 0x15171C;
	static final int SIZE = Projection.TILE_PIXELS;

	/** Time spent writing and reading pictures, and how many, since the server started; for the passes' log lines. Web map thread only. */
	static long encodeNanos;
	static long encodes;
	static long decodeNanos;
	static long decodes;

	private WebTiles() {
	}

	static Path file(Path dimension, TileFormat format, int zoom, int u, int v) {
		return dimension.resolve(Integer.toString(zoom)).resolve(u + "_" + v + "." + format.extension);
	}

	/**
	 * Writes a tile from the tracer's pixels (0xAABBGGRR, alpha how much of the pixel is known),
	 * or deletes it if none are known. Returns the bytes written, 0 for none.
	 */
	static long write(Path file, TileFormat format, int[] abgr) throws IOException {
		int[] rgb = new int[SIZE * SIZE];
		boolean any = false;
		for (int i = 0; i < rgb.length; i++) {
			int p = abgr[i];
			int a = p >>> 24;
			any |= a != 0;
			rgb[i] = over(p & 0xFF, p >> 8 & 0xFF, p >> 16 & 0xFF, a);
		}
		return any ? save(file, format, rgb) : delete(file);
	}

	/** A pixel's colour over the background, as 0xRRGGBB. */
	private static int over(int r, int g, int b, int a) {
		int br = BACKGROUND >> 16 & 0xFF;
		int bg = BACKGROUND >> 8 & 0xFF;
		int bb = BACKGROUND & 0xFF;
		return (br + (r - br) * a / 255) << 16 | (bg + (g - bg) * a / 255) << 8 | (bb + (b - bb) * a / 255);
	}

	/**
	 * Makes a zoomed-out tile from the four under it, (0, 0), (1, 0), (0, 1), (1, 1), each pixel
	 * the average of four; deletes it when none of them exists. Returns the bytes written.
	 */
	static long shrink(Path out, TileFormat format, Path @Nullable [] children) throws IOException {
		int[] image = null;
		int half = SIZE / 2;
		for (int i = 0; i < 4; i++) {
			Path child = children[i];
			if (child == null || !Files.exists(child)) {
				continue;
			}
			int[] read = read(child, format);
			if (read == null) {
				continue;
			}
			if (image == null) {
				image = new int[SIZE * SIZE];
				java.util.Arrays.fill(image, BACKGROUND);
			}
			int ox = (i & 1) * half;
			int oy = (i >> 1) * half;
			for (int y = 0; y < half; y++) {
				int top = 2 * y * SIZE;
				int bottom = top + SIZE;
				int to = (oy + y) * SIZE + ox;
				for (int x = 0; x < half; x++) {
					int a = read[top + 2 * x];
					int b = read[top + 2 * x + 1];
					int c = read[bottom + 2 * x];
					int d = read[bottom + 2 * x + 1];
					int r = ((a >> 16 & 0xFF) + (b >> 16 & 0xFF) + (c >> 16 & 0xFF) + (d >> 16 & 0xFF) + 2) >> 2;
					int g = ((a >> 8 & 0xFF) + (b >> 8 & 0xFF) + (c >> 8 & 0xFF) + (d >> 8 & 0xFF) + 2) >> 2;
					int bl = ((a & 0xFF) + (b & 0xFF) + (c & 0xFF) + (d & 0xFF) + 2) >> 2;
					image[to + x] = r << 16 | g << 8 | bl;
				}
			}
		}
		return image == null ? delete(out) : save(out, format, image);
	}

	/** A tile's pixels as 0xRRGGBB, or null if it isn't one of the map's tiles (damaged, another size). */
	static int @Nullable [] read(Path file, TileFormat format) throws IOException {
		long started = System.nanoTime();
		int[] rgb;
		if (format == TileFormat.WEBP) {
			byte[] bytes = Files.readAllBytes(file);
			Vp8lDecoder.Picture picture;
			try {
				picture = Vp8lDecoder.decode(bytes);
			} catch (IOException e) {
				// Damaged: the tile's land is drawn again when it's next saved, or on a redraw.
				return null;
			}
			if (picture.width() != SIZE || picture.height() != SIZE) {
				return null;
			}
			rgb = picture.argb();
		} else {
			BufferedImage image = ImageIO.read(file.toFile());
			if (image == null || image.getWidth() != SIZE || image.getHeight() != SIZE) {
				return null;
			}
			rgb = image.getRGB(0, 0, SIZE, SIZE, null, 0, SIZE);
		}
		for (int i = 0; i < rgb.length; i++) {
			rgb[i] &= 0xFFFFFF;
		}
		decodeNanos += System.nanoTime() - started;
		decodes++;
		return rgb;
	}

	private static long delete(Path file) throws IOException {
		Files.deleteIfExists(file);
		return 0;
	}

	/** Writes the pixels (0xRRGGBB) beside the file, then moves them in, so a browser never gets half a picture. */
	private static long save(Path file, TileFormat format, int[] rgb) throws IOException {
		long started = System.nanoTime();
		Files.createDirectories(file.getParent());
		Path part = file.resolveSibling(file.getFileName() + ".part");
		if (format == TileFormat.WEBP) {
			int[] argb = new int[rgb.length];
			for (int i = 0; i < rgb.length; i++) {
				argb[i] = 0xFF000000 | rgb[i];
			}
			Files.write(part, Vp8lEncoder.encode(argb, SIZE, SIZE));
		} else {
			BufferedImage image = new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);
			image.setRGB(0, 0, SIZE, SIZE, rgb, 0, SIZE);
			writeJpeg(part, image);
		}
		long size = Files.size(part);
		Files.move(part, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
		encodeNanos += System.nanoTime() - started;
		encodes++;
		return size;
	}

	private static void writeJpeg(Path part, BufferedImage image) throws IOException {
		Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
		if (!writers.hasNext()) {
			throw new IOException("this Java has no JPEG writer");
		}
		ImageWriter writer = writers.next();
		try (OutputStream raw = Files.newOutputStream(part); ImageOutputStream out = ImageIO.createImageOutputStream(raw)) {
			writer.setOutput(out);
			ImageWriteParam param = writer.getDefaultWriteParam();
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			param.setCompressionQuality(QUALITY);
			// Colour at every pixel (4:4:4): JPEG's default keeps it for every other pixel each way,
			// which smears the edges between blocks of different colour.
			IIOMetadata metadata = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(image), param);
			Node tree = metadata.getAsTree("javax_imageio_jpeg_image_1.0");
			NodeList components = ((Element) tree).getElementsByTagName("componentSpec");
			for (int i = 0; i < components.getLength(); i++) {
				Element component = (Element) components.item(i);
				component.setAttribute("HsamplingFactor", "1");
				component.setAttribute("VsamplingFactor", "1");
			}
			metadata.setFromTree("javax_imageio_jpeg_image_1.0", tree);
			writer.write(null, new IIOImage(image, null, metadata), param);
		} finally {
			writer.dispose();
		}
	}
}
