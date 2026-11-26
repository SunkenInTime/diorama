package dev.diorama.render;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.client.model.HeadedModel;
import net.minecraft.client.model.Model;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.model.geom.PartPose;
import org.jspecify.annotations.Nullable;

/**
 * Faces read off a mob's own model, for mobs {@link MobFaces} has no face for, modded ones
 * included. The face is the model's head as seen from the front, flat: the front of every box
 * in the head and the parts on it (a hat, a nose, ears, horns), each a rectangle of the mob's
 * texture, drawn back to front. Read at rest, the way the model is built, not mid-animation.
 * Models that don't come from Minecraft's model parts (GeckoLib's, say) have none.
 */
public final class ModelFaces {
	/** Larger than this, in texture pixels, and it's not a face but most of the mob. */
	private static final int MAX_SIZE = 32;
	/** A box's face within this of looking straight out of the front counts as its front. */
	private static final float FACING = 0.999F;

	/** Each model's head, found once: a game's models live until a resource reload builds new ones. */
	private static final Map<Object, Optional<ModelPart>> HEADS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
	/** Each model's whole tree of parts, found once, for mobs with no head to read. */
	private static final Map<Object, Optional<ModelPart>> ROOTS = new com.google.common.collect.MapMaker().weakKeys().makeMap();
	/**
	 * The faces read off each model, by which parts of its head are shown: a model can show a part
	 * for one mob and not another, like a goat's horns.
	 */
	private static final Map<Object, Map<BitSet, Optional<MobFaces.Face>>> FACES = new com.google.common.collect.MapMaker().weakKeys().makeMap();

	private ModelFaces() {
	}

	/**
	 * The face of a model as it's drawn for one mob, or null if it has no head this can read.
	 * {@code state} is the mob's render state, which says which parts it shows; before 1.21.2 there
	 * was none, and the parts are as the model was last drawn. The model is left as it was found.
	 * Render thread only; public for tests.
	 */
	public static MobFaces.@Nullable Face of(Model model, @Nullable Object state) {
		try {
			return read(model, state);
		} catch (RuntimeException e) {
			// Another mod's model that doesn't hold still to be read: its mobs stay dots.
			dev.diorama.Diorama.LOGGER.debug("Couldn't read a face off {}", model.getClass().getName(), e);
			return null;
		}
	}

	/**
	 * A face of a box that looks out of the front: where its middle is (in texture pixels), how deep,
	 * and the texture rectangle on it, from 0 to 1. {@code straight} when it's the box's own front,
	 * unturned, so {@code w} x {@code h}, the box's size, is also its size in texture pixels.
	 * {@code shrunk} when it's that but drawn smaller than its texture rectangle.
	 */
	private record Front(float cx, float cy, float z, int w, int h, boolean straight, boolean shrunk, float uLeft, float vTop, float uRight, float vBottom) {
	}

	/** A part's pose, size and whether it's shown, to put back after reading. */
	private record Held(ModelPart part, PartPose pose, float xScale, float yScale, float zScale, boolean visible, boolean skipDraw) {
		static Held of(ModelPart part) {
			return new Held(part, part.storePose(), part.xScale, part.yScale, part.zScale, part.visible, part.skipDraw);
		}

