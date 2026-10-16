package astratto.render;

/** What a frame is lit and fogged by: the sun, the sky, fog, weather, the camera, the player's own light. Plain values, set by the game each frame. */
public final class Env {
    /** The fog bank (0.6): metres at which the world starts to dissolve and is gone; how much of the sky it takes at the horizon and overhead. Off by default (previews, menus). */
    public float fogWallStart = 1e9f, fogWallEnd = 2e9f, fogSkyHorizon, fogSkyZenith;
    public float daylight = 1, time, fogDensity = 1f / 200f, flashlight, rain, sunX = 0.3f, sunY = 0.8f, sunZ = 0.4f;
    /** 0.7: the least light any surface gets (daylight that has bounced its way indoors; a trace at night). */
    public float ambientFloor = 0.0035f;
    /** 1.0, the weather system's numbers: cloud over the sun (-1 = take it from rain), how wet open ground is (-1 = from rain), how much snow lies, the lightning's light this instant. */
    public float overcast = -1, wet = -1, snow, lightning;
    public float fogR = 0.6f, fogG = 0.62f, fogB = 0.64f, skyR = 1, skyG = 1, skyB = 1, tintR = 1, tintG = 1, tintB = 1;
    public float camX, camY, camZ, dirX, dirY, dirZ, flashR, flashX, flashY, flashZ;
}
