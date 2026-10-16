// Astratto Engine - straight-line walkability: what steering asks before it leaves a route, ported from the game's
// Java navigation (world.Nav.clear, clearBody, choke) and held to it by the game's gates.
#include <cmath>

#include "nav/pathfinder.h"

#if defined(_MSC_VER)
#  pragma fp_contract(off)
#endif

namespace astratto {

namespace {

inline int floorInt(float v) { return static_cast<int>(std::floor(v)); }
/** Java's Math.round(float): the nearest integer, halves up. */
inline int roundHalfUp(float v) { return static_cast<int>(std::floor(v + 0.5f)); }

inline bool stairUp(const VoxelWorld& w, int x, int ny, int z) { return (w.def(x, ny - 1, z).flags & ASTRATTO_BLOCK_STEP) != 0; }

/** One edge of a body's sweep: every cell under it can be stood in at about the height the body is at. */
bool edge(const VoxelWorld& w, float sx, float sy, float sz, float tx, float tz, bool doors) {
    const float dx = tx - sx, dz = tz - sz, len = std::sqrt(dx * dx + dz * dz);
    int y = roundHalfUp(sy);
    const int steps = static_cast<int>(len / 0.4f) + 1;
    for (int i = 0; i <= steps; i++) {
        const float t = static_cast<float>(i) / static_cast<float>(steps);
        const int x = floorInt(sx + dx * t), z = floorInt(sz + dz * t);
        int ny = Pathfinder::stepY(w, x, z, y, doors);
        if (ny < 0) { ny = Pathfinder::stepY(w, x, z, y + 1, doors); if (ny < 0) return false; }
        if (ny > y && !stairUp(w, x, ny, z)) return false;
        y = ny;
    }
    return true;
}

inline bool blocks(const VoxelWorld& w, int x, int y, int z) {
    const uint32_t f = w.def(x, y, z).flags;
    return (f & ASTRATTO_BLOCK_SOLID) != 0 && (f & ASTRATTO_BLOCK_DOOR) == 0;
}
inline bool side(const VoxelWorld& w, int x, int y, int z) { return blocks(w, x, y, z) || blocks(w, x, y + 1, z); }

}  // namespace

bool Pathfinder::clear(const VoxelWorld& w, float sx, float sy, float sz, float tx, float tz, bool doors) {
    const float dx = tx - sx, dz = tz - sz, len = std::sqrt(dx * dx + dz * dz);
    int y = roundHalfUp(sy);
    int px = floorInt(sx), pz = floorInt(sz);
    if (!stand(w, px, y, pz, doors)) { y = stepY(w, px, pz, y, doors); if (y < 0) return false; }
    const int steps = static_cast<int>(len / 0.45f) + 1;
    for (int i = 1; i <= steps; i++) {
        const float t = static_cast<float>(i) / static_cast<float>(steps);
        const int x = floorInt(sx + dx * t), z = floorInt(sz + dz * t);
        if (x == px && z == pz) continue;
        // no squeezing diagonally between two wall corners
        if (x != px && z != pz && (stepY(w, px, z, y, doors) < 0 || stepY(w, x, pz, y, doors) < 0)) return false;
        const int ny = stepY(w, x, z, y, doors);
        if (ny < 0) return false;
        if (ny > y && !stairUp(w, x, ny, z)) return false;
        if (!doors && (w.def(x, ny, z).flags & ASTRATTO_BLOCK_DOOR) && w.solid(x, ny, z)) return false;
        y = ny; px = x; pz = z;
    }
    return true;
}

bool Pathfinder::clearBody(const VoxelWorld& w, float sx, float sy, float sz, float tx, float tz, bool doors, float r) {
    if (!clear(w, sx, sy, sz, tx, tz, doors)) return false;
    const float dx = tx - sx, dz = tz - sz, len = std::sqrt(dx * dx + dz * dz);
    if (len < 0.8f) return true;
    const float px = -dz / len * r, pz = dx / len * r;
    return edge(w, sx + px, sy, sz + pz, tx + px, tz + pz, doors) && edge(w, sx - px, sy, sz - pz, tx - px, tz - pz, doors);
}

bool Pathfinder::choke(const VoxelWorld& w, int x, int y, int z) {
    return (side(w, x - 1, y, z) && side(w, x + 1, y, z)) || (side(w, x, y, z - 1) && side(w, x, y, z + 1));
}

}  // namespace astratto
