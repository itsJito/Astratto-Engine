package astratto.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.stb.STBTruetype.*;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import org.lwjgl.stb.STBTTAlignedQuad;
import org.lwjgl.stb.STBTTBakedChar;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

/** TrueType font baked with stb_truetype from the system fonts; draws through Batch2D. */
public final class Font {
    private static final int BW = 1024, BH = 1024, FIRST = 32, COUNT = 224;
    /** The colon sign of 0.6 and earlier: still drawn (as a struck-through C) wherever old text carries it. 0.7 money is "$", an ordinary glyph. */
    private static final char COIN = '₡';
    private int tex;
    private STBTTBakedChar.Buffer chars;
    public final float bakeSize;
    private final float[] advance = new float[COUNT];

    public Font(float bakeSize) { this.bakeSize = bakeSize; }

    public void init() {
        String[] candidates = {"C:/Windows/Fonts/bahnschrift.ttf", "C:/Windows/Fonts/segoeui.ttf", "C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/consola.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf"};
        byte[] data = null;
        for (String c : candidates) {
            try { data = Files.readAllBytes(Path.of(c)); break; } catch (IOException ignored) {}
        }
        if (data == null) throw new IllegalStateException("No usable TTF font found");
        ByteBuffer ttf = MemoryUtil.memAlloc(data.length);
        ttf.put(data).flip();
        ByteBuffer bitmap = MemoryUtil.memAlloc(BW * BH);
        chars = STBTTBakedChar.malloc(COUNT);
        stbtt_BakeFontBitmap(ttf, bakeSize, bitmap, BW, BH, FIRST, chars);
        tex = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, tex);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 1);
        glTexImage2D(GL_TEXTURE_2D, 0, org.lwjgl.opengl.GL30.GL_R8, BW, BH, 0, GL_RED, GL_UNSIGNED_BYTE, bitmap);
        glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        for (int i = 0; i < COUNT; i++) advance[i] = chars.get(i).xadvance();
        MemoryUtil.memFree(bitmap);
        MemoryUtil.memFree(ttf);
    }

    public float width(String s, float size) {
        float sc = size / bakeSize, w = 0;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i) - FIRST;
            if (s.charAt(i) == COIN) c = 'C' - FIRST;
            if (c < 0 || c >= COUNT) c = '?' - FIRST;
            w += advance[c];
        }
        return w * sc;
    }

    /** Draws text with its top-left at (x,y). Returns the width drawn. */
    public float draw(Batch2D b, String s, float x, float y, float size, int argb) {
        if (s == null) return 0;
        float sc = size / bakeSize;
        float r = ((argb >> 16) & 255) / 255f, g = ((argb >> 8) & 255) / 255f, bl = (argb & 255) / 255f, a = ((argb >>> 24) & 255) / 255f;
        try (MemoryStack st = MemoryStack.stackPush()) {
            STBTTAlignedQuad q = STBTTAlignedQuad.malloc(st);
            float[] px = {0}, py = {0};
            float baseline = bakeSize * 0.78f;
            for (int i = 0; i < s.length(); i++) {
                int c = s.charAt(i);
                boolean coin = c == COIN;
                if (coin) c = 'C';
                if (c < FIRST || c >= FIRST + COUNT) c = '?';
                float[] fx = {px[0]}, fy = {0};
                stbtt_GetBakedQuad(chars, BW, BH, c - FIRST, fx, fy, q, true);
                float x0 = x + (px[0] + (q.x0() - px[0])) * sc, y0 = y + (q.y0() + baseline) * sc;
                float w = (q.x1() - q.x0()) * sc, h = (q.y1() - q.y0()) * sc;
                if (w > 0 && h > 0) b.quad(tex, x0, y0, w, h, q.s0(), q.t0(), q.s1(), q.t1(), r, g, bl, a, 1);
                if (coin && w > 0) {
                    // the colon sign is a C struck through twice; system fonts rarely carry the glyph
                    float t = Math.max(1f, size * 0.07f);
                    for (int k = 0; k < 2; k++) { float gx = x0 + w * (0.42f + k * 0.24f); b.line(gx - w * 0.14f, y0 + h * 1.12f, gx + w * 0.14f, y0 - h * 0.12f, t, argb); }
                }
                px[0] = fx[0];
            }
            return px[0] * sc;
        }
    }

    public void drawShadow(Batch2D b, String s, float x, float y, float size, int argb) {
        draw(b, s, x + 1, y + 1, size, (argb & 0xFF000000) | 0x000000);
        draw(b, s, x, y, size, argb);
    }

    public void drawCentered(Batch2D b, String s, float cx, float y, float size, int argb) {
        draw(b, s, cx - width(s, size) / 2, y, size, argb);
    }

    /** Word-wrapped text; returns height used. */
    public float drawWrapped(Batch2D b, String s, float x, float y, float maxW, float size, int argb) {
        float lineH = size * 1.25f, cy = y;
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            for (String word : para.split(" ")) {
                String trial = line.length() == 0 ? word : line + " " + word;
                if (width(trial, size) > maxW && line.length() > 0) {
                    draw(b, line.toString(), x, cy, size, argb);
                    cy += lineH;
                    line = new StringBuilder(word);
                } else line = new StringBuilder(trial);
            }
            draw(b, line.toString(), x, cy, size, argb);
            cy += lineH;
        }
        return cy - y;
    }

    public float wrappedHeight(String s, float maxW, float size) {
        float lineH = size * 1.25f;
        int lines = 0;
        for (String para : s.split("\n")) {
            StringBuilder line = new StringBuilder();
            lines++;
            for (String word : para.split(" ")) {
                String trial = line.length() == 0 ? word : line + " " + word;
                if (width(trial, size) > maxW && line.length() > 0) { lines++; line = new StringBuilder(word); }
                else line = new StringBuilder(trial);
            }
        }
        return lines * lineH;
    }
}
