package astratto.audio;

import static org.lwjgl.system.JNI.*;
import static org.lwjgl.system.MemoryUtil.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.APIUtil;
import org.lwjgl.system.Platform;
import org.lwjgl.system.SharedLibrary;

/**
 * This process's entry in the Windows Volume Mixer.
 *
 * Windows remembers a volume and a mute flag per application and per output device, keyed by the path of the
 * executable. The game runs on the build's Java runtime, so its entry is "OpenJDK Platform binary" - and when that
 * entry has been dragged to zero (or muted) on the device you listen on, every sound the game mixes is thrown away
 * by Windows after it leaves the process. Nothing inside the mixer can tell: the line opens, frames are consumed,
 * meters move. That was the "no audio at all" of 0.2 and 0.3.
 *
 * This class asks WASAPI for the default render endpoint's session for this process (the one the mixer's output
 * line plays into) and can read and restore its level. It is plain COM through LWJGL's raw call helpers; every
 * failure is reported as "unavailable" and nothing else is touched.
 */
public final class WinAudioSession {
    private WinAudioSession() {}

    /** What the Volume Mixer holds for this process: level 0..1, muted, and whether the query worked at all. */
    public record State(boolean available, float volume, boolean muted, String note) {
        /** Inaudible no matter what the game does. */
        public boolean silenced() { return available && (muted || volume < 0.02f); }
    }

    public static final State UNAVAILABLE = new State(false, 1, false, "not queried");

    private static final int S_OK = 0, CLSCTX_ALL = 23, E_RENDER = 0, E_CONSOLE = 0, COINIT_MULTITHREADED = 0;

    private static long guid(String g) {
        // {XXXXXXXX-XXXX-XXXX-XXXX-XXXXXXXXXXXX}: three little-endian fields, then eight bytes as written
        String h = g.replace("-", "");
        ByteBuffer b = memAlloc(16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt((int) Long.parseLong(h.substring(0, 8), 16));
        b.putShort((short) Integer.parseInt(h.substring(8, 12), 16));
        b.putShort((short) Integer.parseInt(h.substring(12, 16), 16));
        for (int i = 0; i < 8; i++) b.put((byte) Integer.parseInt(h.substring(16 + i * 2, 18 + i * 2), 16));
        return memAddress(b, 0);
    }

    private static long vt(long obj, int slot) { return memGetAddress(memGetAddress(obj) + (long) slot * org.lwjgl.system.Pointer.POINTER_SIZE); }
    private static void release(long obj) { if (obj != NULL) callPI(obj, vt(obj, 2)); }

    /** Reads the session, and with restore = true lifts a silenced session back to full level and unmuted. */
    public static synchronized State query(boolean restore) {
        if (Platform.get() != Platform.WINDOWS) return new State(false, 1, false, "not Windows");
        long clsid = NULL, iidEnum = NULL, iidMgr = NULL, out = NULL, enumerator = NULL, device = NULL, manager = NULL, volume = NULL;
        try {
            SharedLibrary ole32 = APIUtil.apiCreateLibrary("ole32");
            long coInit = ole32.getFunctionAddress("CoInitializeEx"), coCreate = ole32.getFunctionAddress("CoCreateInstance");
            if (coInit == NULL || coCreate == NULL) return new State(false, 1, false, "ole32 entry points missing");
            callPPI(NULL, (long) COINIT_MULTITHREADED, coInit);   // S_OK, S_FALSE or RPC_E_CHANGED_MODE: all leave COM usable on this thread
            clsid = guid("BCDE0395-E52F-467C-8E3D-C4579291692E");   // MMDeviceEnumerator
            iidEnum = guid("A95664D2-9614-4F35-A746-DE8DB63617E6"); // IMMDeviceEnumerator
            iidMgr = guid("BFA971F1-4D5E-40BB-935E-967039BFBEE4");  // IAudioSessionManager
            out = nmemCalloc(1, 16);
            int hr = callPPPPPI(clsid, NULL, (long) CLSCTX_ALL, iidEnum, out, coCreate);
            if (hr != S_OK || (enumerator = memGetAddress(out)) == NULL) return new State(false, 1, false, String.format("no device enumerator (0x%08X)", hr));
            memPutAddress(out, NULL);
            hr = callPPPPI(enumerator, (long) E_RENDER, (long) E_CONSOLE, out, vt(enumerator, 4));          // GetDefaultAudioEndpoint
            if (hr != S_OK || (device = memGetAddress(out)) == NULL) return new State(false, 1, false, String.format("no default output device (0x%08X)", hr));
            memPutAddress(out, NULL);
            hr = callPPPPPI(device, iidMgr, (long) CLSCTX_ALL, NULL, out, vt(device, 3));                    // Activate
            if (hr != S_OK || (manager = memGetAddress(out)) == NULL) return new State(false, 1, false, String.format("no session manager (0x%08X)", hr));
            memPutAddress(out, NULL);
            hr = callPPPPI(manager, NULL, 0L, out, vt(manager, 4));                                          // GetSimpleAudioVolume (this process, default session)
            if (hr != S_OK || (volume = memGetAddress(out)) == NULL) return new State(false, 1, false, String.format("no session volume (0x%08X)", hr));
            memPutAddress(out, NULL);
            memPutAddress(out + 8, NULL);
            if (callPPI(volume, out, vt(volume, 4)) != S_OK) return new State(false, 1, false, "level unreadable");   // GetMasterVolume
            float level = memGetFloat(out);
            if (callPPI(volume, out + 8, vt(volume, 6)) != S_OK) return new State(false, 1, false, "mute unreadable"); // GetMute
            boolean muted = memGetInt(out + 8) != 0;
            State before = new State(true, level, muted, "");
            if (!restore || !before.silenced()) return before;
            int a = callPPI(volume, 1.0f, NULL, vt(volume, 3));                                              // SetMasterVolume
            int b = callPPPI(volume, 0L, NULL, vt(volume, 5));                                               // SetMute(FALSE)
            if (a != S_OK || b != S_OK) return new State(true, level, muted, String.format("could not restore (0x%08X / 0x%08X)", a, b));
            return new State(true, 1, false, String.format("restored from %d%%%s", Math.round(level * 100), muted ? ", muted" : ""));
        } catch (Throwable t) {
            return new State(false, 1, false, t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            release(volume); release(manager); release(device); release(enumerator);
            if (out != NULL) nmemFree(out);
            if (clsid != NULL) nmemFree(clsid);
            if (iidEnum != NULL) nmemFree(iidEnum);
            if (iidMgr != NULL) nmemFree(iidMgr);
        }
    }

    /** Diagnostic entry point: prints the session, and restores it when run with "restore". */
    public static void main(String[] args) {
        State s = query(args.length > 0 && args[0].equals("restore"));
        System.out.println("[Audio] Windows session: " + (s.available() ? Math.round(s.volume() * 100) + "%" + (s.muted() ? " muted" : "") : "unavailable") + (s.note().isEmpty() ? "" : " (" + s.note() + ")"));
    }
}
