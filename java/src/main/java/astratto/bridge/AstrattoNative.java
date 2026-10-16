package astratto.bridge;

import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.JNI;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.system.SharedLibrary;

/**
 * 1.2, Astratto Engine: the Java side of the bridge to the native core ({@code astratto/native}, C++). This class is
 * the binding and nothing else - it finds the library, checks that it speaks the version of the interface these
 * bindings were written for, and calls the handful of C functions in {@code astratto/astratto.h}. Every one of them
 * takes pointers and returns a status, so one calling convention covers the lot and nothing here is generated.
 *
 * Nothing in the {@code astratto} package knows of any game. What a game hands across is written with
 * {@link CommandBuffer} and comes back through {@link NativeEngine}.
 *
 * The constants below are the C header's, by hand: {@code astratto/native/src/api/c_api.cpp} holds the layouts to
 * these sizes with static assertions, and {@code NativeEngine.create} refuses a library of another major version.
 */
public final class AstrattoNative {
    private AstrattoNative() {}

    public static final int API_MAJOR = 1, API_MINOR = 1, API_VERSION = API_MAJOR << 16 | API_MINOR;

    public static final int OK = 0, ERR_ARGUMENT = -1, ERR_VERSION = -2, ERR_STATE = -3, ERR_MEMORY = -4, ERR_STREAM = -5, ERR_INTERNAL = -6;

    // what the engine needs to know of a kind of block
    public static final int BLOCK_SOLID = 1, BLOCK_OPAQUE = 1 << 1, BLOCK_CUTOUT = 1 << 2, BLOCK_CLIMB = 1 << 3, BLOCK_STEP = 1 << 4,
            BLOCK_LIQUID = 1 << 5, BLOCK_HAZARD = 1 << 6, BLOCK_DOOR = 1 << 7;
    public static final int MATERIAL_OTHER = 0, MATERIAL_GLASS = 1, MATERIAL_FOLIAGE = 2;

    // commands and events
    public static final int CMD_BLOCK_SET = 1, CMD_DOOR_SEAL = 2, CMD_NAV_TUNING = 3, CMD_DOORS_UNSEAL = 4, CMD_PATH_REQUEST = 16, CMD_SIGHT_REQUEST = 17;
    public static final int EVT_PATH_RESULT = 16, EVT_SIGHT_RESULT = 17;
    public static final int PATH_DOORS = 1, PATH_GOAL_Y = 1 << 1, PATH_MANTLE = 1 << 2;
    public static final int SIGHT_LIGHT = 0, SIGHT_EYE = 1;
    public static final int PATH_FOUND = 0, PATH_NONE = 1;
    public static final int SNAPSHOT_STATS = 1;

    // 1.1, rendering: what the mesher needs to know of a kind of block, how a shape reads its facing, and the column options
    public static final int RB_CROSS = 1, RB_TRANSLUCENT = 1 << 1, RB_LIQUID = 1 << 2, RB_CUTOUT = 1 << 3, RB_EMISSIVE = 1 << 4, RB_LEAF = 1 << 5, RB_SWAY = 1 << 6,
            RB_OCCLUDES = 1 << 7, RB_DOOR = 1 << 8, RB_SOLID = 1 << 9, RB_STEP = 1 << 10, RB_SEAT = 1 << 11, RB_PLANT = 1 << 12;
    public static final int ORIENT_NONE = 0, ORIENT_BACKING = 1, ORIENT_DOOR = 2, ORIENT_JOIN = 3, ORIENT_CHAIR = 4, ORIENT_PEW = 5, ORIENT_SCATTER = 6, ORIENT_STAIR = 7;
    public static final int MESH_DOORS = 1, MESH_NO_PLANTS = 1 << 1;
    static final int SIZEOF_RENDER_BLOCK = 12, SIZEOF_SHAPE_DEF = 12, SIZEOF_RENDER_TABLE = 48, SIZEOF_MESH_IO = 72;

    // structure sizes (bytes), as the header lays them out on a 64-bit machine
    static final int SIZEOF_CONFIG = 16, SIZEOF_BLOCK_DEF = 8, SIZEOF_BLOCK_TABLE = 16, SIZEOF_WORLD_DESC = 40, SIZEOF_FRAME = 40, SIZEOF_SNAPSHOT = 24, SIZEOF_STATS = 96;

    private static SharedLibrary lib;
    private static long fnVersion, fnBuildInfo, fnCreate, fnCreateStatus, fnDestroy, fnLastError, fnBlockTable, fnWorldLoad, fnFrame, fnSnapshot, fnMesherCreate, fnMesherDestroy, fnMeshColumn;
    private static boolean tried;
    private static String why = "not looked for yet", path = "";

