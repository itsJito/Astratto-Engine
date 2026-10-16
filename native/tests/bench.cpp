// Astratto Engine - the benchmark: times the native core's hot paths through the public C interface, the same way
// every run (fixed worlds, fixed seeds), and holds each to a budget.
//   astratto_bench [budgets.txt] [results.json]
// Each measure is run several times and its median reported, in milliseconds. A budgets file has lines
// "<measure> <milliseconds>" ('#' starts a comment); a measure over its budget fails the run (exit 1), so a build
// that got slower is stopped the same way as one that got wrong. results.json is for keeping a history of runs.
#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <initializer_list>
#include <map>
#include <sstream>
#include <string>
#include <vector>

#include "astratto/astratto.h"

namespace {

using Clock = std::chrono::steady_clock;
double msSince(Clock::time_point t0) { return std::chrono::duration<double, std::milli>(Clock::now() - t0).count(); }

struct Rng {
    uint64_t s;
    explicit Rng(uint64_t seed) : s(seed * 0x9E3779B97F4A7C15ull + 1) {}
    uint32_t next() { s ^= s << 13; s ^= s >> 7; s ^= s << 17; return static_cast<uint32_t>(s >> 16); }
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

int32_t frame(astratto_engine* e, Cmds& c, float dt) {
    astratto_frame_io io{};
    io.struct_size = sizeof io; io.dt = dt;
    io.commands = c.bytes.empty() ? nullptr : c.bytes.data(); io.command_bytes = static_cast<uint32_t>(c.bytes.size());
    const int32_t st = astratto_frame(e, &io);
    c.bytes.clear();
    return st;
}

enum { AIR = 0, STONE = 1, GRASS = 2, TORCH = 3, LEAVES = 4, BLOCKS = 5 };
const int SX = 256, SY = 64, SZ = 256;

astratto_engine* engine(uint32_t workers) {
    astratto_config cfg{};
    cfg.struct_size = sizeof cfg; cfg.api_version = ASTRATTO_API_VERSION; cfg.worker_threads = workers;
    astratto_engine* e = astratto_create(&cfg);
    if (e == nullptr) return nullptr;
    astratto_block_def d[BLOCKS]{};
    d[STONE].flags = d[GRASS].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_OPAQUE;
    d[TORCH].emission = 14;
    d[LEAVES].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_SKY_DIM;
    astratto_block_table t{};
    t.struct_size = sizeof t; t.count = BLOCKS; t.defs = d;
    astratto_world_block_table(e, &t);
    return e;
}

/** Hills, caves under them, trees and torches: a world with the light and the faces of a real one. */
std::vector<uint8_t> terrain() {
    std::vector<uint8_t> cells(static_cast<size_t>(SX) * SY * SZ, AIR);
    Rng r(42);
    auto at = [&](int x, int y, int z) -> uint8_t& { return cells[(static_cast<size_t>(x) * SZ + z) * SY + y]; };
    for (int x = 0; x < SX; x++) for (int z = 0; z < SZ; z++) {
        const int h = 20 + static_cast<int>(8 * std::sin(x * 0.05) * std::cos(z * 0.04) + 4 * std::sin((x + z) * 0.13));
        for (int y = 0; y < h; y++) at(x, y, z) = static_cast<uint8_t>(y == h - 1 ? GRASS : STONE);
        for (int y = 6; y < h - 4; y++) if (std::sin(x * 0.21 + y * 0.4) * std::cos(z * 0.19 - y * 0.3) > 0.55) at(x, y, z) = AIR;
        if ((r.next() & 255) == 0 && x > 2 && z > 2 && x < SX - 3 && z < SZ - 3) {
            for (int y = h; y < h + 5; y++) at(x, y, z) = STONE;
            for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) for (int y = h + 3; y < h + 7; y++) if (at(x + dx, y, z + dz) == AIR) at(x + dx, y, z + dz) = LEAVES;
        }
        if ((r.next() & 511) == 0) at(x, h, z) = TORCH;
    }
    return cells;
}

astratto_handle makeWorld(astratto_engine* e, const std::vector<uint8_t>& cells, bool light) {
    astratto_world_desc d{};
    d.struct_size = sizeof d; d.size_x = SX; d.size_y = SY; d.size_z = SZ; d.below_floor_block = STONE; d.flags = light ? ASTRATTO_WORLD_LIGHT : 0;
    d.blocks = cells.data(); d.block_bytes = cells.size();
    astratto_handle h = 0;
    astratto_world_create(e, &d, &h);
    return h;
}

void activate(astratto_engine* e, astratto_handle w) {
    Cmds c;
    c.add<astratto_cmd_handle>(ASTRATTO_CMD_WORLD_ACTIVATE).handle = w;
    frame(e, c, 0);
}

template <typename F> double median(int runs, F&& f) {
    std::vector<double> t;
    for (int i = 0; i < runs; i++) t.push_back(f());
    std::sort(t.begin(), t.end());
    return t[t.size() / 2];
}

struct Result { std::string name, what; double ms; };

}  // namespace

