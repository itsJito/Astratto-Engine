// Astratto Engine - the hardening checks: what has to hold when the engine is pushed rather than used as meant. Large
// route searches, the same frames run on one thread and on several, command streams of noise, handles made and
// released by the ten thousand, and gameplay modules that release themselves from inside their own code. Like the
// other checks it drives the engine through the public C interface only.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "astratto/astratto.h"
#include "astratto/astratto_module.h"

void check(const char* what, bool ok);   // selftest.cpp

namespace {

/** Reproducible noise: the same seed, the same stream, on every compiler. */
struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed * 0x9E3779B97F4A7C15ull + 1) {}
    uint32_t next() { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return static_cast<uint32_t>(s >> 16); }
    uint32_t below(uint32_t n) { return n ? next() % n : 0; }
    float range(float a, float b) { return a + (b - a) * static_cast<float>(next() & 0xFFFFFF) / 16777216.0f; }
};

struct Cmds {
    std::vector<uint8_t> bytes;
    template <typename T> T& add(uint32_t type) {
        const size_t at = bytes.size();
        bytes.resize(at + sizeof(T));
        T* rec = reinterpret_cast<T*>(bytes.data() + at);
        std::memset(rec, 0, sizeof(T));
        rec->head.type = type; rec->head.bytes = sizeof(T);
        return *rec;
    }
};

struct Frame { int32_t status = 0; std::vector<uint8_t> events; uint32_t count = 0; };

Frame frame(astratto_engine* e, Cmds& c, float dt) {
    astratto_frame_io io{};
    io.struct_size = sizeof io; io.dt = dt;
    io.commands = c.bytes.empty() ? nullptr : c.bytes.data(); io.command_bytes = static_cast<uint32_t>(c.bytes.size());
    Frame f;
    f.status = astratto_frame(e, &io);
    f.count = io.event_count;
    if (io.event_bytes) f.events.assign(io.events, io.events + io.event_bytes);
    c.bytes.clear();
    return f;
}

template <typename F> void eachEvent(const Frame& f, uint32_t type, F fn) {
    uint32_t at = 0;
    while (at + sizeof(astratto_record) <= f.events.size()) {
        astratto_record h;
        std::memcpy(&h, f.events.data() + at, sizeof h);
        if (h.bytes < sizeof h || h.bytes > f.events.size() - at) break;
        if (h.type == type) fn(f.events.data() + at, h.bytes);
        at += h.bytes;
    }
}

std::vector<uint8_t> snapshot(astratto_engine* e, uint32_t kind) {
    astratto_snapshot_io io{};
    io.struct_size = sizeof io; io.kind = kind;
    if (astratto_snapshot(e, &io) != ASTRATTO_OK || io.data == nullptr) return {};
    const uint8_t* p = static_cast<const uint8_t*>(io.data);
    return std::vector<uint8_t>(p, p + io.bytes);
}

uint32_t live(astratto_engine* e, uint32_t kind) {
    const std::vector<uint8_t> s = snapshot(e, ASTRATTO_SNAPSHOT_STATS);
    if (s.size() < sizeof(astratto_stats)) return 0xFFFFFFFFu;
    astratto_stats st;
    std::memcpy(&st, s.data(), sizeof st);
    return st.live[kind];
}

enum { AIR = 0, STONE = 1, STAIR = 2, DOOR = 3, WATER = 4, LAVA = 5, LADDER = 6, BLOCKS = 7 };
const int SX = 128, SY = 24, SZ = 128;

astratto_engine* makeEngine(uint32_t workers, uint32_t flags) {
    astratto_config cfg{};
    cfg.struct_size = sizeof cfg; cfg.api_version = ASTRATTO_API_VERSION; cfg.worker_threads = workers; cfg.flags = flags;
    astratto_engine* e = astratto_create(&cfg);
    if (e == nullptr) return nullptr;
    astratto_block_def defs[BLOCKS]{};
    defs[STONE].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_OPAQUE;
    defs[STAIR].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_STEP;
    defs[DOOR].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_DOOR;
    defs[WATER].flags = ASTRATTO_BLOCK_LIQUID | ASTRATTO_BLOCK_SKY_DIM;
    defs[LAVA].flags = ASTRATTO_BLOCK_LIQUID | ASTRATTO_BLOCK_HAZARD; defs[LAVA].emission = 15;
    defs[LADDER].flags = ASTRATTO_BLOCK_CLIMB;
    astratto_block_table t{};
    t.struct_size = sizeof t; t.count = BLOCKS; t.defs = defs;
    astratto_world_block_table(e, &t);
    return e;
}

