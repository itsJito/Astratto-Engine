package astratto.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

import astratto.math.Mat4;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;

/** Immediate-mode textured/coloured quad batcher for all 2D UI (screen pixel coordinates, y down). */
public final class Batch2D {
    private static final int MAX_QUADS = 8192;
    private static final int FLOATS_PER_VERT = 9; // x y u v r g b a mode
    private Shader shader;
    private int vao, vbo, white;
    private FloatBuffer buf;
    private int quads, currentTex;
    private int curMode;
    private final Mat4 proj = new Mat4();
    public int width, height;

    public void init() {
        shader = new Shader(VS, FS);
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) MAX_QUADS * 6 * FLOATS_PER_VERT * 4, GL_DYNAMIC_DRAW);
        int stride = FLOATS_PER_VERT * 4;
        glVertexAttribPointer(0, 2, GL_FLOAT, false, stride, 0);
        glVertexAttribPointer(1, 2, GL_FLOAT, false, stride, 8);
        glVertexAttribPointer(2, 4, GL_FLOAT, false, stride, 16);
        glVertexAttribPointer(3, 1, GL_FLOAT, false, stride, 32);
        for (int i = 0; i < 4; i++) glEnableVertexAttribArray(i);
        glBindVertexArray(0);
        buf = MemoryUtil.memAllocFloat(MAX_QUADS * 6 * FLOATS_PER_VERT);
        white = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, white);
        ByteBuffer px = MemoryUtil.memAlloc(4);
        px.put((byte) -1).put((byte) -1).put((byte) -1).put((byte) -1).flip();
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 1, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, px);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
        MemoryUtil.memFree(px);
    }

    public void begin(int w, int h) {
        width = w; height = h;
        proj.ortho(0, w, h, 0, -1, 1);
        glDisable(GL_DEPTH_TEST);
        glDisable(GL_CULL_FACE);
        glEnable(GL_BLEND);
        glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        shader.use();
        shader.mat4("uProj", proj);
        shader.i1("uTex", 0);
        quads = 0;
        currentTex = white;
        curMode = 0;
    }

    public void end() {
        flush();
        glDisable(GL_BLEND);
        glEnable(GL_DEPTH_TEST);
    }

    public void flush() {
        if (quads == 0) return;
        buf.flip();
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, currentTex);
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
        glDrawArrays(GL_TRIANGLES, 0, quads * 6);
        buf.clear();
        quads = 0;
    }

    private void tex(int t) { if (t != currentTex) { flush(); currentTex = t; } }

    /** mode 0 = rgba texture, 1 = alpha-only (font) texture. */
    private void vert(float x, float y, float u, float v, float r, float g, float b, float a, float mode) {
        buf.put(x).put(y).put(u).put(v).put(r).put(g).put(b).put(a).put(mode);
    }

    public void quad(int texId, float x, float y, float w, float h, float u0, float v0, float u1, float v1, float r, float g, float b, float a, int mode) {
        tex(texId);
        if (quads >= MAX_QUADS) flush();
        vert(x, y, u0, v0, r, g, b, a, mode);
        vert(x + w, y, u1, v0, r, g, b, a, mode);
        vert(x + w, y + h, u1, v1, r, g, b, a, mode);
        vert(x + w, y + h, u1, v1, r, g, b, a, mode);
        vert(x, y + h, u0, v1, r, g, b, a, mode);
        vert(x, y, u0, v0, r, g, b, a, mode);
        quads++;
    }

    /** Quad with arbitrary corner positions (rotated icons, lines). */
    public void quad4(int texId, float[] xy, float[] uv, float r, float g, float b, float a, int mode) {
        tex(texId);
        if (quads >= MAX_QUADS) flush();
        int[] order = {0, 1, 2, 2, 3, 0};
        for (int k : order) vert(xy[k * 2], xy[k * 2 + 1], uv[k * 2], uv[k * 2 + 1], r, g, b, a, mode);
        quads++;
    }

    public void rect(float x, float y, float w, float h, int argb) {
        quad(white, x, y, w, h, 0, 0, 1, 1, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f, 0);
    }

    public void rect(float x, float y, float w, float h, float r, float g, float b, float a) {
        quad(white, x, y, w, h, 0, 0, 1, 1, r, g, b, a, 0);
    }

    public void outline(float x, float y, float w, float h, float t, int argb) {
        rect(x, y, w, t, argb);
        rect(x, y + h - t, w, t, argb);
        rect(x, y, t, h, argb);
        rect(x + w - t, y, t, h, argb);
    }

    public void line(float x0, float y0, float x1, float y1, float t, int argb) {
        float dx = x1 - x0, dy = y1 - y0, l = (float) Math.sqrt(dx * dx + dy * dy);
        if (l < 1e-3f) return;
        float nx = -dy / l * t / 2, ny = dx / l * t / 2;
        quad4(white, new float[]{x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny}, new float[8],
                ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f, 0);
    }

    public void image(int texId, float x, float y, float w, float h, int argb) {
        quad(texId, x, y, w, h, 0, 0, 1, 1, ((argb >> 16) & 255) / 255f, ((argb >> 8) & 255) / 255f, (argb & 255) / 255f, ((argb >>> 24) & 255) / 255f, 0);
    }

    public void scissor(int x, int y, int w, int h) {
        flush();
        glEnable(GL_SCISSOR_TEST);
        glScissor(x, height - y - h, Math.max(0, w), Math.max(0, h));
    }

    public void noScissor() { flush(); glDisable(GL_SCISSOR_TEST); }

    public int whiteTex() { return white; }

    private static final String VS = astratto.render.Shaders.source("ui2d.vert");
    private static final String FS = astratto.render.Shaders.source("ui2d.frag");
}
