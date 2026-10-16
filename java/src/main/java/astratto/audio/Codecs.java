package astratto.audio;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/**
 * UltraEngine's decoders (0.8). One entry point - {@link #decode(Path)} - reads any sound file the engine accepts and
 * returns it as mono samples at the mixer rate. The format is taken from what the file contains, not from what it is
 * called: a WAVE saved as .mp3, or an MP3 with a .wav extension, still plays.
 *
 *   WAV   read here, by hand: PCM at 8, 16, 24 and 32 bits, IEEE float at 32 and 64 bits, WAVE_FORMAT_EXTENSIBLE, any
 *         number of channels at any rate (javax.sound refuses several of those).
 *   OGG   Vorbis, through stb_vorbis.
 *   MP3   MPEG-1/2/2.5 layer III (and I, II), through JLayer. ID3 tags are skipped.
 *
 * Nothing here touches the audio thread. A file that cannot be read gives a {@link Failure} that says why.
 */
public final class Codecs {
    private Codecs() {}

    public static final String[] EXTENSIONS = {".wav", ".ogg", ".mp3"};

    /** A decoded file: mono samples at the mixer rate, and what it was before. */
    public record Decoded(float[] mono, String format, int sourceRate, int channels) {
        public float seconds() { return mono.length / (float) UltraEngine.RATE; }
    }

    public static final class Failure extends Exception {
        public Failure(String why) { super(why); }
    }

    /** True for a file name the engine will try to read. */
    public static boolean accepts(String fileName) {
        String f = fileName.toLowerCase();
        for (String e : EXTENSIONS) if (f.endsWith(e)) return true;
        return false;
    }

    /** The name a file is known by: lower case, without its extension. */
    public static String stem(String fileName) {
        String f = fileName.toLowerCase();
        for (String e : EXTENSIONS) if (f.endsWith(e)) return f.substring(0, f.length() - e.length());
        return f;
    }

    public static String sniff(byte[] d) {
        if (d.length >= 12 && d[0] == 'R' && d[1] == 'I' && d[2] == 'F' && d[3] == 'F' && d[8] == 'W' && d[9] == 'A' && d[10] == 'V' && d[11] == 'E') return "wav";
        if (d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S') return "ogg";
        if (d.length >= 3 && d[0] == 'I' && d[1] == 'D' && d[2] == '3') return "mp3";
        if (d.length >= 2 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xE0) == 0xE0) return "mp3";
        return "";
    }

    public static Decoded decode(Path p) throws Failure {
        byte[] raw;
        try { raw = Files.readAllBytes(p); } catch (Exception e) { throw new Failure("cannot be read (" + e.getMessage() + ")"); }
        return decode(raw, p.getFileName().toString());
    }

    public static Decoded decode(byte[] raw, String fileName) throws Failure { return decode(raw, fileName, 0); }

    /**
     * A long bed, in part: at most {@code maxSeconds} of it, and - for an MP3, whose frames can be picked up anywhere -
     * starting from a place in the file chosen by {@code from} (0..1), so a fifteen-minute recording gives a different
     * four minutes each time it is wanted without fifteen minutes of it ever being held in memory.
     */
    public static Decoded excerpt(Path p, float maxSeconds, float from) throws Failure {
        byte[] raw;
        try { raw = Files.readAllBytes(p); } catch (Exception e) { throw new Failure("cannot be read (" + e.getMessage() + ")"); }
        if (sniff(raw).equals("mp3") && from > 0 && raw.length > 2_000_000) {
            int off = (int) (raw.length * Math.max(0, Math.min(0.75f, from)));
            raw = java.util.Arrays.copyOfRange(raw, off, raw.length);
        }
        return decode(raw, p.getFileName().toString(), maxSeconds);
    }

