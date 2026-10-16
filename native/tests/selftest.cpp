// Astratto Engine - the native core's own checks. This program knows nothing of any game: it makes a small world
// of its own, drives the engine through the public C interface only, and says what held and what did not.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <vector>

#include "astratto/astratto.h"

namespace {

int passed = 0, failed = 0;

void check(const char* what, bool ok) {
    std::printf("  [%s] %s\n", ok ? " ok " : "FAIL", what);
    if (ok) passed++; else failed++;
}

// a world of this program's own: 64 x 32 x 64, a floor at y = 7, and five kinds of block
const int SX = 64, SY = 32, SZ = 64, FLOOR = 8;
enum { AIR = 0, STONE = 1, GLASS = 2, DOOR = 3, STAIR = 4, LADDER = 5, LAVA = 6, DESK = 7 };

std::vector<uint8_t> cells(SX * SY * SZ, 0);
void put(int x, int y, int z, int id) { cells[(x * SZ + z) * SY + y] = static_cast<uint8_t>(id); }

struct Stream {
    std::vector<uint8_t> bytes;
    template <typename T> void add(T rec, uint32_t type) {
        rec.head.type = type; rec.head.bytes = sizeof(T);
        const size_t at = bytes.size();
        bytes.resize(at + sizeof(T));
        std::memcpy(bytes.data() + at, &rec, sizeof(T));
    }
    void path(uint32_t id, float sx, float sy, float sz, float tx, float ty, float tz, uint32_t flags, uint32_t expand = 4000) {
        astratto_cmd_path_request c{};
        c.request = id; c.flags = flags; c.sx = sx; c.sy = sy; c.sz = sz; c.tx = tx; c.ty = ty; c.tz = tz; c.max_expand = expand;
        add(c, ASTRATTO_CMD_PATH_REQUEST);
    }
    void sight(uint32_t id, float ax, float ay, float az, float bx, float by, float bz, uint32_t mode) {
        astratto_cmd_sight_request c{};
        c.request = id; c.mode = mode; c.ax = ax; c.ay = ay; c.az = az; c.bx = bx; c.by = by; c.bz = bz;
        add(c, ASTRATTO_CMD_SIGHT_REQUEST);
    }
    void block(int x, int y, int z, int id) { astratto_cmd_block_set c{}; c.x = x; c.y = y; c.z = z; c.block = static_cast<uint32_t>(id); add(c, ASTRATTO_CMD_BLOCK_SET); }
    void seal(int x, int y, int z, bool on) { astratto_cmd_door_seal c{}; c.x = x; c.y = y; c.z = z; c.sealed = on ? 1u : 0u; add(c, ASTRATTO_CMD_DOOR_SEAL); }
};

struct PathAnswer { uint32_t status = 99, expansions = 0; std::vector<int32_t> cells; };

struct Answers {
    std::vector<PathAnswer> paths = std::vector<PathAnswer>(64);
    std::vector<int> sights = std::vector<int>(64, -1);
    uint32_t count = 0;
};

Answers run(astratto_engine* e, const Stream& s, int32_t* statusOut = nullptr) {
    astratto_frame_io io{};
    io.struct_size = sizeof(io); io.dt = 1.0f / 60; io.commands = s.bytes.data(); io.command_bytes = static_cast<uint32_t>(s.bytes.size());
    const int32_t status = astratto_frame(e, &io);
    if (statusOut) *statusOut = status;
    Answers a;
    a.count = io.event_count;
    uint32_t at = 0;
    while (at + sizeof(astratto_record) <= io.event_bytes) {
        astratto_record head;
        std::memcpy(&head, io.events + at, sizeof(head));
        if (head.type == ASTRATTO_EVT_PATH_RESULT) {
            astratto_evt_path_result r;
            std::memcpy(&r, io.events + at, sizeof(r));
            PathAnswer& p = a.paths[r.request & 63];
            p.status = r.status; p.expansions = r.expansions;
            p.cells.resize(r.cell_count * 3);
            if (r.cell_count) std::memcpy(p.cells.data(), io.events + at + sizeof(r), r.cell_count * 3 * sizeof(int32_t));
        } else if (head.type == ASTRATTO_EVT_SIGHT_RESULT) {
            astratto_evt_sight_result r;
            std::memcpy(&r, io.events + at, sizeof(r));
            a.sights[r.request & 63] = static_cast<int>(r.clear);
        }
        at += head.bytes;
    }
    return a;
}

bool endsAt(const PathAnswer& p, int x, int y, int z) {
    const size_t n = p.cells.size();
    return p.status == ASTRATTO_PATH_FOUND && n >= 3 && p.cells[n - 3] == x && p.cells[n - 2] == y && p.cells[n - 1] == z;
}

bool passesThrough(const PathAnswer& p, int x, int z) {
    for (size_t i = 0; i + 2 < p.cells.size(); i += 3) if (p.cells[i] == x && p.cells[i + 2] == z) return true;
    return false;
}

/** Every step of a route is to a neighbouring column, and never more than one block up unless it is a pull-up. */
bool connected(const PathAnswer& p, int sx, int sz) {
    int px = sx, pz = sz;
    for (size_t i = 0; i + 2 < p.cells.size(); i += 3) {
        if (std::abs(p.cells[i] - px) > 1 || std::abs(p.cells[i + 2] - pz) > 1) return false;
        px = p.cells[i]; pz = p.cells[i + 2];
    }
    return true;
}

}  // namespace

