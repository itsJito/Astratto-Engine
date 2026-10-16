// Astratto Engine - the C interface: the one door in the wall. Nothing C++ crosses it, in either direction.
#include <cstddef>
#include <cstring>
#include <exception>
#include <new>
#include <vector>

#include "astratto/astratto.h"
#include "astratto/astratto_module.h"
#include "audio/audio_service.h"
#include "core/engine.h"
#include "modules/module_host.h"
#include "render/chunk_mesher.h"
#include "render/renderer.h"
#include <string>
#include <unordered_map>

using astratto::Engine;

// The layouts the other side of the bridge writes by hand: if one of these moves, so must every binding (and
// astratto_layout below tells a binding the sizes, so that it can check itself when it loads).
static_assert(sizeof(astratto_record) == 8, "record header");
static_assert(sizeof(astratto_block_def) == 12, "block def");
static_assert(sizeof(astratto_cmd_block_set) == 24, "block set");
static_assert(sizeof(astratto_cmd_handle) == 16, "handle command");
static_assert(sizeof(astratto_cmd_door_seal) == 24, "door seal");
static_assert(sizeof(astratto_cmd_nav_tuning) == 24, "nav tuning");
static_assert(sizeof(astratto_cmd_path_request) == 48, "path request");
static_assert(sizeof(astratto_cmd_sight_request) == 40, "sight request");
static_assert(sizeof(astratto_cmd_ray_query) == 40, "ray query");
static_assert(sizeof(astratto_evt_path_result) == 24, "path result");
static_assert(sizeof(astratto_evt_sight_result) == 16, "sight result");
static_assert(sizeof(astratto_evt_ray_result) == 48, "ray result");
static_assert(sizeof(astratto_evt_error) == 24 && offsetof(astratto_evt_error, handle) == 16, "error event");
static_assert(sizeof(astratto_frame_io) == 40 && offsetof(astratto_frame_io, commands) == 8 && offsetof(astratto_frame_io, events) == 24, "frame");
static_assert(sizeof(astratto_block_table) == 16, "block table");
static_assert(sizeof(astratto_snapshot_io) == 24, "snapshot");
static_assert(sizeof(astratto_config) == 24, "config");
static_assert(sizeof(astratto_cmd_mover_create) == 48 && offsetof(astratto_cmd_mover_create, x) == 16, "mover create");
static_assert(sizeof(astratto_evt_agent) == 32, "agent event");
static_assert(sizeof(astratto_action) == 28 && sizeof(astratto_consideration) == 24, "policy parts");
static_assert(sizeof(astratto_cmd_voice_play) == 72, "voice play");
static_assert(sizeof(astratto_render_block) == 12, "render block");
static_assert(sizeof(astratto_shape_def) == 12, "shape def");
static_assert(sizeof(astratto_render_table) == 48 && offsetof(astratto_render_table, blocks) == 8 && offsetof(astratto_render_table, shapes) == 24 && offsetof(astratto_render_table, boxes) == 32, "render table");
static_assert(sizeof(astratto_rcmd_foliage) == 168 && sizeof(astratto_rcmd_props) == 16 && sizeof(astratto_rcmd_decals) == 32, "2.1 render layer records");
static_assert(sizeof(astratto_mesh_io) == 72 && offsetof(astratto_mesh_io, blocks) == 32 && offsetof(astratto_mesh_io, opaque) == 48 && offsetof(astratto_mesh_io, opaque_bytes) == 64, "mesh io");

struct astratto_engine {
    Engine impl;
    std::unordered_map<std::string, std::string> shaders;     // handed over by name before a renderer is made
    explicit astratto_engine(const astratto_config& c) : impl(c) {}
};
struct astratto_renderer { astratto::Renderer impl; };
struct astratto_mesher {
    astratto::ChunkMesher impl;
    std::vector<uint8_t> cells, light;     // a column of an engine world, copied out for meshing
    explicit astratto_mesher(const astratto_render_table& t) : impl(t) {}
};

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

/** The same, for a renderer. */
template <typename F> int32_t rguarded(astratto_renderer* r, F&& body) {
    if (r == nullptr) return ASTRATTO_ERR_ARGUMENT;
    try { return body(r->impl); }
    catch (const std::bad_alloc&) { r->impl.fail("out of memory"); return ASTRATTO_ERR_MEMORY; }
    catch (const std::exception& x) { r->impl.fail(x.what()); return ASTRATTO_ERR_INTERNAL; }
    catch (...) { r->impl.fail("unknown failure"); return ASTRATTO_ERR_INTERNAL; }
}

