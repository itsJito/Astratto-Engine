#include "nav/pathfinder.h"

#include <cmath>
#include <cstdlib>

// The costs below are compared for equality of outcome with the Java search they replace: no fused multiply-adds.
#if defined(_MSC_VER)
#  pragma fp_contract(off)
#endif

namespace astratto {

namespace {

const int DIRS[8][2] = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

inline int key(int x, int y, int z) { return x | z << 10 | y << 20; }
inline int floorInt(float v) { return static_cast<int>(std::floor(v)); }
/** Half up, as the game rounds a height to the cell a body stands in. */
inline int roundInt(float v) { return static_cast<int>(std::floor(static_cast<double>(v) + 0.5)); }

inline float heuristic(int x, int z, int gx, int gz) {
    const int dx = std::abs(x - gx), dz = std::abs(z - gz);
    const int hi = dx > dz ? dx : dz, lo = dx > dz ? dz : dx;
    return static_cast<float>(hi) + 0.414f * static_cast<float>(lo);
}

}  // namespace

Pathfinder::Pathfinder()
    : nk_(CAP), parent_(CAP), heap_(CAP + 1), slot_(SLOTS), slotGen_(SLOTS, 0), ng_(CAP), nf_(CAP), closed_(CAP) {}

bool Pathfinder::pass(const VoxelWorld& w, int x, int y, int z, bool doors) {
    const int id = w.get(x, y, z);
    if (id == 0) return true;
    const uint32_t f = w.defOf(id).flags;
    if (f & ASTRATTO_BLOCK_SOLID) return doors && (f & ASTRATTO_BLOCK_DOOR) != 0 && !w.sealed(x, y, z);
    return (f & ASTRATTO_BLOCK_HAZARD) == 0;
}

bool Pathfinder::stand(const VoxelWorld& w, int x, int y, int z, bool doors) {
    if (y < 1 || y >= w.sizeY() - 2 || x < 1 || z < 1 || x >= w.sizeX() - 1 || z >= w.sizeZ() - 1) return false;
    if (!w.solid(x, y - 1, z) || (w.def(x, y - 1, z).flags & ASTRATTO_BLOCK_HAZARD) != 0) return false;
    return pass(w, x, y, z, doors) && pass(w, x, y + 1, z, doors);
}

int Pathfinder::stepY(const VoxelWorld& w, int x, int z, int y, bool doors) {
    if (stand(w, x, y, z, doors)) return y;
    if (stand(w, x, y + 1, z, doors)) return y + 1;
    for (int d = 1; d <= 3; d++) {
        if (w.solid(x, y - d + 1, z)) break;
        if (stand(w, x, y - d, z, doors)) return y - d;
    }
    return -1;
}

bool Pathfinder::climb(const VoxelWorld& w, int x, int y, int z) {
    const int id = w.get(x, y, z);
    if (id == 0) return false;
    const uint32_t f = w.defOf(id).flags;
    return (f & ASTRATTO_BLOCK_CLIMB) != 0 && (f & ASTRATTO_BLOCK_SOLID) == 0;
}

int Pathfinder::find(int k, bool create) {
    int h = static_cast<int>((static_cast<uint32_t>(k) * 0x9E3779B1u) >> 18);
    for (;;) {
        if (slotGen_[h] != gen_) {
            if (!create || count_ >= CAP) return -1;
            slotGen_[h] = gen_; slot_[h] = count_; nk_[count_] = k; ng_[count_] = 3.4028235e38f; closed_[count_] = 0; parent_[count_] = -1;
            return count_++;
        }
        if (nk_[slot_[h]] == k) return slot_[h];
        h = (h + 1) & (SLOTS - 1);
    }
}

void Pathfinder::push(int n) {
    // a node is pushed again each time a cheaper way to it is found (the stale entry is skipped when popped), so the
    // heap can hold more entries than there are nodes: it grows rather than writing past its end
    int i = ++heapN_;
    if (static_cast<size_t>(i) >= heap_.size()) heap_.resize(heap_.size() * 2);
    heap_[i] = n;
    while (i > 1 && nf_[heap_[i >> 1]] > nf_[heap_[i]]) { const int t = heap_[i]; heap_[i] = heap_[i >> 1]; heap_[i >> 1] = t; i >>= 1; }
}

int Pathfinder::pop() {
    const int top = heap_[1];
    heap_[1] = heap_[heapN_--];
    int i = 1;
    for (;;) {
        const int l = i << 1, r = l + 1;
        int m = i;
        if (l <= heapN_ && nf_[heap_[l]] < nf_[heap_[m]]) m = l;
        if (r <= heapN_ && nf_[heap_[r]] < nf_[heap_[m]]) m = r;
        if (m == i) break;
        const int t = heap_[i]; heap_[i] = heap_[m]; heap_[m] = t; i = m;
    }
    return top;
}

void Pathfinder::relax(int from, int x, int y, int z, float cost, int gx, int gz) {
    const int m = find(key(x, y, z), true);
    if (m < 0 || closed_[m]) return;
    const float g = ng_[from] + cost;
    if (g < ng_[m]) { ng_[m] = g; nf_[m] = g + heuristic(x, z, gx, gz); parent_[m] = from; push(m); }
}

bool Pathfinder::path(const VoxelWorld& w, const NavTuning& tune, const PathQuery& q, std::vector<int32_t>& out, uint32_t& expansions) {
    out.clear();
    expansions = 0;
    const bool doors = q.doors;
    const int goalY = q.goalY ? roundInt(q.ty) : -1;
    // a new generation of the hash: every slot of the last search is stale without being cleared
    if (++gen_ == 0) { std::fill(slotGen_.begin(), slotGen_.end(), 0); gen_ = 1; }
    count_ = 0; heapN_ = 0;

    const int x0 = floorInt(q.sx), z0 = floorInt(q.sz);
    int y0 = roundInt(q.sy);
    const bool onLadder = climb(w, x0, y0, z0) || climb(w, x0, y0 - 1, z0);
    if (!stand(w, x0, y0, z0, doors) && !onLadder) {
        int y = stepY(w, x0, z0, y0, doors);
        if (y < 0) y = stepY(w, x0, z0, y0 + 1, doors);
        if (y < 0) return false;
        y0 = y;
    }
    const int gx = floorInt(q.tx), gz = floorInt(q.tz);
    const int s = find(key(x0, y0, z0), true);
    ng_[s] = 0; nf_[s] = heuristic(x0, z0, gx, gz);
    push(s);
    int best = s;
    float bestH = nf_[s] + (goalY < 0 ? 0.0f : static_cast<float>(std::abs(y0 - goalY)) * 2.0f);
    int done = 0;
    while (heapN_ > 0 && done < q.maxExpand) {
        const int n = pop();
        if (closed_[n]) continue;
        closed_[n] = 1;
        done++;
        const int k = nk_[n], x = k & 1023, z = (k >> 10) & 1023, y = k >> 20;
        const float hh = heuristic(x, z, gx, gz) + (goalY < 0 ? 0.0f : static_cast<float>(std::abs(y - goalY)) * 2.0f);
        if (hh < bestH) { bestH = hh; best = n; }
        if (x == gx && z == gz && (goalY < 0 || std::abs(y - goalY) <= 1)) { best = n; break; }
        // ladders: a rung column is a vertical edge. A body on a ladder needs no floor, only room.
        if (climb(w, x, y, z) && pass(w, x, y + 1, z, doors) && pass(w, x, y + 2, z, doors)) relax(n, x, y + 1, z, tune.ladderCost, gx, gz);
        if (climb(w, x, y - 1, z) && pass(w, x, y - 1, z, doors)) relax(n, x, y - 1, z, tune.ladderCost * 0.7f, gx, gz);
        const bool hanging = !stand(w, x, y, z, doors);
        for (int d = 0; d < 8; d++) {
            const int nx = x + DIRS[d][0], nz = z + DIRS[d][1];
            int ny = hanging ? (stand(w, nx, y, nz, doors) ? y : -1) : stepY(w, nx, nz, y, doors);
            // the head of a ladder beside a roof or a landing: no floor in that cell, but rungs to hold
            if (ny < 0 && d < 4 && climb(w, nx, y - 1, nz) && pass(w, nx, y, nz, doors) && pass(w, nx, y + 1, nz, doors)) ny = y;
            // a ledge two or three blocks up, straight ahead, with room on it and nothing overhead on the way
            bool pullUp = false;
            if (ny < 0 && doors && q.mantle && d < 4 && !hanging) {
                for (int up = 2; up <= 3 && ny < 0; up++) {
                    if (!stand(w, nx, y + up, nz, doors) || !w.solid(nx, y + up - 1, nz) || w.def(nx, y + up - 1, nz).shape != 0) continue;
                    bool clear = true;
                    for (int h = 2; h <= up + 1 && clear; h++) clear = pass(w, x, y + h, z, doors);
                    if (clear) { ny = y + up; pullUp = true; }
                }
            }
            if (ny < 0) continue;
            if (hanging && d >= 4) continue;
            if (d >= 4 && (stepY(w, x + DIRS[d][0], z, y, doors) < 0 || stepY(w, x, z + DIRS[d][1], y, doors) < 0)) continue;
            const int m = find(key(nx, ny, nz), true);
            if (m < 0 || closed_[m]) continue;
            // a step up is nearly free on a stair and a deliberate jump anywhere else: routes prefer stairs to ledges
            float extra;
            if (pullUp) extra = tune.mantleCost * static_cast<float>(ny - y);
            else if (ny > y) extra = (w.def(nx, ny - 1, nz).flags & ASTRATTO_BLOCK_STEP) ? 0.6f : tune.jumpCost;
            else if (ny < y) extra = 0.3f * static_cast<float>(y - ny);
            else extra = 0.0f;
            float cost = (d >= 4 ? 1.414f : 1.0f) + extra;
            const int here = w.get(nx, ny, nz);
            if (here != 0) {
                const uint32_t f = w.defOf(here).flags;
                if ((f & ASTRATTO_BLOCK_DOOR) && (f & ASTRATTO_BLOCK_SOLID)) cost += 5.0f;        // opening a door takes a moment
                else if (f & ASTRATTO_BLOCK_LIQUID) cost += 2.5f;                                   // wading is slow and loud
            }
            const float g = ng_[n] + cost;
            if (g < ng_[m]) { ng_[m] = g; nf_[m] = g + heuristic(nx, nz, gx, gz); parent_[m] = n; push(m); }
        }
    }
    expansions = static_cast<uint32_t>(done);
    if (best == s) return false;
    int len = 0;
    for (int n = best; n != s; n = parent_[n]) len++;
    out.resize(static_cast<size_t>(len) * 3);
    int i = len;
    for (int n = best; n != s; n = parent_[n]) {
        i--;
        const int k = nk_[n];
        out[i * 3] = k & 1023; out[i * 3 + 1] = k >> 20; out[i * 3 + 2] = (k >> 10) & 1023;
    }
    return true;
}

}  // namespace astratto
