package astratto.fx;

import astratto.render.ParticleBatch;

/**
 * The Astratto particle system (1.0). Every short-lived thing in the air belongs to it: flame, smoke, embers, sparks,
 * dust, debris, shockwave rings, muzzle flashes, rain, snow, splashes. Before 1.0 each effect of the game kept its own
 * list and its own rules (fire in one class, gun smoke in another, "effects" that fell through the floor in a third);
 * now there is one pool, one integrator and one set of rules, and an effect is a few lines that say what to emit.
 *
 * Design:
 * <ul>
 * <li><b>One pool, structure of arrays.</b> Nothing is allocated while the game runs. A particle is an index; dying is a
 *     swap with the last live one. When the pool is full the oldest cosmetic particles give way.</li>
 * <li><b>Behaviour is data.</b> A particle has a kind (how it is drawn) and flags (what it obeys): gravity, drag, wind,
 *     collision with the world, bouncing, coming to rest, being lit by the place it is in, casting light of its own.</li>
 * <li><b>Two ways of being drawn.</b> Sprites go to the renderer's particle pass ({@link #submit}); "chunks" are voxels -
 *     small lit cubes that tumble, land and lie - and are handed to whoever draws models ({@link #chunks}), so debris
 *     looks like the world it was knocked out of and takes shadow like everything else.</li>
 * <li><b>The world is an interface.</b> {@link Terrain} is all the system knows of it.</li>
 * </ul>
 */
public final class ParticleSystem {
    /** What the system needs to know of the world it is in. */
    public interface Terrain {
        boolean solid(int x, int y, int z);
        /** The y of the highest solid cell of a column (rain and snow stop there; a roof is whatever is above the camera). */
        int top(int x, int z);
        /** How much light falls at a point, 0..1 or a little over (smoke, dust and debris are lit by where they are). */
        float light(float x, float y, float z);
    }

    public interface ChunkSink { void chunk(float x, float y, float z, float size, float yaw, float pitch, int argb, float light, float glow); }
    public interface LightSink { void light(float x, float y, float z, float radius, float r, float g, float b); }

    // ---- sprite kinds (the particle shader's own numbers) and the voxel kind
    public static final int FLAME = 0, SMOKE = 1, EMBER = 2, GLOW = 3, RING = 4, STREAK = 5, FLASH = 6, DROP = 7, MOTE = 8, CHUNK = 16;
    // ---- what a particle obeys
    public static final int GRAVITY = 1, COLLIDE = 2, BOUNCE = 4, LIT = 8, LIGHT = 16, REST = 32, WIND = 64, SPLASH = 128, FADE_IN = 256, COOL = 512, TOP_ONLY = 1024;

    public final int capacity;
    private final float[] x, y, z, vx, vy, vz, age, life, size, grow, r, g, b, a, rot, spin, seed, drag, grav, lit;
    private final int[] kind, flags;
    private int count;
    private long frame;
    /** The wind: metres a second. Smoke, dust, rain and snow lean with it. */
    public float windX, windZ;
    /** 0..1: how much of what is asked for is made (the options menu's particle detail). Rain and snow have their own density. */
    public float detail = 1f;
    private final java.util.Random rnd = new java.util.Random(11);
    /** Counters, for gates and the debug line. */
    public long spawned, landed, dropped;

    public ParticleSystem() { this(24576); }

    public ParticleSystem(int capacity) {
        this.capacity = capacity;
        x = new float[capacity]; y = new float[capacity]; z = new float[capacity];
        vx = new float[capacity]; vy = new float[capacity]; vz = new float[capacity];
        age = new float[capacity]; life = new float[capacity]; size = new float[capacity]; grow = new float[capacity];
        r = new float[capacity]; g = new float[capacity]; b = new float[capacity]; a = new float[capacity];
        rot = new float[capacity]; spin = new float[capacity]; seed = new float[capacity]; drag = new float[capacity]; grav = new float[capacity]; lit = new float[capacity];
        kind = new int[capacity]; flags = new int[capacity];
    }

    public int count() { return count; }
    public void clear() { count = 0; }
    /** 1.1: takes everything the weather made out of the air at once - drops, flakes and their splashes. */
    public void clearPrecipitation() { for (int i = count - 1; i >= 0; i--) if ((flags[i] & TOP_ONLY) != 0) kill(i); }
    public int count(int ofKind) { int n = 0; for (int i = 0; i < count; i++) if (kind[i] == ofKind) n++; return n; }

