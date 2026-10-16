// Astratto Engine - checks of the 2.0 simulation services, through the public C interface only: handles and their
// lifetimes, physics (movers, Sydney's ragdolls, soft bodies, cars), AI (policies, perception, routes followed),
// audio (the mixer rendered without a device), world views and edits, and a C++ gameplay module loaded from a library.
// Like selftest.cpp it knows nothing of any game: it builds a small walled yard of its own.
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "astratto/astratto.h"
#include "astratto/astratto_module.h"

void check(const char* what, bool ok);   // selftest.cpp

namespace {

const int SX = 64, SY = 24, SZ = 64, FLOOR = 4;
enum { AIR = 0, STONE = 1, GLASS = 2 };

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
    void bare(uint32_t type) { astratto_record r{type, sizeof(astratto_record)}; raw(&r, sizeof r); }
    void raw(const void* p, size_t n) { const size_t at = bytes.size(); bytes.resize(at + n); std::memcpy(bytes.data() + at, p, n); }
    void clear() { bytes.clear(); }
};

struct Events {
    std::vector<uint8_t> copy;
    uint32_t count = 0;
    int32_t status = 0;
    template <typename F> void each(uint32_t type, F f) const {
        uint32_t at = 0;
        while (at + sizeof(astratto_record) <= copy.size()) {
            astratto_record h;
            std::memcpy(&h, copy.data() + at, sizeof h);
            if (h.bytes < sizeof h || h.bytes > copy.size() - at) break;
            if (h.type == type) f(copy.data() + at, h.bytes);
            at += h.bytes;
        }
    }
    int counted(uint32_t type) const { int n = 0; each(type, [&](const uint8_t*, uint32_t) { n++; }); return n; }
};

Events frame(astratto_engine* e, Cmds& c, float dt) {
    astratto_frame_io io{};
    io.struct_size = sizeof io; io.dt = dt;
    io.commands = c.bytes.empty() ? nullptr : c.bytes.data(); io.command_bytes = static_cast<uint32_t>(c.bytes.size());
    Events ev;
    ev.status = astratto_frame(e, &io);
    ev.count = io.event_count;
    if (io.event_bytes) ev.copy.assign(io.events, io.events + io.event_bytes);
    c.clear();
    return ev;
}

template <typename T> const T* findSnap(const astratto_snapshot_io& io, uint32_t type, astratto_handle h) {
    const uint8_t* p = static_cast<const uint8_t*>(io.data);
    uint64_t at = 0;
    while (at + sizeof(astratto_record) <= io.bytes) {
        astratto_record r;
        std::memcpy(&r, p + at, sizeof r);
        if (r.bytes < sizeof r) break;
        if (r.type == type) { const T* t = reinterpret_cast<const T*>(p + at); if (t->handle == h) return t; }
        at += r.bytes;
    }
    return nullptr;
}

astratto_snapshot_io snap(astratto_engine* e, uint32_t kind) {
    astratto_snapshot_io io{};
    io.struct_size = sizeof io; io.kind = kind;
    if (astratto_snapshot(e, &io) != ASTRATTO_OK) { io.data = nullptr; io.bytes = 0; }
    return io;
}

astratto_handle reserve(astratto_engine* e, uint32_t kind) { astratto_handle h = 0; astratto_handles_reserve(e, kind, 1, &h); return h; }

}  // namespace

