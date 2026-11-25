package dev.diorama.testaddon.linkage;

/** LinkageAddon compiles against this; its jar leaves it out. */
public final class MissingClass {
	private MissingClass() {
	}

	public static void call() {
	}
}