struct Layout { const char* name; uint32_t size; };
#define L(T) {#T, static_cast<uint32_t>(sizeof(T))}
const Layout LAYOUTS[] = {
    L(astratto_config), L(astratto_block_def), L(astratto_block_table), L(astratto_world_desc), L(astratto_world_view), L(astratto_record),
    L(astratto_cmd_block_set), L(astratto_edit_fill), L(astratto_edit_box), L(astratto_edit_light_fill), L(astratto_edit_light_set), L(astratto_edit_column),
    L(astratto_cmd_handle), L(astratto_cmd_door_seal), L(astratto_cmd_nav_tuning), L(astratto_cmd_path_request), L(astratto_cmd_sight_request),
    L(astratto_cmd_walk_query), L(astratto_cmd_ray_query), L(astratto_evt_error), L(astratto_evt_path_result), L(astratto_evt_sight_result),
    L(astratto_evt_walk_result), L(astratto_evt_ray_result),
    L(astratto_cmd_surface), L(astratto_cmd_gravity), L(astratto_cmd_mover_create), L(astratto_cmd_mover_input), L(astratto_cmd_body_place),
    L(astratto_cmd_body_impulse), L(astratto_cmd_ragdoll_create), L(astratto_cmd_ragdoll_pose), L(astratto_cmd_ragdoll_hit), L(astratto_cmd_ragdoll_impulse),
    L(astratto_cmd_ragdoll_control), L(astratto_cmd_disturb), L(astratto_cmd_ragdoll_carry), L(astratto_sydney_profile), L(astratto_cmd_sydney_profile),
    L(astratto_softbody_particle), L(astratto_softbody_stick), L(astratto_softbody_limit), L(astratto_cmd_softbody_create), L(astratto_cmd_softbody_impulse),
    L(astratto_vehicle_desc), L(astratto_cmd_vehicle_create), L(astratto_cmd_vehicle_input), L(astratto_evt_body), L(astratto_evt_ragdoll_state),
    L(astratto_evt_vehicle_impact),
    L(astratto_consideration), L(astratto_action), L(astratto_cmd_policy_create), L(astratto_cmd_agent_create), L(astratto_cmd_agent_fact),
    L(astratto_cmd_agent_target), L(astratto_cmd_agent_goal), L(astratto_cmd_agent_policy), L(astratto_cmd_agent_force), L(astratto_cmd_noise), L(astratto_evt_agent),
    L(astratto_sound_desc), L(astratto_cmd_listener), L(astratto_cmd_voice_play), L(astratto_cmd_voice_set), L(astratto_cmd_voice_stop), L(astratto_cmd_audio_mix),
    L(astratto_frame_io), L(astratto_stats), L(astratto_snap_mover), L(astratto_snap_ragdoll), L(astratto_snap_softbody), L(astratto_snap_vehicle),
    L(astratto_snap_agent), L(astratto_audio_stats), L(astratto_snapshot_io), L(astratto_evt_module_log), L(astratto_module_host), L(astratto_module),
    L(astratto_render_block), L(astratto_shape_def), L(astratto_render_table), L(astratto_mesh_io),
    L(astratto_renderer_desc), L(astratto_texture_desc), L(astratto_world_look), L(astratto_rcmd_camera), L(astratto_rcmd_env), L(astratto_rcmd_light),
    L(astratto_rcmd_model), L(astratto_rcmd_particles), L(astratto_rcmd_quads), L(astratto_rcmd_grading), L(astratto_rcmd_body), L(astratto_rcmd_world),
    L(astratto_rcmd_clear), L(astratto_rcmd_quality), L(astratto_render_io), L(astratto_rcmd_foliage), L(astratto_rcmd_props), L(astratto_rcmd_decals)};
#undef L

}  // namespace