void services_checks(const char* moduleDir) {
    astratto_config cfg{};
    cfg.struct_size = sizeof cfg; cfg.api_version = ASTRATTO_API_VERSION; cfg.worker_threads = 2; cfg.flags = ASTRATTO_CONFIG_DETERMINISTIC;
    astratto_engine* e = astratto_create(&cfg);
    check("2.0: an engine for the services is made, without an audio device", e != nullptr);
    if (!e) return;

    // ------------------------------------------------------------ the world is the engine's
    astratto_block_def defs[3]{};
    defs[STONE].flags = ASTRATTO_BLOCK_SOLID | ASTRATTO_BLOCK_OPAQUE;
    defs[GLASS].flags = ASTRATTO_BLOCK_SOLID; defs[GLASS].material = ASTRATTO_MATERIAL_GLASS;
    astratto_block_table table{};
    table.struct_size = sizeof table; table.count = 3; table.defs = defs;
    astratto_world_block_table(e, &table);
    astratto_world_desc wd{};
    wd.struct_size = sizeof wd; wd.size_x = SX; wd.size_y = SY; wd.size_z = SZ; wd.below_floor_block = STONE; wd.flags = ASTRATTO_WORLD_LIGHT;
    astratto_handle world = 0;
    check("a world is made empty, by the engine, and lit", astratto_world_create(e, &wd, &world) == ASTRATTO_OK && ASTRATTO_HANDLE_KIND(world) == ASTRATTO_KIND_WORLD);
    {
        // the floor, then a wall across the yard at z = 40 with a gap at x 30..33
        Cmds b;
        astratto_edit_fill& f = b.add<astratto_edit_fill>(ASTRATTO_EDIT_FILL);
        f.x0 = 0; f.y0 = 0; f.z0 = 0; f.x1 = SX - 1; f.y1 = FLOOR - 1; f.z1 = SZ - 1; f.block = STONE | ASTRATTO_SET_RAW;
        astratto_edit_fill& w1 = b.add<astratto_edit_fill>(ASTRATTO_EDIT_FILL);
        w1.x0 = 0; w1.y0 = FLOOR; w1.z0 = 40; w1.x1 = 29; w1.y1 = FLOOR + 5; w1.z1 = 40; w1.block = STONE | ASTRATTO_SET_RAW;
        astratto_edit_fill& w2 = b.add<astratto_edit_fill>(ASTRATTO_EDIT_FILL);
        w2.x0 = 34; w2.y0 = FLOOR; w2.z0 = 40; w2.x1 = SX - 1; w2.y1 = FLOOR + 5; w2.z1 = 40; w2.block = STONE | ASTRATTO_SET_RAW;
        b.bare(ASTRATTO_EDIT_LIGHT_ALL);
        check("a batch of edits builds it", astratto_world_edit(e, world, b.bytes.data(), static_cast<uint32_t>(b.bytes.size())) == ASTRATTO_OK);
    }
    astratto_world_view v{};
    v.struct_size = sizeof v;
    const bool viewed = astratto_world_get_view(e, world, &v) == ASTRATTO_OK;
    const uint64_t edits0 = viewed ? v.edits : 0;
    check("its view is the engine's own memory: the edits are there, read straight back, and it is lit from the sky",
          viewed && v.blocks[(10 * SZ + 10) * SY + 0] == STONE && v.blocks[(10 * SZ + 40) * SY + FLOOR + 2] == STONE && v.blocks[(31 * SZ + 40) * SY + FLOOR] == AIR
          && (v.light[(10 * SZ + 10) * SY + FLOOR + 1] >> 4) == 15);
    astratto_world_set(e, world, 31, FLOOR, 40, STONE);
    check("a single set changes the same memory the view shows, and the edit count moves",
          v.blocks[(31 * SZ + 40) * SY + FLOOR] == STONE && astratto_world_get_view(e, world, &v) == ASTRATTO_OK && v.edits > edits0);
    astratto_world_set(e, world, 31, FLOOR, 40, AIR);

    Cmds c;
    c.add<astratto_cmd_handle>(ASTRATTO_CMD_WORLD_ACTIVATE).handle = world;

    // ------------------------------------------------------------ handles
    astratto_handle mover = reserve(e, ASTRATTO_KIND_MOVER);
    {
        astratto_handle bogus = mover ^ (static_cast<astratto_handle>(1) << 32);   // another generation of the same slot
        astratto_cmd_mover_input& in = c.add<astratto_cmd_mover_input>(ASTRATTO_CMD_MOVER_INPUT);
        in.handle = bogus;
        Events ev = frame(e, c, 0);
        int errs = 0;
        ev.each(ASTRATTO_EVT_ERROR, [&](const uint8_t* p, uint32_t) { astratto_evt_error er; std::memcpy(&er, p, sizeof er); if (er.status == ASTRATTO_ERR_HANDLE && er.handle == bogus) errs++; });
        check("a handle of the wrong generation is refused with an error event, and the frame goes on", ev.status == ASTRATTO_OK && errs == 1);
    }

    // ------------------------------------------------------------ physics: a mover walks, falls and is stopped by walls
    {
        astratto_cmd_mover_create& m = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        m.handle = mover; m.x = 10.5f; m.y = FLOOR + 3.0f; m.z = 10.5f; m.radius = 0.3f; m.height = 1.8f; m.step = 0.6f;
        frame(e, c, 0);
        for (int i = 0; i < 60; i++) frame(e, c, 1.0f / 60);
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        const astratto_snap_mover* s = findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, mover);
        check("a mover made in the air falls onto the floor and stands on it", s && s->grounded && std::fabs(s->y - FLOOR) < 0.05f);
        float y0 = s ? s->z : 0;
        for (int i = 0; i < 60; i++) { astratto_cmd_mover_input& in = c.add<astratto_cmd_mover_input>(ASTRATTO_CMD_MOVER_INPUT); in.handle = mover; in.vz = 4; frame(e, c, 1.0f / 60); }
        io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        s = findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, mover);
        check("asked to walk at 4 m/s for a second, it goes about four metres", s && s->z - y0 > 3.3f && s->z - y0 < 4.3f);
        for (int i = 0; i < 400; i++) { astratto_cmd_mover_input& in = c.add<astratto_cmd_mover_input>(ASTRATTO_CMD_MOVER_INPUT); in.handle = mover; in.vz = 6; frame(e, c, 1.0f / 60); }
        io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        s = findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, mover);
        check("walking on into the wall, it is stopped at the wall's face", s && s->z < 40.0f && s->z > 39.0f);
    }
    {
        // the same run cut into different frame lengths ends in the same place: fixed steps
        astratto_handle a = reserve(e, ASTRATTO_KIND_MOVER), b = reserve(e, ASTRATTO_KIND_MOVER);
        astratto_cmd_mover_create& ma = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        ma.handle = a; ma.x = 50.5f; ma.y = FLOOR + 6.0f; ma.z = 5.5f; ma.radius = 0.3f; ma.height = 1.8f; ma.step = 0.6f; ma.flags = ASTRATTO_MOVER_AGENT;
        frame(e, c, 0);
        for (int i = 0; i < 120; i++) frame(e, c, 1.0f / 120);
        astratto_cmd_mover_create& mb = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        mb.handle = b; mb.x = 54.5f; mb.y = FLOOR + 6.0f; mb.z = 5.5f; mb.radius = 0.3f; mb.height = 1.8f; mb.step = 0.6f; mb.flags = ASTRATTO_MOVER_AGENT;
        frame(e, c, 0);
        for (int i = 0; i < 24; i++) frame(e, c, 1.0f / 24);
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        const astratto_snap_mover* sa = findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, a);
        const astratto_snap_mover* sb = findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, b);
        check("a body's fall is the same whether its second is cut into 120 frames or 24 (fixed-rate steps)", sa && sb && std::fabs(sa->y - sb->y) < 1e-3f && std::fabs(sa->vy - sb->vy) < 1e-3f);
        c.add<astratto_cmd_handle>(ASTRATTO_CMD_RELEASE).handle = a;
        c.add<astratto_cmd_handle>(ASTRATTO_CMD_RELEASE).handle = b;
        frame(e, c, 0);
        io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        check("released by command, they are gone from the snapshot", findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, a) == nullptr);
        astratto_cmd_mover_input& in = c.add<astratto_cmd_mover_input>(ASTRATTO_CMD_MOVER_INPUT); in.handle = a;
        Events ev = frame(e, c, 0);
        check("and a released handle used again is refused, never reaching freed memory", ev.counted(ASTRATTO_EVT_ERROR) == 1);
    }

    // ------------------------------------------------------------ Sydney: a ragdoll stands, is struck, falls, gets up or dies
    astratto_handle rd = reserve(e, ASTRATTO_KIND_RAGDOLL);
    {
        astratto_cmd_ragdoll_create& r = c.add<astratto_cmd_ragdoll_create>(ASTRATTO_CMD_RAGDOLL_CREATE);
        r.handle = rd; r.scale = 1; r.profile = ASTRATTO_SYDNEY_HUMAN; r.flags = ASTRATTO_RAGDOLL_ACTIVE | ASTRATTO_RAGDOLL_STAND;
        r.x = 20.5f; r.y = FLOOR; r.z = 20.5f;
        frame(e, c, 0);
        for (int i = 0; i < 90; i++) frame(e, c, 1.0f / 60);
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        const astratto_snap_ragdoll* s = findSnap<astratto_snap_ragdoll>(io, ASTRATTO_SNAP_RAGDOLL, rd);
        const float headY = s ? s->p[ASTRATTO_RD_HEAD * 3 + 1] : 0;
        check("a ragdoll made standing is held up by Sydney's controllers: after a second and a half its head is still well over the floor",
              s && s->active && headY > FLOOR + 1.3f);
        int states = 0, fell = 0;
        astratto_cmd_ragdoll_hit& h = c.add<astratto_cmd_ragdoll_hit>(ASTRATTO_CMD_RAGDOLL_HIT);
        h.handle = rd; h.particle = ASTRATTO_RD_NECK; h.dx = 1; h.impulse = 400; h.force = 80;
        for (int i = 0; i < 240; i++) {
            Events ev = frame(e, c, 1.0f / 60);
            ev.each(ASTRATTO_EVT_RAGDOLL_STATE, [&](const uint8_t* p, uint32_t) {
                astratto_evt_ragdoll_state st; std::memcpy(&st, p, sizeof st);
                if (st.handle == rd) { states++; if (st.state == ASTRATTO_SYDNEY_FALL || st.state == ASTRATTO_SYDNEY_DOWN) fell++; }
            });
        }
        check("struck hard at the neck it is knocked down, and its state machine says so in events", states > 0 && fell > 0);
        c.add<astratto_cmd_ragdoll_control>(ASTRATTO_CMD_RAGDOLL_CONTROL) = astratto_cmd_ragdoll_control{{ASTRATTO_CMD_RAGDOLL_CONTROL, sizeof(astratto_cmd_ragdoll_control)}, rd, ASTRATTO_CONTROL_DIE, 0};
        bool dead = false, settled = false;
        for (int i = 0; i < 900 && !settled; i++) {
            Events ev = frame(e, c, 1.0f / 60);
            ev.each(ASTRATTO_EVT_RAGDOLL_STATE, [&](const uint8_t* p, uint32_t) { astratto_evt_ragdoll_state st; std::memcpy(&st, p, sizeof st); if (st.handle == rd && st.state == ASTRATTO_SYDNEY_DEAD) dead = true; });
            ev.each(ASTRATTO_EVT_SETTLED, [&](const uint8_t* p, uint32_t) { astratto_evt_body b; std::memcpy(&b, p, sizeof b); if (b.handle == rd) settled = true; });
        }
        io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        s = findSnap<astratto_snap_ragdoll>(io, ASTRATTO_SNAP_RAGDOLL, rd);
        bool onFloor = s != nullptr;
        for (int i = 0; s && i < ASTRATTO_RD_PARTICLES; i++) if (s->p[i * 3 + 1] < FLOOR - 0.05f || s->p[i * 3 + 1] > FLOOR + 0.9f) onFloor = false;
        check("told to die it goes limp, lies on the floor (no particle through it) and comes to rest", dead && settled && onFloor);
    }
    {
        // a soft body of two particles and a stick: dropped, it lands and keeps its length
        astratto_handle sb = reserve(e, ASTRATTO_KIND_SOFTBODY);
        std::vector<uint8_t> rec(sizeof(astratto_cmd_softbody_create) + 2 * sizeof(astratto_softbody_particle) + sizeof(astratto_softbody_stick));
        astratto_cmd_softbody_create sc{};
        sc.head.type = ASTRATTO_CMD_SOFTBODY_CREATE; sc.head.bytes = static_cast<uint32_t>(rec.size());
        sc.handle = sb; sc.particles = 2; sc.sticks = 1;
        astratto_softbody_particle ps[2] = {{40.5f, FLOOR + 3.0f, 20.5f, 0.1f, 5}, {41.5f, FLOOR + 3.0f, 20.5f, 0.1f, 5}};
        astratto_softbody_stick st{0, 1};
        std::memcpy(rec.data(), &sc, sizeof sc);
        std::memcpy(rec.data() + sizeof sc, ps, sizeof ps);
        std::memcpy(rec.data() + sizeof sc + sizeof ps, &st, sizeof st);
        c.raw(rec.data(), rec.size());
        frame(e, c, 0);
        for (int i = 0; i < 180; i++) frame(e, c, 1.0f / 60);
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        const astratto_snap_softbody* s = findSnap<astratto_snap_softbody>(io, ASTRATTO_SNAP_SOFTBODY, sb);
        bool ok = s && s->particles == 2;
        if (ok) {
            float p[6];
            std::memcpy(p, reinterpret_cast<const uint8_t*>(s) + sizeof *s, sizeof p);
            const float len = std::sqrt((p[3] - p[0]) * (p[3] - p[0]) + (p[4] - p[1]) * (p[4] - p[1]) + (p[5] - p[2]) * (p[5] - p[2]));
            ok = std::fabs(len - 1.0f) < 0.05f && p[1] < FLOOR + 0.3f && p[1] > FLOOR - 0.05f;
        }
        check("a soft body dropped lands on the floor with its stick still its length", ok);
    }

    // ------------------------------------------------------------ a car
    astratto_handle car = reserve(e, ASTRATTO_KIND_VEHICLE);
    {
        astratto_cmd_vehicle_create& vc = c.add<astratto_cmd_vehicle_create>(ASTRATTO_CMD_VEHICLE_CREATE);
        astratto_vehicle_desc& d = vc.desc;
        vc.handle = car;
        d.mass = 1400; d.width = 1.8f; d.length = 4.4f; d.height = 1.45f; d.com_y = 0.5f;
        d.engine_accel = 4; d.max_velocity = 33; d.max_reverse = 8; d.first_gear_boost = 1.5f; d.second_gear_boost = 1.2f;
        d.traction = 1; d.traction_loss = 0.82f; d.traction_bias = 0.5f; d.steering_lock = 34; d.brake_decel = 9; d.brake_bias = 0.62f;
        d.susp_force = 1.4f; d.susp_damping = 0.28f; d.susp_upper = 0.28f; d.susp_lower = -0.16f; d.susp_bias = 0.5f; d.anti_dive = 0.3f;
        d.wheel_radius = 0.34f; d.handbrake_tyre = 0.55f;
        const float wh[12] = {-0.8f, 0.34f, 1.35f, 0.8f, 0.34f, 1.35f, -0.8f, 0.34f, -1.35f, 0.8f, 0.34f, -1.35f};
        std::memcpy(d.wheels, wh, sizeof wh);
        d.gears = 4; d.drive = ASTRATTO_DRIVE_REAR;
        vc.x = 8.5f; vc.y = FLOOR + 1.0f; vc.z = 6.5f; vc.yaw = 0;
        frame(e, c, 0);
        for (int i = 0; i < 120; i++) frame(e, c, 1.0f / 60);
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        const astratto_snap_vehicle* s = findSnap<astratto_snap_vehicle>(io, ASTRATTO_SNAP_VEHICLE, car);
        const float z0 = s ? s->matrix[14] : 0;
        check("a car dropped onto the floor comes to rest on its four wheels", s && s->grounded_wheels == 4 && s->speed < 0.3f);
        int gearEvents = 0;
        for (int i = 0; i < 180; i++) {
            astratto_cmd_vehicle_input& in = c.add<astratto_cmd_vehicle_input>(ASTRATTO_CMD_VEHICLE_INPUT);
            in.handle = car; in.throttle = 1; in.engine_on = 1;
            Events ev = frame(e, c, 1.0f / 60);
            gearEvents += ev.counted(ASTRATTO_EVT_GEAR);
        }
        io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        s = findSnap<astratto_snap_vehicle>(io, ASTRATTO_SNAP_VEHICLE, car);
        check("full throttle for three seconds drives it forward (+z), changing up through the gears", s && s->matrix[14] - z0 > 8 && s->forward_speed > 4 && s->gear >= 2 && gearEvents >= 1);
        if (s) std::printf("       car: %.1f m in 3 s, %.1f m/s, gear %u\n", s->matrix[14] - z0, s->forward_speed, s->gear);
        int impacts = 0;
        for (int i = 0; i < 600 && impacts == 0; i++) {
            astratto_cmd_vehicle_input& in = c.add<astratto_cmd_vehicle_input>(ASTRATTO_CMD_VEHICLE_INPUT);
            in.handle = car; in.throttle = 1; in.engine_on = 1;
            Events ev = frame(e, c, 1.0f / 60);
            impacts += ev.counted(ASTRATTO_EVT_BLOCK_HIT) + ev.counted(ASTRATTO_EVT_VEHICLE_IMPACT);
        }
        io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        s = findSnap<astratto_snap_vehicle>(io, ASTRATTO_SNAP_VEHICLE, car);
        check("driven on into the wall it strikes it (an impact event) and does not pass through", impacts > 0 && s && s->matrix[14] < 40.0f);
    }

    // ------------------------------------------------------------ AI: a policy, perception through the engine's sight lines, routes
    astratto_handle policy = reserve(e, ASTRATTO_KIND_POLICY), agent = reserve(e, ASTRATTO_KIND_AGENT), body = reserve(e, ASTRATTO_KIND_MOVER), quarry = reserve(e, ASTRATTO_KIND_MOVER);
    enum { IDLE = 10, CHASE = 11, GOTO = 12 };
    {
        // idle unless the target is seen (then approach to 2 m) or fact 0 is set (then go to the goal)
        std::vector<uint8_t> rec;
        astratto_cmd_policy_create pc{};
        astratto_action acts[3]{};
        astratto_consideration cons[2]{};
        acts[0] = astratto_action{IDLE, ASTRATTO_ACT_IDLE, 0.2f, 0, 0, 0, 0};
        acts[1] = astratto_action{CHASE, ASTRATTO_ACT_APPROACH, 1.0f, 2.0f, 4.0f, 0, 1};
        acts[2] = astratto_action{GOTO, ASTRATTO_ACT_MOVE_TO_GOAL, 0.9f, 0, 3.0f, 1, 1};
        cons[0] = astratto_consideration{ASTRATTO_IN_TARGET_SEEN, ASTRATTO_CURVE_STEP, 0, 1, 1, 0};
        cons[1] = astratto_consideration{ASTRATTO_IN_FACT0, ASTRATTO_CURVE_STEP, 0, 1, 1, 0};
        pc.head.type = ASTRATTO_CMD_POLICY_CREATE; pc.head.bytes = sizeof pc + sizeof acts + sizeof cons;
        pc.handle = policy; pc.actions = 3; pc.considerations = 2; pc.inertia = 0.1f; pc.think = 0.1f;
        c.raw(&pc, sizeof pc); c.raw(acts, sizeof acts); c.raw(cons, sizeof cons);
        astratto_cmd_mover_create& mb = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        mb.handle = body; mb.x = 31.5f; mb.y = FLOOR; mb.z = 50.5f; mb.radius = 0.3f; mb.height = 1.8f; mb.step = 0.6f; mb.flags = ASTRATTO_MOVER_AGENT;
        astratto_cmd_agent_create& ac = c.add<astratto_cmd_agent_create>(ASTRATTO_CMD_AGENT_CREATE);
        ac.handle = agent; ac.policy = policy; ac.body = body; ac.eye = 1.6f; ac.view_range = 60; ac.view_cos = -1; ac.hear_range = 30; ac.team = 1; ac.seed = 7;
        // the quarry stands behind the wall, out of sight
        astratto_cmd_mover_create& mq = c.add<astratto_cmd_mover_create>(ASTRATTO_CMD_MOVER_CREATE);
        mq.handle = quarry; mq.x = 10.5f; mq.y = FLOOR; mq.z = 30.5f; mq.radius = 0.3f; mq.height = 1.8f; mq.step = 0.6f;
        astratto_cmd_agent_target& t = c.add<astratto_cmd_agent_target>(ASTRATTO_CMD_AGENT_TARGET);
        t.handle = agent; t.target = quarry;
        frame(e, c, 0);
        int chose = -1, sees = 0;
        for (int i = 0; i < 30; i++) {
            Events ev = frame(e, c, 1.0f / 30);
            ev.each(ASTRATTO_EVT_AGENT_ACTION, [&](const uint8_t* p, uint32_t) { astratto_evt_agent a; std::memcpy(&a, p, sizeof a); if (a.handle == agent) chose = a.value; });
            sees += ev.counted(ASTRATTO_EVT_AGENT_SEES);
        }
        check("an agent whose target is behind a wall does not see it, and its policy chooses to idle", sees == 0 && chose == IDLE);
        // the game sets a fact: go to the goal, through the gap, to the quarry's side of the wall
        astratto_cmd_agent_goal& g = c.add<astratto_cmd_agent_goal>(ASTRATTO_CMD_AGENT_GOAL);
        g.handle = agent; g.x = 31.5f; g.y = FLOOR; g.z = 30.5f;
        astratto_cmd_agent_fact& f = c.add<astratto_cmd_agent_fact>(ASTRATTO_CMD_AGENT_FACT);
        f.handle = agent; f.index = 0; f.value = 1;
        bool wentToGoal = false, sawIt = false, chased = false, arrived = false;
        for (int i = 0; i < 900 && !arrived; i++) {
            Events ev = frame(e, c, 1.0f / 30);
            ev.each(ASTRATTO_EVT_AGENT_ACTION, [&](const uint8_t* p, uint32_t) { astratto_evt_agent a; std::memcpy(&a, p, sizeof a); if (a.handle == agent) { if (a.value == GOTO) wentToGoal = true; if (a.value == CHASE) chased = true; } });
            ev.each(ASTRATTO_EVT_AGENT_SEES, [&](const uint8_t* p, uint32_t) { astratto_evt_agent a; std::memcpy(&a, p, sizeof a); if (a.handle == agent && a.other == quarry) sawIt = true; });
            if (chased) ev.each(ASTRATTO_EVT_AGENT_ARRIVED, [&](const uint8_t* p, uint32_t) { astratto_evt_agent a; std::memcpy(&a, p, sizeof a); if (a.handle == agent) arrived = true; });
        }
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_BODIES);
        const astratto_snap_mover* sb = findSnap<astratto_snap_mover>(io, ASTRATTO_SNAP_MOVER, body);
        const float d = sb ? std::sqrt((sb->x - 10.5f) * (sb->x - 10.5f) + (sb->z - 30.5f) * (sb->z - 30.5f)) : 1e9f;
        check("told by a fact to go, it routes through the gap in the wall, sees the quarry with the engine's sight lines, chooses to approach and arrives by it, walking its mover",
              wentToGoal && sawIt && chased && arrived && d < 3.0f && sb->z < 40.0f);
        astratto_snapshot_io ai = snap(e, ASTRATTO_SNAPSHOT_AGENTS);
        const astratto_snap_agent* sa = findSnap<astratto_snap_agent>(ai, ASTRATTO_SNAP_AGENT, agent);
        check("its snapshot says what it does and what it sees", sa && sa->action == CHASE && sa->sees == 1 && sa->target == quarry);
        // heard: a noise near it is reported
        astratto_cmd_noise& n = c.add<astratto_cmd_noise>(ASTRATTO_CMD_NOISE);
        n.x = sb ? sb->x + 3 : 0; n.y = FLOOR; n.z = sb ? sb->z : 0; n.radius = 20; n.loudness = 1; n.team = 0;
        Events ev = frame(e, c, 1.0f / 30);
        check("a noise in its hearing comes back as heard", ev.counted(ASTRATTO_EVT_AGENT_HEARD) >= 1);
    }

    // ------------------------------------------------------------ audio: the mixer, rendered on request
    {
        const int rate = 44100, n = rate / 2;
        std::vector<float> tone(n);
        for (int i = 0; i < n; i++) tone[i] = 0.5f * std::sin(2 * 3.14159265f * 440.0f * i / rate);
        astratto_sound_desc sd{};
        sd.struct_size = sizeof sd; sd.channels = 1; sd.rate = rate; sd.frames = n; sd.samples = tone.data();
        astratto_handle sound = 0;
        check("a sound is handed over (copied) and named by a handle", astratto_sound_create(e, &sd, &sound) == ASTRATTO_OK && ASTRATTO_HANDLE_KIND(sound) == ASTRATTO_KIND_SOUND);
        std::vector<float> out(2048 * 2);
        astratto_audio_render(e, out.data(), 2048);
        float silent = 0;
        for (float s : out) silent = std::max(silent, std::fabs(s));
        astratto_handle left = reserve(e, ASTRATTO_KIND_VOICE), near = reserve(e, ASTRATTO_KIND_VOICE);
        astratto_cmd_listener& l = c.add<astratto_cmd_listener>(ASTRATTO_CMD_LISTENER);
        l.x = 20; l.y = FLOOR + 1.6f; l.z = 10; l.yaw = 0;
        astratto_cmd_voice_play& p = c.add<astratto_cmd_voice_play>(ASTRATTO_CMD_VOICE_PLAY);
        p.voice = left; p.sound = sound; p.bus = ASTRATTO_BUS_SFX; p.flags = ASTRATTO_VOICE_POSITIONAL; p.gain = 1; p.pitch = 1;
        p.x = 12; p.y = FLOOR + 1.6f; p.z = 10; p.range = 40;     // to the listener's left (it faces -z, so -x is its left)
        frame(e, c, 1.0f / 60);
        astratto_audio_render(e, out.data(), 2048);
        double lsum = 0, rsum = 0;
        for (int i = 0; i < 2048; i++) { lsum += std::fabs(out[i * 2]); rsum += std::fabs(out[i * 2 + 1]); }
        check("silence before anything plays; a positional voice to the listener's left is heard, louder in the left channel", silent < 1e-6f && lsum > 1.0 && lsum > rsum * 1.3);
        // the same sound far behind a wall is quieter than near and in the open
        astratto_cmd_voice_stop& st = c.add<astratto_cmd_voice_stop>(ASTRATTO_CMD_VOICE_STOP); st.voice = left;
        frame(e, c, 1.0f / 60);
        for (int i = 0; i < 4; i++) astratto_audio_render(e, out.data(), 2048);
        astratto_cmd_voice_play& q = c.add<astratto_cmd_voice_play>(ASTRATTO_CMD_VOICE_PLAY);
        q.voice = near; q.sound = sound; q.bus = ASTRATTO_BUS_SFX; q.flags = ASTRATTO_VOICE_POSITIONAL | ASTRATTO_VOICE_OCCLUDE; q.gain = 1; q.pitch = 1;
        q.x = 20; q.y = FLOOR + 1.6f; q.z = 50; q.range = 60;   // the far side of the wall at z = 40
        frame(e, c, 1.0f / 60);
        astratto_audio_render(e, out.data(), 2048);
        double far = 0;
        for (float s : out) far += std::fabs(s);
        check("a voice beyond a wall and further off is quieter than the near one in the open", far > 0.01 && far < (lsum + rsum) * 0.5);
        bool ended = false;
        for (int i = 0; i < 60 && !ended; i++) {
            astratto_audio_render(e, out.data(), 2048);
            Events ev = frame(e, c, 1.0f / 60);
            ev.each(ASTRATTO_EVT_VOICE_ENDED, [&](const uint8_t* pp, uint32_t) { astratto_evt_body b; std::memcpy(&b, pp, sizeof b); if (b.handle == near) ended = true; });
        }
        check("a voice that has played its sound to the end says so in an event", ended);
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_AUDIO);
        const astratto_audio_stats* as = static_cast<const astratto_audio_stats*>(io.data);
        check("the audio snapshot says there is no device and that frames were mixed", as && as->device == 0 && as->frames_mixed >= 2048 * 6);
    }

    // ------------------------------------------------------------ a C++ gameplay module
    if (moduleDir) {
        std::string path = std::string(moduleDir) +
#if defined(_WIN32)
            "/astratto_example_module.dll";
#elif defined(__APPLE__)
            "/libastratto_example_module.dylib";
#else
            "/libastratto_example_module.so";
#endif
        astratto_handle mod = 0;
        const int32_t st = astratto_module_load(e, path.c_str(), "radio 31.5 4 45.5 0.5 1", &mod);
        check("a C++ gameplay module is loaded from its library and named by a handle", st == ASTRATTO_OK && ASTRATTO_HANDLE_KIND(mod) == ASTRATTO_KIND_MODULE);
        int logs = 0, heard = 0;
        for (int i = 0; i < 60; i++) {
            Events ev = frame(e, c, 1.0f / 30);
            logs += ev.counted(ASTRATTO_EVT_MODULE_LOG);
            heard += ev.counted(ASTRATTO_EVT_AGENT_HEARD);
        }
        check("run inside each frame, its radio's noises (its own commands) are heard by the agent, and its log lines come back as events", logs >= 1 && heard >= 2);
        check("released, it is shut down and unloaded", astratto_release(e, mod) == ASTRATTO_OK && astratto_release(e, mod) == ASTRATTO_ERR_HANDLE);
        astratto_handle bad = 0;
        check("a library that is not there is refused, not a crash", astratto_module_load(e, "no_such_module_library", "", &bad) != ASTRATTO_OK && bad == 0);
    }

    // ------------------------------------------------------------ releasing the world takes what lives in it
    {
        astratto_snapshot_io io = snap(e, ASTRATTO_SNAPSHOT_STATS);
        const astratto_stats* s = static_cast<const astratto_stats*>(io.data);
        const bool before = s && s->live[ASTRATTO_KIND_WORLD] == 1 && s->sim_steps > 0 && s->sim_time > 10;
        check("the stats count the world, the fixed steps run and the time simulated (the frame's dt is used)", before);
        check("the world is released by its handle; a view of it is refused afterwards",
              astratto_release(e, world) == ASTRATTO_OK && astratto_world_get_view(e, world, &v) == ASTRATTO_ERR_HANDLE);
    }
    astratto_destroy(e);
}
