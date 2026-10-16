package astratto.render;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL15.*;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;
import static org.lwjgl.opengl.GL31.*;
import static org.lwjgl.opengl.GL33.*;

import java.nio.FloatBuffer;
import org.lwjgl.system.MemoryUtil;

/**
 * Camera-facing particles drawn in one instanced call: flames, smoke, embers and soft glows. Simulation lives with
 * whoever owns the effect (see scene.FireSystem); this class is only the per-frame vertex stream. Output is
 * premultiplied, so glowing kinds add their light and smoke covers what is behind it in the same pass.
 */
public final class ParticleBatch {
    public static final int FLAME = 0, SMOKE = 1, EMBER = 2, GLOW = 3;
    private static final int FLOATS = 12;
    private int vao, vbo, capacity = 4096;
    private FloatBuffer buf = MemoryUtil.memAllocFloat(capacity * FLOATS);
    public int count;

    public void init() {
        vao = glGenVertexArrays();
        vbo = glGenBuffers();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * FLOATS * 4, GL_STREAM_DRAW);
        for (int i = 0; i < 3; i++) {
            glVertexAttribPointer(i, 4, GL_FLOAT, false, FLOATS * 4, i * 16L);
            glEnableVertexAttribArray(i);
            glVertexAttribDivisor(i, 1);
        }
        glBindVertexArray(0);
    }

    public void clear() { count = 0; buf.clear(); }

    /** 2.0: the particles added since clear(), 12 floats each, read-only (what the native renderer is handed). */
    public FloatBuffer data() { FloatBuffer d = buf.duplicate(); d.flip(); return d.asReadOnlyBuffer(); }

    /** rgb may exceed 1 (HDR); age is 0..1 over the particle's life; seed decorrelates the noise. */
    public void add(float x, float y, float z, float size, float r, float g, float b, float a, float rotation, int kind, float age, float seed) {
        if (count == capacity) {
            capacity *= 2;
            FloatBuffer nb = MemoryUtil.memAllocFloat(capacity * FLOATS);
            buf.flip();
            nb.put(buf);
            MemoryUtil.memFree(buf);
            buf = nb;
        }
        buf.put(x).put(y).put(z).put(size).put(r).put(g).put(b).put(a).put(rotation).put(kind).put(age).put(seed);
        count++;
    }

    /** Draws everything added since clear() with the bound particle program. */
    public void draw() {
        if (count == 0) return;
        buf.flip();
        glBindVertexArray(vao);
        glBindBuffer(GL_ARRAY_BUFFER, vbo);
        glBufferData(GL_ARRAY_BUFFER, (long) capacity * FLOATS * 4, GL_STREAM_DRAW);   // orphan, then fill
        glBufferSubData(GL_ARRAY_BUFFER, 0, buf);
        glDrawArraysInstanced(GL_TRIANGLE_STRIP, 0, 4, count);
        glBindVertexArray(0);
        buf.limit(buf.capacity());
        buf.position(count * FLOATS);
    }
}
