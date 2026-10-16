package astratto.math;

/** View frustum extracted from a combined projection*view matrix, used for chunk culling. */
public final class Frustum {
    private final float[][] p = new float[6][4];

    public void update(Mat4 pv) {
        float[] m = pv.m;
        set(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]);
        set(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]);
        set(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]);
        set(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]);
        set(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]);
        set(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]);
    }

    private void set(int i, float a, float b, float c, float d) {
        float l = (float) Math.sqrt(a * a + b * b + c * c);
        p[i][0] = a / l; p[i][1] = b / l; p[i][2] = c / l; p[i][3] = d / l;
    }

    public boolean aabb(float x0, float y0, float z0, float x1, float y1, float z1) {
        for (float[] q : p) {
            float x = q[0] >= 0 ? x1 : x0;
            float y = q[1] >= 0 ? y1 : y0;
            float z = q[2] >= 0 ? z1 : z0;
            if (q[0] * x + q[1] * y + q[2] * z + q[3] < 0) return false;
        }
        return true;
    }

    public boolean sphere(float x, float y, float z, float r) {
        for (float[] q : p) if (q[0] * x + q[1] * y + q[2] * z + q[3] < -r) return false;
        return true;
    }
}
