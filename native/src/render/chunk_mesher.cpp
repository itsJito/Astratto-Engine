// Astratto Engine - rendering: the voxel chunk mesher. See chunk_mesher.h. Every rule here is the reference mesher's,
// in the same order, so that the bytes come out the same: where a comment says why something is so, the reason is the
// one the reference gives.
#include "render/chunk_mesher.h"

#include <algorithm>
#include <cstring>

namespace astratto {

namespace {
constexpr int AX[3][3] = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
constexpr int UAX[3] = {2, 0, 0}, VAX[3] = {1, 2, 1};
constexpr int STAIR_DX[4] = {0, -1, 0, 1}, STAIR_DZ[4] = {1, 0, -1, 0};
constexpr int PLANE = 8;
constexpr int REG = 18;   // the column and its border, on each side
}  // namespace

ChunkMesher::ChunkMesher(const astratto_render_table& t) {
    blocks_.assign(256, Block{0, 0, 0, 0, 1, 1, 1, 0});
    for (uint32_t i = 0; i < t.block_count && i < 256; i++) {
        const astratto_render_block& b = t.blocks[i];
        blocks_[i] = Block{b.layer_top, b.layer_side, b.layer_bottom, b.flags, b.var_top, b.var_side, b.var_bottom, b.shape};
    }
    shapes_.resize(t.shape_count);
    for (uint32_t i = 0; i < t.shape_count; i++) {
        const astratto_shape_def& s = t.shapes[i];
        shapes_[i] = Shape{s.orient, s.joins, s.invisible, s.present, s.first_box, s.box_count};
    }
    boxes_.assign(t.boxes, t.boxes + static_cast<size_t>(t.box_count) * 8);
    below_ = t.below_floor_block;
}

bool ChunkMesher::outside(int lx, int lz) const {
    const int wx = ox_ + lx, wz = oz_ + lz;
    return wx < 0 || wz < 0 || wx >= worldX_ || wz >= worldZ_;
}

int ChunkMesher::id(int lx, int y, int lz) const {
    if (y < 0) return static_cast<int>(below_);
    if (y >= H_) return 0;
    return cells_[static_cast<size_t>(((lx + 1) * REG + (lz + 1)) * H_ + y)];
}

int ChunkMesher::lightAt(int lx, int y, int lz) const {
    if (y >= H_ || y < 0 || outside(lx, lz)) return 0xF0;
    return light_[static_cast<size_t>(((lx + 1) * REG + (lz + 1)) * H_ + y)];
}

// the world's own rule: under the floor and beyond the edge is solid, over the top is not
bool ChunkMesher::solidAt(int lx, int y, int lz) const {
    if (y < 0) return true;
    if (outside(lx, lz)) return true;
    if (y >= H_) return false;
    return (def(id(lx, y, lz)).flags & ASTRATTO_RB_SOLID) != 0;
}

bool ChunkMesher::opq(int lx, int y, int lz) const { return (def(id(lx, y, lz)).flags & ASTRATTO_RB_OCCLUDES) != 0; }
bool ChunkMesher::leaf(int bid) const { return (def(bid).flags & ASTRATTO_RB_LEAF) != 0; }

bool ChunkMesher::step(int lx, int y, int lz) const { return (def(id(lx, y, lz)).flags & ASTRATTO_RB_STEP) != 0; }
bool ChunkMesher::backing(int lx, int y, int lz) const { const Block& n = def(id(lx, y, lz)); return (n.flags & ASTRATTO_RB_SOLID) && n.shape == 0; }
bool ChunkMesher::wall(int lx, int y, int lz) const { const Block& n = def(id(lx, y, lz)); return (n.flags & ASTRATTO_RB_SOLID) && n.shape == 0 && !(n.flags & ASTRATTO_RB_DOOR); }
bool ChunkMesher::joins(int bid, int lx, int y, int lz) const {
    const int n = id(lx, y, lz);
    if (n == bid) return true;
    const Block& b = def(n);
    const bool post = b.shape < shapes_.size() && b.shape != 0 && shapes_[b.shape].joins;
    return post || ((b.flags & ASTRATTO_RB_SOLID) && b.shape == 0);
}
bool ChunkMesher::seatFor(int lx, int y, int lz) const { return (def(id(lx, y, lz)).flags & ASTRATTO_RB_SEAT) != 0; }

int ChunkMesher::stairDir(int x, int y, int z) const {
    for (int d = 0; d < 4; d++) if (step(x + STAIR_DX[d], y + 1, z + STAIR_DZ[d])) return d;
    for (int d = 0; d < 4; d++) if (step(x - STAIR_DX[d], y - 1, z - STAIR_DZ[d])) return d;
    for (int d = 0; d < 4; d++) {
        const int hx = x + STAIR_DX[d], hz = z + STAIR_DZ[d], lx = x - STAIR_DX[d], lz = z - STAIR_DZ[d];
        if (backing(hx, y, hz) && !solidAt(hx, y + 1, hz) && !solidAt(lx, y, lz)) return d;
    }
    for (int d = 0; d < 4; d++) { const int lx = x - STAIR_DX[d], lz = z - STAIR_DZ[d]; if (!solidAt(lx, y, lz) && solidAt(lx, y - 1, lz)) return d; }
    return 0;
}

int ChunkMesher::orientation(int bid, int x, int y, int z) const {
    const Block& b = def(bid);
    const int wx = ox_ + x, wz = oz_ + z;
    const uint8_t rule = b.shape < shapes_.size() ? shapes_[b.shape].orient : 0;
    switch (rule) {
        case ASTRATTO_ORIENT_BACKING:
            if (backing(x, y, z - 1)) return 0;
            if (backing(x + 1, y, z)) return 1;
            if (backing(x, y, z + 1)) return 2;
            if (backing(x - 1, y, z)) return 3;
            return (wx * 31 + wz * 17) & 3;
        case ASTRATTO_ORIENT_DOOR: {
            const bool ax = wall(x - 1, y, z) || wall(x + 1, y, z), az = wall(x, y, z - 1) || wall(x, y, z + 1);
            return az && !ax ? 1 : 0;
        }
        case ASTRATTO_ORIENT_JOIN: {
            const bool ax = joins(bid, x - 1, y, z) || joins(bid, x + 1, y, z), az = joins(bid, x, y, z - 1) || joins(bid, x, y, z + 1);
            return az && !ax ? 1 : az ? 4 : 0;
        }
        case ASTRATTO_ORIENT_CHAIR:
            if (seatFor(x, y, z + 1)) return 0;
            if (seatFor(x - 1, y, z)) return 1;
            if (seatFor(x, y, z - 1)) return 2;
            if (seatFor(x + 1, y, z)) return 3;
            return (wx * 13 + wz * 7 + y) & 3;
        case ASTRATTO_ORIENT_PEW: return id(x, y, z - 1) == bid || id(x, y, z + 1) == bid ? 1 : 0;
        case ASTRATTO_ORIENT_SCATTER: return (wx * 31 + wz * 17) & 3;
        case ASTRATTO_ORIENT_STAIR: return stairDir(x, y, z);
        default: return 0;
    }
}

static void rotateBox(const int* box, int rot, int* out) {
    int x0 = box[0], z0 = box[2], x1 = box[3], z1 = box[5];
    for (int r = 0; r < (rot & 3); r++) {
        const int nx0 = 16 - z1, nx1 = 16 - z0, nz0 = x0, nz1 = x1;
        x0 = nx0; x1 = nx1; z0 = nz0; z1 = nz1;
    }
    out[0] = x0; out[1] = box[1]; out[2] = z0; out[3] = x1; out[4] = box[4]; out[5] = z1; out[6] = box[6];
}

bool ChunkMesher::mesh(const astratto_mesh_io& io) {
    if (io.blocks == nullptr || io.light == nullptr || io.size_y == 0 || io.size_y > 2048) return false;
    cells_ = io.blocks; light_ = io.light; H_ = static_cast<int>(io.size_y);
    ox_ = io.origin_x; oz_ = io.origin_z; worldX_ = io.world_x; worldZ_ = io.world_z;
    doors_ = (io.flags & ASTRATTO_MESH_DOORS) != 0; plants_ = (io.flags & ASTRATTO_MESH_NO_PLANTS) == 0;
    opaque_.clear(); translucent_.clear();
    if (mask_.size() < static_cast<size_t>(std::max(H_, 16)) * 16) mask_.assign(static_cast<size_t>(std::max(H_, 16)) * 16, 0);

    int maxY = 0;
    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
        const size_t base = static_cast<size_t>(((x + 1) * REG + (z + 1)) * H_);
        for (int y = H_ - 1; y > maxY; y--) if (cells_[base + static_cast<size_t>(y)] != 0) { maxY = y; break; }
    }
    maxY = std::min(H_ - 1, maxY + 1);
    const int size[3] = {16, maxY + 1, 16};
    int p[3], q[3];
    int cs[4], cb[4], ca[4];

