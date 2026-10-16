package astratto.bridge;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Astratto Engine: the engine's state read back, as plain Java values. A snapshot is the engine's memory only until the
 * next snapshot of its kind; these classes copy it out while it is valid, so what a caller holds is its own and never
 * changes under it (rule 3 of the boundary: state is seen as read-only snapshots).
 */
public final class Snapshots {
    private Snapshots() {}

    /** The engine's counters ({@code astratto_stats}). */
    public static final class Stats {
        public int workers, navVersion, sealedDoors, worldX, worldY, worldZ, worlds;
        public long frames, commands, blockEdits, pathRequests, pathExpansions, pathsFound, sightRequests, lightCells, simSteps;
        public float lastFrameMs, worstFrameMs, simMs;
        public double simTime;
        /** Things held, by kind ({@code KIND_*}). */
        public final int[] live = new int[AstrattoNative.KINDS];

        static Stats read(ByteBuffer b) {
            Stats s = new Stats();
            s.workers = b.getInt(4); s.frames = b.getLong(8); s.commands = b.getLong(16); s.blockEdits = b.getLong(24); s.pathRequests = b.getLong(32);
            s.pathExpansions = b.getLong(40); s.pathsFound = b.getLong(48); s.sightRequests = b.getLong(56); s.navVersion = b.getInt(64); s.sealedDoors = b.getInt(68);
            s.lastFrameMs = b.getFloat(72); s.worstFrameMs = b.getFloat(76); s.worldX = b.getInt(80); s.worldY = b.getInt(84); s.worldZ = b.getInt(88);
            s.worlds = b.getInt(92); s.lightCells = b.getLong(96); s.simSteps = b.getLong(104);
            for (int k = 0; k < AstrattoNative.KINDS; k++) s.live[k] = b.getInt(112 + k * 4);
            s.simMs = b.getFloat(156); s.simTime = b.getDouble(160);
            return s;
        }

        @Override public String toString() {
            return String.format("%d frames (%.2f s simulated), %d commands, %d block edits, %d routes (%d found, %d cells expanded), %d sight lines, nav v%d, %d sealed doors, last frame %.3f ms (worst %.3f) on %d workers",
                    frames, simTime, commands, blockEdits, pathRequests, pathsFound, pathExpansions, sightRequests, navVersion, sealedDoors, lastFrameMs, worstFrameMs, workers);
        }
    }

    /** The mixer's state ({@code astratto_audio_stats}). */
    public static final class AudioStats {
        public boolean device;
        public int voices, sounds, limited, underruns;
        public long framesMixed;
        public final float[] busPeak = new float[AstrattoNative.BUSES];
        public float outPeak, roomDecay, roomWet, listenerIndoor, mixMs;

        static AudioStats read(ByteBuffer b) {
            AudioStats s = new AudioStats();
            s.device = b.getInt(4) != 0; s.voices = b.getInt(8); s.sounds = b.getInt(12); s.framesMixed = b.getLong(16);
            for (int i = 0; i < AstrattoNative.BUSES; i++) s.busPeak[i] = b.getFloat(24 + i * 4);
            s.outPeak = b.getFloat(44); s.roomDecay = b.getFloat(48); s.roomWet = b.getFloat(52); s.listenerIndoor = b.getFloat(56);
            s.limited = b.getInt(60); s.underruns = b.getInt(64); s.mixMs = b.getFloat(68);
            return s;
        }
    }

    public static final class Mover {
        public long handle;
        public float x, y, z, vx, vy, vz;
        public boolean grounded, crouched;
    }

    public static final class Ragdoll {
        public long handle;
        public int state, outcome, grounded;
        public boolean settled, active;
        public float comX, comY, comZ, yaw, standX, standZ;
        /** Every particle's world position: 15 x {x, y, z} ({@code RD_*}). */
        public final float[] p = new float[AstrattoNative.RD_PARTICLES * 3];
    }

    public static final class SoftBody {
        public long handle;
        public boolean settled;
        public float cx, cy, cz;
        public float[] p;
    }

    public static final class Vehicle {
        public long handle;
        /** Car frame to world, column-major. */
        public final float[] matrix = new float[16];
        public float speed, forwardSpeed, steerAngle, engineBand;
        public int gear, groundedWheels;
        public boolean asleep;
        /** Per wheel: compression, rotation, steer angle, sliding (1/0). */
        public final float[] wheel = new float[16];
    }

    public static final class Agent {
        public long handle, target;
        public float x, y, z, yaw, tx, ty, tz;
        public int action, routeCells;
        public boolean sees, moving, stuck;
    }

    /** Every body the physics world holds ({@code ASTRATTO_SNAPSHOT_BODIES}). */
    public static final class Bodies {
        public final List<Mover> movers = new ArrayList<>();
        public final List<Ragdoll> ragdolls = new ArrayList<>();
        public final List<SoftBody> softBodies = new ArrayList<>();
        public final List<Vehicle> vehicles = new ArrayList<>();