    private float rf() { return rnd.nextFloat(); }
    private float rs() { return rnd.nextFloat() * 2 - 1; }

    /**
     * One particle. Colour may exceed 1 for anything that glows (it is radiance). Returns its index for the moment, or -1
     * when the pool had no room for something that was only decoration.
     */
    public int spawn(int kindOf, int flagsOf, float px, float py, float pz, float pvx, float pvy, float pvz, float lifeS, float sizeM, float growPerS, float cr, float cg, float cb, float ca) {
        int i;
        if (count < capacity) i = count++;
        else {
            // full: precipitation and motes give way to anything else; otherwise the request is dropped
            if (kindOf == DROP || kindOf == MOTE) { dropped++; return -1; }
            i = -1;
            for (int k = 0; k < 24; k++) { int c = rnd.nextInt(capacity); if (kind[c] == DROP || kind[c] == MOTE) { i = c; break; } }
            if (i < 0) { dropped++; return -1; }
        }
        kind[i] = kindOf; flags[i] = flagsOf;
        x[i] = px; y[i] = py; z[i] = pz; vx[i] = pvx; vy[i] = pvy; vz[i] = pvz;
        age[i] = 0; life[i] = Math.max(0.02f, lifeS); size[i] = sizeM; grow[i] = growPerS;
        r[i] = cr; g[i] = cg; b[i] = cb; a[i] = ca;
        rot[i] = rf() * 6.2832f; spin[i] = 0; seed[i] = rf(); drag[i] = 0; grav[i] = 9.81f; lit[i] = -1;
        spawned++;
        return i;
    }

    /** Adjusts the particle just made: air drag (1/s), gravity (m/s^2, negative to rise), spin (rad/s). */
    public void tune(int i, float dragPerS, float gravity, float spinPerS) { if (i >= 0) { drag[i] = dragPerS; grav[i] = gravity; spin[i] = spinPerS; } }

    private void kill(int i) {
        int l = --count;
        if (i == l) return;
        x[i] = x[l]; y[i] = y[l]; z[i] = z[l]; vx[i] = vx[l]; vy[i] = vy[l]; vz[i] = vz[l];
        age[i] = age[l]; life[i] = life[l]; size[i] = size[l]; grow[i] = grow[l];
        r[i] = r[l]; g[i] = g[l]; b[i] = b[l]; a[i] = a[l];
        rot[i] = rot[l]; spin[i] = spin[l]; seed[i] = seed[l]; drag[i] = drag[l]; grav[i] = grav[l]; lit[i] = lit[l];
        kind[i] = kind[l]; flags[i] = flags[l];
    }