    for (int a = 0; a < 3; a++) {
        const int ua = UAX[a], va = VAX[a];
        const int su = size[ua], sv = size[va];
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            const int normal = a * 2 + (sgn > 0 ? 0 : 1);
            for (int i = 0; i < size[a]; i++) {
                // ---- build mask
                bool any = false;
                for (int v = 0; v < sv; v++) for (int u = 0; u < su; u++) {
                    p[a] = i; p[ua] = u; p[va] = v;
                    const int lx = p[0], wy = p[1], lz = p[2];
                    const int bid = id(lx, wy, lz);
                    uint64_t key = 0;
                    if (bid != 0) {
                        const Block& b = def(bid);
                        if (!(b.flags & ASTRATTO_RB_CROSS) && b.shape == 0) {
                            const int nx = lx + AX[a][0] * sgn, ny = wy + AX[a][1] * sgn, nz = lz + AX[a][2] * sgn;
                            const int nid = id(nx, ny, nz);
                            const Block& nb = def(nid);
                            const bool nOcc = (nb.flags & ASTRATTO_RB_OCCLUDES) != 0;
                            bool vis;
                            const bool trans = (b.flags & ASTRATTO_RB_TRANSLUCENT) != 0;
                            const bool liq = (b.flags & ASTRATTO_RB_LIQUID) != 0, nliq = (nb.flags & ASTRATTO_RB_LIQUID) != 0;
                            if (trans) vis = !nOcc && nid != bid && !(liq && nliq) && !((nb.flags & ASTRATTO_RB_TRANSLUCENT) && !liq && !nliq);
                            else if (b.flags & ASTRATTO_RB_CUTOUT) vis = !nOcc && nid != bid && !(leaf(bid) && leaf(nid));
                            else vis = !nOcc;
                            if (ny >= H_ && a == 1) vis = true;
                            if (vis) {
                                int layer, var;
                                if (a == 1 && sgn > 0) { layer = b.layerTop; var = b.varTop; }
                                else if (a == 1) { layer = b.layerBottom; var = b.varBottom; }
                                else { layer = b.layerSide; var = b.varSide; }
                                for (int c = 0; c < 4; c++) {
                                    const int du = (c == 1 || c == 2) ? 1 : -1, dv = (c >= 2) ? 1 : -1;
                                    q[0] = nx; q[1] = ny; q[2] = nz;
                                    const int s1x = q[0] + (ua == 0 ? du : 0), s1y = q[1] + (ua == 1 ? du : 0), s1z = q[2] + (ua == 2 ? du : 0);
                                    const int s2x = q[0] + (va == 0 ? dv : 0), s2y = q[1] + (va == 1 ? dv : 0), s2z = q[2] + (va == 2 ? dv : 0);
                                    const int ccx = s1x + (va == 0 ? dv : 0), ccy = s1y + (va == 1 ? dv : 0), ccz = s1z + (va == 2 ? dv : 0);
                                    const bool o1 = opq(s1x, s1y, s1z), o2 = opq(s2x, s2y, s2z), oc = opq(ccx, ccy, ccz);
                                    int ao = (o1 && o2) ? 0 : 3 - ((o1 ? 1 : 0) + (o2 ? 1 : 0) + (oc ? 1 : 0));
                                    const bool leafy = leaf(bid);
                                    int leafAo = 3;
                                    if (leafy) {
                                        const int n3 = (o1 || leaf(id(s1x, s1y, s1z)) ? 1 : 0) + (o2 || leaf(id(s2x, s2y, s2z)) ? 1 : 0) + (oc || leaf(id(ccx, ccy, ccz)) ? 1 : 0);
                                        leafAo = n3 >= 3 ? 1 : n3 == 2 ? 2 : n3 == 1 ? 2 : 3;
                                        if (a == 1 && sgn < 0) leafAo = std::max(1, leafAo - 1);
                                    }
                                    const int l0 = lightAt(nx, ny, nz);
                                    int ss = (l0 >> 4) & 15, sb = l0 & 15, n = 1;
                                    if (!o1) { const int l = lightAt(s1x, s1y, s1z); ss += (l >> 4) & 15; sb += l & 15; n++; }
                                    if (!o2) { const int l = lightAt(s2x, s2y, s2z); ss += (l >> 4) & 15; sb += l & 15; n++; }
                                    if (!oc && !(o1 && o2)) { const int l = lightAt(ccx, ccy, ccz); ss += (l >> 4) & 15; sb += l & 15; n++; }
                                    if (trans || (b.flags & ASTRATTO_RB_CUTOUT)) ao = leafy ? leafAo : 3;
                                    cs[c] = (ss + n / 2) / n; cb[c] = (sb + n / 2) / n; ca[c] = ao;
                                }
                                const int flags = ((b.flags & ASTRATTO_RB_EMISSIVE) ? 1 : 0) | ((b.flags & ASTRATTO_RB_SWAY) ? 2 : 0) | (liq ? 4 : 0);
                                key = 1ull | (static_cast<uint64_t>(layer) << 1) | (static_cast<uint64_t>(var - 1) << 11) | (static_cast<uint64_t>(flags) << 13) | ((trans ? 1ull : 0ull) << 16);
                                for (int c = 0; c < 4; c++) key |= (static_cast<uint64_t>(ca[c]) << (17 + c * 2)) | (static_cast<uint64_t>(cs[c]) << (25 + c * 4)) | (static_cast<uint64_t>(cb[c]) << (41 + c * 4));
                                any = true;
                            }
                        }
                    }
                    mask_[static_cast<size_t>(u + v * su)] = key;
                }
                if (!any) continue;
                // ---- greedy merge
                for (int v = 0; v < sv; v++) for (int u = 0; u < su;) {
                    const uint64_t k = mask_[static_cast<size_t>(u + v * su)];
                    if (k == 0) { u++; continue; }
                    int wdt = 1;
                    while (u + wdt < su && mask_[static_cast<size_t>(u + wdt + v * su)] == k) wdt++;
                    int hgt = 1;
                    for (bool going = true; going && v + hgt < sv;) {
                        for (int t = 0; t < wdt; t++) if (mask_[static_cast<size_t>(u + t + (v + hgt) * su)] != k) { going = false; break; }
                        if (going) hgt++;
                    }
                    for (int hh = 0; hh < hgt; hh++) for (int t = 0; t < wdt; t++) mask_[static_cast<size_t>(u + t + (v + hh) * su)] = 0;
                    emitQuad(k, a, ua, va, sgn, normal, i, u, v, wdt, hgt);
                    u += wdt;
                }
            }
        }
    }
    // ---- cross-shaped props (grass, flames, candles, webs)
    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y <= maxY; y++) {
        const int bid = id(x, y, z);
        if (bid == 0 || !(def(bid).flags & ASTRATTO_RB_CROSS)) continue;
        if (!plants_ && (def(bid).flags & ASTRATTO_RB_PLANT)) continue;
        const Block& b = def(bid);
        const int l = lightAt(x, y, z);
        const int flags = ((b.flags & ASTRATTO_RB_EMISSIVE) ? 1 : 0) | 2;
        const int planes[2][4] = {{x, z, x + 1, z + 1}, {x + 1, z, x, z + 1}};
        for (const auto& pl : planes) for (int side = 0; side < 2; side++) {
            const int cs4[4][3] = {{pl[0], y, pl[1]}, {pl[2], y, pl[3]}, {pl[2], y + 1, pl[3]}, {pl[0], y + 1, pl[1]}};
            for (int c = 0; c < 4; c++) {
                const int cc = side == 0 ? c : 3 - c;
                const int* cp = cs4[cc];
                putVertex(opaque_, cp[0], cp[1], cp[2], 6, cp[0] == x ? 0 : 1, cp[1], b.layerSide, b.varSide, flags, (l >> 4) & 15, l & 15, 3);
            }
        }
    }
    // ---- shaped blocks: ladders, doors, fences, posts, furniture (sub-block boxes)
    int box[7], src[7];
    for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) for (int y = 0; y <= maxY; y++) {
        const int bid = id(x, y, z);
        if (bid == 0) continue;
        const Block& b = def(bid);
        if (!doors_ && (b.flags & ASTRATTO_RB_DOOR)) continue;      // the leaf is drawn by the game, on its hinge
        if (b.shape == 0 || b.shape >= shapes_.size() || !shapes_[b.shape].present || shapes_[b.shape].invisible) continue;
        const Shape& sh = shapes_[b.shape];
        // a shaped block sits in its own (often unlit, opaque) cell: light it from the brightest side
        int sky = 0, blk = 0;
        for (int n = 0; n < 7; n++) {
            const int l = n == 0 ? lightAt(x, y, z) : lightAt(x + (n == 1 ? 1 : n == 2 ? -1 : 0), y + (n == 3 ? 1 : n == 4 ? -1 : 0), z + (n == 5 ? 1 : n == 6 ? -1 : 0));
            sky = std::max(sky, (l >> 4) & 15); blk = std::max(blk, l & 15);
        }
        const int rot = orientation(bid, x, y, z);
        const int flags = (b.flags & ASTRATTO_RB_EMISSIVE) ? 1 : 0;
        std::vector<uint8_t>& out = (b.flags & ASTRATTO_RB_TRANSLUCENT) ? translucent_ : opaque_;
        for (int pass = 0; pass < (rot == 4 ? 2 : 1); pass++) for (uint32_t k = 0; k < sh.count; k++) {
            const int8_t* bx = &boxes_[static_cast<size_t>(sh.first + k) * 8];
            for (int j = 0; j < 7; j++) src[j] = bx[j];
            rotateBox(src, rot == 4 ? pass : rot, box);
            shapeBox(out, b, x * 16, y * 16, z * 16, box, flags, sky, blk);
        }
    }
    return true;
}

