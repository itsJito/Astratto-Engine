// Astratto Engine - one engine: its worlds, its services, and the tick that runs a batch of commands through them.
#pragma once

#include <cstdint>
#include <cmath>
#include <cstring>
#include <memory>
#include <string>
#include <vector>

#include "astratto/astratto.h"
#include "core/handles.h"
#include "core/job_pool.h"
#include "nav/pathfinder.h"
#include "world/voxel_world.h"

namespace astratto {

class Engine;

/** Reads a record of a known kind out of a stream that need not be aligned for it. */
template <typename T> bool readRecord(const uint8_t* at, uint32_t bytes, T& out) {
    if (bytes < sizeof(T)) return false;
    std::memcpy(&out, at, sizeof(T));
    return true;
}

/** Appends a record to a byte stream (snapshots, events): its header filled in, padded to four bytes. */
template <typename T> void appendRecord(std::vector<uint8_t>& out, T rec, uint32_t type, const void* tail = nullptr, size_t tailBytes = 0) {
    rec.head.type = type;
    rec.head.bytes = static_cast<uint32_t>((sizeof(T) + tailBytes + 3) & ~size_t(3));
    const size_t at = out.size();
    out.resize(at + rec.head.bytes, 0);
    std::memcpy(out.data() + at, &rec, sizeof(T));
    if (tailBytes) std::memcpy(out.data() + at + sizeof(T), tail, tailBytes);
}

/**
 * A part of the engine that takes commands and runs in the tick (physics, AI, audio, modules). Commands are offered
 * in the order they were written; a service answers whether the type was its own. Its step runs after the whole batch
 * has been read, in a fixed order of services.
 */
class Service {
public:
    virtual ~Service() = default;
    /** -1: not mine. 0: done. Anything else: a status, reported as an error event (the frame goes on). */
    virtual int command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) = 0;
    virtual void step(Engine& e, float dt) = 0;
    /** Whether handles of a kind are this service's, and a fresh one of them. */
    virtual bool owns(uint32_t kind) const { (void)kind; return false; }
    virtual astratto_handle reserve(uint32_t kind) { (void)kind; return ASTRATTO_NO_HANDLE; }
    /** A handle of one of this service's kinds is being released. True if it was this service's. */
    virtual bool release(Engine& e, astratto_handle h) { (void)e; (void)h; return false; }
    /** The world everything is in is about to change or go (bodies on it are dropped from it, routes forgotten). */
    virtual void worldChanged(Engine& e) { (void)e; }
    /** Writes a snapshot of a kind this service keeps. False when the kind is not its own. */
    virtual bool snapshot(Engine& e, uint32_t kind, std::vector<uint8_t>& out) { (void)e; (void)kind; (void)out; return false; }
    /** How many things of a kind it holds (statistics). */
    virtual uint32_t live(uint32_t kind) const { (void)kind; return 0; }
    /** Runs after the questions of a frame are answered, with the frame's events (modules see them here). */
    virtual void frameEnd(Engine& e) { (void)e; }
};

class Engine {
public:
    explicit Engine(const astratto_config& cfg);
    ~Engine();

    const astratto_config& config() const { return config_; }
    JobPool& pool() { return pool_; }
    BlockTable& table() { return table_; }
    const NavTuning& tuning() const { return tuning_; }

    // ---- handles
    int32_t reserve(uint32_t kind, uint32_t count, astratto_handle* out);
    int32_t release(astratto_handle h);
    /** The table of a kind that has one in the engine itself (worlds); services keep their own. */
    HandleTable<VoxelWorld>& worlds() { return worlds_; }
    void addService(std::unique_ptr<Service> s) { services_.push_back(std::move(s)); }
    template <typename T> T* service() const { for (const auto& s : services_) if (auto* t = dynamic_cast<T*>(s.get())) return t; return nullptr; }

    // ---- worlds
    int32_t createWorld(const astratto_world_desc& d, astratto_handle* out);
    int32_t edit(astratto_handle world, const uint8_t* records, uint32_t bytes);
    int32_t setCell(astratto_handle world, int x, int y, int z, uint32_t block);
    VoxelWorld* active() const { return active_; }
    astratto_handle activeHandle() const { return activeHandle_; }

    /** Runs one batch. The events it produced are in events() until the next call. */
    int32_t frame(const uint8_t* commands, uint32_t bytes, float dt);
    const std::vector<uint8_t>& events() const { return events_; }
    uint32_t eventCount() const { return eventCount_; }

    /**
     * Runs one command record as if the caller had written it at this point of the frame (modules write theirs through
     * this). Returns ASTRATTO_ERR_STREAM for a record shorter than its kind; anything else is reported as an event.
     */
    int32_t command(const uint8_t* rec, uint32_t bytes);

    /** Appends an event: a record header and body, then an optional tail of bytes. Only on the frame's own thread. */
    template <typename T> void emit(T head, uint32_t type, const void* tail = nullptr, size_t tailBytes = 0) {
        // outside a frame (a module loading or shutting down) the event waits for the next frame's events
        if (!inFrame_) { appendRecord(pending_, head, type, tail, tailBytes); pendingCount_++; return; }
        appendRecord(events_, head, type, tail, tailBytes);
        eventCount_++;
    }
    void error(uint32_t command, int32_t status, astratto_handle h);

    const astratto_stats& stats();
    astratto_stats& counters() { return stats_; }
    /** A snapshot of a kind; the bytes stay the engine's until the next snapshot of the same kind. */
    int32_t snapshot(uint32_t kind, const void** data, uint64_t* bytes);

    void fail(const std::string& what) { error_ = what; }
    const char* error() const { return error_.c_str(); }

    /** Seconds simulated since the engine was made. */
    double time() const { return stats_.sim_time; }

private:
    struct Path { astratto_cmd_path_request cmd; std::vector<int32_t> cells; uint32_t expansions = 0; bool found = false; };
    struct Sight { astratto_cmd_sight_request cmd; bool clear = false; };
    struct Walk { astratto_cmd_walk_query cmd; int32_t value = 0; };
    struct Ray { astratto_cmd_ray_query cmd; astratto_evt_ray_result out{}; };

    /** -1: not an edit. Otherwise a status. */
    int applyEdit(VoxelWorld& w, uint32_t type, const uint8_t* rec, uint32_t bytes);
    void activate(astratto_handle h);
    void answerQuestions(int32_t& status);

    astratto_config config_;
    JobPool pool_;
    BlockTable table_;
    HandleTable<VoxelWorld> worlds_{ASTRATTO_KIND_WORLD};
    std::vector<std::unique_ptr<Service>> services_;
    VoxelWorld* active_ = nullptr;
    astratto_handle activeHandle_ = ASTRATTO_NO_HANDLE;
    NavTuning tuning_;
    std::vector<std::unique_ptr<Pathfinder>> finders_;   // one per worker
    std::vector<Path> paths_;
    std::vector<Sight> sights_;
    std::vector<Walk> walks_;
    std::vector<Ray> rays_;
    /** Every question of the frame in the order it was written: its kind and its index in the list of that kind. */
    enum : uint32_t { Q_PATH = 0, Q_SIGHT = 1, Q_WALK = 2, Q_RAY = 3 };
    std::vector<std::pair<uint32_t, uint32_t>> asked_;
    std::vector<uint8_t> events_, pending_;
    uint32_t eventCount_ = 0, pendingCount_ = 0;
    bool inFrame_ = false;
    astratto_stats stats_{};
    std::vector<std::vector<uint8_t>> snapshots_;
    std::string error_;
};

}  // namespace astratto