        public Mover mover(long h) { for (Mover m : movers) if (m.handle == h) return m; return null; }
        public Ragdoll ragdoll(long h) { for (Ragdoll r : ragdolls) if (r.handle == h) return r; return null; }
        public SoftBody softBody(long h) { for (SoftBody s : softBodies) if (s.handle == h) return s; return null; }
        public Vehicle vehicle(long h) { for (Vehicle v : vehicles) if (v.handle == h) return v; return null; }

        static Bodies read(ByteBuffer b, int bytes) {
            Bodies out = new Bodies();
            int at = 0;
            while (at + 8 <= bytes) {
                int type = b.getInt(at), size = b.getInt(at + 4);
                if (size < 8 || at + size > bytes) break;
                switch (type) {
                    case AstrattoNative.SNAP_MOVER -> {
                        Mover m = new Mover();
                        m.handle = b.getLong(at + 8);
                        m.x = b.getFloat(at + 16); m.y = b.getFloat(at + 20); m.z = b.getFloat(at + 24);
                        m.vx = b.getFloat(at + 28); m.vy = b.getFloat(at + 32); m.vz = b.getFloat(at + 36);
                        m.grounded = b.getInt(at + 40) != 0; m.crouched = b.getInt(at + 44) != 0;
                        out.movers.add(m);
                    }
                    case AstrattoNative.SNAP_RAGDOLL -> {
                        Ragdoll r = new Ragdoll();
                        r.handle = b.getLong(at + 8);
                        r.state = b.getInt(at + 16); r.outcome = b.getInt(at + 20); r.settled = b.getInt(at + 24) != 0; r.active = b.getInt(at + 28) != 0;
                        r.comX = b.getFloat(at + 32); r.comY = b.getFloat(at + 36); r.comZ = b.getFloat(at + 40); r.yaw = b.getFloat(at + 44);
                        for (int i = 0; i < r.p.length; i++) r.p[i] = b.getFloat(at + 48 + i * 4);
                        r.grounded = b.getInt(at + 228); r.standX = b.getFloat(at + 232); r.standZ = b.getFloat(at + 236);
                        out.ragdolls.add(r);
                    }
                    case AstrattoNative.SNAP_SOFTBODY -> {
                        SoftBody s = new SoftBody();
                        s.handle = b.getLong(at + 8);
                        int n = b.getInt(at + 16);
                        s.settled = b.getInt(at + 20) != 0;
                        s.cx = b.getFloat(at + 24); s.cy = b.getFloat(at + 28); s.cz = b.getFloat(at + 32);
                        s.p = new float[Math.max(0, Math.min(n * 3, (size - 40) / 4))];
                        for (int i = 0; i < s.p.length; i++) s.p[i] = b.getFloat(at + 40 + i * 4);
                        out.softBodies.add(s);
                    }
                    case AstrattoNative.SNAP_VEHICLE -> {
                        Vehicle v = new Vehicle();
                        v.handle = b.getLong(at + 8);
                        for (int i = 0; i < 16; i++) v.matrix[i] = b.getFloat(at + 16 + i * 4);
                        v.speed = b.getFloat(at + 80); v.forwardSpeed = b.getFloat(at + 84); v.steerAngle = b.getFloat(at + 88); v.engineBand = b.getFloat(at + 92);
                        v.gear = b.getInt(at + 96); v.groundedWheels = b.getInt(at + 100); v.asleep = b.getInt(at + 104) != 0;
                        for (int i = 0; i < 16; i++) v.wheel[i] = b.getFloat(at + 112 + i * 4);
                        out.vehicles.add(v);
                    }
                    default -> { }   // a kind of body from a newer engine: stepped over by its own size
                }
                at += size;
            }
            return out;
        }
    }

    /** Every agent ({@code ASTRATTO_SNAPSHOT_AGENTS}). */
    public static final class Agents {
        public final List<Agent> agents = new ArrayList<>();

        public Agent agent(long h) { for (Agent a : agents) if (a.handle == h) return a; return null; }

        static Agents read(ByteBuffer b, int bytes) {
            Agents out = new Agents();
            int at = 0;
            while (at + 8 <= bytes) {
                int type = b.getInt(at), size = b.getInt(at + 4);
                if (size < 8 || at + size > bytes) break;
                if (type == AstrattoNative.SNAP_AGENT) {
                    Agent a = new Agent();
                    a.handle = b.getLong(at + 8); a.target = b.getLong(at + 16);
                    a.x = b.getFloat(at + 24); a.y = b.getFloat(at + 28); a.z = b.getFloat(at + 32); a.yaw = b.getFloat(at + 36);
                    a.tx = b.getFloat(at + 40); a.ty = b.getFloat(at + 44); a.tz = b.getFloat(at + 48);
                    a.action = b.getInt(at + 52); a.sees = b.getInt(at + 56) != 0; a.moving = b.getInt(at + 60) != 0; a.stuck = b.getInt(at + 64) != 0;
                    a.routeCells = b.getInt(at + 68);
                    out.agents.add(a);
                }
                at += size;
            }
            return out;
        }
    }
}