/**
 * Rolling ground of one-block ledges (a jump each, or a stair), pools, doors and ladders: costs that differ cell to
 * cell, so a search keeps finding cheaper ways to cells it has already queued - the case that grows its heap.
 */
std::vector<uint8_t> roughGround(uint64_t seed) {
    std::vector<uint8_t> cells(static_cast<size_t>(SX) * SY * SZ, 0);
    Rng r(seed);
    auto at = [&](int x, int y, int z) -> uint8_t& { return cells[(static_cast<size_t>(x) * SZ + z) * SY + y]; };
    for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) {
        const int h = 4 + static_cast<int>(2.5 + 2.4 * std::sin(x * 0.37) * std::cos(z * 0.29) + (r.below(4) == 0 ? 1 : 0));
        for (int y = 0; y < h; y++) at(x, y, z) = STONE;
        const uint32_t k = r.below(40);
        if (k == 0) at(x, h, z) = STAIR;
        else if (k == 1) at(x, h, z) = WATER;
        else if (k == 2) { at(x, h, z) = DOOR; at(x, h + 1, z) = DOOR; }
        else if (k == 3) at(x, h - 1, z) = LAVA;
        else if (k == 4) for (int y = h; y < h + 4 && y < SY; y++) at(x, y, z) = LADDER;
    }
    return cells;
}

astratto_handle world(astratto_engine* e, const std::vector<uint8_t>& cells) {
    astratto_world_desc d{};
    d.struct_size = sizeof d; d.size_x = SX; d.size_y = SY; d.size_z = SZ; d.below_floor_block = STONE; d.flags = ASTRATTO_WORLD_LIGHT;
    d.blocks = cells.data(); d.block_bytes = cells.size();
    astratto_handle h = 0;
    if (astratto_world_create(e, &d, &h) != ASTRATTO_OK) return 0;
    Cmds c;
    c.add<astratto_cmd_handle>(ASTRATTO_CMD_WORLD_ACTIVATE).handle = h;
    frame(e, c, 0);
    return h;
}

void addPaths(Cmds& c, Rng& r, int n, uint32_t expand) {
    for (int i = 0; i < n; i++) {
        astratto_cmd_path_request& p = c.add<astratto_cmd_path_request>(ASTRATTO_CMD_PATH_REQUEST);
        p.request = static_cast<uint32_t>(i);
        p.sx = r.range(2, SX - 2); p.sy = 8; p.sz = r.range(2, SZ - 2);
        p.tx = r.range(2, SX - 2); p.ty = 8; p.tz = r.range(2, SZ - 2);
        p.flags = (i & 1 ? ASTRATTO_PATH_DOORS : 0u) | (i & 2 ? ASTRATTO_PATH_MANTLE | ASTRATTO_PATH_DOORS : 0u);
        p.max_expand = expand;
    }
}

// ------------------------------------------------------------------ route searches as big as they go
void largeSearches() {
    astratto_engine* e = makeEngine(4, 0);
    if (e == nullptr) { check("hardening: an engine is made for the route searches", false); return; }
    world(e, roughGround(7));
    Rng r(11);
    Cmds c;
    addPaths(c, r, 64, 0x7FFFFFFF);
    const Frame f = frame(e, c, 0);
    int answered = 0, maxed = 0;
    eachEvent(f, ASTRATTO_EVT_PATH_RESULT, [&](const uint8_t* p, uint32_t) {
        astratto_evt_path_result ev;
        std::memcpy(&ev, p, sizeof ev);
        answered++;
        if (ev.expansions >= 5000) maxed++;
    });
    check("hardening: 64 unbounded route searches over rough ground with doors, stairs, pools and ladders are all answered, on four workers",
          f.status == ASTRATTO_OK && answered == 64);
    std::printf("       %d of them searched to the node cap\n", maxed);
    astratto_destroy(e);
}

// ------------------------------------------------------------------ one thread or several: the same frames
std::vector<uint8_t> simulate(uint32_t workers, uint32_t flags) {
    astratto_engine* e = makeEngine(workers, flags);
    if (e == nullptr) return {};
    world(e, roughGround(3));
    std::vector<astratto_handle> movers(24, 0);
    astratto_handles_reserve(e, ASTRATTO_KIND_MOVER, static_cast<uint32_t>(movers.size()), movers.data());
    Rng r(5);
    Cmds c;
    for (size_t i = 0; i < movers.size(); i++) {
        astratto_cmd_mover_create& m = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        m.handle = movers[i]; m.x = r.range(8, SX - 8); m.y = 14; m.z = r.range(8, SZ - 8); m.radius = 0.3f; m.height = 1.8f;
    }
    std::vector<uint8_t> trace;
    for (int f = 0; f < 120; f++) {
        for (size_t i = 0; i < movers.size(); i++) {
            astratto_cmd_mover_input& in = c.add<astratto_cmd_mover_input>(ASTRATTO_CMD_MOVER_INPUT);
            in.handle = movers[i]; in.vx = 3.0f * std::sin(f * 0.05f + static_cast<float>(i)); in.vz = 3.0f * std::cos(f * 0.07f + static_cast<float>(i));
        }
        if (f % 10 == 0) addPaths(c, r, 16, 3000);
        const Frame fr = frame(e, c, 1.0f / 60);
        trace.insert(trace.end(), fr.events.begin(), fr.events.end());
        const std::vector<uint8_t> bodies = snapshot(e, ASTRATTO_SNAPSHOT_BODIES);
        trace.insert(trace.end(), bodies.begin(), bodies.end());
    }
    astratto_destroy(e);
    return trace;
}

