package astratto.audio;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 2.0: what is done to a recording once, at load, before it is played: levelled, its ends faded, its dead air trimmed,
 * filtered, made to loop without a seam. These were the game's sound pack's own until 2.0; they are the engine's, so
 * that the engine's weapon profiles and streamed beds need nothing of any game.
 */
public final class SampleOps {
    private SampleOps() {}

    /** The audio files in a directory, by stem (file name without extension). */
    public static Map<String, Path> index(Path dir) {
        Map<String, Path> m = new LinkedHashMap<>();
        if (!Files.isDirectory(dir)) return m;
        try (var st = Files.list(dir)) {
            for (Path p : (Iterable<Path>) st.sorted()::iterator) {
                String f = p.getFileName().toString();
                if (Files.isRegularFile(p) && Codecs.accepts(f)) m.put(Codecs.stem(f), p);
            }
        } catch (Exception ignored) { }
        return m;
    }

    /** Peak level. */
    public static void level(float[] d, float target) {
        float m = 1e-6f;
        for (float v : d) m = Math.max(m, Math.abs(v));
        float k = target / m;
        for (int i = 0; i < d.length; i++) d[i] *= k;
    }

    /** Short fades at both ends: no clicks from a file cut mid-wave. */
    public static void edges(float[] d) {
        int in = Math.min(d.length / 16, 64), out = Math.min(d.length / 8, 400);
        for (int i = 0; i < in; i++) d[i] *= i / (float) in;
        for (int i = 0; i < out; i++) d[d.length - 1 - i] *= i / (float) out;
    }

    /** Silences the dead air some recordings start with (anything under -60 dB of the peak before the sound begins), so a sound plays when it is asked for. */
    public static void trim(float[] d) {
        float m = 1e-6f;
        for (float v : d) m = Math.max(m, Math.abs(v));
        float floor = m * 0.001f;
        int start = 0;
        while (start < d.length && Math.abs(d[start]) < floor) start++;
        start = Math.max(0, start - 32);
        if (start > 64 && start < d.length - 64) { System.arraycopy(d, start, d, 0, d.length - start); java.util.Arrays.fill(d, d.length - start, d.length, 0f); }
    }

    /** One-pole low-pass, twice (12 dB an octave): what distance does to a sound. */
    public static void lowpass(float[] d, float hz) {
        float k = (float) (1 - Math.exp(-2 * Math.PI * hz / UltraEngine.RATE));
        for (int pass = 0; pass < 2; pass++) { float s = 0; for (int i = 0; i < d.length; i++) { s += (d[i] - s) * k; d[i] = s; } }
    }

    /** Makes a bed loop without a seam: the last second is cross-faded into the first, and the bed is that much shorter. */
    public static float[] seam(float[] d) {
        int n = Math.min(UltraEngine.RATE, d.length / 4);
        float[] o = java.util.Arrays.copyOf(d, d.length - n);
        for (int i = 0; i < n; i++) { float t = i / (float) n; o[i] = d[i] * t + d[d.length - n + i] * (1 - t); }
        return o;
    }
}
