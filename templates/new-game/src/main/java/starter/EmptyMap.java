package starter;

import astratto.bridge.AstrattoNative;
import astratto.bridge.CommandBuffer;
import astratto.bridge.NativeEngine;
import astratto.bridge.NativeMesher;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * The starter game's map: a field of grass with a small brick hut (a doorway, glass windows, a lamp under the roof) and
 * a few stone posts. Everything a game says about its blocks is here: what they mean to the engine (solid, opaque,
 * glass, light), how they look (texture layers, which are painted here from code so the starter needs no files), and
 * the light and colour the renderer's probes use. Replace it with your own map.
 */
public final class EmptyMap {
    private EmptyMap() {}

    public static final int SX = 96, SY = 32, SZ = 96, FLOOR = 4;
    public static final int AIR = 0, GRASS = 1, DIRT = 2, STONE = 3, BRICK = 4, GLASS = 5, LAMP = 6, BLOCKS = 7;
    /** Texture layers, one each, in this order. */
    static final int L_GRASS_TOP = 0, L_GRASS_SIDE = 1, L_DIRT = 2, L_STONE = 3, L_BRICK = 4, L_GLASS = 5, L_LAMP = 6, LAYERS = 7;
    public static final int TEX = 32;
    /** Where things are. */
    public static final float HUT_X0 = 40, HUT_Z0 = 40, HUT_X1 = 50, HUT_Z1 = 50, LAMP_X = 45.5f, LAMP_Y = 8.5f, LAMP_Z = 45.5f;

    /** What each block means to the engine's simulation: collision, sight, light. */
    public static NativeEngine.BlockDef[] blockDefs() {
        NativeEngine.BlockDef[] d = new NativeEngine.BlockDef[BLOCKS];
        int solid = AstrattoNative.BLOCK_SOLID | AstrattoNative.BLOCK_OPAQUE;
        d[AIR] = new NativeEngine.BlockDef(0);
        d[GRASS] = new NativeEngine.BlockDef(solid); d[GRASS].surface = 3;
        d[DIRT] = new NativeEngine.BlockDef(solid); d[DIRT].surface = 3;
        d[STONE] = new NativeEngine.BlockDef(solid); d[STONE].surface = 1;
        d[BRICK] = new NativeEngine.BlockDef(solid);
        d[GLASS] = new NativeEngine.BlockDef(AstrattoNative.BLOCK_SOLID | AstrattoNative.BLOCK_LIGHT_PASS); d[GLASS].material = AstrattoNative.MATERIAL_GLASS;
        d[LAMP] = new NativeEngine.BlockDef(solid); d[LAMP].emission = 15;
        return d;
    }

    /** Builds the map into a new engine world (lit) and returns its handle. */
    public static long build(NativeEngine e) {
        if (!e.blockTable(blockDefs())) throw new IllegalStateException("block table refused: " + e.lastError());
        long world = e.createWorld(SX, SY, SZ, STONE, null, null, 0);
        if (world == 0) throw new IllegalStateException("world refused: " + e.lastError());
        CommandBuffer b = new CommandBuffer(1 << 14);
        int raw = AstrattoNative.SET_RAW;
        b.fill(0, 0, 0, SX - 1, FLOOR - 3, SZ - 1, STONE | raw);
        b.fill(0, FLOOR - 2, 0, SX - 1, FLOOR - 2, SZ - 1, DIRT | raw);
        b.fill(0, FLOOR - 1, 0, SX - 1, FLOOR - 1, SZ - 1, GRASS | raw);
        // the hut: four brick walls, a stone roof, a doorway on the south side, windows on the north
        int x0 = (int) HUT_X0, z0 = (int) HUT_Z0, x1 = (int) HUT_X1, z1 = (int) HUT_Z1, top = FLOOR + 4;
        b.fill(x0, FLOOR, z0, x1, top, z0, BRICK | raw);
        b.fill(x0, FLOOR, z1, x1, top, z1, BRICK | raw);
        b.fill(x0, FLOOR, z0, x0, top, z1, BRICK | raw);
        b.fill(x1, FLOOR, z0, x1, top, z1, BRICK | raw);
        b.fill(x0, top + 1, z0, x1, top + 1, z1, STONE | raw);
        b.fill(44, FLOOR, z0, 46, FLOOR + 2, z0, AIR | raw);
        b.fill(42, FLOOR + 2, z1, 43, FLOOR + 3, z1, GLASS | raw);
        b.fill(47, FLOOR + 2, z1, 48, FLOOR + 3, z1, GLASS | raw);
        b.blockSetRaw((int) LAMP_X, top, (int) LAMP_Z, LAMP);
        // posts across the field
        for (int i = 0; i < 6; i++) b.fill(12 + i * 12, FLOOR, 20, 12 + i * 12, FLOOR + 2, 20, STONE | raw);
        b.lightAll();
        if (!e.edit(world, b)) throw new IllegalStateException("map edits refused: " + e.lastError());
        b.free();
        return world;
    }