void determinism() {
    const std::vector<uint8_t> one = simulate(1, ASTRATTO_CONFIG_DETERMINISTIC), again = simulate(1, ASTRATTO_CONFIG_DETERMINISTIC), many = simulate(4, 0);
    check("hardening: 120 frames of 24 walking bodies and 192 route searches come out byte for byte the same when run again",
          !one.empty() && one == again);
    check("hardening: and the same on four worker threads as on one (the work depends on its index, never its thread)", !one.empty() && one == many);
}

// ------------------------------------------------------------------ command streams of noise
void noise() {
    astratto_engine* e = makeEngine(3, 0);
    if (e == nullptr) { check("hardening: an engine is made for the noise", false); return; }
    world(e, roughGround(9));
    // some handles of every kind the services keep, so that noise sometimes names a real thing
    std::vector<astratto_handle> handles;
    for (uint32_t k = ASTRATTO_KIND_RAGDOLL; k <= ASTRATTO_KIND_POLICY; k++) {
        if (k == ASTRATTO_KIND_SOUND) continue;
        astratto_handle h[4] = {};
        if (astratto_handles_reserve(e, k, 4, h) == ASTRATTO_OK) handles.insert(handles.end(), h, h + 4);
    }
    Rng r(2026);
    Cmds c;
    bool survived = true;
    int refused = 0;
    for (int f = 0; f < 1500 && survived; f++) {
        const int n = 1 + static_cast<int>(r.below(12));
        for (int i = 0; i < n; i++) {
            // a record of a known size class with a random kind; its body is random, but a handle-shaped slot is often real
            const uint32_t bytes = 8 + 4 * r.below(48);
            const size_t at = c.bytes.size();
            c.bytes.resize(at + bytes);
            for (uint32_t b = 8; b < bytes; b += 4) {
                uint32_t v = r.next();
                switch (r.below(6)) {
                    case 0: v = 0; break;
                    case 1: v &= 0x3F; break;
                    case 2: { const float fv = r.range(-4, 140); std::memcpy(&v, &fv, 4); break; }
                    default: break;
                }
                std::memcpy(c.bytes.data() + at + b, &v, 4);
            }
            if (!handles.empty() && bytes >= 16 && r.below(2) == 0) {
                const astratto_handle h = handles[r.below(static_cast<uint32_t>(handles.size()))];
                std::memcpy(c.bytes.data() + at + 8, &h, sizeof h);
            }
            astratto_record head{r.below(4) == 0 ? r.below(256) : r.below(70), bytes};
            if (head.type == ASTRATTO_EDIT_LIGHT_ALL || head.type == ASTRATTO_EDIT_CLEAR || head.type == ASTRATTO_EDIT_PRISTINE_RESTORE) head.type = ASTRATTO_CMD_NOISE;
            std::memcpy(c.bytes.data() + at, &head, sizeof head);
        }
        if (r.below(50) == 0) c.bytes.resize(c.bytes.size() - 4);          // now and then, a stream cut short
        const Frame fr = frame(e, c, r.below(20) == 0 ? -1.0f : 1.0f / 60);
        if (fr.status != ASTRATTO_OK && fr.status != ASTRATTO_ERR_STREAM && fr.status != ASTRATTO_ERR_STATE) survived = false;
        eachEvent(fr, ASTRATTO_EVT_ERROR, [&](const uint8_t*, uint32_t) { refused++; });
    }
    check("hardening: 1500 frames of random command records - random kinds, sizes, bodies and real handles - are each refused or run, never a crash",
          survived && astratto_last_error(e) != nullptr);
    std::printf("       %d records refused with an error event\n", refused);
    Cmds q;
    q.add<astratto_cmd_noise>(ASTRATTO_CMD_NOISE).radius = 4;
    check("hardening: and the engine still runs a frame cleanly afterwards", frame(e, q, 1.0f / 60).status == ASTRATTO_OK);
    astratto_destroy(e);
}