void ChunkMesher::shapeBox(std::vector<uint8_t>& out, const Block& b, int bx, int by, int bz, const int* box, int flags, int sky, int blk) {
    const int tex = box[6] & 7;
    const bool plane = (box[6] & PLANE) != 0;
    const int lo[3] = {bx + box[0], by + box[1], bz + box[2]}, hi[3] = {bx + box[3], by + box[4], bz + box[5]};
    int thin = 0;
    for (int a = 1; a < 3; a++) if (hi[a] - lo[a] < hi[thin] - lo[thin]) thin = a;
    int pos[3];
    for (int a = 0; a < 3; a++) {
        if (plane && a != thin) continue;
        const int ua = UAX[a], va = VAX[a];
        for (int sgn = -1; sgn <= 1; sgn += 2) {
            const int normal = a * 2 + (sgn > 0 ? 0 : 1);
            int layer, var;
            if (tex == 1 || (tex == 0 && a != 1)) { layer = b.layerSide; var = b.varSide; }
            else if (tex == 2 || (tex == 0 && sgn > 0)) { layer = b.layerTop; var = b.varTop; }
            else { layer = b.layerBottom; var = b.varBottom; }
            const bool reversed = (a == 2) ? (sgn < 0) : (sgn > 0);
            static const int fwd[4] = {0, 1, 2, 3}, rev[4] = {0, 3, 2, 1};
            const int* order = reversed ? rev : fwd;
            for (int oi = 0; oi < 4; oi++) {
                const int c = order[oi];
                const bool u1 = c == 1 || c == 2, v1 = c >= 2;
                pos[a] = sgn > 0 ? hi[a] : lo[a]; pos[ua] = u1 ? hi[ua] : lo[ua]; pos[va] = v1 ? hi[va] : lo[va];
                putVertexF(out, pos[0], pos[1], pos[2], normal, u1 ? 1 : 0, v1 ? 1 : 0, layer, var, flags, sky, blk);
            }
        }
    }
}

