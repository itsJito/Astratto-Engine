package starter;

import astratto.Astratto;
import astratto.bridge.AstrattoNative;
import astratto.bridge.NativeEngine;
import astratto.game.Game;
import astratto.platform.Window;
import java.io.File;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.system.MemoryUtil;

/**
 * The starter game: the smallest thing that uses all of the Astratto Engine - a window, the native renderer, an engine
 * world built from code, a player (Java) moving a body the engine simulates, an AI watcher whose wants are written in
 * Java and carried out natively, Sydney's ragdoll, a car and a soft body, positional audio through the engine's mixer,
 * and a gameplay module written in C++ (the engine's example "radio", loaded from its library) running inside the
 * engine's frame beside the Java modules.
 *
 * <pre>
 *   ./gradlew run                                  play: WASD, mouse, Space, Shift; Esc quits
 *   ./gradlew run --args="--frames 600 --hidden --check [--shot out.png]"
 *                                                  run 600 frames off-screen, then check that every service did its part
 * </pre>
 */
public final class StarterGame {
    public static void main(String[] args) {
        int frames = -1;
        boolean hidden = false, check = false;
        String shot = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--frames" -> frames = Integer.parseInt(args[++i]);
                case "--hidden" -> hidden = true;
                case "--check" -> check = true;
                case "--shot" -> shot = args[++i];
                default -> { System.err.println("unknown argument " + args[i]); System.exit(2); }
            }
        }
        System.exit(run(frames, hidden, check, shot));
    }

    static int run(int frames, boolean hidden, boolean check, String shot) {
        System.out.println(Astratto.NAME + " " + Astratto.VERSION + " - starter game" + (Astratto.javaFallbackAllowed() ? "" : " (Java fallbacks disabled)"));
        Window win = new Window();
        win.show = !hidden;
        win.create("Astratto starter", 1280, 720);
        if (!win.gl45) { System.err.println("the native renderer needs OpenGL 4.5: " + win.glInfo); return 1; }
        boolean scripted = frames >= 0;
        if (!scripted) win.setCursorLocked(true);

        NativeEngine.Config cfg = new NativeEngine.Config();
        // an automated run mixes its audio on request (the same mixer, no device needed); play opens the speakers
        cfg.audioDevice = !scripted;
        cfg.deterministic = scripted;
        Game game;
        try { game = Game.create(cfg); }
        catch (IllegalStateException e) { System.err.println(e.getMessage()); win.destroy(); return 1; }
        System.out.println(AstrattoNative.buildInfo() + " (" + AstrattoNative.path() + ")");
        boolean audioOnRequest = !game.engine().audioStats().device;

        long world = EmptyMap.build(game.engine());
        game.activate(world);
        PlayerModule player = game.add(new PlayerModule(win.input, scripted));
        WatcherModule watcher = game.add(new WatcherModule(player));
        ToysModule toys = game.add(new ToysModule());
        SoundModule sound = game.add(new SoundModule(player, audioOnRequest));
        Census census = game.add(new Census());

        // the C++ module: a radio near the hut that comes on every few seconds (the watcher hears it and goes to look)
        String module = findModule("example");
        if (module != null) game.loadNative(module, "radio 45.5 5 38.5 3 0.9");
        else System.out.println("no C++ example module found next to the native library; running Java modules only");

        View view = new View(game.engine(), win.width, win.height);
        long last = System.nanoTime();
        int n = 0;
        int status = 0;
        try {
            while (!win.shouldClose() && (frames < 0 || n < frames)) {
                win.poll();
                if (win.input.keyPressed(org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE)) win.requestClose();
                long now = System.nanoTime();
                float dt = scripted ? 1f / 60f : Math.min(0.1f, (now - last) / 1e9f);
                last = now;
                if (!game.tick(dt)) { status = 1; break; }
                view.draw(world, player, game.bodies(), watcher, toys, Math.max(1, win.width), Math.max(1, win.height), dt, game.time());
                win.swap();
                win.input.endFrame();
                n++;
                if (!scripted && n % 120 == 0) win.setTitle("Astratto starter - " + view.last);
            }
            System.out.println("[render] " + view.last);
            System.out.println(watcher.describe());
            System.out.println(toys.describe());
            System.out.println(sound.describe());
            System.out.println(census.describe());
            if (shot != null) save(view, win.width, win.height, new File(shot));
            if (check) status = check(view, win, player, watcher, toys, sound, census, module != null, n);
        } finally {
            view.close();
            game.close();
            win.destroy();
        }
        return status;
    }

    /** That every part of the engine did its share, in an automated run. */
    private static int check(View view, Window win, PlayerModule player, WatcherModule watcher, ToysModule toys, SoundModule sound, Census census, boolean module, int n) {
        List<String> failed = new ArrayList<>();
        need(failed, "rendering: the world was meshed and drawn natively", view.last != null && view.last.columnsDrawn > 0 && view.last.quadsDrawn > 0 && view.frames == n);
        ByteBuffer px = view.pixels(win.width, win.height);
        java.util.Set<Integer> colours = new java.util.HashSet<>();
        for (int i = 0; i < win.width * win.height; i += 97) colours.add(px.getInt(i * 4) & 0xF8F8F8);
        MemoryUtil.memFree(px);
        need(failed, "rendering: the picture is a picture (" + colours.size() + " distinct colours sampled)", colours.size() > 40);
        need(failed, "physics: the player's body stands on the ground and walked", player.grounded && player.walked > 3);
        need(failed, "physics: Sydney's person was knocked about (state changes)", toys.ragdollStates > 0);
        need(failed, "physics: the car drove", toys.carTravel > 4);
        need(failed, "AI: the watcher decided, and moved its body", watcher.decisions > 0 && watcher.travelled > 2);
        need(failed, "AI: the watcher heard something", watcher.heard > 0);
        need(failed, "audio: the mixer mixed voices", sound.framesMixed > 0 && sound.peakVoices > 0);
        need(failed, "Java modules ran every tick", player.updates == n && census.updates == n);
        need(failed, "events reached the Java modules", census.events > 0);
        if (module) need(failed, "the C++ module ran inside the frames and logged", census.moduleLines > 0);
        if (failed.isEmpty()) { System.out.println("starter: every check passed"); return 0; }
        for (String f : failed) System.out.println("starter: FAILED " + f);
        return 1;
    }

    private static void need(List<String> failed, String what, boolean ok) {
        System.out.println("  [" + (ok ? " ok " : "FAIL") + "] " + what);
        if (!ok) failed.add(what);
    }

    /** The example gameplay module's library: beside the native core, or bundled in the engine's jar. */
    static String findModule(String base) {
        String name = AstrattoNative.moduleName(base);
        File beside = new File(new File(AstrattoNative.path()).getParentFile(), name);
        if (beside.isFile()) return beside.getAbsolutePath();
        File bundled = AstrattoNative.extractBundled(name);
        if (bundled != null) return bundled.getAbsolutePath();
        File dir = AstrattoNative.engineBuildDir();
        if (dir != null && new File(dir, name).isFile()) return new File(dir, name).getAbsolutePath();
        return null;
    }

    private static void save(View view, int w, int h, File out) {
        ByteBuffer px = view.pixels(w, h);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - 1 - y) * w + x) * 4;
            img.setRGB(x, y, (px.get(i) & 255) << 16 | (px.get(i + 1) & 255) << 8 | (px.get(i + 2) & 255));
        }
        MemoryUtil.memFree(px);
        try {
            if (out.getAbsoluteFile().getParentFile() != null) out.getAbsoluteFile().getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", out);
            System.out.println("[shot] " + out.getAbsolutePath());
        } catch (java.io.IOException e) { System.err.println("could not save " + out + ": " + e); }
    }

    /** A Java module that only listens: it counts what the frames say (and prints the C++ module's lines). */
    static final class Census implements astratto.game.GameModule {
        int updates, events, moduleLines;
        @Override public void update(astratto.game.GameContext ctx, float dt) { updates++; }
        @Override public void agentAction(long agent, int action, int previous) { events++; }
        @Override public void ragdollState(long r, int s, int p, int o) { events++; }
        @Override public void landed(long b, float speed) { events++; }
        @Override public void voiceEnded(long v) { events++; }
        @Override public void moduleLog(long module, String text) { moduleLines++; }
        String describe() { return String.format("census: %d ticks, %d events counted, %d lines from the C++ module", updates, events, moduleLines); }
    }
}