    // ------------------------------------------------------------------ how it looks
    public static NativeMesher.BlockDef[] renderBlocks() {
        NativeMesher.BlockDef[] r = new NativeMesher.BlockDef[BLOCKS];
        int cube = AstrattoNative.RB_SOLID | AstrattoNative.RB_OCCLUDES;
        r[AIR] = new NativeMesher.BlockDef();
        r[GRASS] = def(L_GRASS_TOP, L_GRASS_SIDE, L_DIRT, cube);
        r[DIRT] = def(L_DIRT, L_DIRT, L_DIRT, cube);
        r[STONE] = def(L_STONE, L_STONE, L_STONE, cube);
        r[BRICK] = def(L_BRICK, L_BRICK, L_BRICK, cube);
        r[GLASS] = def(L_GLASS, L_GLASS, L_GLASS, AstrattoNative.RB_SOLID | AstrattoNative.RB_TRANSLUCENT);
        r[LAMP] = def(L_LAMP, L_LAMP, L_LAMP, cube | AstrattoNative.RB_EMISSIVE);
        return r;
    }

    private static NativeMesher.BlockDef def(int top, int side, int bottom, int flags) {
        NativeMesher.BlockDef d = new NativeMesher.BlockDef();
        d.layerTop = top; d.layerSide = side; d.layerBottom = bottom; d.flags = flags;
        return d;
    }

    /** No shaped blocks in this map: one empty shape (every block is a full cube). */
    public static NativeMesher.ShapeDef[] renderShapes() { return new NativeMesher.ShapeDef[]{new NativeMesher.ShapeDef()}; }

    private static final int[][] BASE = {
            {88, 140, 62}, {104, 96, 60}, {112, 86, 62}, {128, 128, 132}, {150, 70, 56}, {190, 220, 235}, {255, 236, 190}};

    /** The texture array: LAYERS layers of TEX x TEX RGBA, painted with a little noise (native memory; the caller frees it). */
    public static ByteBuffer atlasPixels() {
        ByteBuffer px = MemoryUtil.memAlloc(TEX * TEX * 4 * LAYERS);
        java.util.Random rnd = new java.util.Random(7);
        for (int l = 0; l < LAYERS; l++) for (int y = 0; y < TEX; y++) for (int x = 0; x < TEX; x++) {
            int[] c = BASE[l];
            float n = 0.86f + rnd.nextFloat() * 0.22f;
            int r = c[0], g = c[1], b = c[2], a = 255;
            if (l == L_GRASS_SIDE && y > TEX / 4 + (x * 7 % 5)) { r = BASE[L_DIRT][0]; g = BASE[L_DIRT][1]; b = BASE[L_DIRT][2]; }
            if (l == L_BRICK && (y % 8 == 0 || (x + (y / 8 % 2) * 8) % 16 == 0)) { r = 200; g = 196; b = 186; n = 1; }
            if (l == L_STONE && ((x / 8 + y / 8) & 1) == 0) n *= 0.94f;
            if (l == L_GLASS) { a = (x == 0 || y == 0 || x == TEX - 1 || y == TEX - 1) ? 255 : 70; n = 1; }
            if (l == L_LAMP) n = 1;
            px.put((byte) clamp(r * n)).put((byte) clamp(g * n)).put((byte) clamp(b * n)).put((byte) a);
        }
        px.flip();
        return px;
    }

    /** A tileable noise texture for the world shader's grime and wear (256 x 256 RGBA; native memory, the caller frees it). */
    public static ByteBuffer noisePixels() {
        ByteBuffer px = MemoryUtil.memAlloc(256 * 256 * 4);
        java.util.Random rnd = new java.util.Random(11);
        for (int i = 0; i < 256 * 256 * 4; i++) px.put((byte) (96 + rnd.nextInt(64)));
        px.flip();
        return px;
    }

    /** Per block id its albedo and emitted light for the probes: 256 x 8 floats. */
    public static float[] palette() {
        float[] p = new float[256 * 8];
        int[] layerOf = {0, L_GRASS_TOP, L_DIRT, L_STONE, L_BRICK, L_GLASS, L_LAMP};
        for (int id = 1; id < BLOCKS; id++) {
            int[] c = BASE[layerOf[id]];
            for (int k = 0; k < 3; k++) p[id * 8 + k] = (float) Math.pow(c[k] / 255.0, 2.2);
            p[id * 8 + 3] = 1;
        }
        p[LAMP * 8 + 4] = 6f; p[LAMP * 8 + 5] = 5.2f; p[LAMP * 8 + 6] = 3.6f;
        return p;
    }

    /** Per texture layer: roughness, metalness, emission, 255. */
    public static byte[] layerMaterials() {
        byte[] m = new byte[LAYERS * 4];
        for (int l = 0; l < LAYERS; l++) { m[l * 4] = (byte) 220; m[l * 4 + 3] = (byte) 255; }
        m[L_GLASS * 4] = (byte) 25;
        m[L_LAMP * 4 + 2] = (byte) 255;
        return m;
    }

    private static int clamp(float v) { return Math.max(0, Math.min(255, Math.round(v))); }
}
