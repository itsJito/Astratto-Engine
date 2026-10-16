package astratto.render;

/**
 * GLSL 4.50 sources of the hybrid global-illumination renderer (see {@link HybridRenderer}), loaded from the engine's
 * shader files ({@code shaders/}, see {@link Shaders}) - the same files the native renderer is given.
 *
 * Conventions shared by every program:
 *   - light "colour" values are radiance-per-unit-albedo (irradiance / pi): outgoing = albedo * L * N.L
 *   - probes store irradiance / pi as a constant term and a linear (L1) term per colour channel
 *   - albedo in the G-buffer is display-encoded (as painted) and linearised where it is lit
 *   - depth is the ordinary 0..1 window depth of the jittered projection; 1.0 is sky
 */
public final class Glsl {
    private Glsl() {}

    /** Texels of a see-through block at or above this opacity are not glass: they are the frame, and are drawn solid. */
    public static final float GLASS_SOLID = 0.92f;

    // 2.0: the sources are the engine's shader files (theastrattoengine/shaders), read once; these names are kept for the Java renderer
    public static final String HEAD = Shaders.source("include/head.glsl");
    public static final String WORLD_VS = Shaders.source("world.vert");
    public static final String WORLD_TEX = Shaders.source("include/world_tex.glsl");
    public static final String WORLD_GBUF_FS = Shaders.source("world_gbuffer.frag");
    public static final String WORLD_SHADOW_FS = Shaders.source("world_shadow.frag");
    public static final String MODEL_VS = Shaders.source("model.vert");
    public static final String MODEL_GBUF_FS = Shaders.source("model_gbuffer.frag");
    public static final String MODEL_SHADOW_FS = Shaders.source("model_shadow.frag");
    public static final String FS_VS = Shaders.source("fullscreen.vert");
    public static final String VOXEL = Shaders.source("include/voxel.glsl");
    public static final String SKY = Shaders.source("include/sky.glsl");
    public static final String PROBES = Shaders.source("include/probes.glsl");
    public static final String BANK = Shaders.source("include/bank.glsl");
    public static final String LIGHT_BUFFERS = Shaders.source("include/light_buffers.glsl");
    public static final String LIGHTING_FS = Shaders.source("lighting.frag");
    public static final String LIGHT_CULL_CS = Shaders.source("light_cull.comp");
    public static final String PROBE_CS = Shaders.source("probes.comp");
    public static final String GTAO_FS = Shaders.source("gtao.frag");
    public static final String AO_BLUR_FS = Shaders.source("ao_blur.frag");
    public static final String SSR_FS = Shaders.source("ssr.frag");
    public static final String VOLUMETRIC_FS = Shaders.source("volumetric.frag");
    public static final String FOG_APPLY_FS = Shaders.source("fog_apply.frag");
    public static final String FWD_FOG = Shaders.source("include/fwd_fog.glsl");
    public static final String WORLD_FWD_FS = Shaders.source("world_forward.frag");
    public static final String MODEL_FWD_FS = Shaders.source("model_forward.frag");
    public static final String PARTICLE_VS = Shaders.source("particle.vert");
    public static final String PARTICLE_FS = Shaders.source("particle.frag");
    public static final String TAA_FS = Shaders.source("taa.frag");
    public static final String BLOOM_DOWN_FS = Shaders.source("bloom_down.frag");
    public static final String BLOOM_UP_FS = Shaders.source("bloom_up.frag");
    public static final String LUM_FS = Shaders.source("luminance.frag");
    public static final String EXPOSURE_FS = Shaders.source("exposure.frag");
    public static final String TONEMAP_FS = Shaders.source("tonemap.frag");
    public static final String COPY_FS = Shaders.source("copy.frag");
}
