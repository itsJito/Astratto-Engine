// Astratto Engine - the voxel world: one byte a cell and one byte of light a cell, owned here; what each kind of block
// means to the engine; the flood-fill light; and what the renderer must re-mesh.
#pragma once

#include <atomic>
#include <cstdint>
#include <mutex>
#include <unordered_set>
#include <vector>

#include "astratto/astratto.h"

namespace astratto {

/** What every world of an engine knows of its 256 kinds of block. */
struct BlockTable {
    astratto_block_def defs[256];
    BlockTable();
    void set(const astratto_block_def* d, uint32_t count);
};

class VoxelWorld {
public:
    explicit VoxelWorld(const BlockTable* table);

    /** Makes the world: sizes, and cells and light to start from (either may be null). False when out of range. */
    bool create(uint32_t sx, uint32_t sy, uint32_t sz, uint32_t belowFloor, const uint8_t* cells, uint64_t cellBytes, const uint8_t* light, uint64_t lightBytes);
    bool loaded() const { return !blocks_.empty(); }

    int sizeX() const { return sx_; }
    int sizeY() const { return sy_; }
    int sizeZ() const { return sz_; }
    int columnsX() const { return (sx_ + 15) >> 4; }
    int columnsZ() const { return (sz_ + 15) >> 4; }

    bool inBounds(int x, int y, int z) const { return x >= 0 && z >= 0 && y >= 0 && x < sx_ && z < sz_ && y < sy_; }
    size_t index(int x, int y, int z) const { return (static_cast<size_t>(x) * sz_ + z) * sy_ + y; }

    /** The id at a cell: the floor block below the world, air beyond its sides and above it. */
    int get(int x, int y, int z) const {
        if (y < 0) return belowFloor_;
        if (!inBounds(x, y, z)) return 0;
        return blocks_[index(x, y, z)];
    }
    const astratto_block_def& def(int x, int y, int z) const { return table_->defs[get(x, y, z)]; }
    const astratto_block_def& defOf(int id) const { return table_->defs[id & 0xFF]; }

    /** The edge of the world is a wall; under it is ground; over it is sky. */
    bool solid(int x, int y, int z) const {
        if (y < 0) return true;
        if (x < 0 || z < 0 || x >= sx_ || z >= sz_) return true;
        if (y >= sy_) return false;
        return (table_->defs[blocks_[index(x, y, z)]].flags & ASTRATTO_BLOCK_SOLID) != 0;
    }

    int sky(int x, int y, int z) const {
        if (y >= sy_) return 15;
        if (!inBounds(x, y, z)) return y < 0 ? 0 : 15;
        return (light_[index(x, y, z)] >> 4) & 15;
    }
    int blockLight(int x, int y, int z) const { return inBounds(x, y, z) ? light_[index(x, y, z)] & 15 : 0; }

    /** Sets a cell and nothing else. Returns true when the change could open or close a way through (the nav version moved). */
    bool setRaw(int x, int y, int z, int id);
    /** A change made in play: relights round the cell when its light changed, and marks the columns the renderer must redo. */
    bool set(int x, int y, int z, int id);
    void fill(int x0, int y0, int z0, int x1, int y1, int z1, int id);
    /** A whole column of cells, set raw (generators build a world a column at a time). False outside the world. */
    bool setColumn(int x, int z, const uint8_t* cells);
    void clear();

    /** Light: every cell, or a box of columns and fifteen cells round it (light carries fifteen). */
    void lightAll();
    void relightBox(int x0, int z0, int x1, int z1) { lightBox(x0 - 15, z0 - 15, x1 + 15, z1 + 15, true); }
    void lightFill(uint8_t v);
    void lightSet(int x, int y, int z, uint8_t v) { if (inBounds(x, y, z)) { light_[index(x, y, z)] = v; lightVersion_++; } }

    void savePristine() { pristine_ = blocks_; }
    bool restorePristine();

    void seal(int x, int y, int z, bool sealed);
    void unsealAll() { if (!sealed_.empty()) { sealed_.clear(); navVersion_++; } }
    bool sealed(int x, int y, int z) const { return !sealed_.empty() && sealed_.count(key(x, y, z)) != 0; }
    uint32_t sealedCount() const { return static_cast<uint32_t>(sealed_.size()); }

    uint32_t navVersion() const { return navVersion_; }
    void navTouched() { navVersion_++; }

    const uint8_t* blocks() const { return blocks_.data(); }
    const uint8_t* light() const { return light_.data(); }
    const uint8_t* pristine() const { return pristine_.empty() ? nullptr : pristine_.data(); }
    uint64_t edits() const { return edits_; }
    uint32_t lightVersion() const { return lightVersion_; }
    uint64_t litCells() const { return litCells_; }

    // ---- what the renderer has to redo: a flag and a version per sixteen-by-sixteen column
    void markDirty(int x, int z);
    void markRegionDirty(int x0, int z0, int x1, int z1);
    void markAllDirty();
    /** Takes the dirty columns (index cx * columnsZ + cz), clearing them. Safe against edits on the caller's thread only. */
    void takeDirty(std::vector<int>& out);
    uint32_t columnVersion(int cx, int cz) const;

private:
    static uint64_t key(int x, int y, int z) { return (static_cast<uint64_t>(static_cast<uint32_t>(x)) << 40) ^ (static_cast<uint64_t>(static_cast<uint32_t>(z)) << 16) ^ static_cast<uint32_t>(y); }
    void markColumn(int cx, int cz);
    void lightBox(int x0, int z0, int x1, int z1, bool seedBorder);
    void push(uint32_t i);
    bool dim(uint32_t ni, int s) const;
    void spread(uint32_t ni, int s, int b);
    bool blocksLight(int id) const { return (table_->defs[id].flags & ASTRATTO_BLOCK_OPAQUE) != 0; }

    const BlockTable* table_;
    int sx_ = 0, sy_ = 0, sz_ = 0, belowFloor_ = 0;
    std::vector<uint8_t> blocks_, light_, pristine_;
    std::unordered_set<uint64_t> sealed_;
    uint32_t navVersion_ = 0, lightVersion_ = 0;
    uint64_t edits_ = 0, litCells_ = 0;
    std::vector<uint32_t> queue_;
    size_t head_ = 0;
    std::vector<uint8_t> dirty_;
    std::vector<uint32_t> columnVersion_;
};

}  // namespace astratto