void ChunkMesher::putVertexF(std::vector<uint8_t>& o, int fx, int fy, int fz, int normal, int s, int t, int layer, int var, int flags, int sky, int blk) {
    const uint8_t d[12] = {
        static_cast<uint8_t>(fx >> 4), static_cast<uint8_t>(fy >> 4), static_cast<uint8_t>(fz >> 4), static_cast<uint8_t>(normal | (fz & 15) << 3),
        static_cast<uint8_t>(s), static_cast<uint8_t>(t), static_cast<uint8_t>(layer & 255), static_cast<uint8_t>(((layer >> 8) & 3) | ((var - 1) << 2) | (flags << 4)),
        static_cast<uint8_t>(sky * 17), static_cast<uint8_t>(blk * 17), static_cast<uint8_t>(255), static_cast<uint8_t>((fx & 15) | (fy & 15) << 4)};
    o.insert(o.end(), d, d + 12);
}

void ChunkMesher::emitQuad(uint64_t k, int a, int ua, int va, int sgn, int normal, int slice, int u, int v, int wdt, int hgt) {
    const int layer = static_cast<int>((k >> 1) & 1023), var = static_cast<int>((k >> 11) & 3) + 1, flags = static_cast<int>((k >> 13) & 7);
    const bool trans = ((k >> 16) & 1) != 0;
    std::vector<uint8_t>& out = trans ? translucent_ : opaque_;
    const int plane = slice + (sgn > 0 ? 1 : 0);
    const int cu[4] = {u, u + wdt, u + wdt, u}, cv[4] = {v, v, v + hgt, v + hgt};
    int ao[4], sk[4], bl[4];
    for (int c = 0; c < 4; c++) { ao[c] = static_cast<int>((k >> (17 + c * 2)) & 3); sk[c] = static_cast<int>((k >> (25 + c * 4)) & 15); bl[c] = static_cast<int>((k >> (41 + c * 4)) & 15); }
    const bool reversed = (a == 2) ? (sgn < 0) : (sgn > 0);
    static const int fwd[4] = {0, 1, 2, 3}, rev[4] = {0, 3, 2, 1}, fwdF[4] = {1, 2, 3, 0}, revF[4] = {3, 2, 1, 0};
    const int* order = reversed ? rev : fwd;
    // flip triangulation to avoid AO anisotropy
    if (ao[0] + sk[0] + ao[2] + sk[2] < ao[1] + sk[1] + ao[3] + sk[3]) order = reversed ? revF : fwdF;
    int pos[3];
    for (int oi = 0; oi < 4; oi++) {
        const int c = order[oi];
        pos[a] = plane; pos[ua] = cu[c]; pos[va] = cv[c];
        putVertex(out, pos[0], pos[1], pos[2], normal, cu[c], cv[c], layer, var, flags, sk[c], bl[c], ao[c]);
    }
}

