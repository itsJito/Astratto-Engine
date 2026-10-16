package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * Astratto Engine: a tick's worth of commands for the native core, written here and handed over whole. Each record is
 * the C structure of the same name in {@code astratto/astratto.h}: a type, its own size, then its fields, at the
 * offsets {@code tools/layouts} prints. Nothing is sent when a method here is called - that is the point - the batch goes
 * across once, in {@link NativeEngine#frame} (or {@link NativeEngine#edit} for a buffer of world edits).
 *
 * Memory: the buffer is native memory owned by this object; the engine reads it during the call it is passed to and
 * never keeps it. {@link #free} gives it back.
 */
public final class CommandBuffer {
    private ByteBuffer buf;
    private int records;

    public CommandBuffer(int capacity) { buf = MemoryUtil.memAlloc(Math.max(256, capacity)).order(ByteOrder.nativeOrder()); }
    public CommandBuffer() { this(4096); }

    private void room(int bytes) {
        if (buf.remaining() >= bytes) return;
        int cap = Math.max(buf.capacity() * 2, buf.position() + bytes);
        buf = MemoryUtil.memRealloc(buf, cap).order(ByteOrder.nativeOrder());
    }

    /** Starts a record of a type and a whole size (a multiple of four), and returns this buffer positioned after its header. */
    public CommandBuffer head(int type, int bytes) {
        if ((bytes & 3) != 0 || bytes < 8) throw new IllegalArgumentException("a record is a multiple of four bytes, at least eight");
        room(bytes);
        buf.putInt(type).putInt(bytes);
        records++;
        return this;
    }
    public CommandBuffer i(int v) { buf.putInt(v); return this; }
    public CommandBuffer f(float v) { buf.putFloat(v); return this; }
    public CommandBuffer h(long handle) { buf.putLong(handle); return this; }
    public CommandBuffer bytes(byte[] b, int off, int len) { room(len); buf.put(b, off, len); return this; }
    /** Zero bytes up to a multiple of four (after a tail of bytes). */
    public CommandBuffer pad() { while ((buf.position() & 3) != 0) buf.put((byte) 0); return this; }

    /** A whole record already laid out (from another buffer or a file): appended as it is. */
    public CommandBuffer raw(ByteBuffer record) { room(record.remaining()); buf.put(record); records++; return this; }

    // ------------------------------------------------------------------ the world, and questions about it (1-31)
    public void blockSet(int x, int y, int z, int block) { head(AstrattoNative.CMD_BLOCK_SET, 24).i(x).i(y).i(z).i(block); }
    /** A cell set and nothing more: no relighting, no re-meshing, no nav version (generators). */
    public void blockSetRaw(int x, int y, int z, int block) { blockSet(x, y, z, (block & 0xFF) | AstrattoNative.SET_RAW); }
    public void fill(int x0, int y0, int z0, int x1, int y1, int z1, int block) { head(AstrattoNative.EDIT_FILL, 40).i(x0).i(y0).i(z0).i(x1).i(y1).i(z1).i(block).i(0); }
    /** One whole column of cells, set raw: sizeY bytes from {@code cells[offset]}. */
    public void column(int x, int z, byte[] cells, int offset, int sizeY) {
        int bytes = (16 + sizeY + 3) & ~3;
        head(AstrattoNative.EDIT_COLUMN, bytes).i(x).i(z).bytes(cells, offset, sizeY).pad();
    }
    public void lightAll() { head(AstrattoNative.EDIT_LIGHT_ALL, 8); }
    public void lightBox(int x0, int z0, int x1, int z1) { head(AstrattoNative.EDIT_LIGHT_BOX, 24).i(x0).i(z0).i(x1).i(z1); }
    public void lightFill(int value) { head(AstrattoNative.EDIT_LIGHT_FILL, 16).i(value).i(0); }
    public void lightSet(int x, int y, int z, int value) { head(AstrattoNative.EDIT_LIGHT_SET, 24).i(x).i(y).i(z).i(value); }
    public void pristineSave() { head(AstrattoNative.EDIT_PRISTINE_SAVE, 8); }
    public void pristineRestore() { head(AstrattoNative.EDIT_PRISTINE_RESTORE, 8); }
    public void markDirty(int x0, int z0, int x1, int z1) { head(AstrattoNative.EDIT_MARK_DIRTY, 24).i(x0).i(z0).i(x1).i(z1); }
    public void clearWorld() { head(AstrattoNative.EDIT_CLEAR, 8); }

    public void doorSeal(int x, int y, int z, boolean sealed) { head(AstrattoNative.CMD_DOOR_SEAL, 24).i(x).i(y).i(z).i(sealed ? 1 : 0); }
    /** Every door is unsealed: what a new set of doors says first. */
    public void doorsUnseal() { head(AstrattoNative.CMD_DOORS_UNSEAL, 8); }
    public void navTuning(float jumpCost, float ladderCost, float mantleCost) { head(AstrattoNative.CMD_NAV_TUNING, 24).f(jumpCost).f(ladderCost).f(mantleCost).i(0); }
    /** The world routes, sight, bodies and agents are in from this point of the frame (0 = none). */
    public void worldActivate(long world) { head(AstrattoNative.CMD_WORLD_ACTIVATE, 16).h(world); }
    /** Releases a thing at this point of the frame. */
    public void release(long handle) { head(AstrattoNative.CMD_RELEASE, 16).h(handle); }

    public void pathRequest(int request, int flags, float sx, float sy, float sz, float tx, float ty, float tz, int maxExpand) {
        head(AstrattoNative.CMD_PATH_REQUEST, 48).i(request).i(flags).f(sx).f(sy).f(sz).f(tx).f(ty).f(tz).i(maxExpand).i(0);
    }
    public void sightRequest(int request, int mode, float ax, float ay, float az, float bx, float by, float bz) {
        head(AstrattoNative.CMD_SIGHT_REQUEST, 40).i(request).i(mode).f(ax).f(ay).f(az).f(bx).f(by).f(bz);
    }
    /** A walkability question (kind {@code WALK_*}); the answer comes back as {@link EventSink#walk}. */
    public void walkQuery(int request, int kind, boolean doors, float radius, float ax, float ay, float az, float bx, float by, float bz) {
        head(AstrattoNative.CMD_WALK_QUERY, 48).i(request).i(kind).i(doors ? 1 : 0).f(radius).f(ax).f(ay).f(az).f(bx).f(by).f(bz);
    }
    public void rayQuery(int request, float maxDist, float ox, float oy, float oz, float dx, float dy, float dz) {
        head(AstrattoNative.CMD_RAY_QUERY, 40).i(request).f(maxDist).f(ox).f(oy).f(oz).f(dx).f(dy).f(dz);
    }

    // ------------------------------------------------------------------ physics (32-63)
    public void surface(int index, float adhesion, float rolling, float drag) { head(AstrattoNative.CMD_SURFACE, 24).i(index).f(adhesion).f(rolling).f(drag); }
    public void gravity(float gravity, float ragdollGravity) { head(AstrattoNative.CMD_GRAVITY, 16).f(gravity).f(ragdollGravity); }
    /** An upright body (a character controller) into a handle reserved beforehand ({@code KIND_MOVER}); (x, y, z) is between its feet. */
    public void moverCreate(long handle, float x, float y, float z, float radius, float height, float step, int flags, float crouchHeight) {
        head(AstrattoNative.CMD_MOVER_CREATE, 48).h(handle).f(x).f(y).f(z).f(radius).f(height).f(step).i(flags).f(crouchHeight);
    }
    /** How a mover wants to move this frame: velocity along the ground (and up, flying or climbing), a jump, how fast it gets there. */
    public void moverInput(long handle, float vx, float vz, float vy, float jump, float accel, int flags) {
        head(AstrattoNative.CMD_MOVER_INPUT, 40).h(handle).f(vx).f(vz).f(vy).f(jump).f(accel).i(flags);
    }
    public void moverPlace(long handle, float x, float y, float z, float vx, float vy, float vz) { place(AstrattoNative.CMD_MOVER_PLACE, handle, x, y, z, vx, vy, vz); }
    public void vehiclePlace(long handle, float x, float y, float z, float yaw) { place(AstrattoNative.CMD_VEHICLE_PLACE, handle, x, y, z, yaw, 0, 0); }
    private void place(int type, long handle, float x, float y, float z, float vx, float vy, float vz) { head(type, 40).h(handle).f(x).f(y).f(z).f(vx).f(vy).f(vz); }
    public void bodyImpulse(long handle, float x, float y, float z, float jx, float jy, float jz) { head(AstrattoNative.CMD_BODY_IMPULSE, 40).h(handle).f(x).f(y).f(z).f(jx).f(jy).f(jz); }

    /** A person's ragdoll (fifteen particles) with Sydney's controllers, into a handle reserved beforehand ({@code KIND_RAGDOLL}). */
    public void ragdollCreate(long handle, float scale, int profile, int flags, float x, float y, float z, float yaw, float vx, float vy, float vz) {
        head(AstrattoNative.CMD_RAGDOLL_CREATE, 56).h(handle).f(scale).i(profile).i(flags).f(x).f(y).f(z).f(yaw).f(vx).f(vy).f(vz);
    }
    /** The animated pose a body is handed to Sydney in: the body's velocity and every particle's world position (45 floats). */
    public void ragdollPose(long handle, float vx, float vy, float vz, float[] particles) {
        if (particles.length < AstrattoNative.RD_PARTICLES * 3) throw new IllegalArgumentException("a pose is fifteen particles");
        head(AstrattoNative.CMD_RAGDOLL_POSE, 208).h(handle).f(vx).f(vy).f(vz);
        for (int i = 0; i < AstrattoNative.RD_PARTICLES * 3; i++) buf.putFloat(particles[i]);
    }
    /** A blow, answered by Sydney's controllers: particle struck ({@code RD_*}), direction, impulse (N s), how hard it was felt. */
    public void ragdollHit(long handle, int particle, float dx, float dy, float dz, float impulse, float force) {
        head(AstrattoNative.CMD_RAGDOLL_HIT, 40).h(handle).i(particle).f(dx).f(dy).f(dz).f(impulse).f(force);
    }
    public void ragdollImpulse(long handle, int region, float vx, float vy, float vz) { head(AstrattoNative.CMD_RAGDOLL_IMPULSE, 32).h(handle).i(region).f(vx).f(vy).f(vz); }
    /** Die, collapse, stay down, release, limbs, wake ({@code CONTROL_*}). */
    public void ragdollControl(long handle, int control, int value) { head(AstrattoNative.CMD_RAGDOLL_CONTROL, 24).h(handle).i(control).i(value); }
    /** A blast or a boot near bodies at rest (handle 0: every ragdoll and soft body within radius). */
    public void disturb(long handle, float x, float y, float z, float radius, float vx, float vy, float vz) {
        head(AstrattoNative.CMD_RAGDOLL_DISTURB, 48).h(handle).f(x).f(y).f(z).f(radius).f(vx).f(vy).f(vz).i(0);
    }
    public void ragdollCarry(long handle, float vx, float vy, float vz, float share) { head(AstrattoNative.CMD_RAGDOLL_CARRY, 32).h(handle).f(vx).f(vy).f(vz).f(share); }
    /** Replaces one of Sydney's sixteen profiles: the 50 floats of {@code astratto_sydney_profile} in its order, then its 4 integers. */
    public void sydneyProfile(int index, float[] floats, int maxSteps, int getUpTries, int holdWound, int style) {
        if (floats.length != 50) throw new IllegalArgumentException("a Sydney profile is 50 floats then 4 integers");
        head(AstrattoNative.CMD_SYDNEY_PROFILE, 232).i(index).i(0);
        for (float v : floats) buf.putFloat(v);
        buf.putInt(maxSteps).putInt(getUpTries).putInt(holdWound).putInt(style);
    }
    /**
     * A soft body of any shape into a reserved handle ({@code KIND_SOFTBODY}): particles {x, y, z, radius, mass} each,
     * sticks {a, b} each, limits {a, b, min, max} each (min and max as shares of the present distance).
     */
    public void softbodyCreate(long handle, float[][] particles, int[][] sticks, float[][] limits, float slide, float bounce, float air, float vx, float vy, float vz) {
        int np = particles.length, ns = sticks == null ? 0 : sticks.length, nl = limits == null ? 0 : limits.length;
        head(AstrattoNative.CMD_SOFTBODY_CREATE, 56 + np * 20 + ns * 8 + nl * 16).h(handle).i(np).i(ns).i(nl).f(slide).f(bounce).f(air).f(vx).f(vy).f(vz).i(0);
        for (float[] p : particles) buf.putFloat(p[0]).putFloat(p[1]).putFloat(p[2]).putFloat(p[3]).putFloat(p[4]);
        for (int k = 0; k < ns; k++) buf.putInt(sticks[k][0]).putInt(sticks[k][1]);
        for (int k = 0; k < nl; k++) buf.putInt((int) limits[k][0]).putInt((int) limits[k][1]).putFloat(limits[k][2]).putFloat(limits[k][3]);
    }
    public void softbodyImpulse(long handle, int particle, float x, float y, float z, float reach, float dvx, float dvy, float dvz) {
        head(AstrattoNative.CMD_SOFTBODY_IMPULSE, 48).h(handle).i(particle).f(x).f(y).f(z).f(reach).f(dvx).f(dvy).f(dvz);
    }
    /**
     * A car into a reserved handle ({@code KIND_VEHICLE}). {@code desc} is the 27 floats of {@code astratto_vehicle_desc}
     * before its wheels (mass .. handbrake_tyre), {@code wheels} its four attachment points (12 floats), then
     * gears, drive, rear-first; {@code boxes} its collision boxes {cx, cy, cz, hx, hy, hz} in the car's frame.
     */
    public void vehicleCreate(long handle, float[] desc, float[] wheels, int gears, int drive, int rearFirst, float[][] boxes, float x, float y, float z, float yaw) {
        if (desc.length != 27 || wheels.length != 12) throw new IllegalArgumentException("a vehicle is 27 handling numbers and 12 wheel coordinates");
        int nb = boxes == null ? 0 : boxes.length;
        head(AstrattoNative.CMD_VEHICLE_CREATE, 208 + nb * 24).h(handle);
        for (float v : desc) buf.putFloat(v);
        for (float v : wheels) buf.putFloat(v);
        buf.putInt(gears).putInt(drive).putInt(rearFirst).putInt(nb);
        buf.putFloat(x).putFloat(y).putFloat(z).putFloat(yaw).putInt(0);
        for (int k = 0; k < nb; k++) for (int j = 0; j < 6; j++) buf.putFloat(boxes[k][j]);
    }
    public void vehicleInput(long handle, float throttle, float brake, float steer, boolean handbrake, boolean reverse, boolean engineOn) {
        head(AstrattoNative.CMD_VEHICLE_INPUT, 40).h(handle).f(throttle).f(brake).f(steer).i(handbrake ? 1 : 0).i(reverse ? 1 : 0).i(engineOn ? 1 : 0);
    }

    // ------------------------------------------------------------------ AI (64-95)
    /** One action of a policy: the game's id for it, its kind ({@code ACT_*}), base score, range, speed, and its considerations. */
    public static final class Action {
        public int id, kind;
        public float base = 0.5f, range = 2, speed = 3;
        public final java.util.List<float[]> considerations = new java.util.ArrayList<>();
        public Action(int id, int kind) { this.id = id; this.kind = kind; }
        public Action base(float b) { base = b; return this; }
        public Action range(float r) { range = r; return this; }
        public Action speed(float s) { speed = s; return this; }
        /** Scored by an input ({@code IN_*}) through a curve ({@code CURVE_*}) between lo and hi, weighted (0 ignores it, 1 is all of it). */
        public Action consider(int input, int curve, float lo, float hi, float weight) { considerations.add(new float[]{input, curve, lo, hi, weight}); return this; }
    }

    /** A policy (a mind: actions and how they are scored) into a reserved handle ({@code KIND_POLICY}). */
    public void policyCreate(long handle, java.util.List<Action> actions, float inertia, float think) {
        int nc = 0;
        for (Action a : actions) nc += a.considerations.size();
        head(AstrattoNative.CMD_POLICY_CREATE, 32 + actions.size() * 28 + nc * 24).h(handle).i(actions.size()).i(nc).f(inertia).f(think);
        int first = 0;
        for (Action a : actions) {
            buf.putInt(a.id).putInt(a.kind).putFloat(a.base).putFloat(a.range).putFloat(a.speed).putInt(first).putInt(a.considerations.size());
            first += a.considerations.size();
        }
        for (Action a : actions) for (float[] c : a.considerations) buf.putInt((int) c[0]).putInt((int) c[1]).putFloat(c[2]).putFloat(c[3]).putFloat(c[4]).putFloat(0);
    }
    /** An agent into a reserved handle ({@code KIND_AGENT}): its policy, the mover it walks (0: it moves itself, from (x, y, z)), its senses, team and flags. */
    public void agentCreate(long handle, long policy, long body, float x, float y, float z, float eye, float viewRange, float viewCos, float hearRange, int team, int flags, int seed) {
        head(AstrattoNative.CMD_AGENT_CREATE, 72).h(handle).h(policy).h(body).f(x).f(y).f(z).f(eye).f(viewRange).f(viewCos).f(hearRange).i(team).i(flags).i(seed);
    }
    public void agentFact(long handle, int index, float value) { head(AstrattoNative.CMD_AGENT_FACT, 24).h(handle).i(index).f(value); }
    /** What an agent is after: a body or agent (target), or with target 0, a place. */
    public void agentTarget(long handle, long target, float x, float y, float z) { head(AstrattoNative.CMD_AGENT_TARGET, 40).h(handle).h(target).f(x).f(y).f(z).i(0); }
    public void agentGoal(long handle, float x, float y, float z) { head(AstrattoNative.CMD_AGENT_GOAL, 32).h(handle).f(x).f(y).f(z).i(0); }
    public void agentPolicy(long handle, long policy) { head(AstrattoNative.CMD_AGENT_POLICY, 24).h(handle).h(policy); }
    /** This action (by its index in the policy), now, whatever the scores say; -1 hands the choice back. */
    public void agentForce(long handle, int action) { head(AstrattoNative.CMD_AGENT_FORCE, 24).h(handle).i(action).i(0); }
    /** Something was heard: where, how far it carries, how loud, and the team that made it (agents do not react to their own). */
    public void noise(float x, float y, float z, float radius, float loudness, int team) { head(AstrattoNative.CMD_NOISE, 32).f(x).f(y).f(z).f(radius).f(loudness).i(team); }

    // ------------------------------------------------------------------ audio (96-127)
    public void listener(float x, float y, float z, float yaw) { head(AstrattoNative.CMD_LISTENER, 24).f(x).f(y).f(z).f(yaw); }
    /** Plays a sound on a voice reserved beforehand ({@code KIND_VOICE}): bus, flags ({@code VOICE_*}), gain, pitch, pan, where, range, fade-in. */
    public void voicePlay(long voice, long sound, int bus, int flags, float gain, float pitch, float pan, float x, float y, float z, float range, float fadeIn) {
        head(AstrattoNative.CMD_VOICE_PLAY, 72).h(voice).h(sound).i(bus).i(flags).f(gain).f(pitch).f(pan).f(x).f(y).f(z).f(range).f(fadeIn).i(0).i(0);
    }
    /** Changes a playing voice: what ({@code SET_*}), and the new values. */
    public void voiceSet(long voice, int what, float gain, float pitch, float x, float y, float z, float fadeTo, float fadeTime) {
        head(AstrattoNative.CMD_VOICE_SET, 48).h(voice).i(what).f(gain).f(pitch).f(x).f(y).f(z).f(fadeTo).f(fadeTime);
    }
    public void voiceStop(long voice, float fade) { head(AstrattoNative.CMD_VOICE_STOP, 24).h(voice).f(fade).i(0); }
    /** Master and bus levels (five buses: sfx, music, voice, ambience, ui); muted bits a bus. */
    public void audioMix(float master, boolean muted, float[] bus, int busMuted) {
        head(AstrattoNative.CMD_AUDIO_MIX, 40).f(master).i(muted ? 1 : 0);
        for (int i = 0; i < AstrattoNative.BUSES; i++) buf.putFloat(bus[i]);
        buf.putInt(busMuted);
    }

    // ------------------------------------------------------------------ the buffer
    public int bytes() { return buf.position(); }
    public int records() { return records; }
    public boolean isEmpty() { return buf.position() == 0; }
    long address() { return MemoryUtil.memAddress0(buf); }
    /** The records written so far, read-only (for tests and tools). */
    public ByteBuffer view() { ByteBuffer d = buf.duplicate().order(ByteOrder.nativeOrder()); d.flip(); return d.asReadOnlyBuffer().order(ByteOrder.nativeOrder()); }
    public void clear() { buf.clear(); records = 0; }
    public void free() { if (buf != null) { MemoryUtil.memFree(buf); buf = null; } }
}
