#include "physics/physics_service.h"

#include <algorithm>
#include <cmath>

namespace astratto {

PhysicsService::PhysicsService() {
    profiles_[ASTRATTO_SYDNEY_HUMAN] = SydneyConfig::human();
    profiles_[ASTRATTO_SYDNEY_INFECTED] = SydneyConfig::infected();
    profiles_[ASTRATTO_SYDNEY_CRAWLER] = SydneyConfig::crawler();
    profiles_[ASTRATTO_SYDNEY_GIANT] = SydneyConfig::giant();
    for (int i = 4; i < 16; i++) profiles_[i] = SydneyConfig::human();
    // the surfaces a game starts with (the game's own table replaces them): asphalt, concrete, dirt, grass, mud, sand
    surfaces_[0] = Surface{0.95f, 0.15f, 0};
    surfaces_[1] = Surface{1.00f, 0.13f, 0};
    surfaces_[2] = Surface{0.72f, 0.38f, 0};
    surfaces_[3] = Surface{0.62f, 0.55f, 0};
    surfaces_[4] = Surface{0.45f, 1.10f, 0.35f};
    surfaces_[5] = Surface{0.52f, 0.90f, 1.10f};
}

bool PhysicsService::owns(uint32_t kind) const {
    return kind == ASTRATTO_KIND_MOVER || kind == ASTRATTO_KIND_RAGDOLL || kind == ASTRATTO_KIND_SOFTBODY || kind == ASTRATTO_KIND_VEHICLE;
}

astratto_handle PhysicsService::reserve(uint32_t kind) {
    switch (kind) {
        case ASTRATTO_KIND_MOVER: return movers_.reserve();
        case ASTRATTO_KIND_RAGDOLL: return ragdolls_.reserve();
        case ASTRATTO_KIND_SOFTBODY: return softs_.reserve();
        case ASTRATTO_KIND_VEHICLE: return vehicles_.reserve();
        default: return ASTRATTO_NO_HANDLE;
    }
}

bool PhysicsService::release(Engine&, astratto_handle h) {
    switch (handleKind(h)) {
        case ASTRATTO_KIND_MOVER: return movers_.release(h);
        case ASTRATTO_KIND_RAGDOLL: return ragdolls_.release(h);
        case ASTRATTO_KIND_SOFTBODY: return softs_.release(h);
        case ASTRATTO_KIND_VEHICLE: return vehicles_.release(h);
        default: return false;
    }
}

uint32_t PhysicsService::live(uint32_t kind) const {
    switch (kind) {
        case ASTRATTO_KIND_MOVER: return movers_.live();
        case ASTRATTO_KIND_RAGDOLL: return ragdolls_.live();
        case ASTRATTO_KIND_SOFTBODY: return softs_.live();
        case ASTRATTO_KIND_VEHICLE: return vehicles_.live();
        default: return 0;
    }
}

void PhysicsService::worldChanged(Engine&) {
    // bodies stay where they are; cars forget which cells their samples were in (the new world's cells are not those)
    vehicles_.each([](astratto_handle, const VehicleRec& v) { const_cast<VehicleRec&>(v).car.prevValid = false; });
}

bool PhysicsService::bodyPosition(astratto_handle h, float* pos, float* height) const {
    switch (handleKind(h)) {
        case ASTRATTO_KIND_MOVER: {
            const Mover* m = movers_.get(h);
            if (!m) return false;
            pos[0] = m->x; pos[1] = m->y + m->h() * 0.5f; pos[2] = m->z; if (height) *height = m->h();
            return true;
        }
        case ASTRATTO_KIND_RAGDOLL: {
            const RagdollBody* r = ragdolls_.get(h);
            if (!r || !r->body) return false;
            pos[0] = r->body->x[rd::NECK]; pos[1] = (r->body->y[rd::NECK] + r->body->y[rd::PELVIS]) * 0.5f; pos[2] = r->body->z[rd::NECK]; if (height) *height = 1.8f;
            return true;
        }
        case ASTRATTO_KIND_VEHICLE: {
            const VehicleRec* v = vehicles_.get(h);
            if (!v) return false;
            pos[0] = v->car.px; pos[1] = v->car.py; pos[2] = v->car.pz; if (height) *height = v->car.d.height;
            return true;
        }
        default: return false;
    }
}

int PhysicsService::command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) {
    switch (type) {
        case ASTRATTO_CMD_SURFACE: {
            astratto_cmd_surface c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (c.index >= 64) return ASTRATTO_ERR_ARGUMENT;
            surfaces_[c.index] = Surface{c.adhesion, c.rolling, c.drag};
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_GRAVITY: {
            astratto_cmd_gravity c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            gravity_ = c.gravity;
            ragdollGravity_ = c.ragdoll_gravity > 0 ? c.ragdoll_gravity : c.gravity;
            ragdolls_.each([&](astratto_handle, const RagdollBody& r) { r.body->gravity = ragdollGravity_; });
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_MOVER_CREATE: {
            astratto_cmd_mover_create c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (!movers_.valid(c.handle)) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            std::unique_ptr<Mover> m(new Mover());
            m->x = c.x; m->y = c.y; m->z = c.z;
            m->radius = c.radius > 0 ? c.radius : 0.3f; m->height = c.height > 0 ? c.height : 1.8f;
            m->crouchHeight = c.crouch_height > 0 ? c.crouch_height : m->height * 0.66f;
            m->step = c.step; m->flags = c.flags;
            movers_.put(c.handle, std::move(m));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_MOVER_INPUT: {
            astratto_cmd_mover_input c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Mover* m = movers_.get(c.handle);
            if (!m) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            m->wishX = c.vx; m->wishZ = c.vz; m->wishY = c.vy;
            if (c.jump > 0) m->jump = c.jump;
            if (c.accel > 0) m->accel = c.accel;
            m->crouch = (c.flags & ASTRATTO_MOVER_CROUCH) != 0;
            m->climb = c.vy != 0;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_MOVER_PLACE: {
            astratto_cmd_body_place c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Mover* m = movers_.get(c.handle);
            if (!m) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            m->x = c.x; m->y = c.y; m->z = c.z; m->vx = c.vx; m->vy = c.vy; m->vz = c.vz; m->onGround = false;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_CREATE: {
            astratto_cmd_ragdoll_create c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (!ragdolls_.valid(c.handle)) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            std::unique_ptr<RagdollBody> r(new RagdollBody());
            r->body.reset(new Ragdoll(c.scale > 0 ? c.scale : 1.0f));
            r->body->gravity = ragdollGravity_;
            r->body->standAt(c.x, c.y, c.z, c.yaw);
            if (c.vx != 0 || c.vy != 0 || c.vz != 0) for (int i = 0; i < rd::N; i++) r->body->kick(i, c.vx, c.vy, c.vz);
            if (c.flags & ASTRATTO_RAGDOLL_ACTIVE) r->sydney.reset(new SydneyCharacter(r->body.get(), profiles_[c.profile & 15]));
            ragdolls_.put(c.handle, std::move(r));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_POSE: {
            astratto_cmd_ragdoll_pose c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            RagdollBody* r = ragdolls_.get(c.handle);
            if (!r) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            r->body->fromPose(c.p, c.vx, c.vy, c.vz);
            r->body->unbury(e.active());
            if (r->sydney) r->sydney->primed = false;          // the pose it now holds is what its muscles hold
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_HIT: {
            astratto_cmd_ragdoll_hit c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            RagdollBody* r = ragdolls_.get(c.handle);
            if (!r) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (r->sydney && r->body->driver) r->sydney->hit(static_cast<int>(c.particle), c.dx, c.dy, c.dz, c.impulse, c.force);
            else {
                // a limp body takes the blow on the particle and its neighbours, through the bones
                const float l = std::sqrt(c.dx * c.dx + c.dy * c.dy + c.dz * c.dz);
                if (l > 1e-5f && c.particle < static_cast<uint32_t>(rd::N)) {
                    const float dv = std::min(6.0f, c.impulse / (rd::MASS[c.particle] * r->body->unit / rd::VOX * r->body->unit / rd::VOX * r->body->unit / rd::VOX));
                    r->body->kick(static_cast<int>(c.particle), c.dx / l * dv, c.dy / l * dv, c.dz / l * dv);
                    r->body->wake();
                }
            }
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_IMPULSE: {
            astratto_cmd_ragdoll_impulse c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            RagdollBody* r = ragdolls_.get(c.handle);
            if (!r) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            r->body->impulse(static_cast<int>(c.region), c.vx, c.vy, c.vz);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_CONTROL: {
            astratto_cmd_ragdoll_control c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            RagdollBody* r = ragdolls_.get(c.handle);
            if (!r) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            SydneyCharacter* s = r->body->driver ? r->sydney.get() : nullptr;
            switch (c.control) {
                case ASTRATTO_CONTROL_DIE: if (s) s->die(); break;
                case ASTRATTO_CONTROL_COLLAPSE: if (s) s->collapse(); break;
                case ASTRATTO_CONTROL_STAY_DOWN: if (s) s->stayDown = c.value != 0; break;
                case ASTRATTO_CONTROL_RELEASE: r->body->driver = nullptr; r->body->live = false; break;
                case ASTRATTO_CONTROL_LIMBS: if (r->sydney) r->sydney->setBody(c.value); break;
                case ASTRATTO_CONTROL_WAKE: r->body->wake(); break;
                default: return ASTRATTO_ERR_ARGUMENT;
            }
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_DISTURB: {
            astratto_cmd_disturb c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (c.handle != ASTRATTO_NO_HANDLE) {
                RagdollBody* r = ragdolls_.get(c.handle);
                if (r) r->body->disturb(c.x, c.y, c.z, c.radius, c.vx, c.vy, c.vz);
                else if (SoftBodyRec* s = softs_.get(c.handle)) s->body.impulseAt(c.x, c.y, c.z, c.vx, c.vy, c.vz, c.radius);
                else e.error(type, ASTRATTO_ERR_HANDLE, c.handle);
                return ASTRATTO_OK;
            }
            ragdolls_.each([&](astratto_handle, const RagdollBody& r) { r.body->disturb(c.x, c.y, c.z, c.radius, c.vx, c.vy, c.vz); });
            softs_.each([&](astratto_handle, const SoftBodyRec& sr) {
                SoftBody& b = const_cast<SoftBodyRec&>(sr).body;
                const float dx = b.centre(0) - c.x, dy = b.centre(1) - c.y, dz = b.centre(2) - c.z;
                if (dx * dx + dy * dy + dz * dz <= c.radius * c.radius) b.impulseAt(c.x, c.y, c.z, c.vx, c.vy, c.vz, c.radius);
            });
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_RAGDOLL_CARRY: {
            astratto_cmd_ragdoll_carry c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            RagdollBody* r = ragdolls_.get(c.handle);
            if (!r) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            r->body->carry(c.vx, c.vy, c.vz, std::max(0.0f, std::min(1.0f, c.share)));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_SYDNEY_PROFILE: {
            astratto_cmd_sydney_profile c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (c.index >= 16) return ASTRATTO_ERR_ARGUMENT;
            profiles_[c.index].from(c.profile);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_SOFTBODY_CREATE: {
            astratto_cmd_softbody_create c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            const uint64_t need = sizeof(c) + static_cast<uint64_t>(c.particles) * sizeof(astratto_softbody_particle) + static_cast<uint64_t>(c.sticks) * sizeof(astratto_softbody_stick) + static_cast<uint64_t>(c.limits) * sizeof(astratto_softbody_limit);
            if (bytes < need) return ASTRATTO_ERR_STREAM;
            if (!softs_.valid(c.handle)) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (c.particles == 0 || c.particles > 256) return ASTRATTO_ERR_ARGUMENT;
            std::unique_ptr<SoftBodyRec> s(new SoftBodyRec());
            SoftBody& b = s->body;
            const int n = static_cast<int>(c.particles);
            b.n = n;
            for (auto* v : {&b.x, &b.y, &b.z, &b.px, &b.py, &b.pz, &b.fx, &b.fy, &b.fz, &b.radius, &b.invMass}) v->assign(static_cast<size_t>(n), 0);
            b.grounded.assign(static_cast<size_t>(n), 0);
            const uint8_t* at = rec + sizeof(c);
            for (int i = 0; i < n; i++) { astratto_softbody_particle p; std::memcpy(&p, at, sizeof(p)); at += sizeof(p); b.particle(i, p.x, p.y, p.z, p.radius, p.mass); }
            for (uint32_t i = 0; i < c.sticks; i++) {
                astratto_softbody_stick k; std::memcpy(&k, at, sizeof(k)); at += sizeof(k);
                if (k.a >= c.particles || k.b >= c.particles) return ASTRATTO_ERR_ARGUMENT;
                b.stickA.push_back(k.a); b.stickB.push_back(k.b); b.stickLen.push_back(b.dist(static_cast<int>(k.a), static_cast<int>(k.b)));
            }
            for (uint32_t i = 0; i < c.limits; i++) {
                astratto_softbody_limit k; std::memcpy(&k, at, sizeof(k)); at += sizeof(k);
                if (k.a >= c.particles || k.b >= c.particles) return ASTRATTO_ERR_ARGUMENT;
                const float dd = b.dist(static_cast<int>(k.a), static_cast<int>(k.b));
                b.limA.push_back(k.a); b.limB.push_back(k.b); b.limMin.push_back(dd * k.min); b.limMax.push_back(dd * k.max);
            }
            if (c.slide > 0) b.slide = c.slide;
            if (c.bounce > 0) b.bounce = c.bounce;
            if (c.air > 0) b.air = c.air;
            if (c.vx != 0 || c.vy != 0 || c.vz != 0) b.velocity(c.vx, c.vy, c.vz);
            softs_.put(c.handle, std::move(s));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_SOFTBODY_IMPULSE: {
            astratto_cmd_softbody_impulse c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            SoftBodyRec* s = softs_.get(c.handle);
            if (!s) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (c.particle < 0) s->body.impulseAt(c.x, c.y, c.z, c.dvx, c.dvy, c.dvz, c.reach);
            else if (c.particle < s->body.n) s->body.impulse(c.particle, c.dvx, c.dvy, c.dvz);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_VEHICLE_CREATE: {
            astratto_cmd_vehicle_create c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (bytes < sizeof(c) + c.desc.box_count * 6 * sizeof(float)) return ASTRATTO_ERR_STREAM;
            if (!vehicles_.valid(c.handle)) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (!(c.desc.mass > 0) || c.desc.box_count > 64) return ASTRATTO_ERR_ARGUMENT;
            std::vector<float> boxes(static_cast<size_t>(c.desc.box_count) * 6);
            if (!boxes.empty()) std::memcpy(boxes.data(), rec + sizeof(c), boxes.size() * sizeof(float));
            std::unique_ptr<VehicleRec> v(new VehicleRec());
            v->car.create(c.desc, boxes.data());
            v->car.place(c.x, c.y, c.z, c.yaw);
            vehicles_.put(c.handle, std::move(v));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_VEHICLE_INPUT: {
            astratto_cmd_vehicle_input c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            VehicleRec* v = vehicles_.get(c.handle);
            if (!v) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            v->car.throttle = std::max(0.0f, std::min(1.0f, c.throttle)); v->car.brakeIn = std::max(0.0f, std::min(1.0f, c.brake)); v->car.steerIn = c.steer;
            v->car.handbrake = c.handbrake != 0; v->car.reverse = c.reverse != 0; v->car.engineOn = c.engine_on != 0;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_VEHICLE_PLACE: {
            astratto_cmd_body_place c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            VehicleRec* v = vehicles_.get(c.handle);
            if (!v) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            v->car.place(c.x, c.y, c.z, c.vx);
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_BODY_IMPULSE: {
            astratto_cmd_body_impulse c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (VehicleRec* v = vehicles_.get(c.handle)) { v->car.asleep = false; v->car.sleepTimer = 0; v->car.applyImpulse(c.x, c.y, c.z, c.jx, c.jy, c.jz); return ASTRATTO_OK; }
            if (Mover* m = movers_.get(c.handle)) { const float k = 1.0f / 80.0f; m->vx += c.jx * k; m->vy += c.jy * k; m->vz += c.jz * k; m->onGround = false; return ASTRATTO_OK; }
            e.error(type, ASTRATTO_ERR_HANDLE, c.handle);
            return ASTRATTO_OK;
        }
        default: return -1;
    }
}

void PhysicsService::step(Engine& e, float dt) {
    if (dt <= 0) return;
    const VoxelWorld* w = e.active();
    JobPool& pool = e.pool();
    std::atomic<uint64_t> steps{0};

    // movers: each on its own, in parallel (none of them changes the world)
    order_.clear();
    movers_.each([&](astratto_handle h, const Mover&) { order_.push_back(h); });
    landed_.assign(order_.size(), 0);
    pool.parallelFor(order_.size(), [&](size_t i, unsigned) { int n = 0; landed_[i] = movers_.get(order_[i])->update(w, dt, gravity_, &n); steps += static_cast<uint64_t>(n); });
    for (size_t i = 0; i < order_.size(); i++) if (landed_[i] > 2.0f) { astratto_evt_body ev{}; ev.handle = order_[i]; ev.value = landed_[i]; e.emit(ev, ASTRATTO_EVT_LANDED); }

    // ragdolls and soft bodies
    order_.clear();
    ragdolls_.each([&](astratto_handle h, const RagdollBody&) { order_.push_back(h); });
    pool.parallelFor(order_.size(), [&](size_t i, unsigned) { steps += static_cast<uint64_t>(ragdolls_.get(order_[i])->body->update(w, dt)); });
    for (astratto_handle h : order_) {
        RagdollBody* r = ragdolls_.get(h);
        if (r->sydney) {
            const int st = r->sydney->state, oc = r->sydney->outcome;
            if (st != r->lastState || oc != r->lastOutcome) {
                astratto_evt_ragdoll_state ev{};
                ev.handle = h; ev.state = static_cast<uint32_t>(st); ev.previous = static_cast<uint32_t>(r->lastState < 0 ? st : r->lastState); ev.outcome = static_cast<uint32_t>(oc);
                if (r->lastState >= 0 || oc != 0) e.emit(ev, ASTRATTO_EVT_RAGDOLL_STATE);
                r->lastState = st; r->lastOutcome = oc;
            }
        }
        if (r->body->settled && !r->wasSettled) { astratto_evt_body ev{}; ev.handle = h; e.emit(ev, ASTRATTO_EVT_SETTLED); }
        r->wasSettled = r->body->settled;
    }
    order_.clear();
    softs_.each([&](astratto_handle h, const SoftBodyRec&) { order_.push_back(h); });
    pool.parallelFor(order_.size(), [&](size_t i, unsigned) { steps += static_cast<uint64_t>(softs_.get(order_[i])->body.update(w, dt, ragdollGravity_)); });
    for (astratto_handle h : order_) {
        SoftBodyRec* s = softs_.get(h);
        if (s->body.settled && !s->wasSettled) { astratto_evt_body ev{}; ev.handle = h; e.emit(ev, ASTRATTO_EVT_SETTLED); }
        s->wasSettled = s->body.settled;
    }

    // cars
    order_.clear();
    vehicles_.each([&](astratto_handle h, const VehicleRec&) { order_.push_back(h); });
    pool.parallelFor(order_.size(), [&](size_t i, unsigned) {
        VehicleRec* v = vehicles_.get(order_[i]);
        v->report = Vehicle::Report();
        steps += static_cast<uint64_t>(v->car.update(w, surfaces_, dt, v->report));
    });
    for (astratto_handle h : order_) {
        VehicleRec* v = vehicles_.get(h);
        for (astratto_evt_vehicle_impact ev : v->report.impacts) { ev.handle = h; e.emit(ev, ASTRATTO_EVT_VEHICLE_IMPACT); }
        for (astratto_evt_vehicle_impact ev : v->report.blocks) { ev.handle = h; e.emit(ev, ASTRATTO_EVT_BLOCK_HIT); }
        if (v->report.landed > 0) { astratto_evt_body ev{}; ev.handle = h; ev.value = v->report.landed; e.emit(ev, ASTRATTO_EVT_LANDED); }
        if (v->report.gearChanged >= 0) { astratto_evt_body ev{}; ev.handle = h; ev.value = static_cast<float>(v->report.gearChanged); e.emit(ev, ASTRATTO_EVT_GEAR); }
    }
    e.counters().sim_steps += steps.load();
}

bool PhysicsService::snapshot(Engine&, uint32_t kind, std::vector<uint8_t>& out) {
    if (kind != ASTRATTO_SNAPSHOT_BODIES) return false;
    movers_.each([&](astratto_handle h, const Mover& m) {
        astratto_snap_mover s{};
        s.handle = h; s.x = m.x; s.y = m.y; s.z = m.z; s.vx = m.vx; s.vy = m.vy; s.vz = m.vz; s.grounded = m.onGround ? 1u : 0u; s.crouched = m.crouch ? 1u : 0u;
        appendRecord(out, s, ASTRATTO_SNAP_MOVER);
    });
    ragdolls_.each([&](astratto_handle h, const RagdollBody& r) {
        astratto_snap_ragdoll s{};
        Ragdoll& b = *r.body;
        s.handle = h;
        s.state = r.sydney ? static_cast<uint32_t>(r.sydney->state) : static_cast<uint32_t>(ASTRATTO_SYDNEY_DEAD);
        s.outcome = r.sydney ? static_cast<uint32_t>(r.sydney->outcome) : 0u;
        s.settled = b.settled ? 1u : 0u;
        s.active = b.driver ? 1u : 0u;
        float mx = 0, my = 0, mz = 0, m = 0;
        for (int i = 0; i < rd::N; i++) {
            s.p[i * 3] = b.x[i]; s.p[i * 3 + 1] = b.y[i]; s.p[i * 3 + 2] = b.z[i];
            if (b.grounded[i]) s.grounded |= 1u << i;
            mx += b.x[i] * rd::MASS[i]; my += b.y[i] * rd::MASS[i]; mz += b.z[i] * rd::MASS[i]; m += rd::MASS[i];
        }
        s.com[0] = mx / m; s.com[1] = my / m; s.com[2] = mz / m;
        s.yaw = b.yaw();
        s.stand_x = (b.x[rd::LA] + b.x[rd::RA]) * 0.5f; s.stand_z = (b.z[rd::LA] + b.z[rd::RA]) * 0.5f;
        appendRecord(out, s, ASTRATTO_SNAP_RAGDOLL);
    });
    softs_.each([&](astratto_handle h, const SoftBodyRec& sr) {
        const SoftBody& b = sr.body;
        astratto_snap_softbody s{};
        s.handle = h; s.particles = static_cast<uint32_t>(b.n); s.settled = b.settled ? 1u : 0u;
        s.cx = b.centre(0); s.cy = b.centre(1); s.cz = b.centre(2);
        std::vector<float> p(static_cast<size_t>(b.n) * 3);
        for (int i = 0; i < b.n; i++) { p[i * 3] = b.x[i]; p[i * 3 + 1] = b.y[i]; p[i * 3 + 2] = b.z[i]; }
        appendRecord(out, s, ASTRATTO_SNAP_SOFTBODY, p.data(), p.size() * sizeof(float));
    });
    vehicles_.each([&](astratto_handle h, const VehicleRec& v) {
        const Vehicle& c = v.car;
        astratto_snap_vehicle s{};
        s.handle = h;
        c.matrix(s.matrix);
        s.speed = c.speed(); s.forward_speed = c.forwardSpeed(); s.steer_angle = c.wheelAngle; s.engine_band = c.band;
        s.gear = static_cast<uint32_t>(c.gear); s.grounded_wheels = static_cast<uint32_t>(c.groundedWheels); s.asleep = c.asleep ? 1u : 0u;
        for (int i = 0; i < 4; i++) {
            s.wheel[i * 4] = c.wheels[i].compression; s.wheel[i * 4 + 1] = c.wheels[i].rotation; s.wheel[i * 4 + 2] = c.wheels[i].steerAngle;
            s.wheel[i * 4 + 3] = c.wheels[i].grounded && c.wheels[i].state != 0 ? 1.0f : 0.0f;
        }
        appendRecord(out, s, ASTRATTO_SNAP_VEHICLE);
    });
    return true;
}

}  // namespace astratto
