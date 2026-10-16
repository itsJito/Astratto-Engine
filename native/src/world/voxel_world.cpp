#include "world/voxel_world.h"

#include <algorithm>
#include <cstring>

namespace astratto {

BlockTable::BlockTable() { std::memset(defs, 0, sizeof(defs)); }

void BlockTable::set(const astratto_block_def* d, uint32_t count) {
    std::memset(defs, 0, sizeof(defs));
    if (d == nullptr) return;
    if (count > 256) count = 256;
    std::memcpy(defs, d, sizeof(astratto_block_def) * count);
}

VoxelWorld::VoxelWorld(const BlockTable* table) : table_(table) {}

bool VoxelWorld::create(uint32_t sx, uint32_t sy, uint32_t sz, uint32_t belowFloor, const uint8_t* cells, uint64_t cellBytes, const uint8_t* light, uint64_t lightBytes) {
    // the path search packs a cell into 32 bits: ten for x, ten for z, the rest for y
    if (sx == 0 || sy == 0 || sz == 0 || sx > 1024 || sz > 1024 || sy > 2048 || belowFloor > 255) return false;
    const uint64_t need = static_cast<uint64_t>(sx) * sy * sz;
    if ((cells != nullptr && cellBytes != need) || (light != nullptr && lightBytes != need)) return false;
    sx_ = static_cast<int>(sx); sy_ = static_cast<int>(sy); sz_ = static_cast<int>(sz);
    belowFloor_ = static_cast<int>(belowFloor);
    if (cells) blocks_.assign(cells, cells + need); else blocks_.assign(need, 0);
    if (light) light_.assign(light, light + need); else light_.assign(need, 0);
    pristine_.clear();
    sealed_.clear();
    navVersion_++;
    dirty_.assign(static_cast<size_t>(columnsX()) * columnsZ(), 1);
    columnVersion_.assign(dirty_.size(), 1);
    return true;
}

bool VoxelWorld::setRaw(int x, int y, int z, int id) {
    if (!inBounds(x, y, z)) return false;
    const size_t i = index(x, y, z);
    const int old = blocks_[i];
    id &= 0xFF;
    if (old == id) return false;
    blocks_[i] = static_cast<uint8_t>(id);
    edits_++;
    const uint32_t a = table_->defs[old].flags, b = table_->defs[id].flags;
    const bool nav = ((a ^ b) & (ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_DOOR)) != 0;
    if (nav) navVersion_++;
    return nav;
}

bool VoxelWorld::set(int x, int y, int z, int id) {
    if (!inBounds(x, y, z)) return false;
    const int old = blocks_[index(x, y, z)];
    id &= 0xFF;
    if (old == id) return false;
    const bool nav = setRaw(x, y, z, id);
    const astratto_block_def &a = table_->defs[old], &b = table_->defs[id];
    // the mesh only changes when something the mesher draws changed (door leaves are drawn as entities by the game)
    const bool aSkip = (a.flags & ASTRATTO_BLOCK_DOOR) != 0 || old == 0, bSkip = (b.flags & ASTRATTO_BLOCK_DOOR) != 0 || id == 0;
    if (!aSkip || !bSkip) markDirty(x, z);
    if (a.emission != b.emission || ((a.flags ^ b.flags) & (ASTRATTO_BLOCK_OPAQUE | ASTRATTO_BLOCK_LIGHT_PASS)) != 0) lightBox(x - 15, z - 15, x + 15, z + 15, true);
    return nav;
}

void VoxelWorld::fill(int x0, int y0, int z0, int x1, int y1, int z1, int id) {
    x0 = std::max(0, x0); y0 = std::max(0, y0); z0 = std::max(0, z0);
    x1 = std::min(sx_ - 1, x1); y1 = std::min(sy_ - 1, y1); z1 = std::min(sz_ - 1, z1);
    if (x0 > x1 || y0 > y1 || z0 > z1) return;
    bool nav = false;
    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
        const size_t base = index(x, 0, z);
        for (int y = y0; y <= y1; y++) {
            const int old = blocks_[base + y];
            if (old == (id & 0xFF)) continue;
            nav |= ((table_->defs[old].flags ^ table_->defs[id & 0xFF].flags) & (ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_DOOR)) != 0;
            blocks_[base + y] = static_cast<uint8_t>(id);
        }
    }
    edits_++;
    if (nav) navVersion_++;
}