extern "C" {

uint32_t astratto_api_version(void) { return ASTRATTO_API_VERSION; }

const char* astratto_build_info(void) {
    return "Astratto Engine native core, API 2.0, built " __DATE__ " " __TIME__
#if defined(_MSC_VER)
           " (MSVC)"
#elif defined(__clang__)
           " (Clang)"
#elif defined(__GNUC__)
           " (GCC)"
#endif
        ;
}

uint32_t astratto_layout(const char* name) {
    if (name == nullptr) return 0;
    for (const Layout& l : LAYOUTS) if (std::strcmp(l.name, name) == 0) return l.size;
    return 0;
}

int32_t astratto_create_status(void) { return createStatus; }

astratto_engine* astratto_create(const astratto_config* config) {
    createStatus = ASTRATTO_OK;
    if (config == nullptr || config->struct_size < 16) { createStatus = ASTRATTO_ERR_ARGUMENT; return nullptr; }
    // a caller built for another major version would misread what it is given; one built for a later minor of this
    // major asks for things this engine has not got
    if ((config->api_version >> 16) != ASTRATTO_API_MAJOR || (config->api_version & 0xFFFFu) > ASTRATTO_API_MINOR) { createStatus = ASTRATTO_ERR_VERSION; return nullptr; }
    astratto_config c{};
    std::memcpy(&c, config, config->struct_size < sizeof(c) ? config->struct_size : sizeof(c));
    c.struct_size = sizeof(c);
    try { return new astratto_engine(c); }
    catch (const std::bad_alloc&) { createStatus = ASTRATTO_ERR_MEMORY; }
    catch (...) { createStatus = ASTRATTO_ERR_INTERNAL; }
    return nullptr;
}

void astratto_destroy(astratto_engine* engine) { delete engine; }

const char* astratto_last_error(astratto_engine* engine) { return engine == nullptr ? "no engine" : engine->impl.error(); }

int32_t astratto_handles_reserve(astratto_engine* engine, uint32_t kind, uint32_t count, astratto_handle* out) {
    return guarded(engine, [&](Engine& e) { return e.reserve(kind, count, out); });
}

int32_t astratto_release(astratto_engine* engine, astratto_handle handle) {
    return guarded(engine, [&](Engine& e) { return e.release(handle); });
}

int32_t astratto_world_block_table(astratto_engine* engine, const astratto_block_table* table) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (table == nullptr || table->struct_size < sizeof(astratto_block_table) || table->count > 256 || (table->count > 0 && table->defs == nullptr)) { e.fail("bad block table"); return ASTRATTO_ERR_ARGUMENT; }
        e.table().set(table->defs, table->count);
        return ASTRATTO_OK;
    });
}

int32_t astratto_world_create(astratto_engine* engine, const astratto_world_desc* desc, astratto_handle* out) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (desc == nullptr || out == nullptr || desc->struct_size < sizeof(astratto_world_desc)) { e.fail("bad world description"); return ASTRATTO_ERR_ARGUMENT; }
        return e.createWorld(*desc, out);
    });
}

int32_t astratto_world_get_view(astratto_engine* engine, astratto_handle world, astratto_world_view* out) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (out == nullptr || out->struct_size < sizeof(astratto_world_view)) { e.fail("bad world view"); return ASTRATTO_ERR_ARGUMENT; }
        const astratto::VoxelWorld* w = e.worlds().get(world);
        if (w == nullptr) { e.fail("view of a world handle that is not a live world"); return ASTRATTO_ERR_HANDLE; }
        out->size_x = static_cast<uint32_t>(w->sizeX()); out->size_y = static_cast<uint32_t>(w->sizeY()); out->size_z = static_cast<uint32_t>(w->sizeZ());
        out->blocks = w->blocks(); out->light = w->light(); out->pristine = w->pristine();
        out->cells = static_cast<uint64_t>(w->sizeX()) * static_cast<uint64_t>(w->sizeY()) * static_cast<uint64_t>(w->sizeZ());
        out->edits = w->edits(); out->nav_version = w->navVersion(); out->light_version = w->lightVersion();
        return ASTRATTO_OK;
    });
}

int32_t astratto_world_edit(astratto_engine* engine, astratto_handle world, const uint8_t* records, uint32_t bytes) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (bytes > 0 && records == nullptr) { e.fail("no edit records"); return ASTRATTO_ERR_ARGUMENT; }
        return e.edit(world, records, bytes);
    });
}

int32_t astratto_world_set(astratto_engine* engine, astratto_handle world, int32_t x, int32_t y, int32_t z, uint32_t block) {
    if (engine == nullptr) return ASTRATTO_ERR_ARGUMENT;
    return engine->impl.setCell(world, x, y, z, block);     // nothing in it can throw: no guard on the hottest call
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
        return e.snapshot(io->kind, &io->data, &io->bytes);
    });
}

