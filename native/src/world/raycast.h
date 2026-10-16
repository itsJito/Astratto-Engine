// Astratto Engine - a ray through the solid cells of a voxel world: where it first goes into one.
#pragma once

#include <cmath>
#include <cstdint>

#include "world/voxel_world.h"

namespace astratto {

struct RayHit {
    float t = -1;              // distance along the (unit) direction; -1 when nothing was struck
    int x = 0, y = 0, z = 0;   // the cell
    int nx = 0, ny = 0, nz = 0;// the face it was entered by
    int block = 0;
};

/**
 * Walks the cells a ray crosses, in order (Amanatides and Woo), and stops at the first solid one within maxLen.
 * The direction must be unit length. A ray that starts inside a solid cell strikes nothing (a wheel that is buried
 * has no contact: what is round it has to push it out) unless startInside is set, when it strikes that cell at 0.
 */
inline RayHit castRay(const VoxelWorld& w, float ox, float oy, float oz, float dx, float dy, float dz, float maxLen, bool startInside = false) {
    RayHit h;
    int x = static_cast<int>(std::floor(ox)), y = static_cast<int>(std::floor(oy)), z = static_cast<int>(std::floor(oz));
    if (w.solid(x, y, z)) {
        if (startInside) { h.t = 0; h.x = x; h.y = y; h.z = z; h.block = w.get(x, y, z); h.ny = 1; }
        return h;
    }
    const float big = 3.4028235e38f;
    const int sx = dx > 0 ? 1 : -1, sy = dy > 0 ? 1 : -1, sz = dz > 0 ? 1 : -1;
    const float tdx = std::fabs(dx) < 1e-6f ? big : std::fabs(1 / dx), tdy = std::fabs(dy) < 1e-6f ? big : std::fabs(1 / dy), tdz = std::fabs(dz) < 1e-6f ? big : std::fabs(1 / dz);
    float tmx = tdx == big ? big : (dx > 0 ? (static_cast<float>(x + 1) - ox) : (ox - static_cast<float>(x))) * tdx;
    float tmy = tdy == big ? big : (dy > 0 ? (static_cast<float>(y + 1) - oy) : (oy - static_cast<float>(y))) * tdy;
    float tmz = tdz == big ? big : (dz > 0 ? (static_cast<float>(z + 1) - oz) : (oz - static_cast<float>(z))) * tdz;
    const int guard = static_cast<int>(maxLen * 3) + 8;
    for (int i = 0; i < guard; i++) {
        float t;
        int axis;
        if (tmx <= tmy && tmx <= tmz) { t = tmx; x += sx; tmx += tdx; axis = 0; }
        else if (tmy <= tmz) { t = tmy; y += sy; tmy += tdy; axis = 1; }
        else { t = tmz; z += sz; tmz += tdz; axis = 2; }
        if (t > maxLen) return h;
        if (w.solid(x, y, z)) {
            h.t = t; h.x = x; h.y = y; h.z = z;
            h.nx = axis == 0 ? -sx : 0; h.ny = axis == 1 ? -sy : 0; h.nz = axis == 2 ? -sz : 0;
            h.block = w.get(x, y, z);
            return h;
        }
    }
    return h;
}

}  // namespace astratto
