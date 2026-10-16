package astratto.tests;

import static org.junit.jupiter.api.Assertions.*;

import astratto.bridge.AstrattoNative;
import astratto.bridge.CommandBuffer;
import astratto.bridge.NativeEngine;
import astratto.bridge.NativeMesher;
import astratto.bridge.NativeRenderer;
import astratto.bridge.RenderCommands;
import astratto.bridge.Snapshots;
import astratto.game.Game;
import astratto.game.GameContext;
import astratto.game.GameModule;
import astratto.math.Mat4;
import astratto.platform.Window;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

/**
 * Integration: a small game, the way a team would write one - Java gameplay modules over the native services - run for
 * ten simulated seconds, and the native renderer drawing an engine world into a real GL 4.5 context. Java fallbacks are
 * off (the test task sets ASTRATTO_JAVA_FALLBACK=0): what passes here ran natively.
 */
class IntegrationTest {
    /** Java gameplay driving every native simulation service at once. */
    @Test void javaGameplayOverNativePhysicsAiAndAudio() {
        NativeEngine.Config cfg = new NativeEngine.Config();
        cfg.deterministic = true;
        try (Game g = Game.create(cfg).logger(null)) {
            long w = Fixtures.yard(g.engine());
            g.activate(w);
            class Play implements GameModule {
                long player, watcher, body, policy, person, car, sound, voice;
                int sees, actions, lastAction = -1, ragdollStates, ended;
                float carZ0 = 10.5f;
                @Override public void init(GameContext ctx) {
                    CommandBuffer c = ctx.commands();
                    player = ctx.reserve(AstrattoNative.KIND_MOVER);
                    c.moverCreate(player, 31.5f, 5, 46.5f, 0.3f, 1.8f, 0.6f, 0, 1.2f);
                    policy = ctx.reserve(AstrattoNative.KIND_POLICY);
                    c.policyCreate(policy, List.of(
                            new CommandBuffer.Action(1, AstrattoNative.ACT_IDLE).base(0.2f),
                            new CommandBuffer.Action(2, AstrattoNative.ACT_APPROACH).base(1).range(2).speed(3.5f).consider(AstrattoNative.IN_TARGET_SEEN, AstrattoNative.CURVE_STEP, 0, 1, 1)), 0.1f, 0.1f);
                    body = ctx.reserve(AstrattoNative.KIND_MOVER);
                    watcher = ctx.reserve(AstrattoNative.KIND_AGENT);
                    c.moverCreate(body, 31.5f, 5, 20.5f, 0.3f, 1.8f, 0.6f, AstrattoNative.MOVER_AGENT, 1.2f);
                    c.agentCreate(watcher, policy, body, 0, 0, 0, 1.6f, 50, -1, 30, 1, 0, 9);
                    c.agentTarget(watcher, player, 0, 0, 0);
                    person = ctx.reserve(AstrattoNative.KIND_RAGDOLL);
                    c.ragdollCreate(person, 1, AstrattoNative.SYDNEY_HUMAN, AstrattoNative.RAGDOLL_ACTIVE | AstrattoNative.RAGDOLL_STAND, 50.5f, 4, 20.5f, 0, 0, 0, 0);
                    car = ctx.reserve(AstrattoNative.KIND_VEHICLE);
                    float[] desc = {1400, 1.8f, 4.4f, 1.45f, 0, 0.5f, 0, 0, 4, 33, 8, 1.5f, 1.2f, 1, 0.82f, 0.5f, 34, 9, 0.62f, 1.4f, 0.28f, 0.28f, -0.16f, 0.5f, 0.3f, 0.34f, 0.55f};
                    float[] wheels = {-0.8f, 0.34f, 1.35f, 0.8f, 0.34f, 1.35f, -0.8f, 0.34f, -1.35f, 0.8f, 0.34f, -1.35f};
                    c.vehicleCreate(car, desc, wheels, 4, AstrattoNative.DRIVE_REAR, 0, null, 8.5f, 4.8f, carZ0, 0);
                    float[] tone = new float[22050];
                    for (int i = 0; i < tone.length; i++) tone[i] = (float) Math.sin(i * 0.06) * 0.4f;
                    sound = ctx.engine().createSound(tone, 1, 44100);
                    voice = ctx.reserve(AstrattoNative.KIND_VOICE);
                    c.listener(31.5f, 5.6f, 30, 0);
                    c.voicePlay(voice, sound, AstrattoNative.BUS_SFX, AstrattoNative.VOICE_POSITIONAL | AstrattoNative.VOICE_OCCLUDE, 1, 1, 0, 25, 5, 30, 30, 0);
                }
                final float[] mix = new float[2 * 800];
                @Override public void update(GameContext ctx, float dt) {
                    ctx.commands().vehicleInput(car, 0.6f, 0, 0, false, false, true);
                    if (ctx.ticks() == 120) ctx.commands().ragdollHit(person, AstrattoNative.RD_NECK, 1, 0, 0, 380, 70);
                    ctx.engine().renderAudio(mix, 735);
                }
                @Override public void agentSees(long a, long other) { if (a == watcher && other == player) sees++; }
                @Override public void agentAction(long a, int action, int previous) { if (a == watcher) { actions++; lastAction = action; } }
                @Override public void ragdollState(long r, int state, int previous, int outcome) { if (r == person) ragdollStates++; }
                @Override public void voiceEnded(long v) { if (v == voice) ended++; }
            }
            Play p = g.add(new Play());
            for (int i = 0; i < 600; i++) assertTrue(g.tick(1 / 60f), "tick " + i);
            Snapshots.Bodies b = g.bodies();
            Snapshots.Mover watcher = b.mover(p.body);
            assertTrue(b.mover(p.player).grounded, "the player's body stands");
            assertTrue(p.sees > 0 && p.actions >= 1 && p.lastAction == 2, "the watcher saw the player and chose to approach (" + p.sees + " sightings, " + p.actions + " decisions, last " + p.lastAction + ")");
            assertTrue(Math.hypot(watcher.x - 31.5, watcher.z - 46.5) < 4, "and walked through the gap in the wall to them: " + watcher.x + ", " + watcher.z);
            assertTrue(p.ragdollStates > 0, "Sydney's person was knocked over");
            assertTrue(b.vehicle(p.car).matrix[14] - p.carZ0 > 10, "the car drove");
            assertEquals(1, p.ended, "the sound played to its end in the mixer");
            assertTrue(g.engine().audioStats().framesMixed >= 600 * 735L);
        }
    }

