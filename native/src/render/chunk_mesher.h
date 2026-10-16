// Astratto Engine - rendering: the voxel chunk mesher (stage 2 of the engine split, API 1.1).
//
// One column of the world, sixteen cells square and the world's height, turned into the vertex streams the renderer
// draws: greedy-merged faces of cubes (merged only where texture layer, per-corner ambient occlusion and per-corner
// smooth light all match), the crossed planes of plants and flames, and the sub-block boxes of shaped blocks turned to
// the way their neighbours say they face. The output is byte for byte what the reference mesher in Java writes for the
// same cells (absentialis.engine.render.ChunkMesher) - that equality is what made it safe to move, and a gate holds it.
//
// Vertex format (12 bytes): x, y, z, normal | texS, texT, layerLo, layerHi | (variants-1) << 2 | flags << 4 |
// sky * 17, block * 17, ao * 85, spare (shaped blocks keep the sixteenth-of-a-block fraction of their position in the
// spare bits: see putVertexF).
//
// A mesher holds only its tables and its scratch memory. It is not shared between threads: each thread that meshes has
// its own, and what it hands back stays valid until its next column.
#pragma once

#include <cstdint>
#include <vector>

#include "astratto/astratto.h"

namespace astratto {

class ChunkMesher {
public:
    explicit ChunkMesher(const astratto_render_table& table);
    /** Meshes one column. Returns false if the input is malformed. */
    bool mesh(const astratto_mesh_io& io);
    const std::vector<uint8_t>& opaque() const { return opaque_; }
    const std::vector<uint8_t>& translucent() const { return translucent_; }

private:
    struct Block { uint16_t layerTop, layerSide, layerBottom, flags; uint8_t varTop, varSide, varBottom, shape; };
    struct Shape { uint8_t orient, joins, invisible, present; uint32_t first, count; };

    std::vector<Block> blocks_;
    std::vector<Shape> shapes_;
    std::vector<int8_t> boxes_;          // 8 bytes a box: x0 y0 z0 x1 y1 z1 tex pad
    uint32_t below_ = 0;
    std::vector<uint64_t> mask_;
    std::vector<uint8_t> opaque_, translucent_;

    // the column being meshed
    const uint8_t* cells_ = nullptr;
    const uint8_t* light_ = nullptr;
    int H_ = 0, ox_ = 0, oz_ = 0, worldX_ = 0, worldZ_ = 0;
    bool doors_ = false, plants_ = true;

    const Block& def(int id) const { return blocks_[static_cast<size_t>(id & 255)]; }
    bool outside(int lx, int lz) const;      // local coordinates (-1..16) outside the world
    int id(int lx, int y, int lz) const;
    int lightAt(int lx, int y, int lz) const;
    bool solidAt(int lx, int y, int lz) const;
    bool opq(int lx, int y, int lz) const;
    bool leaf(int bid) const;
    int orientation(int bid, int lx, int y, int lz) const;
    int stairDir(int lx, int y, int lz) const;
    bool step(int lx, int y, int lz) const;
    bool backing(int lx, int y, int lz) const;
    bool wall(int lx, int y, int lz) const;
    bool joins(int bid, int lx, int y, int lz) const;
    bool seatFor(int lx, int y, int lz) const;

    void emitQuad(uint64_t k, int a, int ua, int va, int sgn, int normal, int slice, int u, int v, int wdt, int hgt);
    void shapeBox(std::vector<uint8_t>& out, const Block& b, int bx, int by, int bz, const int* box, int flags, int sky, int blk);
    static void putVertex(std::vector<uint8_t>& o, int x, int y, int z, int normal, int s, int t, int layer, int var, int flags, int sky, int blk, int ao);
    static void putVertexF(std::vector<uint8_t>& o, int fx, int fy, int fz, int normal, int s, int t, int layer, int var, int flags, int sky, int blk);
};

}  // namespace astratto
