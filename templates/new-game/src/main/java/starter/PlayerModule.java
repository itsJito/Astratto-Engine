package starter;

import astratto.bridge.AstrattoNative;
import astratto.bridge.Snapshots;
import astratto.game.GameContext;
import astratto.game.GameModule;
import astratto.platform.Input;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Gameplay in Java: the player. A body the engine moves (a mover: walking, stepping, falling, stopped by walls), steered
 * by the keyboard and mouse - or, in an automated run, by a script that walks it round a circle in front of the hut,
 * looking about the field. The camera sits at its eyes.
 */
public final class PlayerModule implements GameModule {
    private final Input input;
    private final boolean scripted;
    public long body;
    public float x = 40.5f, y = EmptyMap.FLOOR, z = 28.5f, yaw = 0, pitch = -0.08f;
    public boolean grounded;
    public int landings, updates;
    public float walked;
    private float lastX, lastZ;

    public PlayerModule(Input input, boolean scripted) { this.input = input; this.scripted = scripted; }

    @Override public void init(GameContext ctx) {
        body = ctx.reserve(AstrattoNative.KIND_MOVER);
        ctx.commands().moverCreate(body, x, y + 1, z, 0.3f, 1.8f, 0.6f, 0, 1.2f);
        lastX = x; lastZ = z;
    }

    @Override public void update(GameContext ctx, float dt) {
        updates++;
        float fwd = 0, side = 0;
        boolean jump = false;
        if (scripted) {
            // a walk round a circle in front of the hut, looking round the field as it goes
            double t = ctx.time();
            yaw = (float) (t * 0.35);
            pitch = -0.1f;
            fwd = t < 1 ? 0 : 0.6f;
        } else {
            yaw -= (float) input.dx * 0.0025f;
            pitch = Math.max(-1.5f, Math.min(1.5f, pitch - (float) input.dy * 0.0025f));
            if (input.keyDown(GLFW_KEY_W)) fwd += 1;
            if (input.keyDown(GLFW_KEY_S)) fwd -= 1;
            if (input.keyDown(GLFW_KEY_D)) side += 1;
            if (input.keyDown(GLFW_KEY_A)) side -= 1;
            jump = input.keyPressed(GLFW_KEY_SPACE);
        }
        float speed = input != null && input.keyDown(GLFW_KEY_LEFT_SHIFT) ? 6.5f : 4.0f;
        // yaw 0 faces -z; forward is (-sin, -cos), right is (cos, -sin)
        float fx = (float) -Math.sin(yaw), fz = (float) -Math.cos(yaw), rx = (float) Math.cos(yaw), rz = (float) -Math.sin(yaw);
        float vx = (fx * fwd + rx * side) * speed, vz = (fz * fwd + rz * side) * speed;
        ctx.commands().moverInput(body, vx, vz, 0, jump && grounded ? 5.2f : 0, 0, 0);
        ctx.commands().listener(x, y + 1.6f, z, yaw);
    }

    @Override public void afterFrame(GameContext ctx) {
        Snapshots.Mover m = ctx.bodies().mover(body);
        if (m == null) return;
        x = m.x; y = m.y; z = m.z; grounded = m.grounded;
        walked += (float) Math.hypot(x - lastX, z - lastZ);
        lastX = x; lastZ = z;
    }

    @Override public void landed(long b, float speed) { if (b == body) landings++; }

    public float eyeY() { return y + 1.6f; }
}