    // ------------------------------------------------------------------ simulation
    public void update(float dt, Terrain t) {
        if (dt <= 0) return;
        dt = Math.min(dt, 0.05f);
        frame++;
        for (int i = count - 1; i >= 0; i--) {
            age[i] += dt;
            if (age[i] >= life[i]) { kill(i); continue; }
            int f = flags[i];
            if ((f & REST) != 0 && vx[i] == 0 && vy[i] == 0 && vz[i] == 0 && age[i] > 0.1f) continue;      // lying still
            if ((f & GRAVITY) != 0) vy[i] -= grav[i] * dt;
            if (drag[i] > 0) {
                float k = (float) Math.exp(-drag[i] * dt);
                float wx = (f & WIND) != 0 ? windX : 0, wz = (f & WIND) != 0 ? windZ : 0;
                // drag is toward the air's own motion: smoke is carried by the wind, not merely slowed
                vx[i] = wx + (vx[i] - wx) * k; vz[i] = wz + (vz[i] - wz) * k;
                if ((f & GRAVITY) == 0) vy[i] *= k;
            } else if ((f & WIND) != 0) { vx[i] += (windX - vx[i]) * Math.min(1, dt * 1.5f); vz[i] += (windZ - vz[i]) * Math.min(1, dt * 1.5f); }
            if (kind[i] == MOTE && (f & TOP_ONLY) != 0) {
                // a snowflake does not fall in a line: it slides from side to side on the air under it
                vx[i] += (float) Math.sin(age[i] * 2.3f + seed[i] * 40f) * 1.6f * dt; vz[i] += (float) Math.cos(age[i] * 1.9f + seed[i] * 31f) * 1.6f * dt;
            }
            float nx = x[i] + vx[i] * dt, ny = y[i] + vy[i] * dt, nz = z[i] + vz[i] * dt;
            if ((f & TOP_ONLY) != 0) {
                // precipitation: the only thing it can hit is the top of a column
                int top = t.top((int) Math.floor(nx), (int) Math.floor(nz));
                if (ny <= top + 1.02f) {
                    if ((f & SPLASH) != 0 && rf() < 0.35f * detail) splash(nx, top + 1.03f, nz);
                    landed++;
                    kill(i);
                    continue;
                }
            } else if ((f & COLLIDE) != 0) {
                float rad = kind[i] == CHUNK ? size[i] * 0.5f : 0.02f;
                int cy = (int) Math.floor(y[i]);
                if (t.solid((int) Math.floor(nx), cy, (int) Math.floor(z[i]))) { vx[i] = (f & BOUNCE) != 0 ? -vx[i] * 0.4f : 0; nx = x[i]; spin[i] *= 0.6f; }
                if (t.solid((int) Math.floor(nx), cy, (int) Math.floor(nz))) { vz[i] = (f & BOUNCE) != 0 ? -vz[i] * 0.4f : 0; nz = z[i]; spin[i] *= 0.6f; }
                if (vy[i] > 0 && t.solid((int) Math.floor(nx), (int) Math.floor(ny + rad), (int) Math.floor(nz))) { vy[i] = -vy[i] * 0.3f; ny = y[i]; }
                else if (vy[i] <= 0 && t.solid((int) Math.floor(nx), (int) Math.floor(ny - rad), (int) Math.floor(nz))) {
                    float floor = (float) Math.floor(ny - rad) + 1 + rad;
                    if ((f & BOUNCE) != 0 && vy[i] < -1.4f) {
                        vy[i] = -vy[i] * (kind[i] == CHUNK ? 0.28f : 0.42f); vx[i] *= 0.6f; vz[i] *= 0.6f; spin[i] *= 0.5f;
                        landed++;
                    } else if ((f & REST) != 0) { vx[i] = vy[i] = vz[i] = 0; spin[i] = 0; }
                    else if ((f & BOUNCE) != 0) { vy[i] = 0; float k = Math.max(0, 1 - dt * 8); vx[i] *= k; vz[i] *= k; spin[i] *= k; if (Math.abs(vx[i]) + Math.abs(vz[i]) < 0.06f && (f & REST) != 0) { vx[i] = vz[i] = 0; } }
                    else { kill(i); continue; }
                    ny = floor;
                }
            }
            x[i] = nx; y[i] = ny; z[i] = nz;
            size[i] = Math.max(0.001f, size[i] + grow[i] * dt);
            rot[i] += spin[i] * dt;
            if ((f & LIT) != 0 && (lit[i] < 0 || ((i + frame) & 15) == 0)) lit[i] = t.light(x[i], y[i], z[i]);
        }
    }

    // ------------------------------------------------------------------ drawing
    /**
     * This frame's sprites, into the renderer's particle pass. The camera's right and up vectors turn a streak along the
     * way it is going; anything further than maxDist from the camera is left out.
     */
    public void submit(ParticleBatch pb, float camX, float camY, float camZ, float rx, float ry, float rz, float ux, float uy, float uz, float maxDist) {
        float md2 = maxDist * maxDist;
        for (int i = 0; i < count; i++) {
            int k = kind[i];
            if (k == CHUNK) continue;
            float dx = x[i] - camX, dy = y[i] - camY, dz = z[i] - camZ;
            if (dx * dx + dy * dy + dz * dz > md2) continue;
            float t = age[i] / life[i];
            float cr = r[i], cg = g[i], cb = b[i], ca = a[i];
            int f = flags[i];
            if ((f & LIT) != 0) { float l = lit[i] < 0 ? 1 : lit[i]; cr *= l; cg *= l; cb *= l; }
            if ((f & FADE_IN) != 0) ca *= Math.min(1, t * 8);
            if ((f & COOL) != 0) { float c = 1 - t; cg *= 0.35f + 0.65f * c; cb *= c * c; }
            float rotation = rot[i], extra = seed[i];
            if (k == STREAK || k == DROP) {
                // the long axis lies along the velocity as the camera sees it; seed carries the width as a share of the length
                float vr = vx[i] * rx + vy[i] * ry + vz[i] * rz, vu = vx[i] * ux + vy[i] * uy + vz[i] * uz;
                rotation = (float) Math.atan2(-vr, vu);
                extra = k == STREAK ? 0.09f : 0.035f;
            }
            pb.add(x[i], y[i], z[i], size[i], cr, cg, cb, ca, rotation, k, t, extra);
        }
    }

