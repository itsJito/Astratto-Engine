package astratto.render;

import static org.lwjgl.opengl.GL20.*;

import astratto.math.Mat4;
import java.util.HashMap;
import java.util.Map;

/** Compiled GLSL program with cached uniform locations. */
public final class Shader {
    public final int id;
    private final Map<String, Integer> locs = new HashMap<>();

    public Shader(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs), f = compile(GL_FRAGMENT_SHADER, fs);
        id = glCreateProgram();
        glAttachShader(id, v);
        glAttachShader(id, f);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) throw new IllegalStateException("Link: " + glGetProgramInfoLog(id));
        glDeleteShader(v);
        glDeleteShader(f);
    }

    /** Compute program (OpenGL 4.3+). */
    public Shader(String cs) {
        int c = compile(org.lwjgl.opengl.GL43.GL_COMPUTE_SHADER, cs);
        id = glCreateProgram();
        glAttachShader(id, c);
        glLinkProgram(id);
        if (glGetProgrami(id, GL_LINK_STATUS) == 0) throw new IllegalStateException("Link: " + glGetProgramInfoLog(id));
        glDeleteShader(c);
    }

    private static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) == 0) throw new IllegalStateException("Shader compile: " + glGetShaderInfoLog(s) + "\n" + numbered(src));
        return s;
    }

    private static String numbered(String src) {
        StringBuilder b = new StringBuilder();
        String[] l = src.split("\n");
        for (int i = 0; i < l.length; i++) b.append(String.format("%4d  ", i + 1)).append(l[i]).append("\n");
        return b.toString();
    }

    public void use() { glUseProgram(id); }
    public void mat4(String n, float[] m16) { glUniformMatrix4fv(loc(n), false, m16); }
    public void i2(String n, int a, int b) { glUniform2i(loc(n), a, b); }
    public void i3(String n, int a, int b, int c) { glUniform3i(loc(n), a, b, c); }
    public void fv(String n, float[] v) { glUniform1fv(loc(n), v); }
    public void iv2(String n, int[] v) { glUniform2iv(loc(n), v); }
    public void uiv(String n, int[] v) { org.lwjgl.opengl.GL30.glUniform1uiv(loc(n), v); }
    public void mat3(String n, float[] m9) { glUniformMatrix3fv(loc(n), false, m9); }
    /** Binds a texture of the given target to a unit and points the sampler uniform at it. */
    public void tex(String n, int unit, int target, int texId) {
        org.lwjgl.opengl.GL13.glActiveTexture(org.lwjgl.opengl.GL13.GL_TEXTURE0 + unit);
        org.lwjgl.opengl.GL11.glBindTexture(target, texId);
        glUniform1i(loc(n), unit);
    }

    private int loc(String n) { return locs.computeIfAbsent(n, k -> glGetUniformLocation(id, k)); }

    private final float[] buf = new float[16];
    public void mat4(String n, Mat4 m) { glUniformMatrix4fv(loc(n), false, m.m); }
    public void f1(String n, float v) { glUniform1f(loc(n), v); }
    public void i1(String n, int v) { glUniform1i(loc(n), v); }
    public void f2(String n, float a, float b) { glUniform2f(loc(n), a, b); }
    public void f3(String n, float a, float b, float c) { glUniform3f(loc(n), a, b, c); }
    public void f4(String n, float a, float b, float c, float d) { glUniform4f(loc(n), a, b, c, d); }
}
