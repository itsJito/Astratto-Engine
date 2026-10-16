#include "core/engine.h"

#include <chrono>
#include <cstring>

#include "perception/sight.h"

namespace astratto {

namespace {

unsigned extraThreads(const astratto_config& c) {
    if (c.flags & ASTRATTO_CONFIG_DETERMINISTIC) return 0;
    unsigned asked = c.worker_threads;
    if (asked == 0) {
        const unsigned hw = std::thread::hardware_concurrency();
        asked = hw > 1 ? hw - 1 : 1;
        if (asked > 8) asked = 8;          // the work is short and parallel: past this the threads only wake each other
    }
    return asked > 0 ? asked - 1 : 0;       // the caller's thread is one of the workers
}

}  // namespace

Engine::Engine(const astratto_config& cfg) : config_(cfg), pool_(extraThreads(cfg)) {
    for (unsigned i = 0; i < pool_.workers(); i++) finders_.emplace_back(new Pathfinder());
    stats_.struct_size = sizeof(astratto_stats);
    stats_.workers = pool_.workers();
}

Engine::~Engine() {
    // services first: they hold handles into the worlds
    services_.clear();
}

void Engine::error(uint32_t command, int32_t status, astratto_handle h) {
    astratto_evt_error e{};
    e.command = command; e.status = status; e.handle = h;
    emit(e, ASTRATTO_EVT_ERROR);
}

// ------------------------------------------------------------------ handles
int32_t Engine::reserve(uint32_t kind, uint32_t count, astratto_handle* out) {
    if (out == nullptr || kind == 0 || kind >= ASTRATTO_KINDS) { fail("bad handle reservation"); return ASTRATTO_ERR_ARGUMENT; }
    if (kind == ASTRATTO_KIND_WORLD) { fail("worlds are made with astratto_world_create"); return ASTRATTO_ERR_ARGUMENT; }
    // a service's kinds are reserved through it: the service that knows the kind takes the reservation
    for (const auto& s : services_) {
        if (!s->owns(kind)) continue;
        for (uint32_t i = 0; i < count; i++) out[i] = s->reserve(kind);
        return ASTRATTO_OK;
    }
    fail("no service for that kind of handle");
    return ASTRATTO_ERR_UNSUPPORTED;
}

int32_t Engine::release(astratto_handle h) {
    if (h == ASTRATTO_NO_HANDLE) return ASTRATTO_ERR_HANDLE;
    if (handleKind(h) == ASTRATTO_KIND_WORLD) {
        if (!worlds_.valid(h)) { fail("release of a stale world handle"); return ASTRATTO_ERR_HANDLE; }
        if (h == activeHandle_) { for (const auto& s : services_) s->worldChanged(*this); active_ = nullptr; activeHandle_ = ASTRATTO_NO_HANDLE; }
        worlds_.release(h);
        return ASTRATTO_OK;
    }
    for (const auto& s : services_) if (s->release(*this, h)) return ASTRATTO_OK;
    fail("release of a handle nothing holds");
    return ASTRATTO_ERR_HANDLE;
}

// ------------------------------------------------------------------ worlds
int32_t Engine::createWorld(const astratto_world_desc& d, astratto_handle* out) {
    std::unique_ptr<VoxelWorld> w(new VoxelWorld(&table_));
    if (!w->create(d.size_x, d.size_y, d.size_z, d.below_floor_block, d.blocks, d.block_bytes, d.light, d.light_bytes)) {
        fail("world sizes out of range, or not the number of cells given");
        return ASTRATTO_ERR_ARGUMENT;
    }
    if (d.flags & ASTRATTO_WORLD_LIGHT) w->lightAll();
    const astratto_handle h = worlds_.reserve();
    worlds_.put(h, std::move(w));
    *out = h;
    return ASTRATTO_OK;
}

int Engine::applyEdit(VoxelWorld& w, uint32_t type, const uint8_t* rec, uint32_t bytes) {
    switch (type) {
        case ASTRATTO_EDIT_BLOCK: {
            astratto_cmd_block_set c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (c.block & ASTRATTO_SET_RAW) w.setRaw(c.x, c.y, c.z, static_cast<int>(c.block & 0xFF));
            else w.set(c.x, c.y, c.z, static_cast<int>(c.block & 0xFF));
            stats_.block_edits++;
            return ASTRATTO_OK;
        }
        case ASTRATTO_EDIT_FILL: {
            astratto_edit_fill c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            w.fill(c.x0, c.y0, c.z0, c.x1, c.y1, c.z1, static_cast<int>(c.block & 0xFF));
            return ASTRATTO_OK;
        }
        case ASTRATTO_EDIT_LIGHT_ALL: w.lightAll(); return ASTRATTO_OK;
        case ASTRATTO_EDIT_LIGHT_BOX: {
            astratto_edit_box c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            w.relightBox(c.x0, c.z0, c.x1, c.z1);
            return ASTRATTO_OK;
        }
        case ASTRATTO_EDIT_LIGHT_FILL: {
            astratto_edit_light_fill c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            w.lightFill(static_cast<uint8_t>(c.value));
            return ASTRATTO_OK;
        }
        case ASTRATTO_EDIT_LIGHT_SET: {
            astratto_edit_light_set c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            w.lightSet(c.x, c.y, c.z, static_cast<uint8_t>(c.value));
            return ASTRATTO_OK;
        }
        case ASTRATTO_EDIT_PRISTINE_SAVE: w.savePristine(); return ASTRATTO_OK;
        case ASTRATTO_EDIT_PRISTINE_RESTORE: return w.restorePristine() ? ASTRATTO_OK : ASTRATTO_ERR_STATE;
        case ASTRATTO_EDIT_MARK_DIRTY: {
            astratto_edit_box c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            w.markRegionDirty(c.x0, c.z0, c.x1, c.z1);
            return ASTRATTO_OK;
        }
        case ASTRATTO_EDIT_CLEAR: w.clear(); return ASTRATTO_OK;
        case ASTRATTO_CMD_DOOR_SEAL: {
            astratto_cmd_door_seal c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            w.seal(c.x, c.y, c.z, c.sealed != 0);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_DOORS_UNSEAL: w.unsealAll(); return ASTRATTO_OK;
        default: return -1;
    }
}

int32_t Engine::edit(astratto_handle world, const uint8_t* records, uint32_t bytes) {
    VoxelWorld* w = worlds_.get(world);
    if (w == nullptr) { fail("edit of a world handle that is not a live world"); return ASTRATTO_ERR_HANDLE; }
    uint32_t at = 0;
    while (at < bytes) {
        astratto_record head;
        if (bytes - at < sizeof(head)) { fail("edit stream ends inside a record header"); return ASTRATTO_ERR_STREAM; }
        std::memcpy(&head, records + at, sizeof(head));
        if (head.bytes < sizeof(head) || (head.bytes & 3u) != 0 || head.bytes > bytes - at) { fail("edit record with a bad size"); return ASTRATTO_ERR_STREAM; }
        const int r = applyEdit(*w, head.type, records + at, head.bytes);
        if (r == ASTRATTO_ERR_STREAM) { fail("edit record shorter than its kind"); return ASTRATTO_ERR_STREAM; }
        if (r == -1) { fail("not a world edit record"); return ASTRATTO_ERR_ARGUMENT; }
        if (r != ASTRATTO_OK) { fail("an edit could not be made (no pristine copy saved?)"); return r; }
        at += head.bytes;
    }
    return ASTRATTO_OK;
}

void Engine::activate(astratto_handle h) {
    if (h == activeHandle_) return;
    for (const auto& s : services_) s->worldChanged(*this);
    active_ = worlds_.get(h);
    activeHandle_ = active_ ? h : ASTRATTO_NO_HANDLE;
}

// ------------------------------------------------------------------ the tick
int32_t Engine::frame(const uint8_t* commands, uint32_t bytes, float dt) {
    const auto t0 = std::chrono::steady_clock::now();
    events_.clear();
    eventCount_ = 0;
    paths_.clear();
    sights_.clear();
    walks_.clear();
    int32_t status = ASTRATTO_OK;

    // 1. the batch, in the order it was written: changes are made now, questions are kept for after
    uint32_t at = 0;
    while (at < bytes) {
        astratto_record head;
        if (bytes - at < sizeof(head)) { fail("command stream ends inside a record header"); status = ASTRATTO_ERR_STREAM; break; }
        std::memcpy(&head, commands + at, sizeof(head));
        if (head.bytes < sizeof(head) || (head.bytes & 3u) != 0 || head.bytes > bytes - at) { fail("command record with a bad size"); status = ASTRATTO_ERR_STREAM; break; }
        const uint8_t* rec = commands + at;
        int r = -1;
        switch (head.type) {
            case ASTRATTO_CMD_NAV_TUNING: {
                astratto_cmd_nav_tuning c;
                r = readRecord(rec, head.bytes, c) ? ASTRATTO_OK : ASTRATTO_ERR_STREAM;
                if (r == ASTRATTO_OK) { tuning_.jumpCost = c.jump_cost; tuning_.ladderCost = c.ladder_cost; tuning_.mantleCost = c.mantle_cost; }
                break;
            }
            case ASTRATTO_CMD_WORLD_ACTIVATE: {
                astratto_cmd_handle c;
                r = readRecord(rec, head.bytes, c) ? ASTRATTO_OK : ASTRATTO_ERR_STREAM;
                if (r == ASTRATTO_OK) { if (c.handle != ASTRATTO_NO_HANDLE && !worlds_.valid(c.handle)) { error(head.type, ASTRATTO_ERR_HANDLE, c.handle); } else activate(c.handle); }
                break;
            }
            case ASTRATTO_CMD_RELEASE: {
                astratto_cmd_handle c;
                r = readRecord(rec, head.bytes, c) ? ASTRATTO_OK : ASTRATTO_ERR_STREAM;
                if (r == ASTRATTO_OK && release(c.handle) != ASTRATTO_OK) error(head.type, ASTRATTO_ERR_HANDLE, c.handle);
                break;
            }
            case ASTRATTO_CMD_PATH_REQUEST: {
                Path p;
                r = readRecord(rec, head.bytes, p.cmd) ? ASTRATTO_OK : ASTRATTO_ERR_STREAM;
                if (r == ASTRATTO_OK) paths_.push_back(std::move(p));
                break;
            }
            case ASTRATTO_CMD_SIGHT_REQUEST: {
                Sight s;
                r = readRecord(rec, head.bytes, s.cmd) ? ASTRATTO_OK : ASTRATTO_ERR_STREAM;
                if (r == ASTRATTO_OK) sights_.push_back(s);
                break;
            }
            case ASTRATTO_CMD_WALK_QUERY: {
                Walk q;
                r = readRecord(rec, head.bytes, q.cmd) ? ASTRATTO_OK : ASTRATTO_ERR_STREAM;
                if (r == ASTRATTO_OK) walks_.push_back(q);
                break;
            }
            default:
                if (active_ != nullptr) r = applyEdit(*active_, head.type, rec, head.bytes);
                else if (head.type < 16) { error(head.type, ASTRATTO_ERR_STATE, ASTRATTO_NO_HANDLE); r = ASTRATTO_OK; }
                if (r == -1) for (const auto& s : services_) { r = s->command(*this, head.type, rec, head.bytes); if (r != -1) break; }
                if (r > 0 || (r < -1 && r != ASTRATTO_ERR_STREAM)) { error(head.type, r, ASTRATTO_NO_HANDLE); r = ASTRATTO_OK; }
                if (r == -1) r = ASTRATTO_OK;   // a command from a newer version of the interface: skipped, by its own stated size
                break;
        }
        if (r == ASTRATTO_ERR_STREAM) { fail("command record shorter than its kind"); status = ASTRATTO_ERR_STREAM; break; }
        stats_.commands++;
        at += head.bytes;
    }

    // 2. the simulation
    const auto s0 = std::chrono::steady_clock::now();
    if (dt > 0) for (const auto& s : services_) s->step(*this, dt);
    stats_.sim_ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - s0).count();

    // 3. the questions, against the world as the batch left it. Nothing changes the world while they run, so they
    //    are shared out among the workers; each answer depends only on its own question.
    if (!paths_.empty() || !sights_.empty() || !walks_.empty()) {
        if (active_ == nullptr) {
            fail("a path, sight or walk request with no active world");
            if (status == ASTRATTO_OK) status = ASTRATTO_ERR_STATE;
        } else {
            const VoxelWorld& world = *active_;
            pool_.parallelFor(paths_.size(), [this, &world](size_t i, unsigned worker) {
                Path& p = paths_[i];
                PathQuery q;
                q.sx = p.cmd.sx; q.sy = p.cmd.sy; q.sz = p.cmd.sz; q.tx = p.cmd.tx; q.ty = p.cmd.ty; q.tz = p.cmd.tz;
                q.maxExpand = static_cast<int>(p.cmd.max_expand);
                q.doors = (p.cmd.flags & ASTRATTO_PATH_DOORS) != 0;
                q.goalY = (p.cmd.flags & ASTRATTO_PATH_GOAL_Y) != 0;
                q.mantle = (p.cmd.flags & ASTRATTO_PATH_MANTLE) != 0;
                p.found = finders_[worker]->path(world, tuning_, q, p.cells, p.expansions);
            });
            pool_.parallelFor(sights_.size(), [this, &world](size_t i, unsigned) {
                Sight& s = sights_[i];
                s.clear = sightClear(world, s.cmd.ax, s.cmd.ay, s.cmd.az, s.cmd.bx, s.cmd.by, s.cmd.bz, s.cmd.mode == ASTRATTO_SIGHT_EYE);
            });
            pool_.parallelFor(walks_.size(), [this, &world](size_t i, unsigned) {
                Walk& q = walks_[i];
                const auto& c = q.cmd;
                const bool doors = c.doors != 0;
                switch (c.kind) {
                    case ASTRATTO_WALK_CLEAR: q.value = Pathfinder::clear(world, c.ax, c.ay, c.az, c.bx, c.bz, doors) ? 1 : 0; break;
                    case ASTRATTO_WALK_CLEAR_BODY: q.value = Pathfinder::clearBody(world, c.ax, c.ay, c.az, c.bx, c.bz, doors, c.radius) ? 1 : 0; break;
                    case ASTRATTO_WALK_STAND: q.value = Pathfinder::stand(world, static_cast<int>(std::floor(c.ax)), static_cast<int>(std::floor(c.ay)), static_cast<int>(std::floor(c.az)), doors) ? 1 : 0; break;
                    case ASTRATTO_WALK_STEP_Y: q.value = Pathfinder::stepY(world, static_cast<int>(std::floor(c.ax)), static_cast<int>(std::floor(c.az)), static_cast<int>(std::floor(c.ay)), doors); break;
                    default: q.value = -1; break;
                }
            });
            // 4. the answers, in the order the questions were asked
            for (const Path& p : paths_) {
                astratto_evt_path_result e{};
                const size_t tail = p.cells.size() * sizeof(int32_t);
                e.request = p.cmd.request;
                e.status = p.found ? ASTRATTO_PATH_FOUND : ASTRATTO_PATH_NONE;
                e.expansions = p.expansions;
                e.cell_count = static_cast<uint32_t>(p.cells.size() / 3);
                emit(e, ASTRATTO_EVT_PATH_RESULT, p.cells.data(), tail);
                stats_.path_requests++;
                stats_.path_expansions += p.expansions;
                if (p.found) stats_.paths_found++;
            }
            for (const Sight& s : sights_) {
                astratto_evt_sight_result e{};
                e.request = s.cmd.request;
                e.clear = s.clear ? 1u : 0u;
                emit(e, ASTRATTO_EVT_SIGHT_RESULT);
                stats_.sight_requests++;
            }
            for (const Walk& q : walks_) {
                astratto_evt_walk_result e{};
                e.request = q.cmd.request;
                e.value = q.value;
                emit(e, ASTRATTO_EVT_WALK_RESULT);
            }
        }
    }

    stats_.frames++;
    const float ms = std::chrono::duration<float, std::milli>(std::chrono::steady_clock::now() - t0).count();
    stats_.last_frame_ms = ms;
    if (ms > stats_.worst_frame_ms) stats_.worst_frame_ms = ms;
    return status;
}

const astratto_stats& Engine::stats() {
    stats_.nav_version = active_ ? active_->navVersion() : 0;
    stats_.sealed_doors = active_ ? active_->sealedCount() : 0;
    stats_.world_x = active_ ? static_cast<uint32_t>(active_->sizeX()) : 0;
    stats_.world_y = active_ ? static_cast<uint32_t>(active_->sizeY()) : 0;
    stats_.world_z = active_ ? static_cast<uint32_t>(active_->sizeZ()) : 0;
    stats_.worlds = worlds_.live();
    uint64_t lit = 0;
    worlds_.each([&](astratto_handle, const VoxelWorld& w) { lit += w.litCells(); });
    stats_.light_cells = lit;
    stats_.live[ASTRATTO_KIND_WORLD] = worlds_.live();
    return stats_;
}

}  // namespace astratto
