package astratto.audio.gun;

/**
 * One round leaving one weapon, as the audio system sees it (0.5, brief 17 phase 4). Immutable: built on the game
 * thread with everything the audio thread will need - when (an absolute sample position on the mixer's clock), what
 * (the weapon's profile and which recorded take), where (muzzle position and direction), how it reaches the
 * listener (the acoustic snapshot taken at the moment of firing), and the seed its variation was drawn from, so the
 * same seed reproduces the same shot.
 */
public record ShotEvent(String weaponId, WeaponAudioProfile profile, int shooterId, int shotIndex, long sample,
                        float x, float y, float z, float dirX, float dirZ, boolean suppressed, boolean local, long seed,
                        int take, int bodyTake, int distantTake, float rateVar, float gainVar, AcousticEnvironment.Acoustics acoustics) {

    /** Deterministic variation from a seed: {take selector, rate factor, gain factor}. */
    public static float[] variation(long seed, float rateBound, float dbBound) { return vary(seed, rateBound, dbBound); }

    static float[] vary(long seed, float rateBound, float dbBound) {
        long s = seed * 0x9E3779B97F4A7C15L + 0x632BE59BD9B4E019L;
        s ^= s >>> 29; s *= 0xBF58476D1CE4E5B9L; s ^= s >>> 32;
        float a = ((s >>> 11) & 0xFFFF) / 65535f, b = ((s >>> 27) & 0xFFFF) / 65535f;
        float rate = 1 + (a * 2 - 1) * rateBound;
        float db = (b * 2 - 1) * dbBound;
        return new float[]{rate, (float) Math.pow(10, db / 20.0)};
    }
}
