package astratto.util;

/** Deterministic seeded random stream (SplitMix64). Used for reproducible generation and simulation. */
public final class Rng {
    private long s;
    public Rng(long seed) { s = seed ^ 0x9E3779B97F4A7C15L; }
    public long nextLong() {
        long z = (s += 0x9E3779B97F4A7C15L);
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
    public int nextInt(int bound) { if (bound <= 0) return 0; return (int) ((nextLong() >>> 1) % bound); }
    public int range(int lo, int hiInclusive) { if (hiInclusive < lo) return lo; return lo + nextInt(hiInclusive - lo + 1); }
    public float nextFloat() { return (nextLong() >>> 40) / (float) (1L << 24); }
    public float range(float lo, float hi) { return lo + nextFloat() * (hi - lo); }
    public boolean chance(double p) { return nextFloat() < p; }
    public double nextGaussian() {
        double u = Math.max(1e-9, nextFloat()), v = nextFloat();
        return Math.sqrt(-2 * Math.log(u)) * Math.cos(2 * Math.PI * v);
    }
    public <T> T pick(T[] a) { return a[nextInt(a.length)]; }
    public <T> T pick(java.util.List<T> a) { return a.get(nextInt(a.size())); }
    public int pick(int[] a) { return a[nextInt(a.length)]; }

    /** Stateless positional hash in [0,1). */
    public static float hash(int x, int y, int z, int seed) {
        long h = x * 73856093L ^ y * 19349663L ^ z * 83492791L ^ seed * 2654435761L;
        h = mix(h, seed);
        return ((h >>> 20) & 0xFFFFFF) / (float) 0x1000000;
    }
    public static long mix(long a, long b) {
        long z = a * 0x9E3779B97F4A7C15L + b;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }
}