    /** The voxels: debris, chips, anything that should look like a piece of the world. */
    public void chunks(ChunkSink sink, float camX, float camZ, float maxDist) {
        for (int i = 0; i < count; i++) {
            if (kind[i] != CHUNK) continue;
            if (Math.abs(x[i] - camX) > maxDist || Math.abs(z[i] - camZ) > maxDist) continue;
            float t = age[i] / life[i];
            float s = size[i] * (t > 0.85f ? (1 - t) / 0.15f : 1);          // it shrinks away at the end rather than blinking out
            int argb = 0xFF000000 | ((int) (Math.min(1, r[i]) * 255) << 16) | ((int) (Math.min(1, g[i]) * 255) << 8) | (int) (Math.min(1, b[i]) * 255);
            float glow = (flags[i] & LIGHT) != 0 ? Math.max(0, 1 - t * 1.5f) : 0;
            sink.chunk(x[i], y[i], z[i], s, rot[i], rot[i] * 0.7f + seed[i] * 6f, argb, lit[i] < 0 ? 1 : lit[i], glow);
        }
    }

    /** The few brightest things that cast light this frame (an explosion's fireball, a shower of sparks). */
    public void lights(LightSink sink, float camX, float camZ, int max) {
        int n = 0;
        for (int i = 0; i < count && n < max; i++) {
            if ((flags[i] & LIGHT) == 0) continue;
            if (Math.abs(x[i] - camX) > 70 || Math.abs(z[i] - camZ) > 70) continue;
            float t = age[i] / life[i], k = Math.max(0, 1 - t);
            if (k < 0.08f) continue;
            float m = kind[i] == CHUNK ? 9f : 1f;
            sink.light(x[i], y[i], z[i], kind[i] == GLOW ? size[i] * 5f + 3f : 5f, Math.min(24, r[i] * m) * k, Math.min(24, g[i] * m) * k, Math.min(24, b[i] * m) * k);
            n++;
        }
    }

    // ------------------------------------------------------------------ the library of effects
    private int n(float wanted) { float v = wanted * detail; int k = (int) v; return k + (rf() < v - k ? 1 : 0); }

    /** Sparks thrown off a surface with normal (nx, ny, nz): hot streaks that arc, bounce once or twice and cool. */
    public void sparks(float px, float py, float pz, float nx, float ny, float nz, float amount, float power) {
        int c = n(amount);
        for (int i = 0; i < c; i++) {
            float sp = power * (0.35f + rf() * 0.9f);
            float dx = nx * 0.9f + rs() * 0.75f, dy = ny * 0.9f + rs() * 0.75f + 0.25f, dz = nz * 0.9f + rs() * 0.75f;
            float l = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + 1e-4f;
            int p = spawn(STREAK, GRAVITY | COLLIDE | BOUNCE | COOL | (i == 0 ? LIGHT : 0), px + nx * 0.04f, py + ny * 0.04f, pz + nz * 0.04f, dx / l * sp, dy / l * sp, dz / l * sp,
                    0.25f + rf() * 0.55f, 0.07f + rf() * 0.07f, 0, 9f, 5.4f, 1.6f, 1f);
            tune(p, 0.6f, 11f, 0);
        }
        if (c > 0) spawn(GLOW, 0, px + nx * 0.06f, py + ny * 0.06f, pz + nz * 0.06f, 0, 0, 0, 0.07f, 0.16f + 0.02f * amount, 0, 7f, 4.6f, 1.6f, 0.9f);
    }

