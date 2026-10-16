// Astratto Engine - the C interface: the one door in the wall. Nothing C++ crosses it, in either direction.
#include <cstddef>
#include <exception>
#include <new>

#include "astratto/astratto.h"
#include "core/engine.h"
#include "render/chunk_mesher.h"

using astratto::Engine;

// The layouts the other side of the bridge writes by hand: if one of these moves, so must every binding.
static_assert(sizeof(astratto_record) == 8, "record header");
static_assert(sizeof(astratto_block_def) == 8, "block def");
static_assert(sizeof(astratto_cmd_block_set) == 24, "block set");
static_assert(sizeof(astratto_cmd_door_seal) == 24, "door seal");
static_assert(sizeof(astratto_cmd_nav_tuning) == 24, "nav tuning");
static_assert(sizeof(astratto_cmd_path_request) == 48, "path request");
static_assert(sizeof(astratto_cmd_sight_request) == 40, "sight request");
static_assert(sizeof(astratto_evt_path_result) == 24, "path result");
static_assert(sizeof(astratto_evt_sight_result) == 16, "sight result");
static_assert(sizeof(astratto_frame_io) == 40 && offsetof(astratto_frame_io, commands) == 8 && offsetof(astratto_frame_io, events) == 24, "frame");
static_assert(sizeof(astratto_world_desc) == 40 && offsetof(astratto_world_desc, blocks) == 24, "world desc");
static_assert(sizeof(astratto_block_table) == 16, "block table");
static_assert(sizeof(astratto_snapshot_io) == 24, "snapshot");
static_assert(sizeof(astratto_stats) == 96, "stats");
static_assert(sizeof(astratto_render_block) == 12, "render block");
static_assert(sizeof(astratto_shape_def) == 12, "shape def");
static_assert(sizeof(astratto_render_table) == 48 && offsetof(astratto_render_table, blocks) == 8 && offsetof(astratto_render_table, shapes) == 24 && offsetof(astratto_render_table, boxes) == 32, "render table");
static_assert(sizeof(astratto_mesh_io) == 72 && offsetof(astratto_mesh_io, blocks) == 32 && offsetof(astratto_mesh_io, opaque) == 48 && offsetof(astratto_mesh_io, opaque_bytes) == 64, "mesh io");

struct astratto_engine { Engine impl; explicit astratto_engine(unsigned workers) : impl(workers) {} };
struct astratto_mesher { astratto::ChunkMesher impl; explicit astratto_mesher(const astratto_render_table& t) : impl(t) {} };

namespace {

thread_local int32_t createStatus = ASTRATTO_OK;

/** Runs a call's body; whatever it throws stays on this side of the boundary. */
template <typename F> int32_t guarded(astratto_engine* e, F&& body) {
    if (e == nullptr) return ASTRATTO_ERR_ARGUMENT;
    try { return body(e->impl); }
    catch (const std::bad_alloc&) { e->impl.fail("out of memory"); return ASTRATTO_ERR_MEMORY; }
    catch (const std::exception& x) { e->impl.fail(x.what()); return ASTRATTO_ERR_INTERNAL; }
    catch (...) { e->impl.fail("unknown failure"); return ASTRATTO_ERR_INTERNAL; }
}

}  // namespace

extern "C" {

uint32_t astratto_api_version(void) { return ASTRATTO_API_VERSION; }

const char* astratto_build_info(void) {
    return "Astratto Engine native core, API 1.1, built " __DATE__ " " __TIME__
#if defined(_MSC_VER)
           " (MSVC)"
#elif defined(__clang__)
           " (Clang)"
#elif defined(__GNUC__)
           " (GCC)"
#endif
        ;
}

int32_t astratto_create_status(void) { return createStatus; }

astratto_engine* astratto_create(const astratto_config* config) {
    createStatus = ASTRATTO_OK;
    if (config == nullptr || config->struct_size < sizeof(astratto_config)) { createStatus = ASTRATTO_ERR_ARGUMENT; return nullptr; }
    // a caller built for another major version would misread what it is given; one built for a later minor of this
    // major asks for things this engine has not got
    if ((config->api_version >> 16) != ASTRATTO_API_MAJOR || (config->api_version & 0xFFFFu) > ASTRATTO_API_MINOR) { createStatus = ASTRATTO_ERR_VERSION; return nullptr; }
    try { return new astratto_engine(config->worker_threads); }
    catch (const std::bad_alloc&) { createStatus = ASTRATTO_ERR_MEMORY; }
    catch (...) { createStatus = ASTRATTO_ERR_INTERNAL; }
    return nullptr;
}

void astratto_destroy(astratto_engine* engine) { delete engine; }

const char* astratto_last_error(astratto_engine* engine) { return engine == nullptr ? "no engine" : engine->impl.error(); }

int32_t astratto_world_block_table(astratto_engine* engine, const astratto_block_table* table) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (table == nullptr || table->struct_size < sizeof(astratto_block_table) || table->count > 256 || (table->count > 0 && table->defs == nullptr)) { e.fail("bad block table"); return ASTRATTO_ERR_ARGUMENT; }
        e.world().setTable(table->defs, table->count);
        return ASTRATTO_OK;
    });
}