bool VoxelWorld::setColumn(int x, int z, const uint8_t* cells) {
    if (x < 0 || z < 0 || x >= sx_ || z >= sz_) return false;
    uint8_t* col = blocks_.data() + index(x, 0, z);
    bool nav = false;
    for (int y = 0; y < sy_; y++) {
        if (col[y] == cells[y]) continue;
        nav |= ((table_->defs[col[y]].flags ^ table_->defs[cells[y]].flags) & (ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_DOOR)) != 0;
        col[y] = cells[y];
    }
    edits_++;
    if (nav) navVersion_++;
    return true;
}

void VoxelWorld::clear() {
    std::fill(blocks_.begin(), blocks_.end(), uint8_t{0});
    std::fill(light_.begin(), light_.end(), uint8_t{0});
    sealed_.clear();
    edits_++; navVersion_++; lightVersion_++;
    markAllDirty();
}

bool VoxelWorld::restorePristine() {
    if (pristine_.size() != blocks_.size()) return false;
    std::memcpy(blocks_.data(), pristine_.data(), blocks_.size());
    edits_++;
    navVersion_ += 100;
    markAllDirty();
    return true;
}

void VoxelWorld::seal(int x, int y, int z, bool sealed) {
    const uint64_t k = key(x, y, z);
    const bool changed = sealed ? sealed_.insert(k).second : sealed_.erase(k) != 0;
    if (changed) navVersion_++;
}

// ------------------------------------------------------------------ dirty columns
void VoxelWorld::markColumn(int cx, int cz) {
    if (cx < 0 || cz < 0 || cx >= columnsX() || cz >= columnsZ()) return;
    const size_t c = static_cast<size_t>(cx) * columnsZ() + cz;
    columnVersion_[c]++;
    dirty_[c] = 1;
}

void VoxelWorld::markDirty(int x, int z) {
    const int cx = x >> 4, cz = z >> 4;
    markColumn(cx, cz);
    if ((x & 15) == 0) markColumn(cx - 1, cz);
    if ((x & 15) == 15) markColumn(cx + 1, cz);
    if ((z & 15) == 0) markColumn(cx, cz - 1);
    if ((z & 15) == 15) markColumn(cx, cz + 1);
}

void VoxelWorld::markRegionDirty(int x0, int z0, int x1, int z1) {
    for (int cx = std::max(0, (x0 >> 4) - 1); cx <= std::min(columnsX() - 1, (x1 >> 4) + 1); cx++)
        for (int cz = std::max(0, (z0 >> 4) - 1); cz <= std::min(columnsZ() - 1, (z1 >> 4) + 1); cz++) markColumn(cx, cz);
}

void VoxelWorld::markAllDirty() { for (int cx = 0; cx < columnsX(); cx++) for (int cz = 0; cz < columnsZ(); cz++) markColumn(cx, cz); }

void VoxelWorld::takeDirty(std::vector<int>& out) {
    out.clear();
    for (size_t c = 0; c < dirty_.size(); c++) if (dirty_[c]) { dirty_[c] = 0; out.push_back(static_cast<int>(c)); }
}

uint32_t VoxelWorld::columnVersion(int cx, int cz) const {
    if (cx < 0 || cz < 0 || cx >= columnsX() || cz >= columnsZ()) return 0;
    return columnVersion_[static_cast<size_t>(cx) * columnsZ() + cz];
}

// ------------------------------------------------------------------ light
// Two channels - daylight from above and light from emitting blocks - each fifteen levels, losing one a cell as they
// spread. The same flood fill the game ran in Java (world.Lighting until 2.0): a sky pass down every column, seeds
// where a lit cell borders a darker open one, the ring round a box when only the box is relit, then the fill.
void VoxelWorld::lightAll() { lightBox(0, 0, sx_ - 1, sz_ - 1, false); }

void VoxelWorld::lightFill(uint8_t v) { std::fill(light_.begin(), light_.end(), v); lightVersion_++; }

void VoxelWorld::push(uint32_t i) {
    if (queue_.size() == queue_.capacity() && head_ > 0 && head_ * 2 >= queue_.size()) {
        queue_.erase(queue_.begin(), queue_.begin() + static_cast<std::ptrdiff_t>(head_));
        head_ = 0;
    }
    queue_.push_back(i);
}

bool VoxelWorld::dim(uint32_t ni, int s) const {
    if (blocksLight(blocks_[ni])) return false;
    return ((light_[ni] >> 4) & 15) < s - 1;
}

