#include "perception/sight.h"

#include <algorithm>
#include <cmath>

#if defined(_MSC_VER)
#  pragma fp_contract(off)
#endif

namespace astratto {

namespace {

const float kFar = 3.4028235e38f;
inline int floorInt(float v) { return static_cast<int>(std::floor(v)); }

/** True when a line crossing a shaped cell between two heights (0..1 of the cell) passes through its bulk. */
inline bool hides(const astratto_block_def& b, float y0, float y1) {
    const int lo = std::max(0, std::min(15, floorInt(y0 * 16.0f))), hi = std::max(0, std::min(15, floorInt(y1 * 16.0f)));
    for (int y = lo; y <= hi; y++) if (b.hide_mask & (1u << y)) return true;
    return false;
}

}  // namespace

bool sightClear(const VoxelWorld& w, float ax, float ay, float az, float bx, float by, float bz, bool eye) {
    const float dx = bx - ax, dy = by - ay, dz = bz - az;
    const float len = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (len < 0.5f) return true;
    int x = floorInt(ax), y = floorInt(ay), z = floorInt(az);
    const int ex = floorInt(bx), ey = floorInt(by), ez = floorInt(bz);
    const int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
    // t runs 0..1 along the line
    const float tdx = dx == 0 ? kFar : std::fabs(1.0f / dx), tdy = dy == 0 ? kFar : std::fabs(1.0f / dy), tdz = dz == 0 ? kFar : std::fabs(1.0f / dz);
    float tmx = dx == 0 ? kFar : (dx > 0 ? (static_cast<float>(x + 1) - ax) : (ax - static_cast<float>(x))) * tdx;
    float tmy = dy == 0 ? kFar : (dy > 0 ? (static_cast<float>(y + 1) - ay) : (ay - static_cast<float>(y))) * tdy;
    float tmz = dz == 0 ? kFar : (dz > 0 ? (static_cast<float>(z + 1) - az) : (az - static_cast<float>(z))) * tdz;
    int leaves = 0;
    float tIn = 0;
    for (int guard = static_cast<int>(len * 3.0f) + 8; guard > 0; guard--) {
        const float tOut = std::min(tmx, std::min(tmy, tmz));
        if (tIn > 0 && !(x == ex && y == ey && z == ez)) {
            const int id = w.get(x, y, z);
            if (id != 0) {
                const astratto_block_def& b = w.defOf(id);
                if (b.flags & ASTRATTO_BLOCK_OPAQUE) return false;
                if (eye && (b.flags & ASTRATTO_BLOCK_SOLID) && b.material != ASTRATTO_MATERIAL_GLASS) {
                    if (b.material == ASTRATTO_MATERIAL_FOLIAGE) { if (++leaves >= 4) return false; }   // canopy
                    else if (b.flags & ASTRATTO_BLOCK_DOOR) return false;
                    else if (b.shape != 0) {
                        const float y0 = ay + dy * tIn - static_cast<float>(y), y1 = ay + dy * std::min(1.0f, tOut) - static_cast<float>(y);
                        if (hides(b, std::min(y0, y1), std::max(y0, y1))) return false;
                    }
                    else if (!(b.flags & ASTRATTO_BLOCK_CUTOUT)) return false;                            // a full block that is not mesh or grating
                }
            }
        }
        if (tOut >= 1 || (x == ex && y == ey && z == ez)) return true;
        tIn = tOut;
        if (tmx <= tmy && tmx <= tmz) { x += sx; tmx += tdx; }
        else if (tmy <= tmz) { y += sy; tmy += tdy; }
        else { z += sz; tmz += tdz; }
    }
    return true;
}

}  // namespace astratto
