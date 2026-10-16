package astratto.math;

/** Small mutable 3-vector plus scalar helpers. */
public final class V3 {
    public float x, y, z;
    public V3() {}
    public V3(float x, float y, float z) { this.x = x; this.y = y; this.z = z; }
    public V3(V3 o) { x = o.x; y = o.y; z = o.z; }
    public V3 set(float x, float y, float z) { this.x = x; this.y = y; this.z = z; return this; }
    public V3 set(V3 o) { x = o.x; y = o.y; z = o.z; return this; }
    public V3 add(float ax, float ay, float az) { x += ax; y += ay; z += az; return this; }
    public V3 add(V3 o, float s) { x += o.x * s; y += o.y * s; z += o.z * s; return this; }
    public float len() { return (float) Math.sqrt(x * x + y * y + z * z); }
    public V3 norm() { float l = len(); if (l > 1e-6f) { x /= l; y /= l; z /= l; } return this; }
    public float dist(V3 o) { float dx = x - o.x, dy = y - o.y, dz = z - o.z; return (float) Math.sqrt(dx * dx + dy * dy + dz * dz); }
    public float dist(float ox, float oy, float oz) { float dx = x - ox, dy = y - oy, dz = z - oz; return (float) Math.sqrt(dx * dx + dy * dy + dz * dz); }
    public float distXZ(V3 o) { float dx = x - o.x, dz = z - o.z; return (float) Math.sqrt(dx * dx + dz * dz); }
    public float distXZ(float ox, float oz) { float dx = x - ox, dz = z - oz; return (float) Math.sqrt(dx * dx + dz * dz); }
    @Override public String toString() { return String.format("(%.1f, %.1f, %.1f)", x, y, z); }

    public static float clamp(float v, float a, float b) { return v < a ? a : (v > b ? b : v); }
    public static int clamp(int v, int a, int b) { return v < a ? a : (v > b ? b : v); }
    public static double clamp(double v, double a, double b) { return v < a ? a : (v > b ? b : v); }
    public static float lerp(float a, float b, float t) { return a + (b - a) * t; }
    public static float wrapAngle(float a) {
        while (a > Math.PI) a -= (float) (Math.PI * 2);
        while (a < -Math.PI) a += (float) (Math.PI * 2);
        return a;
    }
    public static float approach(float v, float target, float step) {
        if (v < target) return Math.min(target, v + step);
        return Math.max(target, v - step);
    }
}