int main() {
    std::printf("%s\n", astratto_build_info());

    // ------------------------------------------------------------ making an engine
    astratto_config bad{};
    bad.struct_size = sizeof(bad); bad.api_version = (ASTRATTO_API_MAJOR + 1u) << 16;
    check("an engine refuses a caller built for another major version", astratto_create(&bad) == nullptr && astratto_create_status() == ASTRATTO_ERR_VERSION);
    check("the version it reports is the header's", astratto_api_version() == ASTRATTO_API_VERSION);

    astratto_config cfg{};
    cfg.struct_size = sizeof(cfg); cfg.api_version = ASTRATTO_API_VERSION; cfg.worker_threads = 4;
    astratto_engine* e = astratto_create(&cfg);
    check("an engine is made", e != nullptr);
    if (e == nullptr) return 1;

    {
        Stream s;
        s.path(1, 5.5f, FLOOR, 5.5f, 20.5f, FLOOR, 5.5f, ASTRATTO_PATH_DOORS);
        int32_t status = 0;
        run(e, s, &status);
        check("a path asked before there is a world is an error, not a crash", status == ASTRATTO_ERR_STATE);
    }

    // ------------------------------------------------------------ the world
    astratto_block_def defs[8]{};
    defs[STONE].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_OPAQUE;
    defs[GLASS].flags = ASTRATTO_BLOCK_SOLID; defs[GLASS].material = ASTRATTO_MATERIAL_GLASS;
    defs[DOOR].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_DOOR; defs[DOOR].shape = 1; defs[DOOR].hide_mask = 0xFFFF;
    defs[STAIR].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_OPAQUE | ASTRATTO_BLOCK_STEP;
    defs[LADDER].flags = ASTRATTO_BLOCK_CLIMB;
    defs[LAVA].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_OPAQUE | ASTRATTO_BLOCK_HAZARD;
    defs[DESK].flags = ASTRATTO_BLOCK_SOLID; defs[DESK].shape = 2; defs[DESK].hide_mask = 0x0FFF;   // bulk up to three quarters of the cell
    astratto_block_table table{};
    table.struct_size = sizeof(table); table.count = 8; table.defs = defs;
    check("the block table is taken", astratto_world_block_table(e, &table) == ASTRATTO_OK);

    for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) for (int y = 0; y < FLOOR; y++) put(x, y, z, STONE);
    // a wall across the world at x = 30, with one doorway (z = 10) and one window (z = 40, at head height)
    for (int z = 0; z < SZ; z++) for (int y = FLOOR; y < FLOOR + 4; y++) put(30, y, z, STONE);
    put(30, FLOOR, 10, DOOR); put(30, FLOOR + 1, 10, DOOR);
    put(30, FLOOR + 1, 40, GLASS);
    // a strip of something nobody walks on, with a way round it
    for (int z = 20; z < 30; z++) put(12, FLOOR - 1, z, LAVA);
    // a platform two up, reached by a stair on one side
    for (int x = 44; x < 50; x++) for (int z = 44; z < 50; z++) { put(x, FLOOR, z, STONE); put(x, FLOOR + 1, z, STONE); }
    put(43, FLOOR, 46, STAIR); put(42, FLOOR, 46, STONE);          // not a stair: 42 is a block, 43 a tread on the ground beside it
    put(43, FLOOR, 46, STAIR);
    put(42, FLOOR, 46, AIR);
    put(44, FLOOR + 1, 46, STAIR); put(44, FLOOR, 46, STONE);
    // a desk in the open
    put(50, FLOOR, 20, DESK);

    astratto_world_desc wd{};
    wd.struct_size = sizeof(wd); wd.size_x = SX; wd.size_y = SY; wd.size_z = SZ; wd.below_floor_block = STONE;
    wd.blocks = cells.data(); wd.block_bytes = cells.size();
    astratto_handle world = ASTRATTO_NO_HANDLE;
    check("the world is made", astratto_world_create(e, &wd, &world) == ASTRATTO_OK && ASTRATTO_HANDLE_KIND(world) == ASTRATTO_KIND_WORLD);
    astratto_world_desc wrong = wd;
    wrong.block_bytes -= 1;
    astratto_handle wrongWorld = ASTRATTO_NO_HANDLE;
    check("a world whose size does not match its cells is refused", astratto_world_create(e, &wrong, &wrongWorld) == ASTRATTO_ERR_ARGUMENT && wrongWorld == ASTRATTO_NO_HANDLE);
    {
        Stream s;
        astratto_cmd_handle act{};
        act.handle = world;
        s.add(act, ASTRATTO_CMD_WORLD_ACTIVATE);
        int32_t status = -99;
        run(e, s, &status);
        check("the world is made the active one by command", status == ASTRATTO_OK);
    }

    // ------------------------------------------------------------ routes
    {
        Stream s;
        s.path(1, 5.5f, FLOOR, 5.5f, 20.5f, FLOOR, 5.5f, ASTRATTO_PATH_DOORS);                 // open ground
        s.path(2, 20.5f, FLOOR, 30.5f, 40.5f, FLOOR, 30.5f, ASTRATTO_PATH_DOORS);              // through the wall: by the door
        s.path(3, 20.5f, FLOOR, 30.5f, 40.5f, FLOOR, 30.5f, 0);                                // one that opens no doors
        s.path(4, 5.5f, FLOOR, 25.5f, 20.5f, FLOOR, 25.5f, ASTRATTO_PATH_DOORS);               // across the hazard: round it
        s.path(5, 40.5f, FLOOR, 46.5f, 46.5f, FLOOR + 2, 46.5f, ASTRATTO_PATH_DOORS | ASTRATTO_PATH_GOAL_Y);   // up the stair
        Answers a = run(e, s);
        check("five routes asked, five answered, in one frame", a.count == 5);
        check("open ground: a route that ends on the goal", endsAt(a.paths[1], 20, FLOOR, 5) && a.paths[1].cells.size() / 3 == 15 && connected(a.paths[1], 5, 5));
        check("through a wall: the route goes by the doorway", endsAt(a.paths[2], 40, FLOOR, 30) && passesThrough(a.paths[2], 30, 10) && connected(a.paths[2], 20, 30));
        check("somebody who opens no doors gets no further than the wall", a.paths[3].status == ASTRATTO_PATH_NONE || !endsAt(a.paths[3], 40, FLOOR, 30));
        bool onHazard = false;
        for (size_t i = 0; i + 2 < a.paths[4].cells.size(); i += 3) if (a.paths[4].cells[i] == 12 && a.paths[4].cells[i + 2] >= 20 && a.paths[4].cells[i + 2] < 30) onHazard = true;
        check("a hazard is gone round, never over", endsAt(a.paths[4], 20, FLOOR, 25) && !onHazard);
        check("a goal on another floor is reached at that floor", endsAt(a.paths[5], 46, FLOOR + 2, 46));
    }

    // a sealed door is a wall; unsealed, a door again
    {
        Stream s;
        s.seal(30, FLOOR, 10, true); s.seal(30, FLOOR + 1, 10, true);
        s.path(1, 20.5f, FLOOR, 12.5f, 40.5f, FLOOR, 12.5f, ASTRATTO_PATH_DOORS);
        Answers a = run(e, s);
        check("a sealed door is not a way through", !endsAt(a.paths[1], 40, FLOOR, 12));
        Stream u;
        u.seal(30, FLOOR, 10, false); u.seal(30, FLOOR + 1, 10, false);
        u.path(1, 20.5f, FLOOR, 12.5f, 40.5f, FLOOR, 12.5f, ASTRATTO_PATH_DOORS);
        a = run(e, u);
        check("unsealed in the same batch as the question, it is one again", endsAt(a.paths[1], 40, FLOOR, 12));
    }

    // the world changes by command, and the question in the same batch sees the change
    {
        Stream s;
        s.block(30, FLOOR, 50, AIR); s.block(30, FLOOR + 1, 50, AIR);
        s.path(1, 28.5f, FLOOR, 50.5f, 32.5f, FLOOR, 50.5f, 0);
        Answers a = run(e, s);
        check("a wall knocked through by command is walked through in the same frame", endsAt(a.paths[1], 32, FLOOR, 50) && a.paths[1].cells.size() / 3 == 4);
    }

    // ------------------------------------------------------------ sight
    {
        Stream s;
        s.sight(1, 5.5f, FLOOR + 1.6f, 5.5f, 25.5f, FLOOR + 1.6f, 5.5f, ASTRATTO_SIGHT_EYE);     // open
        s.sight(2, 25.5f, FLOOR + 1.6f, 5.5f, 35.5f, FLOOR + 1.6f, 5.5f, ASTRATTO_SIGHT_EYE);    // the wall
        s.sight(3, 25.5f, FLOOR + 1.5f, 40.5f, 35.5f, FLOOR + 1.5f, 40.5f, ASTRATTO_SIGHT_EYE);  // the window
        s.sight(4, 25.5f, FLOOR + 0.5f, 10.5f, 35.5f, FLOOR + 0.5f, 10.5f, ASTRATTO_SIGHT_EYE);  // the shut door
        s.sight(5, 25.5f, FLOOR + 0.5f, 10.5f, 35.5f, FLOOR + 0.5f, 10.5f, ASTRATTO_SIGHT_LIGHT);
        s.sight(6, 48.5f, FLOOR + 0.4f, 20.5f, 52.5f, FLOOR + 0.4f, 20.5f, ASTRATTO_SIGHT_EYE);  // low, across the desk
        s.sight(7, 48.5f, FLOOR + 0.9f, 20.5f, 52.5f, FLOOR + 0.9f, 20.5f, ASTRATTO_SIGHT_EYE);  // over the desk
        Answers a = run(e, s);
        check("open ground is seen across", a.sights[1] == 1);
        check("a wall is not seen through", a.sights[2] == 0);
        check("a window is", a.sights[3] == 1);
        check("a shut door stops an eye", a.sights[4] == 0);
        check("but not light: it is not opaque", a.sights[5] == 1);
        check("a desk hides what is low behind it", a.sights[6] == 0);
        check("and is seen over", a.sights[7] == 1);
    }

    // ------------------------------------------------------------ batches, threads, bad streams
    {
        // the same sixty routes, twice: the answers do not depend on which worker ran which
        Stream s;
        for (uint32_t i = 0; i < 60; i++) s.path(i, 2.5f + (i % 9) * 3, FLOOR, 2.5f + (i % 7) * 8, 35.5f + (i % 11) * 2, FLOOR, 60.5f - (i % 13) * 4, ASTRATTO_PATH_DOORS, 6000);
        Answers a = run(e, s), b = run(e, s);
        bool same = a.count == 60 && b.count == 60;
        for (int i = 0; i < 60 && same; i++) same = a.paths[i].status == b.paths[i].status && a.paths[i].cells == b.paths[i].cells && a.paths[i].expansions == b.paths[i].expansions;
        check("a batch of sixty routes comes back the same on every run", same);
    }
    {
        Stream s;
        s.path(1, 5.5f, FLOOR, 5.5f, 20.5f, FLOOR, 5.5f, ASTRATTO_PATH_DOORS);
        s.bytes.resize(s.bytes.size() - 6);                       // cut short
        int32_t status = 0;
        run(e, s, &status);
        check("a command stream cut short is refused, not read past its end", status == ASTRATTO_ERR_STREAM && std::strlen(astratto_last_error(e)) > 0);
        Stream t;
        astratto_cmd_block_set unknown{};
        t.add(unknown, 9999);                                       // a command this version has never heard of
        t.path(1, 5.5f, FLOOR, 5.5f, 20.5f, FLOOR, 5.5f, ASTRATTO_PATH_DOORS);
        Answers a = run(e, t, &status);
        check("an unknown command is stepped over by its own size", status == ASTRATTO_OK && endsAt(a.paths[1], 20, FLOOR, 5));
    }

    // ------------------------------------------------------------ the snapshot
    {
        astratto_snapshot_io io{};
        io.struct_size = sizeof(io); io.kind = ASTRATTO_SNAPSHOT_STATS;
        const bool ok = astratto_snapshot(e, &io) == ASTRATTO_OK && io.bytes == sizeof(astratto_stats);
        const astratto_stats* st = ok ? static_cast<const astratto_stats*>(io.data) : nullptr;
        check("the statistics snapshot counts what was done", ok && st->path_requests >= 128 && st->sight_requests == 7 && st->block_edits == 2 && st->world_x == static_cast<uint32_t>(SX) && st->workers == 4);
        if (st) std::printf("       %llu frames, %llu routes (%llu cells expanded), worst frame %.3f ms on %u workers\n",
                            static_cast<unsigned long long>(st->frames), static_cast<unsigned long long>(st->path_requests), static_cast<unsigned long long>(st->path_expansions), st->worst_frame_ms, st->workers);
    }

    astratto_destroy(e);

    // ------------------------------------------------------------ 1.1: the chunk mesher, without any engine
    {
        astratto_render_block rb[4]{};
        rb[1].flags = ASTRATTO_RB_SOLID | ASTRATTO_RB_OCCLUDES; rb[1].layer_top = rb[1].layer_side = rb[1].layer_bottom = 300; rb[1].var_top = rb[1].var_side = rb[1].var_bottom = 1;
        rb[2].flags = ASTRATTO_RB_CROSS | ASTRATTO_RB_PLANT; rb[2].layer_side = 7; rb[2].var_top = rb[2].var_side = rb[2].var_bottom = 1;
        rb[3] = rb[1];                                             // the floor under everything
        astratto_shape_def none{};
        astratto_render_table t{};
        t.struct_size = sizeof(t); t.block_count = 4; t.blocks = rb; t.shape_count = 1; t.shapes = &none; t.below_floor_block = 3;
        astratto_mesher* m = astratto_mesher_create(&t);
        const int H = 8;
        std::vector<uint8_t> cells(static_cast<size_t>(18 * 18 * H), 0), light(cells.size(), 0xF0);
        cells[static_cast<size_t>(((0 + 1) * 18 + (0 + 1)) * H + 0)] = 1;    // a cube on the floor at (0, 0, 0)
        cells[static_cast<size_t>(((5 + 1) * 18 + (5 + 1)) * H + 0)] = 2;    // a tuft of grass at (5, 0, 5)
        astratto_mesh_io io{};
        io.struct_size = sizeof(io); io.size_y = H; io.world_x = 64; io.world_z = 64; io.blocks = cells.data(); io.light = light.data();
        const bool ok = m != nullptr && astratto_mesh_column(m, &io) == ASTRATTO_OK;
        check("a mesher made from a render table meshes a column with no engine: a cube on the floor is five faces, a tuft two crossed planes seen from both sides", ok && io.opaque_bytes == (5 + 4) * 4 * 12 && io.translucent_bytes == 0);
        io.flags = ASTRATTO_MESH_NO_PLANTS;
        check("plants grown as foliage are left out when asked", m != nullptr && astratto_mesh_column(m, &io) == ASTRATTO_OK && io.opaque_bytes == 5 * 4 * 12);
        astratto_render_table bad = t; bad.block_count = 300;
        check("a malformed render table makes no mesher", astratto_mesher_create(&bad) == nullptr);
        astratto_mesher_destroy(m);
    }
    std::printf("%d passed, %d failed\n", passed, failed);
    return failed == 0 ? 0 : 1;
}
