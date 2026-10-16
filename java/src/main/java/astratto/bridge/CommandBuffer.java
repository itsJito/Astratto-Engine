package astratto.bridge;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.system.MemoryUtil;

/**
 * 1.2, Astratto Engine: a tick's worth of commands for the native core, written here and handed over whole. Each
 * record is the C structure of the same name in {@code astratto/astratto.h}: a type, its own size, then its fields.
 * Nothing is sent when a method here is called - that is the point - the batch goes across once, in
 * {@link NativeEngine#frame}.
 */
public final class CommandBuffer {
    private ByteBuffer buf;
    private int records;

    public CommandBuffer(int capacity) { buf = MemoryUtil.memAlloc(Math.max(256, capacity)).order(ByteOrder.nativeOrder()); }

    private void room(int bytes) {
        if (buf.remaining() >= bytes) return;
        int cap = Math.max(buf.capacity() * 2, buf.position() + bytes);
        buf = MemoryUtil.memRealloc(buf, cap).order(ByteOrder.nativeOrder());
    }

    private void head(int type, int bytes) { room(bytes); buf.putInt(type).putInt(bytes); records++; }

    public void blockSet(int x, int y, int z, int block) { head(AstrattoNative.CMD_BLOCK_SET, 24); buf.putInt(x).putInt(y).putInt(z).putInt(block); }

    public void doorSeal(int x, int y, int z, boolean sealed) { head(AstrattoNative.CMD_DOOR_SEAL, 24); buf.putInt(x).putInt(y).putInt(z).putInt(sealed ? 1 : 0); }

    /** Every door is unsealed: what a new set of doors says first. */
    public void doorsUnseal() { head(AstrattoNative.CMD_DOORS_UNSEAL, 8); }

    public void navTuning(float jumpCost, float ladderCost, float mantleCost) { head(AstrattoNative.CMD_NAV_TUNING, 24); buf.putFloat(jumpCost).putFloat(ladderCost).putFloat(mantleCost).putInt(0); }

    public void pathRequest(int request, int flags, float sx, float sy, float sz, float tx, float ty, float tz, int maxExpand) {
        head(AstrattoNative.CMD_PATH_REQUEST, 48);
        buf.putInt(request).putInt(flags).putFloat(sx).putFloat(sy).putFloat(sz).putFloat(tx).putFloat(ty).putFloat(tz).putInt(maxExpand).putInt(0);
    }

    public void sightRequest(int request, int mode, float ax, float ay, float az, float bx, float by, float bz) {
        head(AstrattoNative.CMD_SIGHT_REQUEST, 40);
        buf.putInt(request).putInt(mode).putFloat(ax).putFloat(ay).putFloat(az).putFloat(bx).putFloat(by).putFloat(bz);
    }

    public int bytes() { return buf.position(); }
    public int records() { return records; }
    public boolean isEmpty() { return buf.position() == 0; }
    long address() { return MemoryUtil.memAddress0(buf); }
    public void clear() { buf.clear(); records = 0; }
    public void free() { if (buf != null) { MemoryUtil.memFree(buf); buf = null; } }
}