int32_t astratto_sound_create(astratto_engine* engine, const astratto_sound_desc* desc, astratto_handle* out) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (desc == nullptr || out == nullptr || desc->struct_size < sizeof(astratto_sound_desc)) { e.fail("bad sound description"); return ASTRATTO_ERR_ARGUMENT; }
        astratto::AudioService* a = e.service<astratto::AudioService>();
        return a ? a->createSound(e, *desc, out) : ASTRATTO_ERR_UNSUPPORTED;
    });
}

int32_t astratto_audio_render(astratto_engine* engine, float* out, uint32_t frames) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (out == nullptr) { e.fail("no buffer to render into"); return ASTRATTO_ERR_ARGUMENT; }
        astratto::AudioService* a = e.service<astratto::AudioService>();
        if (a == nullptr) return ASTRATTO_ERR_UNSUPPORTED;
        if (!a->render(out, frames)) { e.fail("the engine has an audio device: it mixes for itself"); return ASTRATTO_ERR_STATE; }
        return ASTRATTO_OK;
    });
}

int32_t astratto_module_load(astratto_engine* engine, const char* path, const char* config, astratto_handle* out) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        astratto::ModuleHost* m = e.service<astratto::ModuleHost>();
        return m ? m->load(e, path, config, out) : ASTRATTO_ERR_UNSUPPORTED;
    });
}

// ------------------------------------------------------------------ rendering: the mesher (1.1)
astratto_mesher* astratto_mesher_create(const astratto_render_table* table) {
    if (table == nullptr || table->struct_size < sizeof(astratto_render_table) || table->block_count > 256 || (table->block_count > 0 && table->blocks == nullptr)
        || (table->shape_count > 0 && table->shapes == nullptr) || (table->box_count > 0 && table->boxes == nullptr)) return nullptr;
    for (uint32_t i = 0; i < table->shape_count; i++) if (static_cast<uint64_t>(table->shapes[i].first_box) + table->shapes[i].box_count > table->box_count) return nullptr;
    try { return new astratto_mesher(*table); }
    catch (...) { return nullptr; }
}

void astratto_mesher_destroy(astratto_mesher* mesher) { delete mesher; }

namespace {
int32_t meshInto(astratto_mesher* mesher, astratto_mesh_io* io) {
    if (!mesher->impl.mesh(*io)) return ASTRATTO_ERR_ARGUMENT;
    const auto& o = mesher->impl.opaque();
    const auto& t = mesher->impl.translucent();
    io->opaque = o.empty() ? nullptr : o.data();
    io->opaque_bytes = static_cast<uint32_t>(o.size());
    io->translucent = t.empty() ? nullptr : t.data();
    io->translucent_bytes = static_cast<uint32_t>(t.size());
    return ASTRATTO_OK;
}
}  // namespace

int32_t astratto_mesh_column(astratto_mesher* mesher, astratto_mesh_io* io) {
    if (mesher == nullptr || io == nullptr || io->struct_size < sizeof(astratto_mesh_io)) return ASTRATTO_ERR_ARGUMENT;
    try { return meshInto(mesher, io); }
    catch (const std::bad_alloc&) { return ASTRATTO_ERR_MEMORY; }
    catch (...) { return ASTRATTO_ERR_INTERNAL; }
}

int32_t astratto_mesh_world_column(astratto_mesher* mesher, astratto_engine* engine, astratto_handle world, int32_t column_x, int32_t column_z, uint32_t flags, astratto_mesh_io* out) {
    if (mesher == nullptr || engine == nullptr || out == nullptr || out->struct_size < sizeof(astratto_mesh_io)) return ASTRATTO_ERR_ARGUMENT;
    try {
        const astratto::VoxelWorld* w = engine->impl.worlds().get(world);
        if (w == nullptr) return ASTRATTO_ERR_HANDLE;
        if (column_x < 0 || column_z < 0 || column_x >= w->columnsX() || column_z >= w->columnsZ()) return ASTRATTO_ERR_ARGUMENT;
        astratto::copyColumn(*w, column_x, column_z, mesher->cells, mesher->light);
        astratto_mesh_io io{};
        io.struct_size = sizeof(io); io.flags = flags; io.size_y = static_cast<uint32_t>(w->sizeY());
        io.origin_x = column_x * 16; io.origin_z = column_z * 16; io.world_x = w->sizeX(); io.world_z = w->sizeZ();
        io.blocks = mesher->cells.data(); io.light = mesher->light.data();
        const int32_t st = meshInto(mesher, &io);
        *out = io;
        return st;
    } catch (const std::bad_alloc&) { return ASTRATTO_ERR_MEMORY; }
    catch (...) { return ASTRATTO_ERR_INTERNAL; }
}

