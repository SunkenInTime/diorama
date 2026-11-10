package dev.diorama.web;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/** The web map's picture format, the {@code web-map-format} setting. */
public enum TileFormat {
	/** JPEG at quality 85, colour at every pixel: the smallest files, with a little blur at block edges. */
	JPEG("jpeg", "jpg", "image/jpeg"),
	/** Lossless WebP: every pixel exactly as drawn, about twice JPEG's disk. Experimental. */
	WEBP("webp", "webp", "image/webp");

	/** As written in the settings. */
	final String setting;
	/** The pictures' file extension, without the dot. */
	final String extension;
	final String mediaType;

	TileFormat(String setting, String extension, String mediaType) {
		this.setting = setting;
		this.extension = extension;
		this.mediaType = mediaType;
	}

	/** The format a setting names, or null if none. */
	static @Nullable TileFormat named(String text) {
		String name = text.trim().toLowerCase(Locale.ROOT);
		for (TileFormat format : values()) {
			if (format.setting.equals(name) || format.extension.equals(name)) {
				return format;
			}
		}
		return null;
	}

	/** The format of a picture file, by its name, or null for any other file. */
	static @Nullable TileFormat ofFile(String name) {
		for (TileFormat format : values()) {
			if (name.endsWith("." + format.extension)) {
				return format;
			}
		}
		return null;
	}
}
