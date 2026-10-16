package astratto.bridge;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * Astratto Engine: the Java side of the bridge to the native core ({@code native/}, C++). This class is the binding and
 * nothing else - it finds the library, checks that it speaks the version of the interface these bindings were written
 * for and that every structure is laid out as they expect, and calls the C functions of {@code astratto/astratto.h}.
 * Every function takes pointers and plain numbers and returns a status, so LWJGL's foreign-call layer covers the lot and
 * nothing here is generated; the native core has no JNI code in it. (Two calls are for C and C++ callers only and are not
 * bound here: astratto_world_set - Java sets cells by batched edit records - and astratto_mesh_world_column.)
 *
 * Nothing in the {@code astratto} packages knows of any game. What a game hands across is written with
 * {@link CommandBuffer} and comes back through {@link NativeEngine} ({@link EventSink}, snapshots, world views).
 *
 * The constants below are the C header's, by hand. When the library loads, the size of every structure the bindings
 * write or read is asked of the library itself ({@code astratto_layout}) and compared with {@link #LAYOUTS}: a library
 * built from a different header is refused rather than misread. {@code tools/layouts} prints every field offset.
 */
public final class AstrattoNative {
    private AstrattoNative() {}

    public static final int API_MAJOR = 2, API_MINOR = 1, API_VERSION = API_MAJOR << 16 | API_MINOR;

    // ---- status
    public static final int OK = 0, ERR_ARGUMENT = -1, ERR_VERSION = -2, ERR_STATE = -3, ERR_MEMORY = -4, ERR_STREAM = -5, ERR_INTERNAL = -6,
            ERR_HANDLE = -7, ERR_UNSUPPORTED = -8;

    // ---- handles: the kind is the top eight bits
    public static final int KIND_WORLD = 1, KIND_RAGDOLL = 2, KIND_SOFTBODY = 3, KIND_MOVER = 4, KIND_VEHICLE = 5, KIND_AGENT = 6, KIND_SOUND = 7,
            KIND_VOICE = 8, KIND_POLICY = 9, KIND_MODULE = 10, KINDS = 11;
    public static int kindOf(long handle) { return (int) (handle >>> 56); }

    public static final int CONFIG_AUDIO_DEVICE = 1, CONFIG_DETERMINISTIC = 1 << 1;

    // ---- the world
    public static final int BLOCK_SOLID = 1, BLOCK_OPAQUE = 1 << 1, BLOCK_CUTOUT = 1 << 2, BLOCK_CLIMB = 1 << 3, BLOCK_STEP = 1 << 4,
            BLOCK_LIQUID = 1 << 5, BLOCK_HAZARD = 1 << 6, BLOCK_DOOR = 1 << 7, BLOCK_SKY_DIM = 1 << 8, BLOCK_LIGHT_PASS = 1 << 9, BLOCK_NO_COLLIDE_AI = 1 << 10;
    public static final int MATERIAL_OTHER = 0, MATERIAL_GLASS = 1, MATERIAL_FOLIAGE = 2;
    public static final int WORLD_LIGHT = 1;
    public static final int EDIT_BLOCK = 1, EDIT_FILL = 5, EDIT_LIGHT_ALL = 6, EDIT_LIGHT_BOX = 7, EDIT_LIGHT_FILL = 8, EDIT_PRISTINE_SAVE = 9,
            EDIT_PRISTINE_RESTORE = 10, EDIT_MARK_DIRTY = 11, EDIT_LIGHT_SET = 12, EDIT_CLEAR = 13, EDIT_COLUMN = 19;
    public static final int SET_RAW = 1 << 8;

    // ---- commands and events: world and questions (1-31)
    public static final int CMD_BLOCK_SET = 1, CMD_DOOR_SEAL = 2, CMD_NAV_TUNING = 3, CMD_DOORS_UNSEAL = 4, CMD_WORLD_ACTIVATE = 14, CMD_RELEASE = 15,
            CMD_PATH_REQUEST = 16, CMD_SIGHT_REQUEST = 17, CMD_WALK_QUERY = 18, CMD_RAY_QUERY = 20;
    public static final int EVT_ERROR = 1, EVT_PATH_RESULT = 16, EVT_SIGHT_RESULT = 17, EVT_WALK_RESULT = 18, EVT_RAY_RESULT = 20;
    public static final int PATH_DOORS = 1, PATH_GOAL_Y = 1 << 1, PATH_MANTLE = 1 << 2;
    public static final int SIGHT_LIGHT = 0, SIGHT_EYE = 1;
    public static final int WALK_CLEAR = 0, WALK_CLEAR_BODY = 1, WALK_STAND = 2, WALK_STEP_Y = 3;
    public static final int PATH_FOUND = 0, PATH_NONE = 1;

    // ---- physics (32-63)
    public static final int CMD_SURFACE = 32, CMD_GRAVITY = 33, CMD_MOVER_CREATE = 34, CMD_MOVER_INPUT = 35, CMD_MOVER_PLACE = 36, CMD_RAGDOLL_CREATE = 37,
            CMD_RAGDOLL_POSE = 38, CMD_RAGDOLL_HIT = 39, CMD_RAGDOLL_IMPULSE = 40, CMD_RAGDOLL_CONTROL = 41, CMD_RAGDOLL_DISTURB = 42, CMD_RAGDOLL_CARRY = 43,
            CMD_SYDNEY_PROFILE = 44, CMD_SOFTBODY_CREATE = 45, CMD_SOFTBODY_IMPULSE = 46, CMD_VEHICLE_CREATE = 47, CMD_VEHICLE_INPUT = 48, CMD_VEHICLE_PLACE = 49,
            CMD_BODY_IMPULSE = 50;
    public static final int EVT_LANDED = 32, EVT_RAGDOLL_STATE = 33, EVT_SETTLED = 34, EVT_VEHICLE_IMPACT = 35, EVT_GEAR = 36, EVT_BLOCK_HIT = 37;
    public static final int MOVER_FLY = 1, MOVER_CROUCH = 1 << 1, MOVER_AGENT = 1 << 2;
    public static final int RD_HEAD = 0, RD_NECK = 1, RD_PELVIS = 2, RD_L_SHOULDER = 3, RD_L_ELBOW = 4, RD_L_WRIST = 5, RD_R_SHOULDER = 6, RD_R_ELBOW = 7,
            RD_R_WRIST = 8, RD_L_HIP = 9, RD_L_KNEE = 10, RD_L_ANKLE = 11, RD_R_HIP = 12, RD_R_KNEE = 13, RD_R_ANKLE = 14, RD_PARTICLES = 15;
    public static final int SYDNEY_HUMAN = 0, SYDNEY_INFECTED = 1, SYDNEY_CRAWLER = 2, SYDNEY_GIANT = 3;
    public static final int RAGDOLL_ACTIVE = 1, RAGDOLL_STAND = 1 << 1;
    public static final int REGION_TORSO = 0, REGION_HEAD = 1, REGION_ARMS = 2, REGION_LEGS = 3;
    public static final int CONTROL_DIE = 1, CONTROL_COLLAPSE = 2, CONTROL_STAY_DOWN = 3, CONTROL_RELEASE = 4, CONTROL_LIMBS = 5, CONTROL_WAKE = 6;
    public static final int STATE_STAND = 0, STATE_STEP = 1, STATE_FALL = 2, STATE_DOWN = 3, STATE_GETUP = 4, STATE_DEAD = 5;
    public static final int OUTCOME_NONE = 0, OUTCOME_RECOVERED = 1, OUTCOME_CRAWL = 2, OUTCOME_STUCK = 3, OUTCOME_DEAD = 4;
    public static final int DRIVE_REAR = 0, DRIVE_FRONT = 1, DRIVE_FOUR = 2;

    // ---- AI (64-95)
    public static final int CMD_POLICY_CREATE = 64, CMD_AGENT_CREATE = 65, CMD_AGENT_FACT = 66, CMD_AGENT_TARGET = 67, CMD_AGENT_GOAL = 68, CMD_AGENT_POLICY = 69,
            CMD_NOISE = 70, CMD_AGENT_FORCE = 71;
    public static final int EVT_AGENT_ACTION = 64, EVT_AGENT_SEES = 65, EVT_AGENT_LOST = 66, EVT_AGENT_ARRIVED = 67, EVT_AGENT_STUCK = 68, EVT_AGENT_HEARD = 69;
    public static final int ACT_IDLE = 0, ACT_WANDER = 1, ACT_MOVE_TO_GOAL = 2, ACT_APPROACH = 3, ACT_FLEE = 4, ACT_INVESTIGATE = 5, ACT_HOLD = 6, ACT_CUSTOM = 7;
    public static final int IN_FACT0 = 0, IN_TARGET_SEEN = 16, IN_TARGET_DISTANCE = 17, IN_SINCE_SEEN = 18, IN_SINCE_HEARD = 19, IN_TIME_IN_ACTION = 20,
            IN_RANDOM = 21, IN_GOAL_DISTANCE = 22, IN_HAS_TARGET = 23, IN_COUNT = 24;
    public static final int CURVE_LINEAR = 0, CURVE_INVERSE = 1, CURVE_QUADRATIC = 2, CURVE_LOGISTIC = 3, CURVE_STEP = 4;
    public static final int AGENT_DOORS = 1, AGENT_MANTLE = 1 << 1, AGENT_BLIND = 1 << 2;

    // ---- audio (96-127)
    public static final int BUS_SFX = 0, BUS_MUSIC = 1, BUS_VOICE = 2, BUS_AMBIENCE = 3, BUS_UI = 4, BUSES = 5;
    public static final int CMD_LISTENER = 96, CMD_VOICE_PLAY = 97, CMD_VOICE_SET = 98, CMD_VOICE_STOP = 99, CMD_AUDIO_MIX = 100;
    public static final int EVT_VOICE_ENDED = 96;
    public static final int VOICE_LOOP = 1, VOICE_POSITIONAL = 1 << 1, VOICE_OCCLUDE = 1 << 2, VOICE_REVERB = 1 << 3;
    public static final int SET_GAIN = 1, SET_PITCH = 1 << 1, SET_POSITION = 1 << 2, SET_FADE = 1 << 3;

    // ---- modules (128-159)
    public static final int EVT_MODULE_LOG = 128;

    // ---- snapshots
    public static final int SNAPSHOT_STATS = 1, SNAPSHOT_BODIES = 2, SNAPSHOT_AGENTS = 3, SNAPSHOT_AUDIO = 4;
    public static final int SNAP_MOVER = 1, SNAP_RAGDOLL = 2, SNAP_SOFTBODY = 3, SNAP_VEHICLE = 4, SNAP_AGENT = 5;

    // ---- rendering: the mesher (1.1)
    public static final int RB_CROSS = 1, RB_TRANSLUCENT = 1 << 1, RB_LIQUID = 1 << 2, RB_CUTOUT = 1 << 3, RB_EMISSIVE = 1 << 4, RB_LEAF = 1 << 5, RB_SWAY = 1 << 6,
            RB_OCCLUDES = 1 << 7, RB_DOOR = 1 << 8, RB_SOLID = 1 << 9, RB_STEP = 1 << 10, RB_SEAT = 1 << 11, RB_PLANT = 1 << 12;
    public static final int ORIENT_NONE = 0, ORIENT_BACKING = 1, ORIENT_DOOR = 2, ORIENT_JOIN = 3, ORIENT_CHAIR = 4, ORIENT_PEW = 5, ORIENT_SCATTER = 6, ORIENT_STAIR = 7;
    public static final int MESH_DOORS = 1, MESH_NO_PLANTS = 1 << 1;

    // ---- rendering: the renderer (2.0)
    public static final int QUALITY_LOW = 0, QUALITY_MEDIUM = 1, QUALITY_HIGH = 2, QUALITY_ULTRA = 3;
    public static final int TEX_2D = 0, TEX_ARRAY = 1;
    public static final int TEX_NEAREST = 1, TEX_MIPMAP = 1 << 1, TEX_REPEAT = 1 << 2, TEX_MIP_LINEAR = 1 << 3, TEX_ANISOTROPIC = 1 << 4;
    /** Stops a texture's mip chain at level n (OR it into the flags). */
    public static int texMaxLevel(int n) { return ((n + 1) & 15) << 8; }
    public static final int RES_TEXTURE = 1, RES_MESH = 2;
    public static final int RCMD_CAMERA = 1, RCMD_ENV = 2, RCMD_LIGHT = 3, RCMD_MODEL = 4, RCMD_PARTICLES = 5, RCMD_QUADS = 6, RCMD_GRADING = 7,
            RCMD_SHADOW_BODY = 8, RCMD_WORLD = 9, RCMD_CLEAR = 10, RCMD_QUALITY = 11, RCMD_FOLIAGE = 12, RCMD_PROPS = 13, RCMD_DECALS = 14;
    /** 2.1: the renderer's layers. */
    public static final int LAYER_FOLIAGE = 1, LAYER_PROP = 2, LAYER_DECAL = 3, INSTANCES_SHADOW = 1;
    public static final int PASS_GTAO = 1, PASS_SSR = 1 << 1, PASS_VOLUMETRICS = 1 << 2, PASS_TAA = 1 << 3, PASS_BLOOM = 1 << 4;
    public static final int LIGHT_SHADOW = 1, LIGHT_SPOT = 2, LIGHT_STATIC = 4;
    public static final int MODEL_SHADOW_ONLY = 1, MODEL_NO_SHADOW = 2, MODEL_FORWARD = 4, MODEL_ADDITIVE = 8, MODEL_VIEWMODEL = 16;

    // ---- structure sizes (bytes) on a 64-bit machine; every one is checked against the library when it loads
    static final int SIZEOF_CONFIG = 24, SIZEOF_BLOCK_DEF = 12, SIZEOF_BLOCK_TABLE = 16, SIZEOF_WORLD_DESC = 56, SIZEOF_WORLD_VIEW = 64,
            SIZEOF_FRAME = 40, SIZEOF_SNAPSHOT = 24, SIZEOF_STATS = 168, SIZEOF_AUDIO_STATS = 72, SIZEOF_SOUND_DESC = 24;
    static final int SIZEOF_RENDER_BLOCK = 12, SIZEOF_SHAPE_DEF = 12, SIZEOF_RENDER_TABLE = 48, SIZEOF_MESH_IO = 72;
    static final int SIZEOF_RENDERER_DESC = 32, SIZEOF_TEXTURE_DESC = 40, SIZEOF_WORLD_LOOK = 48, SIZEOF_RENDER_IO = 72;

    /** Every structure these bindings lay out by hand, and the size they lay it out at. */
    static final Object[][] LAYOUTS = {
            {"astratto_config", 24}, {"astratto_block_def", 12}, {"astratto_block_table", 16}, {"astratto_world_desc", 56}, {"astratto_world_view", 64},
            {"astratto_record", 8}, {"astratto_cmd_block_set", 24}, {"astratto_edit_fill", 40}, {"astratto_edit_box", 24}, {"astratto_edit_light_fill", 16},
            {"astratto_edit_light_set", 24}, {"astratto_edit_column", 16}, {"astratto_cmd_handle", 16}, {"astratto_cmd_door_seal", 24},
            {"astratto_cmd_nav_tuning", 24}, {"astratto_cmd_path_request", 48}, {"astratto_cmd_sight_request", 40}, {"astratto_cmd_walk_query", 48},
            {"astratto_cmd_ray_query", 40}, {"astratto_evt_error", 24}, {"astratto_evt_path_result", 24}, {"astratto_evt_sight_result", 16},
            {"astratto_evt_walk_result", 16}, {"astratto_evt_ray_result", 48}, {"astratto_cmd_surface", 24}, {"astratto_cmd_gravity", 16},
            {"astratto_cmd_mover_create", 48}, {"astratto_cmd_mover_input", 40}, {"astratto_cmd_body_place", 40}, {"astratto_cmd_body_impulse", 40},
            {"astratto_cmd_ragdoll_create", 56}, {"astratto_cmd_ragdoll_pose", 208}, {"astratto_cmd_ragdoll_hit", 40}, {"astratto_cmd_ragdoll_impulse", 32},
            {"astratto_cmd_ragdoll_control", 24}, {"astratto_cmd_disturb", 48}, {"astratto_cmd_ragdoll_carry", 32}, {"astratto_sydney_profile", 216},
            {"astratto_cmd_sydney_profile", 232}, {"astratto_softbody_particle", 20}, {"astratto_softbody_stick", 8}, {"astratto_softbody_limit", 16},
            {"astratto_cmd_softbody_create", 56}, {"astratto_cmd_softbody_impulse", 48}, {"astratto_vehicle_desc", 172}, {"astratto_cmd_vehicle_create", 208},
            {"astratto_cmd_vehicle_input", 40}, {"astratto_evt_body", 24}, {"astratto_evt_ragdoll_state", 32}, {"astratto_evt_vehicle_impact", 48},
            {"astratto_consideration", 24}, {"astratto_action", 28}, {"astratto_cmd_policy_create", 32}, {"astratto_cmd_agent_create", 72},
            {"astratto_cmd_agent_fact", 24}, {"astratto_cmd_agent_target", 40}, {"astratto_cmd_agent_goal", 32}, {"astratto_cmd_agent_policy", 24},
            {"astratto_cmd_agent_force", 24}, {"astratto_cmd_noise", 32}, {"astratto_evt_agent", 32}, {"astratto_sound_desc", 24},
            {"astratto_cmd_listener", 24}, {"astratto_cmd_voice_play", 72}, {"astratto_cmd_voice_set", 48}, {"astratto_cmd_voice_stop", 24},
            {"astratto_cmd_audio_mix", 40}, {"astratto_frame_io", 40}, {"astratto_stats", 168}, {"astratto_snap_mover", 48}, {"astratto_snap_ragdoll", 240},
            {"astratto_snap_softbody", 40}, {"astratto_snap_vehicle", 176}, {"astratto_snap_agent", 72}, {"astratto_audio_stats", 72},
            {"astratto_snapshot_io", 24}, {"astratto_evt_module_log", 24}, {"astratto_render_block", 12}, {"astratto_shape_def", 12},
            {"astratto_render_table", 48}, {"astratto_mesh_io", 72}, {"astratto_renderer_desc", 32}, {"astratto_texture_desc", 40},
            {"astratto_world_look", 48}, {"astratto_rcmd_camera", 256}, {"astratto_rcmd_env", 112}, {"astratto_rcmd_light", 56}, {"astratto_rcmd_model", 112},
            {"astratto_rcmd_particles", 16}, {"astratto_rcmd_quads", 16}, {"astratto_rcmd_grading", 32}, {"astratto_rcmd_body", 32},
            {"astratto_rcmd_world", 16}, {"astratto_rcmd_clear", 24}, {"astratto_rcmd_quality", 24}, {"astratto_render_io", 72},
            {"astratto_rcmd_foliage", 168}, {"astratto_rcmd_props", 16}, {"astratto_rcmd_decals", 32}};

    private static SharedLibrary lib;
    private static long fnVersion, fnBuildInfo, fnLayout, fnCreate, fnCreateStatus, fnDestroy, fnLastError, fnReserve, fnRelease,
            fnBlockTable, fnWorldCreate, fnWorldView, fnWorldEdit, fnFrame, fnSnapshot, fnSoundCreate, fnAudioRender, fnModuleLoad,
            fnMesherCreate, fnMesherDestroy, fnMeshColumn,
            fnRendererShader, fnRendererCreate, fnRendererDestroy, fnRendererError, fnRenderTexture, fnRenderMesh, fnRenderRelease, fnRenderWorldLook,
            fnRenderFrame, fnRenderReadPixels, fnRenderLayerMesh, fnRenderInstances;
    private static boolean tried;
    private static String why = "not looked for yet", path = "";

    /** The file name of the library on this platform. */
    public static String libraryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "astratto.dll" : os.contains("mac") ? "libastratto.dylib" : "libastratto.so";
    }

    /** The file name a gameplay module library has on this platform, from its base name ("example" -> astratto_example_module.dll). */
    public static String moduleName(String base) {
        String os = System.getProperty("os.name", "").toLowerCase();
        String n = "astratto_" + base + "_module";
        return os.contains("win") ? n + ".dll" : os.contains("mac") ? "lib" + n + ".dylib" : "lib" + n + ".so";
    }

    /** This machine as the engine's builds name it: {@code <os>-<arch>}, e.g. windows-x64, linux-x64, macos-arm64. */
    public static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(), arch = System.getProperty("os.arch", "").toLowerCase();
        return (os.contains("win") ? "windows" : os.contains("mac") ? "macos" : "linux") + "-" + (arch.contains("aarch64") || arch.contains("arm64") ? "arm64" : "x64");
    }

    /**
     * Where the library is, in the order it is looked for: {@code ASTRATTO_NATIVE} (environment) or {@code astratto.native}
     * (system property), a file or a directory; the copy bundled in the engine's jar for this platform
     * ({@code astratto/natives/<platform>/}), extracted once to a private directory under the temporary directory; and the
     * engine's own build output ({@code native/bin/<platform>} of the engine directory, found from the working directory
     * or from where the engine's classes were loaded) so that a build that has not been packaged runs too.
     */
    static java.util.List<File> candidates() {
        java.util.List<File> l = new java.util.ArrayList<>();
        for (String p : new String[]{System.getenv("ASTRATTO_NATIVE"), System.getProperty("astratto.native")}) {
            if (p == null || p.isBlank()) continue;
            File f = new File(p);
            l.add(f.isDirectory() ? new File(f, libraryName()) : f);
        }
        File bundled = extractBundled(libraryName());
        if (bundled != null) l.add(bundled);
        File dir = engineBuildDir();
        if (dir != null) l.add(new File(dir, libraryName()));
        return l;
    }

    /** The engine's own native build output ({@code native/bin/<platform>}), when this process can find the engine directory. */
    public static File engineBuildDir() {
        String rel = "native/bin/" + platform();
        for (String base : new String[]{"theastrattoengine", ".", "..", "../..", "../../.."}) {
            File d = new File(base);
            if (new File(d, "native/include/astratto/astratto.h").isFile()) return new File(d, rel);
        }
        try {
            File here = new File(AstrattoNative.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (File d = here; d != null; d = d.getParentFile()) if (new File(d, "native/include/astratto/astratto.h").isFile()) return new File(d, rel);
        } catch (Exception ignored) { }
        return null;
    }

    /**
     * A native library bundled in the engine's jar ({@code astratto/natives/<platform>/<name>}), copied out to where the
     * system can load it from - once per version and content. Null when the jar has none (a build run from its directory).
     */
    public static File extractBundled(String name) {
        String res = "/astratto/natives/" + platform() + "/" + name;
        try (java.io.InputStream in = AstrattoNative.class.getResourceAsStream(res)) {
            if (in == null) return null;
            byte[] data = in.readAllBytes();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            File dir = new File(System.getProperty("java.io.tmpdir"), "astratto-" + API_MAJOR + "." + API_MINOR + "-" + Long.toHexString(crc.getValue()));
            File f = new File(dir, name);
            if (f.isFile() && f.length() == data.length) return f;
            dir.mkdirs();
            File tmp = File.createTempFile("astratto", ".part", dir);
            java.nio.file.Files.write(tmp.toPath(), data);
            if (!tmp.renameTo(f)) { tmp.delete(); if (!(f.isFile() && f.length() == data.length)) return null; }
            return f;
        } catch (Exception e) { return null; }
    }

    /**
     * Finds and loads the library, once. False - with {@link #reason} saying why - when there is none, when it is not the
     * version these bindings speak, or when one of its structures is not the size these bindings write.
     */
    public static synchronized boolean load() {
        if (tried) return lib != null;
        tried = true;
        if (ByteOrder.nativeOrder() != ByteOrder.LITTLE_ENDIAN) { why = "a big-endian machine: the bridge's layouts are little-endian"; return false; }
        if (!System.getProperty("os.arch", "").contains("64")) { why = "not a 64-bit machine"; return false; }
        File found = null;
        for (File f : candidates()) if (f.isFile()) { found = f; break; }
        if (found == null) { why = "no " + libraryName() + " for " + platform() + " (build it: theastrattoengine/gradlew buildNative)"; return false; }
        SharedLibrary l = null;
        try {
            l = APIUtil.apiCreateLibrary(found.getAbsolutePath());
            fnVersion = need(l, "astratto_api_version");
            int v = JNI.invokeI(fnVersion);
            if (v >>> 16 != API_MAJOR || (v & 0xFFFF) < API_MINOR) {
                l.free();
                why = "the library speaks interface " + (v >>> 16) + "." + (v & 0xFFFF) + ", these bindings " + API_MAJOR + "." + API_MINOR;
                return false;
            }
            fnBuildInfo = need(l, "astratto_build_info"); fnLayout = need(l, "astratto_layout");
            fnCreate = need(l, "astratto_create"); fnCreateStatus = need(l, "astratto_create_status"); fnDestroy = need(l, "astratto_destroy");
            fnLastError = need(l, "astratto_last_error"); fnReserve = need(l, "astratto_handles_reserve"); fnRelease = need(l, "astratto_release");
            fnBlockTable = need(l, "astratto_world_block_table"); fnWorldCreate = need(l, "astratto_world_create"); fnWorldView = need(l, "astratto_world_get_view");
            fnWorldEdit = need(l, "astratto_world_edit");
            fnFrame = need(l, "astratto_frame"); fnSnapshot = need(l, "astratto_snapshot");
            fnSoundCreate = need(l, "astratto_sound_create"); fnAudioRender = need(l, "astratto_audio_render"); fnModuleLoad = need(l, "astratto_module_load");
            fnMesherCreate = need(l, "astratto_mesher_create"); fnMesherDestroy = need(l, "astratto_mesher_destroy"); fnMeshColumn = need(l, "astratto_mesh_column");
            // the renderer is looked for but not required: a core built without it still serves everything else
            fnRendererShader = l.getFunctionAddress("astratto_renderer_shader"); fnRendererCreate = l.getFunctionAddress("astratto_renderer_create");
            fnRendererDestroy = l.getFunctionAddress("astratto_renderer_destroy"); fnRendererError = l.getFunctionAddress("astratto_renderer_error");
            fnRenderTexture = l.getFunctionAddress("astratto_render_texture"); fnRenderMesh = l.getFunctionAddress("astratto_render_mesh");
            fnRenderRelease = l.getFunctionAddress("astratto_render_release"); fnRenderWorldLook = l.getFunctionAddress("astratto_render_world_look");
            fnRenderFrame = l.getFunctionAddress("astratto_render_frame"); fnRenderReadPixels = l.getFunctionAddress("astratto_render_read_pixels");
            fnRenderLayerMesh = l.getFunctionAddress("astratto_render_layer_mesh"); fnRenderInstances = l.getFunctionAddress("astratto_render_instances");
            String bad = checkLayouts();
            if (bad != null) { l.free(); why = found + ": " + bad; return false; }
            lib = l;
            path = found.getAbsolutePath();
            why = "";
            return true;
        } catch (Throwable t) {
            if (l != null) try { l.free(); } catch (Throwable ignored) { }
            why = found + ": " + t;
            return false;
        }
    }

    /** Null when every structure is the size these bindings write; otherwise the first that is not. */
    private static String checkLayouts() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            for (Object[] e : LAYOUTS) {
                String name = (String) e[0];
                int want = (Integer) e[1];
                int got = JNI.invokePI(MemoryUtil.memAddress(stack.ASCII(name)), fnLayout);
                if (got != want) return "structure " + name + " is " + got + " bytes in the library, " + want + " in these bindings";
            }
        }
        return null;
    }

    private static long need(SharedLibrary l, String name) {
        long a = l.getFunctionAddress(name);
        if (a == 0) throw new IllegalStateException("no function " + name);
        return a;
    }

    public static boolean loaded() { return lib != null; }
    /** Whether the loaded core has the renderer in it. */
    public static boolean hasRenderer() {
        return lib != null && fnRendererShader != 0 && fnRendererCreate != 0 && fnRendererDestroy != 0 && fnRendererError != 0 && fnRenderTexture != 0
                && fnRenderMesh != 0 && fnRenderRelease != 0 && fnRenderWorldLook != 0 && fnRenderFrame != 0 && fnRenderReadPixels != 0
                && fnRenderLayerMesh != 0 && fnRenderInstances != 0;
    }
    /** Why there is no native core (empty when there is one). */
    public static String reason() { return why; }
    public static String path() { return path; }

    public static int apiVersion() { return JNI.invokeI(fnVersion); }
    public static String buildInfo() { return MemoryUtil.memASCII(JNI.invokeP(fnBuildInfo)); }
    /** The size the library gives a structure of its header, by name (0 for a name it does not know). */
    public static int layout(String structName) {
        try (MemoryStack stack = MemoryStack.stackPush()) { return JNI.invokePI(MemoryUtil.memAddress(stack.ASCII(structName)), fnLayout); }
    }

    static long create(ByteBuffer config) { return JNI.invokePP(MemoryUtil.memAddress(config), fnCreate); }
    static int createStatus() { return JNI.invokeI(fnCreateStatus); }
    static void destroy(long engine) { JNI.invokePV(engine, fnDestroy); }
    static String lastError(long engine) { long p = JNI.invokePP(engine, fnLastError); return p == 0 ? "" : MemoryUtil.memUTF8(p); }
    static int reserve(long engine, int kind, int count, long out) { return JNI.invokePPI(engine, kind, count, out, fnReserve); }
    static int release(long engine, long handle) { return JNI.invokePJI(engine, handle, fnRelease); }
    static int blockTable(long engine, ByteBuffer table) { return JNI.invokePPI(engine, MemoryUtil.memAddress(table), fnBlockTable); }
    static int worldCreate(long engine, ByteBuffer desc, long out) { return JNI.invokePPPI(engine, MemoryUtil.memAddress(desc), out, fnWorldCreate); }
    // a handle is a uint64_t and a pointer a 64-bit address: on every platform the engine builds for they are passed alike
    static int worldView(long engine, long world, ByteBuffer out) { return JNI.invokePPPI(engine, world, MemoryUtil.memAddress(out), fnWorldView); }
    static int worldEdit(long engine, long world, long records, int bytes) { return JNI.invokePPPI(engine, world, records, bytes, fnWorldEdit); }
    static int frame(long engine, ByteBuffer io) { return JNI.invokePPI(engine, MemoryUtil.memAddress(io), fnFrame); }
    static int snapshot(long engine, ByteBuffer io) { return JNI.invokePPI(engine, MemoryUtil.memAddress(io), fnSnapshot); }
    static int soundCreate(long engine, ByteBuffer desc, long out) { return JNI.invokePPPI(engine, MemoryUtil.memAddress(desc), out, fnSoundCreate); }
    static int audioRender(long engine, long out, int frames) { return JNI.invokePPI(engine, out, frames, fnAudioRender); }
    static int moduleLoad(long engine, long path, long config, long out) { return JNI.invokePPPPI(engine, path, config, out, fnModuleLoad); }

    static long mesherCreate(ByteBuffer table) { return JNI.invokePP(MemoryUtil.memAddress(table), fnMesherCreate); }
    static void mesherDestroy(long mesher) { JNI.invokePV(mesher, fnMesherDestroy); }
    static int meshColumn(long mesher, ByteBuffer io) { return JNI.invokePPI(mesher, MemoryUtil.memAddress(io), fnMeshColumn); }

    static int rendererShader(long engine, long name, long source) { return JNI.invokePPPI(engine, name, source, fnRendererShader); }
    static int rendererCreate(long engine, ByteBuffer desc, long out) { return JNI.invokePPPI(engine, MemoryUtil.memAddress(desc), out, fnRendererCreate); }
    static void rendererDestroy(long renderer) { JNI.invokePV(renderer, fnRendererDestroy); }
    static String rendererError(long renderer) { long p = JNI.invokePP(renderer, fnRendererError); return p == 0 ? "" : MemoryUtil.memUTF8(p); }
    static int renderTexture(long renderer, ByteBuffer desc, long out) { return JNI.invokePPPI(renderer, MemoryUtil.memAddress(desc), out, fnRenderTexture); }
    static int renderMesh(long renderer, long vertices, int count, long out) { return JNI.invokePPPI(renderer, vertices, count, out, fnRenderMesh); }
    static int renderRelease(long renderer, int kind, int id) { return JNI.invokePI(renderer, kind, id, fnRenderRelease); }
    static int renderWorldLook(long renderer, ByteBuffer look) { return JNI.invokePPI(renderer, MemoryUtil.memAddress(look), fnRenderWorldLook); }
    static int renderFrame(long renderer, long engine, ByteBuffer io) { return JNI.invokePPPI(renderer, engine, MemoryUtil.memAddress(io), fnRenderFrame); }
    static int renderReadPixels(long renderer, int x, int y, int w, int h, long out) { return JNI.invokePPI(renderer, x, y, w, h, out, fnRenderReadPixels); }
    static int renderLayerMesh(long renderer, int kind, long vertices, int count, long out) { return JNI.invokePPPI(renderer, kind, vertices, count, out, fnRenderLayerMesh); }
    static int renderInstances(long renderer, int mesh, long data, int count, int flags) { return JNI.invokePPI(renderer, mesh, data, count, flags, fnRenderInstances); }

    public static String status(int code) {
        return switch (code) {
            case OK -> "ok"; case ERR_ARGUMENT -> "bad argument"; case ERR_VERSION -> "incompatible version"; case ERR_STATE -> "not ready";
            case ERR_MEMORY -> "out of memory"; case ERR_STREAM -> "malformed command stream"; case ERR_HANDLE -> "bad handle";
            case ERR_UNSUPPORTED -> "not supported here"; default -> "internal error (" + code + ")";
        };
    }
}
