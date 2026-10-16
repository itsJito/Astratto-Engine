package astratto.platform;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.system.MemoryUtil.NULL;

import org.lwjgl.glfw.GLFWErrorCallback;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.GL;

/** GLFW window + OpenGL 4.5 core context (3.3 core as a fallback for the legacy renderer). */
public final class Window {
    public long handle;
    public int width, height;
    public final Input input = new Input();
    private boolean cursorLocked;
    /** True when the context is OpenGL 4.5 core: the deferred hybrid-GI renderer can run. */
    public boolean gl45;
    public String glInfo = "";

    public void create(String title, int w, int h) {
        GLFWErrorCallback.createPrint(System.err).set();
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_RESIZABLE, GLFW_TRUE);
        glfwWindowHint(GLFW_SAMPLES, 0);
        GLFWVidMode vm = glfwGetVideoMode(glfwGetPrimaryMonitor());
        if (vm != null) { w = Math.min(w, vm.width() - 80); h = Math.min(h, vm.height() - 100); }
        // The hybrid GI renderer needs OpenGL 4.5 (compute shaders, direct state access). If the driver cannot give
        // one, fall back to a 3.3 context: the game then runs on the legacy forward renderer.
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 5);
        boolean forceLegacy = System.getenv("ABS_LEGACY_GL") != null;
        GLFWErrorCallback.createPrint(new java.io.PrintStream(java.io.OutputStream.nullOutputStream())).set();
        handle = forceLegacy ? NULL : glfwCreateWindow(w, h, title, NULL, NULL);
        GLFWErrorCallback.createPrint(System.err).set();
        gl45 = handle != NULL;
        if (handle == NULL) {
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            handle = glfwCreateWindow(w, h, title, NULL, NULL);
            if (handle != NULL) System.err.println("[Boot] OpenGL 4.5 is not available" + (forceLegacy ? " (ABS_LEGACY_GL)" : "") + ": using the legacy forward renderer.");
        }
        if (handle == NULL) throw new IllegalStateException("Window creation failed (OpenGL 3.3 or newer required)");
        width = w; height = h;
        if (vm != null) glfwSetWindowPos(handle, (vm.width() - w) / 2, (vm.height() - h) / 2);

        glfwSetKeyCallback(handle, (win, key, sc, action, mods) -> input.onKey(key, action));
        glfwSetMouseButtonCallback(handle, (win, b, action, mods) -> { input.clickMods = action == GLFW_PRESS ? mods : input.clickMods; input.onMouseButton(b, action); });
        glfwSetCursorPosCallback(handle, (win, x, y) -> input.onCursor(x, y));
        glfwSetScrollCallback(handle, (win, sx, sy) -> input.onScroll(sy));
        glfwSetCharCallback(handle, (win, cp) -> input.onChar(cp));
        glfwSetFramebufferSizeCallback(handle, (win, fw, fh) -> { width = fw; height = fh; });

        glfwMakeContextCurrent(handle);
        GL.createCapabilities();
        glInfo = glGetString(GL_RENDERER) + "  -  OpenGL " + glGetString(GL_VERSION);
        System.out.println("[Boot] " + glInfo);
        glfwSwapInterval(System.getenv("ABS_NOVSYNC") != null ? 0 : 1);
        int[] fw = new int[1], fh = new int[1];
        glfwGetFramebufferSize(handle, fw, fh);
        width = fw[0]; height = fh[0];
        glfwShowWindow(handle);
        glClearColor(0.05f, 0.05f, 0.06f, 1);
    }

    public void setCursorLocked(boolean locked) {
        if (locked == cursorLocked) return;
        cursorLocked = locked;
        glfwSetInputMode(handle, GLFW_CURSOR, locked ? GLFW_CURSOR_DISABLED : GLFW_CURSOR_HIDDEN); // the UI draws a pixel-art cursor
        if (locked && glfwRawMouseMotionSupported()) glfwSetInputMode(handle, GLFW_RAW_MOUSE_MOTION, GLFW_TRUE);
        input.resetMouseDelta();
    }

    public boolean cursorLocked() { return cursorLocked; }
    public boolean shouldClose() { return glfwWindowShouldClose(handle); }
    public void requestClose() { glfwSetWindowShouldClose(handle, true); }
    public void swap() { glfwSwapBuffers(handle); }
    public void poll() { glfwPollEvents(); }
    public void setTitle(String t) { glfwSetWindowTitle(handle, t); }

    public void destroy() {
        glfwDestroyWindow(handle);
        glfwTerminate();
    }
}