int32_t astratto_world_load(astratto_engine* engine, const astratto_world_desc* world) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (world == nullptr || world->struct_size < sizeof(astratto_world_desc)) { e.fail("bad world description"); return ASTRATTO_ERR_ARGUMENT; }
        if (!e.world().load(world->size_x, world->size_y, world->size_z, world->below_floor_block, world->blocks, world->block_bytes)) { e.fail("world sizes out of range, or not the number of cells given"); return ASTRATTO_ERR_ARGUMENT; }
        return ASTRATTO_OK;
    });
}

int32_t astratto_frame(astratto_engine* engine, astratto_frame_io* io) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (io == nullptr || io->struct_size < sizeof(astratto_frame_io) || (io->command_bytes > 0 && io->commands == nullptr)) { e.fail("bad frame"); return ASTRATTO_ERR_ARGUMENT; }
        const int32_t status = e.frame(io->commands, io->command_bytes, io->dt);
        io->events = e.events().empty() ? nullptr : e.events().data();
        io->event_bytes = static_cast<uint32_t>(e.events().size());
        io->event_count = e.eventCount();
        return status;
    });
}

int32_t astratto_snapshot(astratto_engine* engine, astratto_snapshot_io* io) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (io == nullptr || io->struct_size < sizeof(astratto_snapshot_io)) { e.fail("bad snapshot request"); return ASTRATTO_ERR_ARGUMENT; }
        switch (io->kind) {
            case ASTRATTO_SNAPSHOT_STATS: { const astratto_stats& s = e.stats(); io->data = &s; io->bytes = sizeof(s); return ASTRATTO_OK; }
            default: e.fail("unknown snapshot kind"); io->data = nullptr; io->bytes = 0; return ASTRATTO_ERR_ARGUMENT;
        }
    });
}

// ------------------------------------------------------------------ rendering (1.1)
astratto_mesher* astratto_mesher_create(const astratto_render_table* table) {
    if (table == nullptr || table->struct_size < sizeof(astratto_render_table) || table->block_count > 256 || (table->block_count > 0 && table->blocks == nullptr)
        || (table->shape_count > 0 && table->shapes == nullptr) || (table->box_count > 0 && table->boxes == nullptr)) return nullptr;
    for (uint32_t i = 0; i < table->shape_count; i++) if (static_cast<uint64_t>(table->shapes[i].first_box) + table->shapes[i].box_count > table->box_count) return nullptr;
    try { return new astratto_mesher(*table); }
    catch (...) { return nullptr; }
}

void astratto_mesher_destroy(astratto_mesher* mesher) { delete mesher; }

int32_t astratto_mesh_column(astratto_mesher* mesher, astratto_mesh_io* io) {
    if (mesher == nullptr || io == nullptr || io->struct_size < sizeof(astratto_mesh_io)) return ASTRATTO_ERR_ARGUMENT;
    try {
        if (!mesher->impl.mesh(*io)) return ASTRATTO_ERR_ARGUMENT;
        const auto& o = mesher->impl.opaque();
        const auto& t = mesher->impl.translucent();
        io->opaque = o.empty() ? nullptr : o.data();
        io->opaque_bytes = static_cast<uint32_t>(o.size());
        io->translucent = t.empty() ? nullptr : t.data();
        io->translucent_bytes = static_cast<uint32_t>(t.size());
        return ASTRATTO_OK;
    } catch (const std::bad_alloc&) { return ASTRATTO_ERR_MEMORY; }
    catch (...) { return ASTRATTO_ERR_INTERNAL; }
}

}  // extern "C"
