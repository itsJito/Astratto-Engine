package astratto.render;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Astratto Engine: the engine's GLSL, read from its shader files ({@code shaders/} in the engine, bundled into the jar
 * as {@code astratto/shaders/}). Shader code stays shader code: these are ordinary .vert/.frag/.comp files, which may
 * {@code #include "include/<piece>.glsl"} - a line that is replaced by the file it names (relative to {@code shaders/}),
 * before the source is handed to the GL (or to the native renderer, which takes its sources by name).
 * {@code ASTRATTO_SHADERS} names a directory whose files are used in place of the bundled ones.
 */
public final class Shaders {
    private Shaders() {}

    private static final Map<String, String> CACHE = new ConcurrentHashMap<>();

    /** Every program the engine's renderers use, by file name. */
    public static final String[] PROGRAMS = {
            "world.vert", "world_gbuffer.frag", "world_shadow.frag", "model.vert", "model_gbuffer.frag", "model_shadow.frag", "fullscreen.vert",
            "lighting.frag", "light_cull.comp", "probes.comp", "gtao.frag", "ao_blur.frag", "ssr.frag", "volumetric.frag", "fog_apply.frag",
            "world_forward.frag", "model_forward.frag", "particle.vert", "particle.frag", "taa.frag", "bloom_down.frag", "bloom_up.frag",
            "luminance.frag", "exposure.frag", "tonemap.frag", "copy.frag", "ui2d.vert", "ui2d.frag", "model_simple.vert", "model_simple.frag",
            "decal.vert", "decal.frag", "foliage.vert", "foliage_gbuffer.frag", "foliage_shadow.frag", "props.vert", "props_gbuffer.frag",
            "props_shadow.frag"};

    /** A shader's source with its includes resolved. Throws when the engine's shaders cannot be found. */
    public static String source(String name) {
        return CACHE.computeIfAbsent(name, n -> resolve(n, 0));
    }

    private static String resolve(String name, int depth) {
        if (depth > 8) throw new IllegalStateException("shader includes nested too deep at " + name);
        String text = raw(name);
        StringBuilder out = new StringBuilder(text.length() + 256);
        int at = 0;
        while (at < text.length()) {
            int end = text.indexOf('\n', at);
            int next = end < 0 ? text.length() : end + 1;
            String line = text.substring(at, end < 0 ? text.length() : end);
            String s = line.endsWith("\r") ? line.substring(0, line.length() - 1) : line;
            if (s.startsWith("#include \"") && s.endsWith("\"")) out.append(resolve(s.substring(10, s.length() - 1), depth + 1));
            else out.append(text, at, next);
            at = next;
        }
        return out.toString();
    }

    private static String raw(String name) {
        // ASTRATTO_SHADERS: a directory of shader files that takes precedence (working on shaders without a rebuild)
        String over = System.getenv("ASTRATTO_SHADERS");
        if (over != null && !over.isBlank() && new File(over, name).isFile()) {
            try { return Files.readString(new File(over, name).toPath(), StandardCharsets.UTF_8); }
            catch (IOException ignored) { }
        }
        try (InputStream in = Shaders.class.getResourceAsStream("/astratto/shaders/" + name)) {
            if (in != null) return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException ignored) { }
        File dir = directory();
        if (dir != null) {
            try { return Files.readString(new File(dir, name).toPath(), StandardCharsets.UTF_8); }
            catch (IOException ignored) { }
        }
        throw new IllegalStateException("shader " + name + " not found (the engine's shaders/ is bundled into its jar by its build)");
    }

    /** The engine's shaders/ directory, when running from its source tree rather than its jar. */
    public static File directory() {
        File b = astratto.bridge.AstrattoNative.engineBuildDir();
        if (b != null) {
            File root = b.getAbsoluteFile().getParentFile().getParentFile().getParentFile();     // <engine>/native/bin/<platform>
            if (root != null && new File(root, "shaders").isDirectory()) return new File(root, "shaders");
        }
        for (String base : new String[]{"theastrattoengine", ".", ".."}) if (new File(base, "shaders/include/head.glsl").isFile()) return new File(base, "shaders");
        return null;
    }
}