void VoxelWorld::spread(uint32_t ni, int s, int b) {
    if (blocksLight(blocks_[ni])) return;
    const int v = light_[ni];
    const int ns = (v >> 4) & 15, nb = v & 15;
    const int ts = std::max(ns, s - 1), tb = std::max(nb, b - 1);
    if (ts != ns || tb != nb) {
        light_[ni] = static_cast<uint8_t>((ts << 4) | tb);
        push(ni);
    }
}

void VoxelWorld::lightBox(int x0, int z0, int x1, int z1, bool seedBorder) {
    if (!loaded()) return;
    x0 = std::max(0, x0); z0 = std::max(0, z0); x1 = std::min(sx_ - 1, x1); z1 = std::min(sz_ - 1, z1);
    if (x0 > x1 || z0 > z1) return;
    const uint32_t H = static_cast<uint32_t>(sy_), D = static_cast<uint32_t>(sz_), XS = D * H;
    uint8_t* bl = blocks_.data();
    uint8_t* li = light_.data();
    queue_.clear(); head_ = 0;
    if (queue_.capacity() < (1u << 20)) queue_.reserve(1u << 20);
    // 1) column sky pass + emitters
    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
        const uint32_t base = (static_cast<uint32_t>(x) * D + static_cast<uint32_t>(z)) * H;
        int sky = 15;
        for (int y = sy_ - 1; y >= 0; y--) {
            const int id = bl[base + y];
            const astratto_block_def& d = table_->defs[id];
            if (d.flags & ASTRATTO_BLOCK_OPAQUE) sky = 0;
            else if (sky > 0 && id != 0 && (d.flags & ASTRATTO_BLOCK_SKY_DIM)) sky = std::max(0, sky - 2);
            const int em = d.emission & 15;
            li[base + y] = static_cast<uint8_t>((sky << 4) | em);
            if (em > 0) push(base + y);
        }
    }
    // 2) seed cells adjacent to darker non-opaque neighbours
    for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) {
        const uint32_t base = (static_cast<uint32_t>(x) * D + static_cast<uint32_t>(z)) * H;
        for (int y = 0; y < sy_; y++) {
            const int s = (li[base + y] >> 4) & 15;
            if (s < 2) continue;
            bool edge = false;
            if (x > 0) edge |= dim(base - XS + y, s);
            if (x < sx_ - 1) edge |= dim(base + XS + y, s);
            if (z > 0) edge |= dim(base - H + y, s);
            if (z < sz_ - 1) edge |= dim(base + H + y, s);
            if (y > 0 && s < 15) edge |= dim(base + y - 1, s);
            if (edge) push(base + y);
        }
    }
    // 3) border ring: existing light flows inward
    if (seedBorder) {
        for (int x = x0 - 1; x <= x1 + 1; x++) for (int z = z0 - 1; z <= z1 + 1; z++) {
            if (x >= x0 && x <= x1 && z >= z0 && z <= z1) continue;
            if (x < 0 || z < 0 || x >= sx_ || z >= sz_) continue;
            const uint32_t base = (static_cast<uint32_t>(x) * D + static_cast<uint32_t>(z)) * H;
            for (int y = 0; y < sy_; y++) if (li[base + y] != 0) push(base + y);
        }
    }
    // 4) flood fill
    const int bx0 = seedBorder ? x0 : 0, bx1 = seedBorder ? x1 : sx_ - 1, bz0 = seedBorder ? z0 : 0, bz1 = seedBorder ? z1 : sz_ - 1;
    while (head_ < queue_.size()) {
        const uint32_t i = queue_[head_++];
        const int v = li[i];
        const int s = (v >> 4) & 15, b = v & 15;
        if (s <= 1 && b <= 1) continue;
        const int y = static_cast<int>(i % H);
        const uint32_t col = i / H;
        const int z = static_cast<int>(col % D), x = static_cast<int>(col / D);
        if (x > bx0) spread(i - XS, s, b);
        if (x < bx1) spread(i + XS, s, b);
        if (z > bz0) spread(i - H, s, b);
        if (z < bz1) spread(i + H, s, b);
        if (y > 0) spread(i - 1, s, b);
        if (y < sy_ - 1) spread(i + 1, s, b);
    }
    litCells_ += static_cast<uint64_t>(x1 - x0 + 1) * static_cast<uint64_t>(z1 - z0 + 1) * H;
    queue_.clear(); head_ = 0;
    lightVersion_++;
    // the light of a box is drawn with its columns: they are meshed again
    markRegionDirty(x0, z0, x1, z1);
}

}  // namespace astratto