    public static Decoded decode(byte[] raw, String fileName, float maxSeconds) throws Failure {
        if (raw.length < 16) throw new Failure("empty");
        String kind = sniff(raw);
        if (kind.isEmpty()) { String f = fileName.toLowerCase(); kind = f.endsWith(".mp3") ? "mp3" : f.endsWith(".ogg") ? "ogg" : f.endsWith(".wav") ? "wav" : ""; }
        Decoded d = switch (kind) {
            case "wav" -> wav(raw);
            case "ogg" -> ogg(raw);
            case "mp3" -> mp3(raw, maxSeconds);
            default -> throw new Failure("not a WAV, Ogg Vorbis or MP3 file");
        };
        if (maxSeconds > 0 && d.mono().length > maxSeconds * UltraEngine.RATE) d = new Decoded(java.util.Arrays.copyOf(d.mono(), (int) (maxSeconds * UltraEngine.RATE)), d.format(), d.sourceRate(), d.channels());
        if (d.mono().length < 8) throw new Failure("holds no sound");
        return d;
    }

    /** The samples of a file, or null when it is absent or unreadable (for callers that have a fallback and no use for the reason). */
    public static float[] read(Path p) {
        try { return Files.exists(p) ? decode(p).mono() : null; } catch (Failure e) { return null; }
    }

    // ------------------------------------------------------------------ WAV
    private static Decoded wav(byte[] d) throws Failure {
        ByteBuffer b = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        int pos = 12, tag = 0, ch = 0, rate = 0, bits = 0, align = 0;
        while (pos + 8 <= d.length) {
            String id = new String(d, pos, 4, StandardCharsets.US_ASCII);
            long size = b.getInt(pos + 4) & 0xFFFFFFFFL;
            int body = pos + 8;
            if (id.equals("fmt ") && body + 16 <= d.length) {
                tag = b.getShort(body) & 0xFFFF; ch = b.getShort(body + 2) & 0xFFFF; rate = b.getInt(body + 4); align = b.getShort(body + 12) & 0xFFFF; bits = b.getShort(body + 14) & 0xFFFF;
                // WAVE_FORMAT_EXTENSIBLE: the real format is the first two bytes of the sub-format GUID
                if (tag == 0xFFFE && size >= 26 && body + 26 <= d.length) tag = b.getShort(body + 24) & 0xFFFF;
            } else if (id.equals("data")) {
                if (ch <= 0 || rate <= 0) throw new Failure("WAV with no format chunk before its data");
                if (tag != 1 && tag != 3) throw new Failure("WAV encoding " + tag + " is compressed; only PCM and float are read");
                int bytes = bits / 8;
                if (bytes < 1 || bytes > 8 || (tag == 3 && bytes != 4 && bytes != 8)) throw new Failure("WAV with " + bits + "-bit samples");
                if (align < bytes * ch) align = bytes * ch;
                int n = (int) Math.min(size, d.length - body) / align;
                float[] mono = new float[n];
                for (int i = 0; i < n; i++) {
                    float s = 0;
                    for (int c = 0; c < ch; c++) {
                        int o = body + i * align + c * bytes;
                        s += tag == 3 ? (bytes == 4 ? b.getFloat(o) : (float) b.getDouble(o))
                                : bytes == 1 ? ((d[o] & 0xFF) - 128) / 128f
                                : bytes == 2 ? b.getShort(o) / 32768f
                                : bytes == 3 ? (((d[o] & 0xFF) | ((d[o + 1] & 0xFF) << 8) | (d[o + 2] << 16))) / 8388608f
                                : b.getInt(o) / 2147483648f;
                    }
                    mono[i] = s / ch;
                }
                return new Decoded(resample(mono, rate), "wav " + (tag == 3 ? "float" : "pcm") + bits, rate, ch);
            }
            if (size > Integer.MAX_VALUE - body) break;
            pos = (int) (body + size + (size & 1));
        }
        throw new Failure("WAV with no data chunk");
    }

