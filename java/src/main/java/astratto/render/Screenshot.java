package astratto.render;

import static org.lwjgl.opengl.GL11.*;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.ByteBuffer;
import org.lwjgl.system.MemoryUtil;

/** Reads the back buffer into a PNG file. */
public final class Screenshot {
    private Screenshot() {}

    public static void save(int w, int h, String path) {
        ByteBuffer buf = MemoryUtil.memAlloc(w * h * 4);
        glPixelStorei(GL_PACK_ALIGNMENT, 1);
        glReadPixels(0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, buf);
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) {
            int i = ((h - 1 - y) * w + x) * 4;
            int r = buf.get(i) & 255, g = buf.get(i + 1) & 255, b = buf.get(i + 2) & 255;
            img.setRGB(x, y, (r << 16) | (g << 8) | b);
        }
        MemoryUtil.memFree(buf);
        try {
            File f = new File(path);
            if (f.getParentFile() != null) f.getParentFile().mkdirs();
            javax.imageio.ImageIO.write(img, "png", f);
            System.out.println("[Screenshot] " + f.getAbsolutePath());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