    /** Pieces knocked out of something: voxels of its own colour that fly, tumble, bounce and lie for a while. */
    public void debris(float px, float py, float pz, float nx, float ny, float nz, float amount, float power, int rgb, float sizeM) {
        int c = n(amount);
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        for (int i = 0; i < c; i++) {
            float sp = power * (0.3f + rf() * 0.8f), sh = 0.78f + rf() * 0.34f;
            int p = spawn(CHUNK, GRAVITY | COLLIDE | BOUNCE | REST | LIT, px + nx * 0.08f + rs() * 0.05f, py + ny * 0.08f + rs() * 0.05f, pz + nz * 0.08f + rs() * 0.05f,
                    (nx + rs() * 0.8f) * sp, (ny + rs() * 0.6f + 0.55f) * sp, (nz + rs() * 0.8f) * sp, 3.5f + rf() * 4.5f, sizeM * (0.5f + rf() * 0.9f), 0, cr * sh, cg * sh, cb * sh, 1);
            tune(p, 0.15f, 14f, rs() * 14f);
        }
    }

    /** Dust: a soft pale cloud that swells, drifts with the wind and thins. */
    public void dust(float px, float py, float pz, float nx, float ny, float nz, float amount, float sizeM, int rgb, float alpha) {
        int c = n(amount);
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        for (int i = 0; i < c; i++) {
            float sp = 0.4f + rf() * 1.3f;
            int p = spawn(SMOKE, LIT | WIND, px + rs() * sizeM * 0.4f, py + rs() * sizeM * 0.3f, pz + rs() * sizeM * 0.4f, (nx + rs() * 0.7f) * sp, (ny + rs() * 0.4f + 0.2f) * sp, (nz + rs() * 0.7f) * sp,
                    0.7f + rf() * 1.1f, sizeM * (0.6f + rf() * 0.7f), sizeM * 0.9f, cr, cg, cb, alpha);
            tune(p, 2.6f, -0.25f, rs() * 0.5f);
        }
    }

    /** Smoke that rises and spreads: one puff. */
    public int smoke(float px, float py, float pz, float pvx, float pvy, float pvz, float sizeM, float lifeS, float shade, float alpha) {
        int p = spawn(SMOKE, LIT | WIND, px, py, pz, pvx, pvy, pvz, lifeS, sizeM, 0.34f, shade, shade, shade * 1.03f, alpha);
        tune(p, 3.2f, -1.2f, rs() * 0.6f);
        return p;
    }

    /**
     * 1.1, a column of smoke from something that is still burning underneath: one puff of it. It leaves the ground small,
     * dark and fast, slows as it cools, swells to several metres across, pales, leans with the wind and thins out a long
     * way up. A column is a few of these a second for as long as the fire lasts ({@link #smokeColumn}).
     */
    public int plume(float px, float py, float pz, float strength) {
        // (looked at: the first version made each puff nearly black, half opaque and metres across within seconds - a dozen of
        // them overlapped into one solid black shape hanging over the fire. Smoke is thin: it is many pale grey veils, each
        // letting most of what is behind it through, and it is their number that makes the column dark at its foot.)
        float s = Math.max(0.3f, strength), shade = 0.17f + rf() * 0.12f;
        int p = spawn(SMOKE, LIT | WIND | FADE_IN, px + rs() * 0.22f * s, py + rf() * 0.3f, pz + rs() * 0.22f * s, rs() * 0.3f, 2.7f + rf() * 1.1f, rs() * 0.3f,
                10f + rf() * 5f, 0.34f * s * (0.8f + rf() * 0.5f), 0.20f + 0.10f * s, shade, shade, shade * 1.05f, 0.30f);
        // very little drag (it is carried up by its own heat for a long way) and a buoyancy that keeps it rising once its first speed has gone
        tune(p, 0.10f, -0.30f, rs() * 0.3f);
        return p;
    }