    /** The file name of the library on this platform. */
    public static String libraryName() {
        String os = System.getProperty("os.name", "").toLowerCase();
        return os.contains("win") ? "astratto.dll" : os.contains("mac") ? "libastratto.dylib" : "libastratto.so";
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
        File bundled = extractBundled();
        if (bundled != null) l.add(bundled);
        String rel = "native/bin/" + platform() + "/" + libraryName();
        for (String base : new String[]{"theastrattoengine", ".", ".."}) l.add(new File(base, rel));
        try {
            File here = new File(AstrattoNative.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            for (File d = here; d != null; d = d.getParentFile()) if (new File(d, "native/include/astratto/astratto.h").isFile()) { l.add(new File(d, rel)); break; }
        } catch (Exception ignored) { }
        return l;
    }

    /** The library inside the jar, copied out to where the system can load it from (once per version and content). */
    private static File extractBundled() {
        String res = "/astratto/natives/" + platform() + "/" + libraryName();
        try (java.io.InputStream in = AstrattoNative.class.getResourceAsStream(res)) {
            if (in == null) return null;
            byte[] data = in.readAllBytes();
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            File dir = new File(System.getProperty("java.io.tmpdir"), "astratto-" + API_MAJOR + "." + API_MINOR + "-" + Long.toHexString(crc.getValue()));
            File f = new File(dir, libraryName());
            if (f.isFile() && f.length() == data.length) return f;
            dir.mkdirs();
            File tmp = File.createTempFile("astratto", ".part", dir);
            java.nio.file.Files.write(tmp.toPath(), data);
            if (!tmp.renameTo(f)) { tmp.delete(); if (!(f.isFile() && f.length() == data.length)) return null; }
            return f;
        } catch (Exception e) { return null; }
    }

    /**
     * Finds and loads the library, once. It is looked for where {@code ASTRATTO_NATIVE} (environment) or
     * {@code astratto.native} (system property) says, then in {@code astratto/bin} under the working directory, then
     * beside the working directory itself. False - with {@link #reason} saying why - when there is none, or when it
     * is not the version these bindings speak.
     */
    public static synchronized boolean load() {
        if (tried) return lib != null;
        tried = true;
        if (!"little".equalsIgnoreCase(ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN ? "little" : "big")) { why = "a big-endian machine: the bridge's layouts are little-endian"; return false; }
        if (!System.getProperty("os.arch", "").contains("64")) { why = "not a 64-bit machine"; return false; }
        File found = null;
        for (File f : candidates()) if (f.isFile()) { found = f; break; }
        if (found == null) { why = "no " + libraryName() + " for " + platform() + " (build it: theastrattoengine/gradlew buildNative)"; return false; }
        try {
            SharedLibrary l = APIUtil.apiCreateLibrary(found.getAbsolutePath());
            fnVersion = need(l, "astratto_api_version"); fnBuildInfo = need(l, "astratto_build_info"); fnCreate = need(l, "astratto_create");
            fnCreateStatus = need(l, "astratto_create_status"); fnDestroy = need(l, "astratto_destroy"); fnLastError = need(l, "astratto_last_error");
            fnBlockTable = need(l, "astratto_world_block_table"); fnWorldLoad = need(l, "astratto_world_load"); fnFrame = need(l, "astratto_frame"); fnSnapshot = need(l, "astratto_snapshot");
            fnMesherCreate = need(l, "astratto_mesher_create"); fnMesherDestroy = need(l, "astratto_mesher_destroy"); fnMeshColumn = need(l, "astratto_mesh_column");
            int v = JNI.invokeI(fnVersion);
            if (v >>> 16 != API_MAJOR || (v & 0xFFFF) < API_MINOR) { l.free(); why = "the library speaks interface " + (v >>> 16) + "." + (v & 0xFFFF) + ", these bindings " + API_MAJOR + "." + API_MINOR; return false; }
            lib = l;
            path = found.getAbsolutePath();
            why = "";
            return true;
        } catch (Throwable t) {
            why = found + ": " + t;
            return false;
        }
    }

    private static long need(SharedLibrary l, String name) {
        long a = l.getFunctionAddress(name);
        if (a == 0) throw new IllegalStateException("no function " + name);
        return a;
    }

    public static boolean loaded() { return lib != null; }
    /** Why there is no native core (empty when there is one). */
    public static String reason() { return why; }
    public static String path() { return path; }

    public static int apiVersion() { return JNI.invokeI(fnVersion); }
    public static String buildInfo() { return MemoryUtil.memASCII(JNI.invokeP(fnBuildInfo)); }

    static long create(ByteBuffer config) { return JNI.invokePP(MemoryUtil.memAddress(config), fnCreate); }
    static int createStatus() { return JNI.invokeI(fnCreateStatus); }
    static void destroy(long engine) { JNI.invokePV(engine, fnDestroy); }
    static String lastError(long engine) { long p = JNI.invokePP(engine, fnLastError); return p == 0 ? "" : MemoryUtil.memASCII(p); }
    static int blockTable(long engine, ByteBuffer table) { return JNI.invokePPI(engine, MemoryUtil.memAddress(table), fnBlockTable); }
    static int worldLoad(long engine, ByteBuffer desc) { return JNI.invokePPI(engine, MemoryUtil.memAddress(desc), fnWorldLoad); }
    static int frame(long engine, ByteBuffer io) { return JNI.invokePPI(engine, MemoryUtil.memAddress(io), fnFrame); }
    static int snapshot(long engine, ByteBuffer io) { return JNI.invokePPI(engine, MemoryUtil.memAddress(io), fnSnapshot); }

    static long mesherCreate(ByteBuffer table) { return JNI.invokePP(MemoryUtil.memAddress(table), fnMesherCreate); }
    static void mesherDestroy(long mesher) { JNI.invokePV(mesher, fnMesherDestroy); }
    static int meshColumn(long mesher, ByteBuffer io) { return JNI.invokePPI(mesher, MemoryUtil.memAddress(io), fnMeshColumn); }

    public static String status(int code) {
        return switch (code) {
            case OK -> "ok"; case ERR_ARGUMENT -> "bad argument"; case ERR_VERSION -> "incompatible version"; case ERR_STATE -> "not ready";
            case ERR_MEMORY -> "out of memory"; case ERR_STREAM -> "malformed command stream"; default -> "internal error (" + code + ")";
        };
    }
}