// ------------------------------------------------------------------ handles by the ten thousand
void churn() {
    astratto_engine* e = makeEngine(1, ASTRATTO_CONFIG_DETERMINISTIC);
    if (e == nullptr) { check("hardening: an engine is made for the handles", false); return; }
    world(e, roughGround(1));
    std::vector<astratto_handle> first(10000, 0), second(10000, 0);
    bool ok = astratto_handles_reserve(e, ASTRATTO_KIND_MOVER, 10000, first.data()) == ASTRATTO_OK;
    Cmds c;
    for (astratto_handle h : first) {
        astratto_cmd_mover_create& m = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        m.handle = h; m.x = 10; m.y = 12; m.z = 10; m.radius = 0.3f; m.height = 1.8f;
    }
    frame(e, c, 1.0f / 60);
    ok = ok && live(e, ASTRATTO_KIND_MOVER) == 10000;
    for (astratto_handle h : first) c.add<astratto_cmd_handle>(ASTRATTO_CMD_RELEASE).handle = h;
    frame(e, c, 1.0f / 60);
    ok = ok && live(e, ASTRATTO_KIND_MOVER) == 0;
    ok = ok && astratto_handles_reserve(e, ASTRATTO_KIND_MOVER, 10000, second.data()) == ASTRATTO_OK;
    // the slots are reused, but under a new generation: every old handle is refused
    int stale = 0;
    for (astratto_handle h : first) c.add<astratto_cmd_handle>(ASTRATTO_CMD_RELEASE).handle = h;
    const Frame f = frame(e, c, 1.0f / 60);
    eachEvent(f, ASTRATTO_EVT_ERROR, [&](const uint8_t*, uint32_t) { stale++; });
    check("hardening: ten thousand bodies made and released leave none behind; their slots are reused under new generations and every old handle is refused",
          ok && stale == 10000 && first != second);
    check("hardening: the C interface refuses no engine, no records and no out-pointer, rather than crash",
          astratto_world_set(nullptr, 0, 0, 0, 0, 0) == ASTRATTO_ERR_ARGUMENT && astratto_frame(e, nullptr) == ASTRATTO_ERR_ARGUMENT
          && astratto_world_edit(e, 0, nullptr, 8) == ASTRATTO_ERR_ARGUMENT && astratto_handles_reserve(e, ASTRATTO_KIND_MOVER, 1, nullptr) == ASTRATTO_ERR_ARGUMENT
          && astratto_world_set(e, 12345, 0, 0, 0, 0) == ASTRATTO_ERR_HANDLE);
    astratto_destroy(e);
}

// ------------------------------------------------------------------ modules that release themselves
void selfRelease(const char* moduleDir) {
    if (moduleDir == nullptr) return;
    const std::string path = std::string(moduleDir) +
#if defined(_WIN32)
        "/astratto_test_module.dll";
#elif defined(__APPLE__)
        "/libastratto_test_module.dylib";
#else
        "/libastratto_test_module.so";
#endif
    astratto_engine* e = makeEngine(1, ASTRATTO_CONFIG_DETERMINISTIC);
    if (e == nullptr) return;
    world(e, roughGround(1));
    const char* when[3] = {"init", "update 3", "event 3"};
    const char* said[3] = {"from inside its init", "from inside its update", "from inside its event handler"};
    for (int i = 0; i < 3; i++) {
        astratto_handle m = 0;
        const int32_t st = astratto_module_load(e, path.c_str(), when[i], &m);
        int still = 0, shut = 0;
        Cmds c;
        for (int f = 0; f < 6; f++) {
            const Frame fr = frame(e, c, 1.0f / 60);
            eachEvent(fr, ASTRATTO_EVT_MODULE_LOG, [&](const uint8_t* p, uint32_t bytes) {
                const std::string text(reinterpret_cast<const char*>(p) + sizeof(astratto_evt_module_log), bytes - sizeof(astratto_evt_module_log));
                if (text.find("still here") != std::string::npos) still++;
                if (text.find("shut down") != std::string::npos) shut++;
            });
        }
        const std::string what = std::string("hardening: a C++ module that releases itself ") + said[i]
                                 + " goes on running to the end of that call, is then shut down once and unloaded, and its handle is refused";
        check(what.c_str(), st == ASTRATTO_OK && still == 1 && shut == 1 && live(e, ASTRATTO_KIND_MODULE) == 0 && astratto_release(e, m) == ASTRATTO_ERR_HANDLE);
    }
    astratto_destroy(e);
}

}  // namespace

void hardening_checks(const char* moduleDir) {
    largeSearches();
    determinism();
    noise();
    churn();
    selfRelease(moduleDir);
}
