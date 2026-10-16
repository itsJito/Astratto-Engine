package astratto.platform;

import static org.lwjgl.glfw.GLFW.*;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keyboard/mouse state with rebindable named actions (GDD 18.3). Edge-triggered "pressed"
 * flags are cleared every frame by {@link #endFrame()}.
 */
public final class Input {
    public static final int MOUSE_OFFSET = 1000; // binding codes >= 1000 are mouse buttons

    private final boolean[] down = new boolean[1100];
    private final boolean[] pressed = new boolean[1100];
    private final boolean[] released = new boolean[1100];
    public double mouseX, mouseY, dx, dy, scroll;
    private double lastX, lastY;
    private boolean firstMouse = true;
    public final ArrayDeque<Character> typed = new ArrayDeque<>();
    public int lastKeyPressed = -1;
    /** Modifier bits delivered with the latest mouse press (shift+click works even if the key event was missed). */
    public int clickMods;

    /** action -> key code */
    public final Map<String, Integer> bindings = new LinkedHashMap<>();

    public Input() { resetBindings(); }

    public void resetBindings() {
        bindings.clear();
        bindings.put("forward", GLFW_KEY_W);
        bindings.put("back", GLFW_KEY_S);
        bindings.put("left", GLFW_KEY_A);
        bindings.put("right", GLFW_KEY_D);
        bindings.put("jump", GLFW_KEY_SPACE);
        bindings.put("sprint", GLFW_KEY_LEFT_SHIFT);
        // 1.1: Left Ctrl is the stance key - a tap goes between standing and a crouch, holding it goes prone
        bindings.put("crouch", GLFW_KEY_LEFT_CONTROL);
        bindings.put("lean_left", GLFW_KEY_Q);
        bindings.put("lean_right", GLFW_KEY_E);
        bindings.put("fire", MOUSE_OFFSET + GLFW_MOUSE_BUTTON_LEFT);
        bindings.put("aim", MOUSE_OFFSET + GLFW_MOUSE_BUTTON_RIGHT);
        bindings.put("reload", GLFW_KEY_R);
        bindings.put("interact", GLFW_KEY_F);
        bindings.put("inventory", GLFW_KEY_I);
        bindings.put("wheel", GLFW_KEY_TAB);
        bindings.put("sort", GLFW_KEY_Z);
        bindings.put("map", GLFW_KEY_M);
        bindings.put("firemode", GLFW_KEY_B);
        bindings.put("primary", GLFW_KEY_1);
        bindings.put("sidearm", GLFW_KEY_2);
        bindings.put("melee", GLFW_KEY_3);
        bindings.put("quick1", GLFW_KEY_4);
        bindings.put("quick2", GLFW_KEY_5);
        bindings.put("quick3", GLFW_KEY_6);
        bindings.put("quick4", GLFW_KEY_7);
        bindings.put("throw", GLFW_KEY_G);
        bindings.put("view", GLFW_KEY_V);
        bindings.put("rotate", GLFW_KEY_R);
        bindings.put("flashlight", GLFW_KEY_L);
        bindings.put("extract", GLFW_KEY_X);
        bindings.put("walk", GLFW_KEY_LEFT_ALT);
        // 1.1: shows every HUD indicator for a few seconds (food and water are otherwise only there when they matter)
        bindings.put("status", GLFW_KEY_H);
    }

    // ------------------------------------------------------------- callbacks
    void onKey(int key, int action) {
        if (key < 0 || key >= MOUSE_OFFSET) return;
        if (action == GLFW_PRESS) { down[key] = true; pressed[key] = true; lastKeyPressed = key; }
        else if (action == GLFW_RELEASE) { down[key] = false; released[key] = true; }
    }
    void onMouseButton(int button, int action) {
        int k = MOUSE_OFFSET + button;
        if (k >= down.length) return;
        if (action == GLFW_PRESS) { down[k] = true; pressed[k] = true; lastKeyPressed = k; }
        else if (action == GLFW_RELEASE) { down[k] = false; released[k] = true; }
    }
    void onCursor(double x, double y) {
        if (firstMouse) { lastX = x; lastY = y; firstMouse = false; }
        dx += x - lastX; dy += y - lastY;
        lastX = x; lastY = y;
        mouseX = x; mouseY = y;
    }
    void onScroll(double s) { scroll += s; }
    void onChar(int cp) { if (cp < 0x10000) typed.add((char) cp); }

    public void endFrame() {
        java.util.Arrays.fill(pressed, false);
        java.util.Arrays.fill(released, false);
        dx = dy = 0; scroll = 0;
        typed.clear();
        lastKeyPressed = -1;
        applyInjected();
    }

    private final java.util.List<int[]> injected = new java.util.ArrayList<>();

    /** Test/automation hook: simulates a key or mouse button (code >= MOUSE_OFFSET) for the next frame. */
    public void inject(int code, boolean isDown) { if (code >= 0 && code < down.length) injected.add(new int[]{code, isDown ? 1 : 0}); }

    private void applyInjected() {
        for (int[] e : injected) {
            int code = e[0];
            boolean isDown = e[1] == 1;
            if (isDown && !down[code]) pressed[code] = true;
            if (!isDown && down[code]) released[code] = true;
            down[code] = isDown;
        }
        injected.clear();
    }

    public void resetMouseDelta() { firstMouse = true; dx = dy = 0; }

    // ------------------------------------------------------------- queries
    public boolean keyDown(int k) { return k >= 0 && k < down.length && down[k]; }
    public boolean keyPressed(int k) { return k >= 0 && k < pressed.length && pressed[k]; }
    public boolean keyReleased(int k) { return k >= 0 && k < released.length && released[k]; }
    public boolean mouseDown(int b) { return keyDown(MOUSE_OFFSET + b); }
    public boolean mousePressed(int b) { return keyPressed(MOUSE_OFFSET + b); }
    public boolean mouseReleased(int b) { return keyReleased(MOUSE_OFFSET + b); }

    public boolean down(String action) { Integer k = bindings.get(action); return k != null && keyDown(k); }
    public boolean pressed(String action) { Integer k = bindings.get(action); return k != null && keyPressed(k); }
    public boolean released(String action) { Integer k = bindings.get(action); return k != null && keyReleased(k); }

    /** Consumes a pressed flag so overlapping UI layers don't double-handle it. */
    public void consume(int k) { if (k >= 0 && k < pressed.length) pressed[k] = false; }

    public static String keyName(int code) {
        if (code >= MOUSE_OFFSET) {
            int b = code - MOUSE_OFFSET;
            return b == 0 ? "LMB" : b == 1 ? "RMB" : b == 2 ? "MMB" : "MOUSE" + (b + 1);
        }
        switch (code) {
            case GLFW_KEY_SPACE: return "SPACE";
            case GLFW_KEY_LEFT_SHIFT: return "LSHIFT";
            case GLFW_KEY_RIGHT_SHIFT: return "RSHIFT";
            case GLFW_KEY_LEFT_CONTROL: return "LCTRL";
            case GLFW_KEY_LEFT_ALT: return "LALT";
            case GLFW_KEY_TAB: return "TAB";
            case GLFW_KEY_ESCAPE: return "ESC";
            case GLFW_KEY_ENTER: return "ENTER";
            case GLFW_KEY_BACKSPACE: return "BKSP";
            case GLFW_KEY_CAPS_LOCK: return "CAPS";
            case GLFW_KEY_UP: return "UP";
            case GLFW_KEY_DOWN: return "DOWN";
            case GLFW_KEY_LEFT: return "LEFT";
            case GLFW_KEY_RIGHT: return "RIGHT";
            case GLFW_KEY_GRAVE_ACCENT: return "~";
            default:
                if (code >= GLFW_KEY_F1 && code <= GLFW_KEY_F12) return "F" + (code - GLFW_KEY_F1 + 1);
                String n = glfwGetKeyName(code, 0);
                return n == null ? ("KEY" + code) : n.toUpperCase();
        }
    }
}