// ------------------------------------------------------------------ rendering: the renderer (2.0)

int32_t astratto_renderer_shader(astratto_engine* engine, const char* name, const char* source) {
    if (engine == nullptr || name == nullptr || source == nullptr) return ASTRATTO_ERR_ARGUMENT;
    try { engine->shaders[name] = source; return ASTRATTO_OK; }
    catch (...) { return ASTRATTO_ERR_MEMORY; }
}

int32_t astratto_renderer_create(astratto_engine* engine, const astratto_renderer_desc* desc, astratto_renderer** out) {
    return guarded(engine, [&](Engine& e) -> int32_t {
        if (desc == nullptr || out == nullptr || desc->struct_size < sizeof(astratto_renderer_desc)) { e.fail("bad renderer description"); return ASTRATTO_ERR_ARGUMENT; }
        *out = nullptr;
        std::unique_ptr<astratto_renderer> r(new astratto_renderer());
        if (!r->impl.create(*desc, engine->shaders)) {
            e.fail(std::string("renderer: ") + r->impl.error());
            return std::string(r->impl.error()).find("OpenGL 4.5") != std::string::npos ? ASTRATTO_ERR_UNSUPPORTED : ASTRATTO_ERR_INTERNAL;
        }
        *out = r.release();
        return ASTRATTO_OK;
    });
}

void astratto_renderer_destroy(astratto_renderer* renderer) { delete renderer; }

const char* astratto_renderer_error(astratto_renderer* renderer) { return renderer == nullptr ? "no renderer" : renderer->impl.error(); }

int32_t astratto_render_texture(astratto_renderer* renderer, const astratto_texture_desc* desc, uint32_t* out) {
    return rguarded(renderer, [&](astratto::Renderer& r) -> int32_t {
        if (desc == nullptr || out == nullptr || desc->struct_size < sizeof(astratto_texture_desc)) { r.fail("bad texture description"); return ASTRATTO_ERR_ARGUMENT; }
        return r.texture(*desc, out);
    });
}

int32_t astratto_render_mesh(astratto_renderer* renderer, const float* vertices, uint32_t vertex_count, uint32_t* out) {
    return rguarded(renderer, [&](astratto::Renderer& r) -> int32_t {
        if (out == nullptr) { r.fail("nowhere to put the mesh"); return ASTRATTO_ERR_ARGUMENT; }
        return r.mesh(vertices, vertex_count, out);
    });
}

int32_t astratto_render_layer_mesh(astratto_renderer* renderer, uint32_t kind, const float* vertices, uint32_t vertex_count, uint32_t* out) {
    return rguarded(renderer, [&](astratto::Renderer& r) -> int32_t {
        if (out == nullptr) { r.fail("nowhere to put the mesh"); return ASTRATTO_ERR_ARGUMENT; }
        return r.layerMesh(kind, vertices, vertex_count, out);
    });
}

int32_t astratto_render_instances(astratto_renderer* renderer, uint32_t mesh, const float* data, uint32_t count, uint32_t flags) {
    return rguarded(renderer, [&](astratto::Renderer& r) { return r.instances(mesh, data, count, flags); });
}

int32_t astratto_render_release(astratto_renderer* renderer, uint32_t kind, uint32_t id) {
    return rguarded(renderer, [&](astratto::Renderer& r) { return r.release(kind, id); });
}

int32_t astratto_render_world_look(astratto_renderer* renderer, const astratto_world_look* look) {
    return rguarded(renderer, [&](astratto::Renderer& r) -> int32_t {
        if (look == nullptr || look->struct_size < sizeof(astratto_world_look)) { r.fail("bad world look"); return ASTRATTO_ERR_ARGUMENT; }
        return r.worldLook(*look);
    });
}

int32_t astratto_render_frame(astratto_renderer* renderer, astratto_engine* engine, astratto_render_io* io) {
    return rguarded(renderer, [&](astratto::Renderer& r) -> int32_t {
        if (io == nullptr || io->struct_size < sizeof(astratto_render_io)) { r.fail("bad render frame"); return ASTRATTO_ERR_ARGUMENT; }
        return r.frame(engine == nullptr ? nullptr : &engine->impl, *io);
    });
}

int32_t astratto_render_read_pixels(astratto_renderer* renderer, uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint8_t* out) {
    return rguarded(renderer, [&](astratto::Renderer& r) { return r.readPixels(x, y, width, height, out); });
}

}  // extern "C"