int main(int argc, char** argv) {
    std::map<std::string, double> budgets;
    if (argc > 1) {
        std::ifstream in(argv[1]);
        if (!in) { std::fprintf(stderr, "astratto_bench: no budgets file %s\n", argv[1]); return 2; }
        std::string line;
        while (std::getline(in, line)) {
            const size_t hash = line.find('#');
            if (hash != std::string::npos) line.resize(hash);
            std::istringstream ls(line);
            std::string name; double ms = 0;
            if (ls >> name >> ms) budgets[name] = ms;
        }
    }
    std::printf("Astratto bench - %s\n", astratto_build_info());
    const std::vector<uint8_t> cells = terrain();
    std::vector<Result> results;

    // ---- light: a whole world, from nothing
    {
        astratto_engine* e = engine(0);
        results.push_back({"light_world", "flood-fill light of a 256 x 64 x 256 world", median(5, [&] {
            const astratto_handle w = makeWorld(e, cells, false);
            const astratto_record all{ASTRATTO_EDIT_LIGHT_ALL, sizeof(astratto_record)};
            const auto t0 = Clock::now();
            astratto_world_edit(e, w, reinterpret_cast<const uint8_t*>(&all), sizeof all);
            const double ms = msSince(t0);
            astratto_release(e, w);
            return ms;
        })});
        // ---- a block set in play that changes the light round it (a torch placed and taken away)
        const astratto_handle w = makeWorld(e, cells, true);
        Rng r(1);
        results.push_back({"block_set_relight", "one runtime block set that relights (mean of 100)", median(5, [&] {
            const auto t0 = Clock::now();
            for (int i = 0; i < 50; i++) {
                const int x = 16 + static_cast<int>(r.next() % (SX - 32)), z = 16 + static_cast<int>(r.next() % (SZ - 32));
                astratto_world_set(e, w, x, 40, z, TORCH);
                astratto_world_set(e, w, x, 40, z, AIR);
            }
            return msSince(t0) / 100;
        })});
        // ---- meshing every column of the world
        astratto_render_block rb[BLOCKS]{};
        for (int i = 1; i < BLOCKS; i++) { rb[i].flags = ASTRATTO_RB_SOLID | ASTRATTO_RB_OCCLUDES; rb[i].layer_top = rb[i].layer_side = rb[i].layer_bottom = static_cast<uint16_t>(i); rb[i].var_top = rb[i].var_side = rb[i].var_bottom = 1; }
        rb[TORCH].flags = ASTRATTO_RB_CROSS | ASTRATTO_RB_EMISSIVE;
        rb[LEAVES].flags = ASTRATTO_RB_SOLID | ASTRATTO_RB_LEAF;
        astratto_shape_def none{};
        astratto_render_table t{};
        t.struct_size = sizeof t; t.block_count = BLOCKS; t.blocks = rb; t.shape_count = 1; t.shapes = &none; t.below_floor_block = STONE;
        astratto_mesher* m = astratto_mesher_create(&t);
        results.push_back({"mesh_world", "mesh all 256 columns of the world", median(5, [&] {
            const auto t0 = Clock::now();
            for (int cx = 0; cx < SX / 16; cx++) for (int cz = 0; cz < SZ / 16; cz++) {
                astratto_mesh_io io{};
                io.struct_size = sizeof io;
                if (m) astratto_mesh_world_column(m, e, w, cx, cz, 0, &io);
            }
            return msSince(t0);
        })});
        // and once more, untimed, for a digest of every byte (FNV-1a): a faster mesher has to make the same mesh
        uint64_t quads = 0, digest = 1469598103934665603ull;
        for (int cx = 0; cx < SX / 16; cx++) for (int cz = 0; cz < SZ / 16; cz++) {
            astratto_mesh_io io{};
            io.struct_size = sizeof io;
            if (m == nullptr || astratto_mesh_world_column(m, e, w, cx, cz, 0, &io) != ASTRATTO_OK) continue;
            quads += (io.opaque_bytes + io.translucent_bytes) / 48;
            for (uint32_t b = 0; b < io.opaque_bytes; b++) digest = (digest ^ io.opaque[b]) * 1099511628211ull;
            for (uint32_t b = 0; b < io.translucent_bytes; b++) digest = (digest ^ io.translucent[b]) * 1099511628211ull;
        }
        std::printf("  (the world meshes to %llu quads, digest %016llx)\n", static_cast<unsigned long long>(quads), static_cast<unsigned long long>(digest));
        astratto_mesher_destroy(m);
        astratto_destroy(e);
    }

    // ---- a frame's route searches, on the worker threads, and the same on one
    for (uint32_t workers : {1u, 0u}) {
        astratto_engine* e = engine(workers);
        activate(e, makeWorld(e, cells, true));
        Rng r(7);
        const char* name = workers == 1 ? "paths_128_one_thread" : "paths_128_workers";
        results.push_back({name, workers == 1 ? "128 route searches in one frame, one thread" : "128 route searches in one frame, every worker", median(5, [&] {
            Cmds c;
            for (int i = 0; i < 128; i++) {
                astratto_cmd_path_request& p = c.add<astratto_cmd_path_request>(ASTRATTO_CMD_PATH_REQUEST);
                p.request = static_cast<uint32_t>(i); p.flags = ASTRATTO_PATH_DOORS;
                p.sx = r.range(8, SX - 8); p.sy = 30; p.sz = r.range(8, SZ - 8);
                p.tx = p.sx + r.range(-40, 40); p.ty = 30; p.tz = p.sz + r.range(-40, 40); p.max_expand = 4000;
            }
            const auto t0 = Clock::now();
            frame(e, c, 0);
            return msSince(t0);
        })});
        astratto_destroy(e);
    }

    // ---- the simulation: a crowd of bodies walking, and an empty frame's cost
    {
        astratto_engine* e = engine(0);
        activate(e, makeWorld(e, cells, true));
        std::vector<astratto_handle> movers(500, 0);
        astratto_handles_reserve(e, ASTRATTO_KIND_MOVER, static_cast<uint32_t>(movers.size()), movers.data());
        Rng r(3);
        Cmds c;
        for (astratto_handle h : movers) {
            astratto_cmd_mover_create& mc = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
            mc.handle = h; mc.x = r.range(8, SX - 8); mc.y = 40; mc.z = r.range(8, SZ - 8); mc.radius = 0.3f; mc.height = 1.8f;
        }
        frame(e, c, 1.0f / 60);
        int f = 0;
        results.push_back({"movers_500_frame", "a frame of 500 walking bodies", median(31, [&] {
            for (size_t i = 0; i < movers.size(); i++) {
                astratto_cmd_mover_input& in = c.add<astratto_cmd_mover_input>(ASTRATTO_CMD_MOVER_INPUT);
                in.handle = movers[i]; in.vx = 3.0f * std::sin(f * 0.05f + static_cast<float>(i)); in.vz = 3.0f * std::cos(f * 0.07f + static_cast<float>(i));
            }
            f++;
            const auto t0 = Clock::now();
            frame(e, c, 1.0f / 60);
            return msSince(t0);
        })});
        astratto_destroy(e);
        astratto_engine* idle = engine(0);
        results.push_back({"empty_frame", "a frame with nothing to do (the fixed cost of a tick, mean of 1000)", median(5, [&] {
            const auto t0 = Clock::now();
            for (int i = 0; i < 1000; i++) frame(idle, c, 1.0f / 60);
            return msSince(t0) / 1000;
        })});
        astratto_destroy(idle);
    }

    // ---- the mixer: 64 positional voices, a second of audio
    {
        astratto_engine* e = engine(0);
        activate(e, makeWorld(e, cells, true));
        const int rate = 44100;
        std::vector<float> tone(rate);
        for (int i = 0; i < rate; i++) tone[i] = 0.25f * std::sin(2 * 3.14159265f * 220.0f * i / rate);
        astratto_sound_desc sd{};
        sd.struct_size = sizeof sd; sd.channels = 1; sd.rate = rate; sd.frames = rate; sd.samples = tone.data();
        astratto_handle sound = 0;
        astratto_sound_create(e, &sd, &sound);
        std::vector<astratto_handle> voices(64, 0);
        astratto_handles_reserve(e, ASTRATTO_KIND_VOICE, 64, voices.data());
        Cmds c;
        astratto_cmd_listener& l = c.add<astratto_cmd_listener>(ASTRATTO_CMD_LISTENER);
        l.x = 128; l.y = 32; l.z = 128;
        Rng r(9);
        for (astratto_handle v : voices) {
            astratto_cmd_voice_play& p = c.add<astratto_cmd_voice_play>(ASTRATTO_CMD_VOICE_PLAY);
            p.voice = v; p.sound = sound; p.flags = ASTRATTO_VOICE_POSITIONAL | ASTRATTO_VOICE_LOOP | ASTRATTO_VOICE_OCCLUDE; p.gain = 1; p.pitch = r.range(0.8f, 1.2f);
            p.x = r.range(96, 160); p.y = 30; p.z = r.range(96, 160); p.range = 60;
        }
        frame(e, c, 1.0f / 60);
        std::vector<float> out(1024 * 2);
        results.push_back({"mix_64_voices_1s", "mix one second of 64 positional, occluded voices", median(5, [&] {
            const auto t0 = Clock::now();
            for (int i = 0; i < 43; i++) astratto_audio_render(e, out.data(), 1024);
            return msSince(t0);
        })});
        astratto_destroy(e);
    }

    int over = 0;
    std::printf("  %-22s %10s %10s  %s\n", "measure", "median ms", "budget", "");
    for (const Result& r : results) {
        const auto b = budgets.find(r.name);
        const bool fail = b != budgets.end() && r.ms > b->second;
        if (fail) over++;
        char budget[32] = "-";
        if (b != budgets.end()) std::snprintf(budget, sizeof budget, "%.3f", b->second);
        std::printf("  %-22s %10.3f %10s  %s%s\n", r.name.c_str(), r.ms, budget, fail ? "OVER BUDGET - " : "", r.what.c_str());
    }
    if (argc > 2) {
        std::ofstream js(argv[2]);
        js << "{\n  \"build\": \"" << astratto_build_info() << "\",\n  \"results\": {\n";
        for (size_t i = 0; i < results.size(); i++) js << "    \"" << results[i].name << "\": " << results[i].ms << (i + 1 < results.size() ? ",\n" : "\n");
        js << "  }\n}\n";
    }
    std::printf("%d measures, %d over budget\n", static_cast<int>(results.size()), over);
    return over == 0 ? 0 : 1;
}