void ChunkMesher::putVertex(std::vector<uint8_t>& o, int x, int y, int z, int normal, int s, int t, int layer, int var, int flags, int sky, int blk, int ao) {
    const uint8_t d[12] = {
        static_cast<uint8_t>(x), static_cast<uint8_t>(y), static_cast<uint8_t>(z), static_cast<uint8_t>(normal),
        static_cast<uint8_t>(s), static_cast<uint8_t>(t), static_cast<uint8_t>(layer & 255), static_cast<uint8_t>(((layer >> 8) & 3) | ((var - 1) << 2) | (flags << 4)),
        static_cast<uint8_t>(sky * 17), static_cast<uint8_t>(blk * 17), static_cast<uint8_t>(ao * 85), 0};
    o.insert(o.end(), d, d + 12);
}

void copyColumn(const VoxelWorld& w, int cx, int cz, std::vector<uint8_t>& cells, std::vector<uint8_t>& light) {
    const int H = w.sizeY();
    const size_t n = static_cast<size_t>(REG) * REG * static_cast<size_t>(H);
    cells.assign(n, 0);
    light.assign(n, 0xF0);
    const uint8_t* b = w.blocks();
    const uint8_t* l = w.light();
    for (int lx = -1; lx <= 16; lx++) for (int lz = -1; lz <= 16; lz++) {
        const int x = cx * 16 + lx, z = cz * 16 + lz;
        if (x < 0 || z < 0 || x >= w.sizeX() || z >= w.sizeZ()) continue;
        const size_t src = w.index(x, 0, z), dst = static_cast<size_t>(((lx + 1) * REG + (lz + 1))) * static_cast<size_t>(H);
        std::memcpy(cells.data() + dst, b + src, static_cast<size_t>(H));
        std::memcpy(light.data() + dst, l + src, static_cast<size_t>(H));
    }
}

}  // namespace astratto