    /**
     * Everything a smoulder gives off in dt seconds, so that whoever owns one need only call this each frame with a
     * running remainder ({@code acc[0]}): the column itself, a glow at its foot, and now and then a spark.
     */
    public void smokeColumn(float px, float py, float pz, float strength, float dt, float[] acc, int accIndex) {
        acc[accIndex] += dt * 6.5f * strength * Math.max(0.35f, detail);
        while (acc[accIndex] >= 1) { acc[accIndex]--; if (plume(px, py + 0.15f, pz, strength) < 0) { acc[accIndex] = 0; break; } }
        spawn(GLOW, 0, px, py + 0.12f, pz, 0, 0, 0, Math.max(0.03f, dt * 1.3f), 0.55f * strength, 0, 1.5f, 0.52f, 0.12f, 0.35f);
        if (rf() < dt * 1.4f * detail) {
            int p = spawn(EMBER, GRAVITY | COLLIDE | WIND, px + rs() * 0.3f, py + 0.2f, pz + rs() * 0.3f, rs() * 0.5f, 1.2f + rf() * 1.8f, rs() * 0.5f, 0.9f + rf() * 1.3f, 0.02f + rf() * 0.02f, 0, 12f, 4.4f, 0.9f, 1f);
            tune(p, 0, 2.2f, 0);
        }
    }

    /** A ring of dust running out along the ground: the shock of a blast, or the breath of a muzzle held low. */
    public void shockwave(float px, float py, float pz, float radius, float seconds, int rgb, float alpha) {
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        int p = spawn(RING, LIT, px, py + 0.04f, pz, 0, 0, 0, seconds, radius * 0.18f, radius * 0.82f / Math.max(0.02f, seconds), cr, cg, cb, alpha);
        tune(p, 0, 0, 0);
    }

    /**
     * What leaves a muzzle besides the round: the flash (a star of flame, a hot core, a frame long), a spit of sparks and
     * unburnt powder on its line, the gas ring thrown off the crown, and - when the muzzle is near the ground - the dust
     * the blast lifts. power is about 1 for a rifle, 1.6 for a shotgun or a magnum, 0.6 for a pistol; a suppressor leaves
     * a wisp and nothing else.
     */
    public void muzzle(float px, float py, float pz, float dx, float dy, float dz, float power, boolean suppressed, float groundY) {
        if (suppressed) { for (int i = 0; i < n(2); i++) smoke(px, py, pz, dx * (0.8f + rf()), dy + 0.2f, dz * (0.8f + rf()), 0.05f, 0.5f + rf() * 0.4f, 0.22f, 0.14f); return; }
        spawn(FLASH, 0, px + dx * 0.12f * power, py + dy * 0.12f * power, pz + dz * 0.12f * power, 0, 0, 0, 0.055f, 0.24f * power, 0.6f, 9f, 5.6f, 2.0f, 0.95f);
        spawn(GLOW, 0, px, py, pz, 0, 0, 0, 0.07f, 0.34f * power, 0, 6f, 3.6f, 1.2f, 0.7f);
        int c = n(3 + 4 * power);
        for (int i = 0; i < c; i++) {
            float sp = (9f + rf() * 13f) * power;
            int p = spawn(STREAK, GRAVITY | COLLIDE | COOL, px, py, pz, (dx + rs() * 0.16f) * sp, (dy + rs() * 0.16f) * sp, (dz + rs() * 0.16f) * sp, 0.06f + rf() * 0.14f, 0.05f + rf() * 0.05f, 0, 8f, 4.6f, 1.3f, 1f);
            tune(p, 5f, 6f, 0);
        }
        // the gas ring: a thin pale annulus that opens round the bore and is gone in a tenth of a second
        int ring = spawn(MOTE, 0, px + dx * 0.2f, py + dy * 0.2f, pz + dz * 0.2f, dx * 2.5f, dy * 2.5f, dz * 2.5f, 0.12f, 0.10f * power, 2.2f * power, 0.55f, 0.55f, 0.56f, 0.10f * power);
        tune(ring, 4f, 0, 0);
        float above = py - groundY;
        if (above < 1.25f && above > -0.2f) {
            shockwave(px + dx * 0.5f, groundY, pz + dz * 0.5f, (1.5f - above) * 0.9f * power, 0.32f, 0xA39A8A, 0.20f * power * (1.25f - above));
            dust(px + dx * 0.6f, groundY + 0.08f, pz + dz * 0.6f, dx * 0.6f, 0.3f, dz * 0.6f, 2.5f * power * (1.25f - above), 0.22f, 0xA39A8A, 0.16f);
        }
    }

