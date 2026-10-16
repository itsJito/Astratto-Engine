package astratto.math;

/** Column-major 4x4 float matrix (OpenGL layout). Mutable, allocation-light. */
public final class Mat4 {
    public final float[] m = new float[16];

    public Mat4() { identity(); }

    public Mat4 identity() {
        java.util.Arrays.fill(m, 0f);
        m[0] = m[5] = m[10] = m[15] = 1f;
        return this;
    }

    public Mat4 set(Mat4 o) { System.arraycopy(o.m, 0, m, 0, 16); return this; }

    public Mat4 perspective(float fovyRad, float aspect, float near, float far) {
        java.util.Arrays.fill(m, 0f);
        float f = (float) (1.0 / Math.tan(fovyRad / 2));
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = -1f;
        m[14] = (2 * far * near) / (near - far);
        return this;
    }

    public Mat4 ortho(float l, float r, float b, float t, float n, float f) {
        java.util.Arrays.fill(m, 0f);
        m[0] = 2 / (r - l);
        m[5] = 2 / (t - b);
        m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l);
        m[13] = -(t + b) / (t - b);
        m[14] = -(f + n) / (f - n);
        m[15] = 1;
        return this;
    }

    private static final ThreadLocal<float[]> TMP = ThreadLocal.withInitial(() -> new float[16]);
    private static final ThreadLocal<Mat4> TMPM = ThreadLocal.withInitial(Mat4::new);

    /** this = this * o */
    public Mat4 mul(Mat4 o) {
        float[] a = m, b = o.m, r = TMP.get();
        for (int c = 0; c < 4; c++) {
            for (int rr = 0; rr < 4; rr++) {
                r[c * 4 + rr] = a[rr] * b[c * 4] + a[4 + rr] * b[c * 4 + 1] + a[8 + rr] * b[c * 4 + 2] + a[12 + rr] * b[c * 4 + 3];
            }
        }
        System.arraycopy(r, 0, m, 0, 16);
        return this;
    }

    public Mat4 translate(float x, float y, float z) {
        m[12] += m[0] * x + m[4] * y + m[8] * z;
        m[13] += m[1] * x + m[5] * y + m[9] * z;
        m[14] += m[2] * x + m[6] * y + m[10] * z;
        m[15] += m[3] * x + m[7] * y + m[11] * z;
        return this;
    }

    public Mat4 scale(float x, float y, float z) {
        for (int i = 0; i < 4; i++) { m[i] *= x; m[4 + i] *= y; m[8 + i] *= z; }
        return this;
    }

    public Mat4 rotateX(float a) {
        Mat4 r = TMPM.get().identity();
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        r.m[5] = c; r.m[6] = s; r.m[9] = -s; r.m[10] = c;
        return mul(r);
    }

    public Mat4 rotateY(float a) {
        Mat4 r = TMPM.get().identity();
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        r.m[0] = c; r.m[2] = -s; r.m[8] = s; r.m[10] = c;
        return mul(r);
    }

    public Mat4 rotateZ(float a) {
        Mat4 r = TMPM.get().identity();
        float c = (float) Math.cos(a), s = (float) Math.sin(a);
        r.m[0] = c; r.m[1] = s; r.m[4] = -s; r.m[5] = c;
        return mul(r);
    }

    /** View matrix looking from eye toward centre with the given up vector. */
    public Mat4 lookAt(float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez, fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        fx /= fl; fy /= fl; fz /= fl;
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux, sl = (float) Math.sqrt(sx * sx + sy * sy + sz * sz);
        sx /= sl; sy /= sl; sz /= sl;
        float vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
        m[0] = sx; m[4] = sy; m[8] = sz; m[12] = -(sx * ex + sy * ey + sz * ez);
        m[1] = vx; m[5] = vy; m[9] = vz; m[13] = -(vx * ex + vy * ey + vz * ez);
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = fx * ex + fy * ey + fz * ez;
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
        return this;
    }

    /** this = inverse(src). General 4x4 inverse (cofactors); returns this, or identity when src is singular. */
    public Mat4 invert(Mat4 src) {
        float[] a = src.m, inv = TMP.get();
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (Math.abs(det) < 1e-20f) return identity();
        det = 1f / det;
        for (int i = 0; i < 16; i++) m[i] = inv[i] * det;
        return this;
    }

    /** Transforms point (x,y,z,1) and writes into out[0..2]. */
    public void transformPoint(float x, float y, float z, float[] out) {
        float ox = m[0] * x + m[4] * y + m[8] * z + m[12];
        float oy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float oz = m[2] * x + m[6] * y + m[10] * z + m[14];
        out[0] = ox; out[1] = oy; out[2] = oz;
    }

    /** Transforms a direction (x,y,z,0). */
    public void transformDir(float x, float y, float z, float[] out) {
        float ox = m[0] * x + m[4] * y + m[8] * z;
        float oy = m[1] * x + m[5] * y + m[9] * z;
        float oz = m[2] * x + m[6] * y + m[10] * z;
        out[0] = ox; out[1] = oy; out[2] = oz;
    }

    /** Projects world point to clip space; returns w; writes NDC into out. */
    public float project(float x, float y, float z, float[] out) {
        float cx = m[0] * x + m[4] * y + m[8] * z + m[12];
        float cy = m[1] * x + m[5] * y + m[9] * z + m[13];
        float cz = m[2] * x + m[6] * y + m[10] * z + m[14];
        float cw = m[3] * x + m[7] * y + m[11] * z + m[15];
        if (Math.abs(cw) < 1e-6f) cw = 1e-6f;
        out[0] = cx / cw; out[1] = cy / cw; out[2] = cz / cw;
        return cw;
    }
}