    /** A unit box, 36 vertices of position, normal, colour (the layer vertex layout). */
    static final class View3 {
        static float[] box() {
            float[] v = new float[36 * 10];
            int[][] f = {{0, 1, 2}, {1, 2, 0}, {2, 0, 1}};
            int k = 0;
            for (int[] a : f) for (int s = -1; s <= 1; s += 2) {
                float[][] q = new float[4][3];
                int[][] c = {{0, 0}, {1, 0}, {1, 1}, {0, 1}};
                for (int i = 0; i < 4; i++) { q[i][a[0]] = s > 0 ? 0.5f : -0.5f; q[i][a[1]] = c[i][0] - 0.5f; q[i][a[2]] = c[i][1] - 0.5f; q[i][1] += 0.5f; }
                int[] order = s > 0 ? new int[]{0, 1, 2, 0, 2, 3} : new int[]{0, 2, 1, 0, 3, 2};
                for (int o : order) {
                    v[k++] = q[o][0]; v[k++] = q[o][1]; v[k++] = q[o][2];
                    v[k++] = a[0] == 0 ? s : 0; v[k++] = a[0] == 1 ? s : 0; v[k++] = a[0] == 2 ? s : 0;
                    v[k++] = 0.3f; v[k++] = 0.6f; v[k++] = 0.2f; v[k++] = 0.5f;
                }
            }
            return v;
        }
    }