    /** An explosion: the fireball, the flash, the ring on the ground, sparks, a column of smoke and the dust it shakes loose. */
    public void explosion(float px, float py, float pz, float radius, float groundY) {
        float s = Math.max(0.6f, radius / 5f);
        int core = spawn(GLOW, LIGHT, px, py + 0.4f, pz, 0, 0.5f, 0, 0.2f, 0.8f * s, 1.5f * s, 11f, 6.5f, 2.2f, 0.85f);
        tune(core, 0, 0, 0);
        for (int i = 0; i < n(10 * s); i++) {
            float sp = 2f + rf() * 4.5f * s;
            float ax = rs(), ay = rf() * 0.9f + 0.1f, az = rs();
            int p = spawn(FLAME, 0, px + ax * 0.4f * s, py + ay * 0.5f, pz + az * 0.4f * s, ax * sp, ay * sp, az * sp, 0.3f + rf() * 0.4f, 0.55f * s * (0.6f + rf() * 0.8f), 1.3f * s, 1, 1, 1, 0.95f);
            tune(p, 3.5f, -2.5f, 0);
        }
        sparks(px, py + 0.3f, pz, 0, 1, 0, 26 * s, 9f + 4f * s);
        for (int i = 0; i < n(30 * s); i++) {
            float ang = rf() * 6.2832f, rr = rf() * 0.8f * s, up = 1.2f + rf() * 3.4f;
            int p = spawn(SMOKE, LIT | WIND | FADE_IN, px + (float) Math.cos(ang) * rr, py + 0.3f + rf() * 0.8f, pz + (float) Math.sin(ang) * rr, (float) Math.cos(ang) * (0.8f + rf() * 1.8f), up, (float) Math.sin(ang) * (0.8f + rf() * 1.8f),
                    2.2f + rf() * 3.2f, 0.4f * s * (0.7f + rf() * 0.6f), 0.5f * s, 0.17f + rf() * 0.08f, 0.16f + rf() * 0.07f, 0.15f + rf() * 0.07f, 0.34f);
            tune(p, 1.2f, -0.9f, rs() * 0.4f);
        }
        if (py - groundY < radius) {
            shockwave(px, groundY, pz, radius * 1.9f, 0.42f, 0xB8AE9C, 0.45f);
            shockwave(px, groundY, pz, radius * 1.1f, 0.6f, 0x8E8678, 0.3f);
            dust(px, groundY + 0.15f, pz, 0, 0.5f, 0, 14 * s, 0.7f * s, 0x9A9080, 0.3f);
        }
    }

    /** A drop landing: a tiny crown that opens flat on whatever it hit. */
    public void splash(float px, float py, float pz) {
        int p = spawn(RING, LIT, px, py, pz, 0, 0, 0, 0.16f + rf() * 0.1f, 0.03f, 0.75f, 0.72f, 0.76f, 0.82f, 0.32f);
        tune(p, 0, 0, 0);
    }

    /** A thin spray: blood off a wound, water off a tyre, bile. Small lit motes on a short arc. */
    public void spray(float px, float py, float pz, float dx, float dy, float dz, float amount, float power, int rgb, float sizeM) {
        int c = n(amount);
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        for (int i = 0; i < c; i++) {
            float sp = power * (0.3f + rf() * 0.9f);
            int p = spawn(MOTE, GRAVITY | COLLIDE | LIT, px, py, pz, (dx + rs() * 0.45f) * sp, (dy + rs() * 0.45f + 0.2f) * sp, (dz + rs() * 0.45f) * sp, 0.3f + rf() * 0.5f, sizeM * (0.6f + rf() * 0.8f), 0, cr, cg, cb, 0.9f);
            tune(p, 0.8f, 10f, 0);
        }
    }