		void restore() {
			// A pose doesn't hold a part's size: put that back separately.
			this.part.loadPose(this.pose);
			this.part.xScale = this.xScale;
			this.part.yScale = this.yScale;
			this.part.zScale = this.zScale;
			this.part.visible = this.visible;
			this.part.skipDraw = this.skipDraw;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static MobFaces.@Nullable Face read(Model model, @Nullable Object state) {
		ModelPart head = HEADS.computeIfAbsent(model, m -> Optional.ofNullable(head((Model) m))).orElse(null);
		ModelPart root = ROOTS.computeIfAbsent(model, m -> Optional.ofNullable(root((Model) m))).orElse(null);
		// Without a head, the whole mob is its picture: a fish, a squid.
		ModelPart top = root != null ? root : head;
		if (top == null) {
			return null;
		}
		// The world draws with this same model, so everything that's moved to read it is put back.
		List<ModelPart> touched = parts(top);
		List<Held> held = new ArrayList<>(touched.size());
		for (ModelPart part : touched) {
			held.add(Held.of(part));
		}
		try {
			//? if >=1.21.2 {
			if (state != null && model instanceof net.minecraft.client.model.EntityModel entityModel) {
				// The model as this mob shows it: which of its parts are there.
				entityModel.setupAnim((net.minecraft.client.renderer.entity.state.EntityRenderState) state);
			}
			//?}
			BitSet shown = new BitSet(touched.size() * 2);
			for (int i = 0; i < touched.size(); i++) {
				shown.set(i * 2, touched.get(i).visible);
				shown.set(i * 2 + 1, touched.get(i).skipDraw);
			}
			Map<BitSet, Optional<MobFaces.Face>> faces = FACES.computeIfAbsent(model, m -> new HashMap<>());
			Optional<MobFaces.Face> face = faces.get(shown);
			if (face == null) {
				MobFaces.Face read = head == null ? null : readHead(head);
				if (read == null && root != null) {
					read = readWhole(root);
				}
				face = Optional.ofNullable(read);
				faces.put(shown, face);
			}
			return face.orElse(null);
		} finally {
			for (Held h : held) {
				h.restore();
			}
		}
	}

	/** The head's face at rest, the way the model is built rather than mid-animation. */
	private static MobFaces.@Nullable Face readHead(ModelPart head) {
		for (ModelPart part : parts(head)) {
			part.resetPose();
		}
		// The head's own turn would tilt the whole face; only how its parts sit on it matters.
		head.setRotation(0, 0, 0);
		return layOut(fronts(head, false, false), false);
	}

	/**
	 * A mob with no head to read, or one too small to make out (a fish's): the whole mob at rest,
	 * from the front or from the side, whichever shows more of it, so a fish shows its side and a
	 * pufferfish its face. One too big for an icon shows its body alone, a squid's without the
	 * tentacles. Xaero's Minimap draws these mobs the same way.
	 */
	private static MobFaces.@Nullable Face readWhole(ModelPart root) {
		for (ModelPart part : parts(root)) {
			part.resetPose();
		}
		// Where, how big and which way round the world draws it doesn't change its picture: a large
		// salmon's model is the medium one scaled up and moved down a fraction of a pixel.
		root.setRotation(0, 0, 0);
		root.setPos(0, 0, 0);
		root.xScale = 1;
		root.yScale = 1;
		root.zScale = 1;
		MobFaces.Face face = largest(root);
		if (face == null) {
			ModelPart body = named(root, "body");
			face = body == null || body == root ? null : largest(body);
		}
		return face;
	}

	/** A part seen from the front and from the side, whichever shows more of it; the front on a tie. */
	private static MobFaces.@Nullable Face largest(ModelPart top) {
		MobFaces.Face front = layOut(fronts(top, false, true), true);
		MobFaces.Face side = layOut(fronts(top, true, true), true);
		if (front == null || side == null) {
			return front != null ? front : side;
		}
		return side.w() * side.h() > front.w() * front.h() ? side : front;
	}

	/**
	 * The faces of every shown box below a part that look at the viewer, who stands in front of
	 * the mob or with {@code side} at its side. {@code whole} for any face of a box, not only its front.
	 */
	private static List<Front> fronts(ModelPart top, boolean side, boolean whole) {
		PoseStack view = new PoseStack();
		if (side) {
			// A quarter turn, on the matrices themselves: PoseStack's own call for it differs between versions.
			view.last().pose().rotateY((float) Math.PI / 2);
			view.last().normal().rotateY((float) Math.PI / 2);
		}
		List<Front> fronts = new ArrayList<>();
		top.visit(view, (pose, path, index, cube) -> {
			if (shown(top, path)) {
				fronts(fronts, pose, cube, whole);
			}
		});
		return fronts;
	}

	/**
	 * The model's whole tree of parts. Before 1.21.2 a model that isn't built as one tree keeps its
	 * parts in fields of its own (a tadpole's): its tree is the field that holds all the others, or
	 * null if none does.
	 */
	private static @Nullable ModelPart root(Model model) {
		//? if >=1.21.2 {
		return model.root();
		//?} else {
		/*if (model instanceof net.minecraft.client.model.HierarchicalModel<?> hierarchical) {
			return hierarchical.root();
		}
		List<ModelPart> fields = new ArrayList<>();
		for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
			for (java.lang.reflect.Field field : c.getDeclaredFields()) {
				if (field.getType() == ModelPart.class && !java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
					try {
						field.setAccessible(true);
						ModelPart part = (ModelPart) field.get(model);
						if (part != null) {
							fields.add(part);
						}
					} catch (ReflectiveOperationException | RuntimeException e) {
						// Not readable; leave it out.
					}
				}
			}
		}
		for (ModelPart candidate : fields) {
			if (parts(candidate).containsAll(fields)) {
				return candidate;
			}
		}
		return null;
		*///?}
	}

	/** The shallowest part below {@code root} with this name, or null. */
	private static @Nullable ModelPart named(ModelPart root, String name) {
		int[] depth = {Integer.MAX_VALUE};
		ModelPart[] found = new ModelPart[1];
		root.visit(new PoseStack(), (pose, path, index, cube) -> {
			String[] names = path.split("/");
			for (int i = 1; i < names.length && i < depth[0]; i++) {
				if (names[i].equals(name)) {
					ModelPart part = resolve(root, names, i);
					if (part != null) {
						found[0] = part;
						depth[0] = i;
					}
				}
			}
		});
		return found[0];
	}

	/** The model's head: the part it says is its head, or else one named head. */
	private static @Nullable ModelPart head(Model model) {
		if (model instanceof HeadedModel headed) {
			return headed.getHead();
		}
		//? if >=1.21.2 {
		ModelPart root = model.root();
		//?} else {
		/*// Models had no root of their own yet. A mod's model keeps its field names in the game, so
		// its head is the field named head, or a part with a head below it. Minecraft's own fields
		// are numbered in a release jar, so there, failing both, its head is the field shaped like
		// a head.
		ModelPart root = model instanceof net.minecraft.client.model.HierarchicalModel<?> hierarchical ? hierarchical.root() : null;
		ModelPart shaped = null;
		if (root == null) {
			for (Class<?> c = model.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
				for (java.lang.reflect.Field field : c.getDeclaredFields()) {
					if (field.getType() != ModelPart.class || java.lang.reflect.Modifier.isStatic(field.getModifiers())) {
						continue;
					}
					try {
						field.setAccessible(true);
						ModelPart part = (ModelPart) field.get(model);
						if (part != null && field.getName().equalsIgnoreCase("head")) {
							return part;
						}
						if (part != null && root == null && part.hasChild("head")) {
							root = part;
						}
						if (part != null && shaped == null && headShapes().contains(shape(part))) {
							shaped = part;
						}
					} catch (ReflectiveOperationException | RuntimeException e) {
						// Not readable; try the next.
					}
				}
			}
			if (root == null) {
				return shaped;
			}
		}
		*///?}
		return headBelow(root);
	}

	//? if <1.21.2 {
	/*private static java.util.@Nullable Set<String> headShapes;

	// The shape of every head in Minecraft's own model layers, which still name their parts in a
	// release jar. Read once: the layers are built in code, so a resource reload doesn't change them.
	private static java.util.Set<String> headShapes() {
		java.util.Set<String> shapes = headShapes;
		if (shapes == null) {
			shapes = new java.util.HashSet<>();
			net.minecraft.client.model.geom.EntityModelSet models = net.minecraft.client.Minecraft.getInstance().getEntityModels();
			for (java.util.Iterator<net.minecraft.client.model.geom.ModelLayerLocation> it = net.minecraft.client.model.geom.ModelLayers.getKnownLocations().iterator(); it.hasNext();) {
				try {
					ModelPart layer = models.bakeLayer(it.next());
					String head = layer.hasChild("head") ? shape(layer.getChild("head")) : "";
					if (!head.isEmpty()) {
						shapes.add(head);
					}
				} catch (RuntimeException e) {
					// A layer the game lists but doesn't build; no head to learn from it.
				}
			}
			headShapes = shapes;
		}
		return shapes;
	}

	// A part's boxes and the parts below it, by their place in it: the same for any copy of a part.
	private static String shape(ModelPart part) {
		StringBuilder shape = new StringBuilder();
		part.visit(new PoseStack(), (pose, path, index, cube) -> shape.append(path).append('#').append(index).append(':')
			.append(cube.minX).append(',').append(cube.minY).append(',').append(cube.minZ).append(',')
			.append(cube.maxX).append(',').append(cube.maxY).append(',').append(cube.maxZ).append(';'));
		return shape.toString();
	}
	*///?}

	/**
	 * The head somewhere below a model's root, by the names models give it. Models exported from
	 * Blockbench nest their bones and name them freely: a part called head wins, then skull, then
	 * one with either in its name (headRot, sf_head), then neck, the shallowest of each.
	 */
	private static @Nullable ModelPart headBelow(ModelPart root) {
		int[] rank = {Integer.MAX_VALUE, Integer.MAX_VALUE};
		ModelPart[] found = new ModelPart[1];
		root.visit(new PoseStack(), (pose, path, index, cube) -> {
			String[] names = path.split("/");
			for (int i = 1; i < names.length; i++) {
				int kind = headName(names[i]);
				if (kind != Integer.MAX_VALUE && (kind < rank[0] || kind == rank[0] && i < rank[1])) {
					ModelPart part = resolve(root, names, i);
					if (part != null) {
						found[0] = part;
						rank[0] = kind;
						rank[1] = i;
					}
				}
			}
		});
		return found[0];
	}

	/** How much a part's name says it's the head, lower for surer; MAX_VALUE for not at all. */
	private static int headName(String name) {
		String lower = name.toLowerCase(java.util.Locale.ROOT);
		if (lower.equals("head")) {
			return 0;
		}
		if (lower.equals("skull")) {
			return 1;
		}
		if (lower.contains("head") || lower.contains("skull")) {
			return 2;
		}
		return lower.equals("neck") ? 3 : Integer.MAX_VALUE;
	}

	/** The part at names[1..last] below the root, by the names {@link ModelPart#visit} gives. */
	private static @Nullable ModelPart resolve(ModelPart root, String[] names, int last) {
		ModelPart part = root;
		for (int i = 1; i <= last; i++) {
			if (!part.hasChild(names[i])) {
				return null;
			}
			part = part.getChild(names[i]);
		}
		return part;
	}

	/**
	 * Whether a part's boxes are drawn: it and every part on the way from the head to it are
	 * visible, and it doesn't skip its own boxes (which, unlike hiding, leaves its children drawn).
	 */
	private static boolean shown(ModelPart head, String path) {
		ModelPart part = head;
		if (!part.visible) {
			return false;
		}
		for (String name : path.split("/")) {
			if (name.isEmpty()) {
				continue;
			}
			if (!part.hasChild(name)) {
				return false;
			}
			part = part.getChild(name);
			if (!part.visible) {
				return false;
			}
		}
		return !part.skipDraw;
	}

	/** A part and every part below it. */
	private static List<ModelPart> parts(ModelPart top) {
		//? if >=1.21.6 {
		return top.getAllParts();
		//?} else {
		/*return top.getAllParts().toList();
		*///?}
	}

	/**
	 * Adds the face of a box that looks straight out at the viewer, as it sits on the head, if one
	 * does. On a head, only a box's own front counts. With {@code anyFace}, any of its faces does,
	 * which is how a mob seen from the side shows: a fin with no width still has a side. A box with
	 * no thickness, a fin, also gives the face on its far side, which the game draws through it:
	 * a fish's texture paints a fin on one side only, either one.
	 * Read from what the box gives a vertex consumer to draw, the one way into its faces on every version.
	 */
	private static void fronts(List<Front> out, PoseStack.Pose pose, ModelPart.Cube cube, boolean anyFace) {
		int w = Math.round(cube.maxX - cube.minX);
		int h = Math.round(cube.maxY - cube.minY);
		int d = Math.round(cube.maxZ - cube.minZ);
		if (!anyFace && (w <= 0 || h <= 0)) {
			return;
		}
		boolean thin = w <= 0 || h <= 0 || d <= 0;
		Corners corners = compile(pose, cube);
		// The same faces in the box's own space, in the same order: which of its sides each one is.
		Corners own = anyFace ? compile(new PoseStack().last(), cube) : null;
		for (int q = 0; q + 4 <= corners.count; q += 4) {
			float facing = corners.at[q * Corners.SIZE + 7];
			boolean back = anyFace && thin && facing >= FACING;
			if (facing > -FACING && !back) {
				continue;
			}
			if (own != null) {
				int o = q * Corners.SIZE;
				// A side's size in texture pixels: depth by height, and the top and bottom width by depth.
				boolean sideways = Math.abs(own.at[o + 5]) > FACING;
				boolean flat = Math.abs(own.at[o + 6]) > FACING;
				w = sideways ? d : Math.round(cube.maxX - cube.minX);
				h = flat ? d : Math.round(cube.maxY - cube.minY);
				if (w <= 0 || h <= 0) {
					continue;
				}
			}
			float minX = Float.MAX_VALUE;
			float maxX = -Float.MAX_VALUE;
			float minY = Float.MAX_VALUE;
			float maxY = -Float.MAX_VALUE;
			float z = 0;
			float uLeft = 0;
			float uRight = 0;
			float vTop = 0;
			float vBottom = 0;
			for (int i = q; i < q + 4; i++) {
				int o = i * Corners.SIZE;
				// Positions come in blocks; texture pixels are sixteenths of one.
				float x = corners.at[o] * 16;
				float y = corners.at[o + 1] * 16;
				z += corners.at[o + 2] * 16 / 4;
				if (x < minX) {
					minX = x;
					uLeft = corners.at[o + 3];
				}
				if (x > maxX) {
					maxX = x;
					uRight = corners.at[o + 3];
				}
				if (y < minY) {
					minY = y;
					vTop = corners.at[o + 4];
				}
				if (y > maxY) {
					maxY = y;
					vBottom = corners.at[o + 4];
				}
			}
			// A grown box (a hat layer) is bigger than its texture rectangle by the same on every side,
			// and a shrunk one (a snow golem's head) smaller by the same.
			// A turned one (a hat's brim) shows another of its faces at the front.
			float grownX = maxX - minX - w;
			float grownY = maxY - minY - h;
			boolean even = !back && Math.abs(grownX - grownY) < 0.01F;
			boolean straight = even && grownX > -0.01F && grownX < 2;
			boolean shrunk = even && grownX <= -0.01F && grownX > -2;
			// The far side goes under the near one.
			out.add(new Front((minX + maxX) / 2, (minY + maxY) / 2, back ? z + 0.01F : z, w, h, straight, shrunk, uLeft, vTop, uRight, vBottom));
			if (!anyFace) {
				return;
			}
		}
	}

	private static Corners compile(PoseStack.Pose pose, ModelPart.Cube cube) {
		Corners corners = new Corners();
		//? if >=1.21 {
		cube.compile(pose, corners, 0, 0, -1);
		//?} else {
		/*cube.compile(pose, corners, 0, 0, 1, 1, 1, 1);
		*///?}
		return corners;
	}

	/** Keeps each vertex a box draws: x, y, z, u, v and the normal's x, y, z, four to a face. */
	private static final class Corners implements VertexConsumer {
		static final int SIZE = 8;
		float[] at = new float[6 * 4 * SIZE];
		int count;

		@Override
		//? if >=1.21 {
		public void addVertex(float x, float y, float z, int color, float u, float v, int overlay, int light, float nx, float ny, float nz) {
		//?} else {
		/*public void vertex(float x, float y, float z, float red, float green, float blue, float alpha, float u, float v, int overlay, int light, float nx, float ny, float nz) {
			// From 1.21 the game hands a box's normals over at unit length; before, a part scaled
			// unevenly left them longer or shorter, and its front could fail the FACING test.
			float length = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
			if (length > 0) {
				nx /= length;
				ny /= length;
				nz /= length;
			}
		*///?}
			if ((this.count + 1) * SIZE > this.at.length) {
				this.at = java.util.Arrays.copyOf(this.at, this.at.length * 2);
			}
			int o = this.count++ * SIZE;
			this.at[o] = x;
			this.at[o + 1] = y;
			this.at[o + 2] = z;
			this.at[o + 3] = u;
			this.at[o + 4] = v;
			this.at[o + 5] = nx;
			this.at[o + 6] = ny;
			this.at[o + 7] = nz;
		}

		// The rest of what a vertex consumer takes, none of which a box's shape needs.
		//? if >=1.21 {
		@Override
		public VertexConsumer addVertex(float x, float y, float z) {
			return this;
		}

		@Override
		public VertexConsumer setColor(int r, int g, int b, int a) {
			return this;
		}

		public VertexConsumer setColor(int color) {
			return this;
		}

		@Override
		public VertexConsumer setUv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setUv1(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer setUv2(int u, int v) {
			return this;
		}

		public VertexConsumer setUv3(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer setNormal(float x, float y, float z) {
			return this;
		}

		public VertexConsumer setLineWidth(float width) {
			return this;
		}
		//?} else {
		/*@Override
		public VertexConsumer vertex(double x, double y, double z) {
			return this;
		}

		@Override
		public VertexConsumer color(int r, int g, int b, int a) {
			return this;
		}

		@Override
		public VertexConsumer uv(float u, float v) {
			return this;
		}

		@Override
		public VertexConsumer overlayCoords(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer uv2(int u, int v) {
			return this;
		}

		@Override
		public VertexConsumer normal(float x, float y, float z) {
			return this;
		}

		@Override
		public void endVertex() {
		}

		@Override
		public void defaultColor(int r, int g, int b, int a) {
		}

		@Override
		public void unsetDefaultColor() {
		}
		*///?}
	}

	/**
	 * The fronts as one face: the box nearest the middle that's widest is the face itself, and
	 * the rest are laid on it at their places, the far ones first. Its texture runs left to right
	 * the way the face's own does, which is how the hand-made faces in {@link MobFaces} draw it.
	 */
	private static MobFaces.@Nullable Face layOut(List<Front> fronts, boolean whole) {
		if (fronts.isEmpty()) {
			return null;
		}
		// The texture's size, in texture pixels, from the biggest unturned box; each face's own size from that.
		Front main = fronts.stream().filter(Front::straight).max(Comparator.comparingInt((Front f) -> f.w() * f.h())).orElse(null);
		if (main == null) {
			// Only when no box is its texture's size: a shrunk box is never picked over one that is.
			main = fronts.stream().filter(Front::shrunk).max(Comparator.comparingInt((Front f) -> f.w() * f.h())).orElse(null);
		}
		if (main == null) {
			return null;
		}
		float du = main.uRight() - main.uLeft();
		float dv = main.vBottom() - main.vTop();
		if (du == 0 || dv == 0) {
			return null;
		}
		boolean flip = du < 0;
		int texW = Math.round(main.w() / Math.abs(du));
		int texH = Math.round(main.h() / Math.abs(dv));
		List<Front> sorted = new ArrayList<>(fronts);
		sorted.sort(Comparator.comparingDouble((Front f) -> -f.z()));
		int minX = Integer.MAX_VALUE;
		int minY = Integer.MAX_VALUE;
		int maxX = Integer.MIN_VALUE;
		int maxY = Integer.MIN_VALUE;
		List<MobFaces.Piece> pieces = new ArrayList<>();
		for (Front f : sorted) {
			float cx = flip ? -f.cx() : f.cx();
			float uLeft = flip ? f.uRight() : f.uLeft();
			float uRight = flip ? f.uLeft() : f.uRight();
			// Mirrored boxes (one of a pair of ears) show their texture flipped. On a head they're left
			// out, as they were before faces could flip; a whole mob's far fin sides need them.
			boolean mirrored = uRight < uLeft;
			if (mirrored && !whole || f.vBottom() < f.vTop()) {
				continue;
			}
			if (mirrored) {
				float swap = uLeft;
				uLeft = uRight;
				uRight = swap;
			}
			int w = Math.round((uRight - uLeft) * texW);
			int h = Math.round((f.vBottom() - f.vTop()) * texH);
			if (w <= 0 || h <= 0) {
				continue;
			}
			// Drawn at its texture's size, centred where it sits.
			int x = Math.round(cx - w / 2.0F);
			int y = Math.round(f.cy() - h / 2.0F);
			pieces.add(new MobFaces.Piece(Math.round(uLeft * texW), Math.round(f.vTop() * texH), w, h, x, y, mirrored));
			minX = Math.min(minX, x);
			minY = Math.min(minY, y);
			maxX = Math.max(maxX, x + w);
			maxY = Math.max(maxY, y + h);
		}
		int w = maxX - minX;
		int h = maxY - minY;
		// A long thin strip (a ray seen edge on, an ostrich's neck) or a scrap (a slime's eye) reads worse than a dot.
		// A whole mob can be longer than a face: a salmon from the side is three and a half times as
		// long as it's tall. And smaller: a tadpole's front is three by two, its eyes on it.
		int longest = whole ? 4 : 3;
		int least = whole ? 2 : 3;
		if (pieces.isEmpty() || w > MAX_SIZE || h > MAX_SIZE || w > longest * h || h > longest * w || w < 3 && h < 3 || w < least || h < least) {
			return null;
		}
		List<MobFaces.Piece> placed = new ArrayList<>(pieces.size());
		BitSet covered = new BitSet(w * h);
		for (MobFaces.Piece p : pieces) {
			placed.add(new MobFaces.Piece(p.u(), p.v(), p.w(), p.h(), p.dx() - minX, p.dy() - minY, p.mirrored()));
			for (int y = p.dy() - minY; y < p.dy() - minY + p.h(); y++) {
				covered.set(y * w + p.dx() - minX, y * w + p.dx() - minX + p.w());
			}
		}
		// A whole mob that's mostly gaps between parts seen edge on (a Blockbench boar from the front)
		// reads worse than a dot. The sparsest good ones, an ostrich's and a parrot's, cover nearly half.
		if (whole && covered.cardinality() * 3 < w * h) {
			return null;
		}
		return new MobFaces.Face(texW, texH, w, h, List.copyOf(placed), whole);
	}
}