    /** The native renderer draws an engine world it meshes itself into a real window's GL 4.5 context. */
    @Test void theNativeRendererDrawsAnEngineWorld() {
        // a build machine with no GPU says so (ASTRATTO_HEADLESS=1) and this one test is reported skipped, not passed;
        // anywhere else a missing OpenGL 4.5 context is a failure
        org.junit.jupiter.api.Assumptions.assumeFalse("1".equals(System.getenv("ASTRATTO_HEADLESS")), "ASTRATTO_HEADLESS=1: no GPU on this machine");
        Window win = new Window();
        win.show = false;
        win.create("astratto test", 640, 360);
        try {
            assertTrue(win.gl45, "an OpenGL 4.5 context: " + win.glInfo);
            try (NativeEngine e = Fixtures.engine()) {
                long w = Fixtures.yard(e);
                NativeRenderer r = NativeRenderer.create(e, win.width, win.height, AstrattoNative.QUALITY_MEDIUM, 1f);
                assertNotNull(r, NativeRenderer.createError);
                try {
                    int layers = 4, tex = 16;
                    ByteBuffer px = MemoryUtil.memAlloc(tex * tex * 4 * layers);
                    int[][] colour = {{0, 0, 0}, {120, 120, 125}, {180, 210, 230}, {255, 230, 170}};
                    for (int l = 0; l < layers; l++) for (int i = 0; i < tex * tex; i++) px.put((byte) colour[l][0]).put((byte) (colour[l][1] - (i % 7) * 3)).put((byte) colour[l][2]).put((byte) (l == 2 ? 90 : 255));
                    px.flip();
                    int atlas = r.texture(AstrattoNative.TEX_ARRAY, tex, tex, layers, AstrattoNative.TEX_NEAREST | AstrattoNative.TEX_REPEAT, px);
                    MemoryUtil.memFree(px);
                    ByteBuffer nz = MemoryUtil.memCalloc(64 * 64 * 4);
                    int noise = r.texture(AstrattoNative.TEX_2D, 64, 64, 1, AstrattoNative.TEX_REPEAT, nz);
                    MemoryUtil.memFree(nz);
                    NativeMesher.BlockDef[] rb = new NativeMesher.BlockDef[4];
                    for (int i = 0; i < 4; i++) { rb[i] = new NativeMesher.BlockDef(); rb[i].layerTop = rb[i].layerSide = rb[i].layerBottom = i; }
                    rb[1].flags = rb[3].flags = AstrattoNative.RB_SOLID | AstrattoNative.RB_OCCLUDES;
                    rb[2].flags = AstrattoNative.RB_SOLID | AstrattoNative.RB_TRANSLUCENT;
                    float[] palette = new float[256 * 8];
                    for (int i = 1; i < 4; i++) for (int k = 0; k < 3; k++) palette[i * 8 + k] = colour[i][k] / 255f;
                    palette[3 * 8 + 4] = 5; palette[3 * 8 + 5] = 4; palette[3 * 8 + 6] = 3;
                    assertTrue(r.worldLook(atlas, noise, layers, rb, new NativeMesher.ShapeDef[]{new NativeMesher.ShapeDef()}, Fixtures.STONE, palette, null, Fixtures.FLOOR, 0), r.lastError());
                    float[] cube = new float[36 * 10];
                    for (int i = 0; i < 36; i++) { cube[i * 10 + 3] = 1; cube[i * 10 + 6] = 1; }
                    int mesh = r.mesh(cube, 36);
                    assertTrue(mesh != 0, r.lastError());
                    // 2.1 layers: a tuft (foliage), a crate (prop), a mark on the floor (decal), each through the engine's own buffers and passes
                    float[] tri = new float[36 * 10];
                    float[] box = View3.box();
                    System.arraycopy(box, 0, tri, 0, box.length);
                    int tuft = r.layerMesh(AstrattoNative.LAYER_FOLIAGE, tri, 36), crate = r.layerMesh(AstrattoNative.LAYER_PROP, tri, 36);
                    float[] mark = {28, 4.01f, 30, 0, 0, 0, 1, 1, 1, 1, 29, 4.01f, 30, 1, 0, 0, 1, 1, 1, 1, 29, 4.01f, 31, 1, 1, 0, 1, 1, 1, 1,
                            28, 4.01f, 30, 0, 0, 0, 1, 1, 1, 1, 29, 4.01f, 31, 1, 1, 0, 1, 1, 1, 1, 28, 4.01f, 31, 0, 1, 0, 1, 1, 1, 1};
                    int floorMark = r.layerMesh(AstrattoNative.LAYER_DECAL, mark, 6);
                    assertTrue(tuft != 0 && crate != 0 && floorMark != 0, r.lastError());
                    java.nio.FloatBuffer inst = MemoryUtil.memAllocFloat(12 * 2);
                    inst.put(new float[]{26, 4, 30, 1, 0, 0.5f, 0, 1, 1, 0, 0, 0, 27, 4, 32, 1, 0, 0.5f, 0, 1, 1, 0, 0, 0}).flip();
                    assertTrue(r.instances(tuft, inst, 2, AstrattoNative.INSTANCES_SHADOW), r.lastError());
                    inst.clear(); inst.put(new float[]{30, 4, 30, 1, 0, 1, 0, 0}).flip();
                    assertTrue(r.instances(crate, inst, 1, AstrattoNative.INSTANCES_SHADOW), r.lastError());
                    MemoryUtil.memFree(inst);
                    ByteBuffer dpx = MemoryUtil.memAlloc(8 * 8 * 4);
                    for (int i = 0; i < 64; i++) dpx.put((byte) 40).put((byte) 30).put((byte) 20).put((byte) 200);
                    dpx.flip();
                    int decalTex = r.texture(AstrattoNative.TEX_ARRAY, 8, 8, 1, 0, dpx);
                    MemoryUtil.memFree(dpx);
                    RenderCommands cmd = new RenderCommands();
                    Mat4 view = new Mat4().lookAt(20, 9, 20, 32, 6, 40, 0, 1, 0), proj = new Mat4().perspective(1.2f, win.width / (float) win.height, 0.05f, 300);
                    NativeRenderer.Stats s = null;
                    for (int f = 0; f < 30; f++) {
                        cmd.world(w);
                        cmd.camera(view.m, proj.m, proj.m, 20, 9, 20, 0.5f, -0.1f, 0.85f, 1.2f, win.width / (float) win.height, 0.05f, 300, 200, 1 / 60f, f == 0, f < 5);
                        cmd.env(0.3f, 0.8f, 0.4f, 1, f / 60f, 0, 0, 0, 0, 0, 0.6f, 0.62f, 0.64f, 1 / 200f, 1e9f, 2e9f, 0, 0, 1, 1, 1, 0.0035f, Float.NaN, 0, 0, 0.05f);
                        cmd.pointLight(50, 8, 50, 8, 4, 3, 2, 0);
                        cmd.model(mesh, 0, new Mat4().translate(30, 5, 35).m, 1, 1, 1, 1, 0, 1, 0.5f, 0);
                        cmd.foliage(1, 0.3f, f / 60f, 0.2f, 40, 56, 0, new float[]{20, 20, 1, 4}, 1);
                        cmd.props(72, 40);
                        cmd.decals(decalTex, 0, 60, 84, 0, mark, 6);
                        s = r.frame(cmd, win.width, win.height);
                        assertNotNull(s, r.lastError());
                        win.swap();
                    }
                    assertTrue(s.columnsDrawn > 0 && s.quadsDrawn > 0, "the engine meshed and drew its own world: " + s);
                    ByteBuffer out = MemoryUtil.memAlloc(win.width * win.height * 4);
                    assertTrue(r.readPixels(0, 0, win.width, win.height, out), r.lastError());
                    Set<Integer> colours = new HashSet<>();
                    for (int i = 0; i < win.width * win.height; i += 31) colours.add(out.getInt(i * 4) & 0xF0F0F0);
                    MemoryUtil.memFree(out);
                    assertTrue(colours.size() > 20, "a lit picture, not a cleared screen: " + colours.size() + " colours");
                    assertTrue(r.release(AstrattoNative.RES_MESH, tuft) && r.release(AstrattoNative.RES_MESH, crate) && r.release(AstrattoNative.RES_MESH, floorMark), "layer meshes are released");
                    assertFalse(r.release(AstrattoNative.RES_MESH, tuft), "once");
                    cmd.free();
                } finally { r.close(); }
            }
        } finally { win.destroy(); }
    }
}