    // ------------------------------------------------------------------ Ogg Vorbis
    private static Decoded ogg(byte[] raw) throws Failure {
        ByteBuffer in = MemoryUtil.memAlloc(raw.length);
        in.put(raw).flip();
        try (MemoryStack st = MemoryStack.stackPush()) {
            IntBuffer ch = st.mallocInt(1), rate = st.mallocInt(1);
            ShortBuffer pcm = STBVorbis.stb_vorbis_decode_memory(in, ch, rate);
            if (pcm == null) throw new Failure("Ogg file that is not Vorbis, or is damaged");
            int c = Math.max(1, ch.get(0)), frames = pcm.remaining() / c;
            float[] mono = new float[frames];
            for (int i = 0; i < frames; i++) { float s = 0; for (int k = 0; k < c; k++) s += pcm.get(i * c + k); mono[i] = s / c / 32768f; }
            org.lwjgl.system.libc.LibCStdlib.free(pcm);
            return new Decoded(resample(mono, rate.get(0)), "ogg vorbis", rate.get(0), c);
        } finally {
            MemoryUtil.memFree(in);
        }
    }

    // ------------------------------------------------------------------ MP3
    private static Decoded mp3(byte[] raw, float maxSeconds) throws Failure {
        javazoom.jl.decoder.Bitstream bs = new javazoom.jl.decoder.Bitstream(new ByteArrayInputStream(raw));
        javazoom.jl.decoder.Decoder dec = new javazoom.jl.decoder.Decoder();
        float[] mono = new float[Math.max(4096, (int) Math.min(raw.length * 3L, maxSeconds > 0 ? (long) (maxSeconds * 48000) + 4096 : Integer.MAX_VALUE / 8))];
        int n = 0, rate = 0, channels = 1, bad = 0;
        try {
            while (true) {
                javazoom.jl.decoder.Header h;
                try { h = bs.readFrame(); } catch (javazoom.jl.decoder.BitstreamException e) { if (++bad > 64) break; continue; }
                if (h == null) break;
                javazoom.jl.decoder.SampleBuffer sb;
                try { sb = (javazoom.jl.decoder.SampleBuffer) dec.decodeFrame(h, bs); }
                catch (javazoom.jl.decoder.DecoderException | ArrayIndexOutOfBoundsException e) { bs.closeFrame(); if (++bad > 64) break; continue; }
                rate = sb.getSampleFrequency(); channels = Math.max(1, sb.getChannelCount());
                short[] buf = sb.getBuffer();
                int frames = sb.getBufferLength() / channels;
                if (n + frames > mono.length) mono = java.util.Arrays.copyOf(mono, Math.max(mono.length * 2, n + frames));
                for (int i = 0; i < frames; i++) { float s = 0; for (int c = 0; c < channels; c++) s += buf[i * channels + c]; mono[n++] = s / channels / 32768f; }
                bs.closeFrame();
                if (maxSeconds > 0 && n >= maxSeconds * rate) break;
            }
        } finally {
            try { bs.close(); } catch (javazoom.jl.decoder.BitstreamException ignored) { }
        }
        if (n == 0 || rate <= 0) throw new Failure("MP3 with no decodable frames");
        return new Decoded(resample(java.util.Arrays.copyOf(mono, n), rate), "mp3", rate, channels);
    }

    /**
     * Resampling to the mixer rate. Going down (48 kHz material) the signal is first low-passed below the new Nyquist
     * so nothing folds back; going up it is interpolated. Linear between neighbours either way - the material is
     * effects and beds, and it is done once, at load.
     */
    public static float[] resample(float[] in, int rate) {
        if (rate == UltraEngine.RATE || rate <= 0 || in.length < 2) return in;
        if (rate > UltraEngine.RATE) {
            // one-pole forward and back: zero phase, about -3 dB at 0.45 of the new rate
            float k = (float) (1 - Math.exp(-2 * Math.PI * 0.45 * UltraEngine.RATE / rate)), s = in[0];
            float[] f = in.clone();
            for (int i = 0; i < f.length; i++) { s += (f[i] - s) * k; f[i] = s; }
            for (int i = f.length - 1; i >= 0; i--) { s += (f[i] - s) * k; f[i] = s; }
            in = f;
        }
        double step = rate / (double) UltraEngine.RATE;
        int n = (int) ((in.length - 1) / step);
        float[] o = new float[Math.max(2, n)];
        for (int i = 0; i < n; i++) { double x = i * step; int i0 = (int) x; float fr = (float) (x - i0); o[i] = in[i0] + (in[i0 + 1] - in[i0]) * fr; }
        return o;
    }
}
