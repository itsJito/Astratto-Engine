package astratto.ai.api;

/** A point or direction in the host's world: metres, +y up. The one value type that crosses the seam in both directions. */
public record Vec3(double x, double y, double z) {
    public static final Vec3 ZERO = new Vec3(0, 0, 0);

    public Vec3 add(Vec3 o) { return new Vec3(x + o.x, y + o.y, z + o.z); }
    public Vec3 sub(Vec3 o) { return new Vec3(x - o.x, y - o.y, z - o.z); }
    public Vec3 mul(double n) { return new Vec3(x * n, y * n, z * n); }
    public double dot(Vec3 o) { return x * o.x + y * o.y + z * o.z; }
    public double length() { return Math.sqrt(dot(this)); }
    public double distance(Vec3 o) { return sub(o).length(); }
    /** Distance over the ground, ignoring height. */
    public double distanceXZ(Vec3 o) { double dx = x - o.x, dz = z - o.z; return Math.sqrt(dx * dx + dz * dz); }
    public Vec3 normalized() { double d = length(); return d < 1e-9 ? ZERO : mul(1 / d); }
    public Vec3 flat() { return new Vec3(x, 0, z); }
    public Vec3 withY(double ny) { return new Vec3(x, ny, z); }
    /** The horizontal direction a quarter turn to the left of this one. */
    public Vec3 left() { return new Vec3(z, 0, -x); }
}