    /**
     * 1.3, blood mist: what a wound puts into the air, apart from the drops that fall. A cloud of fine blood that leaves
     * the wound along (dx, dy, dz), slows in a few centimetres, hangs, sinks a little and thins - and the fine spray inside
     * it, which never reaches the floor as anything (it is not the blood that pools). How much, how far and how fine:
     * {@code severity} 0..1 (a graze to a round straight through), {@code spread} the size of the region it came from
     * (a head throws a wide pink cloud, a hand a small puff), {@code exit} for where a round came out (forward and
     * dense), and the colour of what bled.
     */
    public void bloodMist(float px, float py, float pz, float dx, float dy, float dz, float severity, float spread, boolean exit, int rgb) {
        severity = Math.max(0.05f, Math.min(1f, severity));
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        int clouds = n((exit ? 3 : 1.5f) + severity * (exit ? 7 : 3) * spread);
        float reach = (exit ? 2.6f : 1.1f) * (0.5f + severity);
        for (int i = 0; i < clouds; i++) {
            float sp = reach * (0.35f + rf() * 0.9f), size = (0.05f + 0.13f * severity) * spread * (0.6f + rf() * 0.8f);
            // the finest of it is lighter and pinker; the body of the cloud the colour of the blood
            float pale = rf() * 0.35f;
            int p = spawn(SMOKE, LIT, px + rs() * 0.04f, py + rs() * 0.04f, pz + rs() * 0.04f, (dx + rs() * 0.55f) * sp, (dy + rs() * 0.45f) * sp, (dz + rs() * 0.55f) * sp,
                    0.35f + rf() * 0.45f + severity * 0.5f, size, size * (1.6f + severity), cr + (1 - cr) * pale * 0.5f, cg + (0.55f - cg) * pale * 0.3f, cb + (0.6f - cb) * pale * 0.3f, 0.32f + 0.4f * severity);
            tune(p, 7.5f, 0.35f, rs() * 1.2f);
        }
        int specks = n((exit ? 10 : 4) + severity * 18 * spread);
        for (int i = 0; i < specks; i++) {
            float sp = reach * 1.4f * (0.4f + rf());
            int p = spawn(MOTE, LIT, px, py, pz, (dx + rs() * 0.5f) * sp, (dy + rs() * 0.5f + 0.1f) * sp, (dz + rs() * 0.5f) * sp, 0.12f + rf() * 0.25f, 0.012f + rf() * 0.02f, 0, cr * 0.8f, cg * 0.7f, cb * 0.7f, 0.85f);
            tune(p, 4.5f, 2.5f, 0);
        }
        mists++;
    }
    public long mists;

    /** One raindrop from the weather: a pale streak on the wind that ends on the first roof or road under it. */
    public void raindrop(float px, float py, float pz, float fall, float len, float alpha) {
        int p = spawn(DROP, TOP_ONLY | SPLASH | LIT, px, py, pz, windX * 0.9f, -fall, windZ * 0.9f, 2.4f, len, 0, 0.62f, 0.67f, 0.74f, alpha);
        if (p >= 0) lit[p] = 1;
    }

    /** One snowflake: slow, small, pushed about by the wind and its own flutter. */
    public void snowflake(float px, float py, float pz, float fall, float sizeM) {
        int p = spawn(MOTE, TOP_ONLY | WIND | LIT, px, py, pz, windX + rs() * 0.5f, -fall, windZ + rs() * 0.5f, 9f, sizeM, 0, 0.92f, 0.94f, 0.98f, 0.9f);
        if (p >= 0) { drag[p] = 0; lit[p] = 1; }
    }

    /** A mote hanging in the air - pollen, ash, dust in a shaft of light. */
    public void mote(float px, float py, float pz, float lifeS, float sizeM, int rgb, float alpha) {
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        int p = spawn(MOTE, WIND | LIT | FADE_IN, px, py, pz, rs() * 0.1f, rs() * 0.05f, rs() * 0.1f, lifeS, sizeM, 0, cr, cg, cb, alpha);
        tune(p, 0.4f, 0, 0);
    }

    /** A glowing voxel thrown out of something burning or bursting (the game's old "effects", now part of the system). */
    public void ember(float px, float py, float pz, float pvx, float pvy, float pvz, float lifeS, float sizeM, int rgb, boolean glow) {
        float cr = ((rgb >> 16) & 255) / 255f, cg = ((rgb >> 8) & 255) / 255f, cb = (rgb & 255) / 255f;
        int p = spawn(CHUNK, GRAVITY | COLLIDE | BOUNCE | REST | (glow ? LIGHT : LIT), px, py, pz, pvx, pvy, pvz, lifeS, sizeM, 0, cr, cg, cb, 1);
        tune(p, 0.2f, 9f, rs() * 9f);
    }

    /** Direct access for emitters that manage their own particles' look (fire). */
    public void setSeed(int i, float s) { if (i >= 0) seed[i] = s; }
    public void setRotation(int i, float rotation, float spinPerS) { if (i >= 0) { rot[i] = rotation; spin[i] = spinPerS; } }
}
