#include "ai/ai_service.h"

#include <algorithm>
#include <cmath>

#include "nav/pathfinder.h"
#include "perception/sight.h"
#include "physics/physics_service.h"

namespace astratto {

namespace {
constexpr float PI = 3.14159265358979f;
inline float clamp01(float v) { return v < 0 ? 0 : v > 1 ? 1 : v; }
inline int fl(float v) { return static_cast<int>(std::floor(v)); }

float curve(uint32_t kind, float t) {
    switch (kind) {
        case ASTRATTO_CURVE_INVERSE: return 1 - t;
        case ASTRATTO_CURVE_QUADRATIC: return t * t;
        case ASTRATTO_CURVE_LOGISTIC: return 1.0f / (1.0f + std::exp(-10.0f * (t - 0.5f)));
        case ASTRATTO_CURVE_STEP: return t >= 0.5f ? 1.0f : 0.0f;
        default: return t;
    }
}
}  // namespace

astratto_handle AiService::reserve(uint32_t kind) {
    if (kind == ASTRATTO_KIND_AGENT) return agents_.reserve();
    if (kind == ASTRATTO_KIND_POLICY) return policies_.reserve();
    return ASTRATTO_NO_HANDLE;
}

bool AiService::release(Engine&, astratto_handle h) {
    if (handleKind(h) == ASTRATTO_KIND_AGENT) return agents_.release(h);
    if (handleKind(h) == ASTRATTO_KIND_POLICY) return policies_.release(h);
    return false;
}

uint32_t AiService::live(uint32_t kind) const {
    if (kind == ASTRATTO_KIND_AGENT) return agents_.live();
    if (kind == ASTRATTO_KIND_POLICY) return policies_.live();
    return 0;
}

void AiService::worldChanged(Engine&) {
    // a new world: every route is of the old one
    agents_.each([](astratto_handle, const Agent& ca) { Agent& a = const_cast<Agent&>(ca); a.route.clear(); a.routeAt = 0; a.wantPath = a.moving; a.navSeen = 0; });
}

int AiService::command(Engine& e, uint32_t type, const uint8_t* rec, uint32_t bytes) {
    switch (type) {
        case ASTRATTO_CMD_POLICY_CREATE: {
            astratto_cmd_policy_create c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            const uint64_t need = sizeof(c) + static_cast<uint64_t>(c.actions) * sizeof(astratto_action) + static_cast<uint64_t>(c.considerations) * sizeof(astratto_consideration);
            if (bytes < need) return ASTRATTO_ERR_STREAM;
            if (!policies_.valid(c.handle)) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (c.actions == 0 || c.actions > 64 || c.considerations > 512) return ASTRATTO_ERR_ARGUMENT;
            std::unique_ptr<Policy> p(new Policy());
            p->actions.resize(c.actions);
            p->considerations.resize(c.considerations);
            std::memcpy(p->actions.data(), rec + sizeof(c), c.actions * sizeof(astratto_action));
            if (c.considerations) std::memcpy(p->considerations.data(), rec + sizeof(c) + c.actions * sizeof(astratto_action), c.considerations * sizeof(astratto_consideration));
            for (const astratto_action& a : p->actions)
                if (static_cast<uint64_t>(a.first) + a.count > c.considerations || a.kind > ASTRATTO_ACT_CUSTOM) return ASTRATTO_ERR_ARGUMENT;
            for (const astratto_consideration& k : p->considerations) if (k.input >= ASTRATTO_IN_COUNT) return ASTRATTO_ERR_ARGUMENT;
            p->inertia = c.inertia;
            p->think = c.think > 0 ? c.think : 0.2f;
            policies_.put(c.handle, std::move(p));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AGENT_CREATE: {
            astratto_cmd_agent_create c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            if (!agents_.valid(c.handle)) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            std::unique_ptr<Agent> a(new Agent());
            a->self = c.handle; a->policy = c.policy; a->body = c.body;
            a->x = c.x; a->y = c.y; a->z = c.z;
            if (PhysicsService* ps = e.service<PhysicsService>()) if (Mover* m = ps->mover(c.body)) { a->x = m->x; a->y = m->y; a->z = m->z; m->flags |= ASTRATTO_MOVER_AGENT; }
            a->homeX = a->x; a->homeY = a->y; a->homeZ = a->z;
            a->eye = c.eye > 0 ? c.eye : 1.6f; a->viewRange = c.view_range > 0 ? c.view_range : 40; a->viewCos = c.view_cos; a->hearRange = c.hear_range;
            a->team = c.team; a->flags = c.flags; a->rng = c.seed ? c.seed : static_cast<uint32_t>(handleIndex(c.handle) * 2654435761u + 1u);
            a->thinkTimer = static_cast<float>(handleIndex(c.handle) % 7) * 0.03f;     // minds do not all think on the same frame
            agents_.put(c.handle, std::move(a));
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AGENT_FACT: {
            astratto_cmd_agent_fact c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Agent* a = agents_.get(c.handle);
            if (!a) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (c.index >= 16) return ASTRATTO_ERR_ARGUMENT;
            a->facts[c.index] = c.value;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AGENT_TARGET: {
            astratto_cmd_agent_target c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Agent* a = agents_.get(c.handle);
            if (!a) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            if (a->target != c.target) { a->sees = false; a->lastSeen = -1e9; }
            a->target = c.target;
            a->hasTargetPlace = c.target == ASTRATTO_NO_HANDLE && (c.x != 0 || c.y != 0 || c.z != 0);
            if (a->hasTargetPlace) { a->targetX = c.x; a->targetY = c.y; a->targetZ = c.z; }
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AGENT_GOAL: {
            astratto_cmd_agent_goal c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Agent* a = agents_.get(c.handle);
            if (!a) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            a->hasGoal = true; a->goalX = c.x; a->goalY = c.y; a->goalZ = c.z;
            a->wantPath = true; a->arrived = false; a->fails = 0; a->stuck = false;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AGENT_POLICY: {
            astratto_cmd_agent_policy c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Agent* a = agents_.get(c.handle);
            if (!a) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            a->policy = c.policy; a->current = -1; a->thinkTimer = 0;
            return ASTRATTO_OK;
        }
        case ASTRATTO_CMD_AGENT_FORCE: {
            astratto_cmd_agent_force c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            Agent* a = agents_.get(c.handle);
            if (!a) { e.error(type, ASTRATTO_ERR_HANDLE, c.handle); return ASTRATTO_OK; }
            const Policy* p = policies_.get(a->policy);
            if (c.action < 0) { a->forced = -2; a->thinkTimer = 0; return ASTRATTO_OK; }
            if (p == nullptr) return ASTRATTO_ERR_STATE;
            for (size_t i = 0; i < p->actions.size(); i++) if (p->actions[i].id == static_cast<uint32_t>(c.action)) { a->forced = static_cast<int>(i); a->thinkTimer = 0; return ASTRATTO_OK; }
            return ASTRATTO_ERR_ARGUMENT;
        }
        case ASTRATTO_CMD_NOISE: {
            astratto_cmd_noise c;
            if (!readRecord(rec, bytes, c)) return ASTRATTO_ERR_STREAM;
            noises_.push_back(c);
            return ASTRATTO_OK;
        }
        default: return -1;
    }
}

bool AiService::targetPosition(Engine& e, const Agent& a, float* pos) const {
    if (a.target == ASTRATTO_NO_HANDLE) return false;
    if (handleKind(a.target) == ASTRATTO_KIND_AGENT) {
        const Agent* t = agents_.get(a.target);
        if (!t) return false;
        pos[0] = t->x; pos[1] = t->y + 0.9f; pos[2] = t->z;
        return true;
    }
    PhysicsService* ps = e.service<PhysicsService>();
    return ps != nullptr && ps->bodyPosition(a.target, pos, nullptr);
}

float AiService::input(const Engine& e, const Agent& a, uint32_t which) const {
    if (which < 16) return a.facts[which];
    const double now = e.time();
    switch (which) {
        case ASTRATTO_IN_TARGET_SEEN: return a.sees ? 1.0f : 0.0f;
        case ASTRATTO_IN_TARGET_DISTANCE: {
            if (a.target == ASTRATTO_NO_HANDLE && !a.hasTargetPlace) return 1e6f;
            const float dx = a.targetX - a.x, dy = a.targetY - a.y, dz = a.targetZ - a.z;
            return std::sqrt(dx * dx + dy * dy + dz * dz);
        }
        case ASTRATTO_IN_SINCE_SEEN: return static_cast<float>(std::min(1e6, now - a.lastSeen));
        case ASTRATTO_IN_SINCE_HEARD: return static_cast<float>(std::min(1e6, now - a.lastHeard));
        case ASTRATTO_IN_TIME_IN_ACTION: return a.actionTime;
        case ASTRATTO_IN_RANDOM: return a.random;
        case ASTRATTO_IN_GOAL_DISTANCE: {
            if (!a.hasGoal) return 1e6f;
            const float dx = a.goalX - a.x, dy = a.goalY - a.y, dz = a.goalZ - a.z;
            return std::sqrt(dx * dx + dy * dy + dz * dz);
        }
        case ASTRATTO_IN_HAS_TARGET: return a.target != ASTRATTO_NO_HANDLE || a.hasTargetPlace ? 1.0f : 0.0f;
        default: return 0;
    }
}

float AiService::score(const Engine& e, const Agent& a, const Policy& p, size_t ai) const {
    const astratto_action& act = p.actions[ai];
    float s = act.base;
    for (uint32_t k = 0; k < act.count && s > 0; k++) {
        const astratto_consideration& c = p.considerations[act.first + k];
        const float span = c.hi - c.lo;
        const float t = span == 0 ? (input(e, a, c.input) >= c.lo ? 1.0f : 0.0f) : clamp01((input(e, a, c.input) - c.lo) / span);
        const float w = clamp01(c.weight);
        s *= 1.0f - w * (1.0f - clamp01(curve(c.curve, t)));
    }
    if (static_cast<int>(ai) == a.current) s *= 1.0f + p.inertia;
    return s;
}

void AiService::decide(Engine& e, Agent& a) {
    const Policy* p = policies_.get(a.policy);
    if (p == nullptr || p->actions.empty()) return;
    a.random = rand01(a);
    int best = -1;
    if (a.forced >= 0 && a.forced < static_cast<int>(p->actions.size())) best = a.forced;
    else {
        float bs = 0;
        for (size_t i = 0; i < p->actions.size(); i++) { const float s = score(e, a, *p, i); if (s > bs) { bs = s; best = static_cast<int>(i); } }
    }
    if (best == a.current) return;
    astratto_evt_agent ev{};
    ev.handle = a.self; ev.other = a.target;
    ev.value = best >= 0 ? static_cast<int32_t>(p->actions[static_cast<size_t>(best)].id) : -1;
    ev.extra = a.current >= 0 ? static_cast<int32_t>(p->actions[static_cast<size_t>(a.current)].id) : -1;
    e.emit(ev, ASTRATTO_EVT_AGENT_ACTION);
    a.current = best;
    a.actionTime = 0;
    a.route.clear(); a.routeAt = 0; a.arrived = false; a.stuck = false; a.fails = 0;
    a.wantPath = false; a.moving = false;
}

/** What the current action wants: a place to go (and a route to be searched for it), or to stay where it is. */
void AiService::plan(Engine& e, Agent& a, const VoxelWorld* w) {
    const Policy* p = policies_.get(a.policy);
    if (p == nullptr || a.current < 0 || w == nullptr) { a.moving = false; return; }
    const astratto_action& act = p->actions[static_cast<size_t>(a.current)];
    a.speed = act.speed > 0 ? act.speed : 1.6f;
    auto setDest = [&](float x, float y, float z) {
        const float dx = x - a.destX, dz = z - a.destZ;
        // a destination that moved more than a metre and a half, or a plan with no route yet, is searched for again
        if (!a.moving || dx * dx + dz * dz > 2.25f || (a.route.empty() && !a.arrived)) { a.wantPath = !a.arrived || dx * dx + dz * dz > 2.25f; if (a.wantPath) a.arrived = false; }
        a.destX = x; a.destY = y; a.destZ = z;
        a.moving = true;
    };
    switch (act.kind) {
        case ASTRATTO_ACT_IDLE: case ASTRATTO_ACT_HOLD: a.moving = false; a.route.clear(); break;
        case ASTRATTO_ACT_CUSTOM:
            if (a.hasGoal) setDest(a.goalX, a.goalY, a.goalZ); else { a.moving = false; a.route.clear(); }
            break;
        case ASTRATTO_ACT_MOVE_TO_GOAL:
            if (a.hasGoal) setDest(a.goalX, a.goalY, a.goalZ); else a.moving = false;
            break;
        case ASTRATTO_ACT_APPROACH: {
            if (a.target == ASTRATTO_NO_HANDLE && !a.hasTargetPlace) { a.moving = false; break; }
            const float dx = a.targetX - a.x, dz = a.targetZ - a.z, d = std::sqrt(dx * dx + dz * dz);
            if (d <= std::max(0.5f, act.range)) { a.moving = false; a.route.clear(); if (!a.arrived) { a.arrived = true; astratto_evt_agent ev{}; ev.handle = a.self; ev.other = a.target; e.emit(ev, ASTRATTO_EVT_AGENT_ARRIVED); } break; }
            a.arrived = false;
            setDest(a.targetX, a.targetY - 0.9f, a.targetZ);
            break;
        }
        case ASTRATTO_ACT_FLEE: {
            if (a.target == ASTRATTO_NO_HANDLE && !a.hasTargetPlace) { a.moving = false; break; }
            float dx = a.x - a.targetX, dz = a.z - a.targetZ, d = std::sqrt(dx * dx + dz * dz);
            const float want = act.range > 0 ? act.range : 20;
            if (d >= want) { a.moving = false; a.route.clear(); break; }
            if (a.moving && !a.route.empty() && a.routeAt < a.route.size() / 3) break;      // keep running the way it chose
            if (d < 1e-3f) { dx = 1; dz = 0; d = 1; }
            dx /= d; dz /= d;
            // the way out: straight away if it can stand there, else a little to either side
            for (int k = 0; k < 7; k++) {
                const float ang = (k == 0 ? 0 : (k + 1) / 2 * 0.45f * ((k & 1) ? 1.0f : -1.0f));
                const float c = std::cos(ang), s = std::sin(ang);
                const float ox = dx * c - dz * s, oz = dx * s + dz * c;
                const float tx = a.x + ox * want, tz = a.z + oz * want;
                const int ty = Pathfinder::stepY(*w, fl(tx), fl(tz), fl(a.y + 0.5f), (a.flags & ASTRATTO_AGENT_DOORS) != 0);
                if (ty >= 0 || k == 6) { a.moving = false; setDest(tx, ty >= 0 ? static_cast<float>(ty) : a.y, tz); a.wantPath = true; break; }
            }
            break;
        }
        case ASTRATTO_ACT_INVESTIGATE: {
            const bool seenLater = a.lastSeen >= a.lastHeard;
            if (a.lastSeen < -1e8 && a.lastHeard < -1e8) { a.moving = false; break; }
            const float tx = seenLater ? a.targetX : a.heardX, ty = seenLater ? a.targetY - 0.9f : a.heardY, tz = seenLater ? a.targetZ : a.heardZ;
            const float dx = tx - a.x, dz = tz - a.z;
            if (dx * dx + dz * dz < 1.0f) { a.moving = false; if (!a.arrived) { a.arrived = true; astratto_evt_agent ev{}; ev.handle = a.self; e.emit(ev, ASTRATTO_EVT_AGENT_ARRIVED); } break; }
            setDest(tx, ty, tz);
            break;
        }
        case ASTRATTO_ACT_WANDER: {
            if (a.moving && !a.arrived && !a.stuck) break;
            if ((a.arrived || a.stuck) && !a.waiting && a.actionTime > 0.05f) { a.waiting = true; a.pause = 1.0f + 2.5f * rand01(a); }
            if (a.waiting && a.pause > 0) break;
            a.waiting = false;
            const float range = act.range > 0 ? act.range : 8;
            for (int tries = 0; tries < 8; tries++) {
                const float ang = rand01(a) * 2 * PI, dist = range * (0.3f + 0.7f * rand01(a));
                const float tx = a.homeX + std::cos(ang) * dist, tz = a.homeZ + std::sin(ang) * dist;
                const int ty = Pathfinder::stepY(*w, fl(tx), fl(tz), fl(a.homeY + 0.5f), (a.flags & ASTRATTO_AGENT_DOORS) != 0);
                if (ty < 0) continue;
                a.arrived = false; a.stuck = false; a.moving = false;
                setDest(static_cast<float>(fl(tx)) + 0.5f, static_cast<float>(ty), static_cast<float>(fl(tz)) + 0.5f);
                a.wantPath = true;
                break;
            }
            break;
        }
        default: a.moving = false; break;
    }
}

void AiService::follow(Engine& e, Agent& a, const VoxelWorld* w, float dt, PhysicsService* phys) {
    Mover* m = phys ? phys->mover(a.body) : nullptr;
    float wantX = 0, wantZ = 0, jump = 0;
    if (a.moving && w != nullptr && !a.route.empty() && a.routeAt * 3 < a.route.size()) {
        const bool doors = (a.flags & ASTRATTO_AGENT_DOORS) != 0;
        const float radius = m ? m->radius : 0.3f;
        // cut corners: the furthest of the next few cells it can walk to in a straight line is the one it heads for
        size_t aim = a.routeAt;
        for (size_t k = a.routeAt + 1; k * 3 < a.route.size() && k <= a.routeAt + 6; k++) {
            const float cx = static_cast<float>(a.route[k * 3]) + 0.5f, cz = static_cast<float>(a.route[k * 3 + 2]) + 0.5f;
            if (a.route[k * 3 + 1] != a.route[aim * 3 + 1]) break;
            if (!Pathfinder::clearBody(*w, a.x, a.y, a.z, cx, cz, doors, radius)) break;
            aim = k;
        }
        if (aim > a.routeAt) a.routeAt = aim;
        const float cx = static_cast<float>(a.route[a.routeAt * 3]) + 0.5f, cy = static_cast<float>(a.route[a.routeAt * 3 + 1]), cz = static_cast<float>(a.route[a.routeAt * 3 + 2]) + 0.5f;
        float dx = cx - a.x, dz = cz - a.z;
        const float d = std::sqrt(dx * dx + dz * dz);
        const bool last = (a.routeAt + 1) * 3 >= a.route.size();
        if (d < (last ? 0.3f : 0.45f) && std::fabs(cy - a.y) < 1.2f) {
            a.routeAt++;
            a.progressBest = 1e9f; a.progressTimer = 0;
            if (a.routeAt * 3 >= a.route.size()) {
                a.route.clear(); a.routeAt = 0; a.moving = false; a.arrived = true;
                astratto_evt_agent ev{}; ev.handle = a.self; ev.other = a.target; e.emit(ev, ASTRATTO_EVT_AGENT_ARRIVED);
            }
        } else {
            dx /= std::max(d, 1e-4f); dz /= std::max(d, 1e-4f);
            const float slow = last ? std::min(1.0f, d / 0.8f + 0.25f) : 1.0f;
            wantX = dx * a.speed * slow; wantZ = dz * a.speed * slow;
            a.yaw = std::atan2(-dx, -dz);
            // a ledge on the route that is not a stair is jumped
            if (m && cy > a.y + 0.6f && d < 1.4f && m->onGround && !(w->def(fl(cx), fl(cy) - 1, fl(cz)).flags & ASTRATTO_BLOCK_STEP)) jump = std::sqrt(2 * 22.0f * (cy - a.y + 0.35f));
            // progress: if it has not got nearer for a second and a half it is stuck - search again; three times and it gives up
            a.progressTimer += dt;
            if (d < a.progressBest - 0.1f) { a.progressBest = d; a.progressTimer = 0; }
            if (a.progressTimer > 1.5f) {
                a.progressTimer = 0; a.progressBest = 1e9f;
                if (++a.fails >= 3) {
                    a.stuck = true; a.moving = false; a.route.clear();
                    astratto_evt_agent ev{}; ev.handle = a.self; e.emit(ev, ASTRATTO_EVT_AGENT_STUCK);
                } else a.wantPath = true;
            }
        }
    }
    // face what it is after when it can see it and is not running from it
    const Policy* p = policies_.get(a.policy);
    if (a.sees && p && a.current >= 0 && p->actions[static_cast<size_t>(a.current)].kind != ASTRATTO_ACT_FLEE && (!a.moving || p->actions[static_cast<size_t>(a.current)].kind == ASTRATTO_ACT_HOLD))
        a.yaw = std::atan2(-(a.targetX - a.x), -(a.targetZ - a.z));
    if (m) {
        m->wishX = wantX; m->wishZ = wantZ;
        if (jump > 0) m->jump = jump;
    } else if (a.moving && (wantX != 0 || wantZ != 0)) {
        // no body: it moves itself, along the route, at its speed
        a.x += wantX * dt; a.z += wantZ * dt;
        if (!a.route.empty() && a.routeAt * 3 < a.route.size()) a.y += (static_cast<float>(a.route[a.routeAt * 3 + 1]) - a.y) * std::min(1.0f, dt * 8);
    }
}

void AiService::step(Engine& e, float dt) {
    if (dt <= 0) { noises_.clear(); return; }
    const VoxelWorld* w = e.active();
    PhysicsService* phys = e.service<PhysicsService>();
    JobPool& pool = e.pool();
    while (finders_.size() < pool.workers()) finders_.emplace_back(new Pathfinder());
    const double now = e.time() + dt;

    order_.clear();
    agents_.each([&](astratto_handle h, const Agent&) { order_.push_back(h); });

    // ---- where everyone is (a body moves its agent)
    for (astratto_handle h : order_) {
        Agent& a = *agents_.get(h);
        if (Mover* m = phys ? phys->mover(a.body) : nullptr) { a.x = m->x; a.y = m->y; a.z = m->z; }
        a.lookValid = false;
        float tp[3];
        if (targetPosition(e, a, tp)) { a.lookX = tp[0]; a.lookY = tp[1]; a.lookZ = tp[2]; a.lookValid = true; }
        else if (a.target == ASTRATTO_NO_HANDLE && !(a.flags & ASTRATTO_AGENT_BLIND)) {
            // no target: the nearest agent of another team (team 0 is nobody's enemy) is the one it looks for
            float best = a.viewRange * a.viewRange;
            agents_.each([&](astratto_handle oh, const Agent& o) {
                if (oh == h || o.team == a.team || o.team == 0 || a.team == 0) return;
                const float dx = o.x - a.x, dz = o.z - a.z, d2 = dx * dx + dz * dz;
                if (d2 < best) { best = d2; a.lookX = o.x; a.lookY = o.y + 0.9f; a.lookZ = o.z; a.lookValid = true; a.target = oh; }
            });
        }
    }

    // ---- eyes: every agent's line to what it is looking for, at once, across the workers
    clear_.assign(order_.size(), 0);
    if (w != nullptr) pool.parallelFor(order_.size(), [&](size_t i, unsigned) {
        const Agent& a = *agents_.get(order_[i]);
        if (!a.lookValid || (a.flags & ASTRATTO_AGENT_BLIND)) return;
        const float ex = a.x, ey = a.y + a.eye, ez = a.z;
        const float dx = a.lookX - ex, dy = a.lookY - ey, dz = a.lookZ - ez, d = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (d > a.viewRange) return;
        if (d > 2.0f) {
            // in front of it: within the field of view of the way it faces (forward is (-sin yaw, -cos yaw))
            const float fx = -std::sin(a.yaw), fz = -std::cos(a.yaw), hl = std::sqrt(dx * dx + dz * dz);
            if (hl > 1e-3f && (fx * dx + fz * dz) / hl < a.viewCos) return;
        }
        clear_[i] = sightClear(*w, ex, ey, ez, a.lookX, a.lookY, a.lookZ, true) ? 1 : 0;
    });
    for (size_t i = 0; i < order_.size(); i++) {
        Agent& a = *agents_.get(order_[i]);
        const bool saw = clear_[i] != 0;
        if (saw) {
            a.targetX = a.lookX; a.targetY = a.lookY; a.targetZ = a.lookZ; a.lastSeen = now;
            if (!a.sees) { a.sees = true; astratto_evt_agent ev{}; ev.handle = a.self; ev.other = a.target; e.emit(ev, ASTRATTO_EVT_AGENT_SEES); }
        } else {
            if (a.sees) { a.sees = false; astratto_evt_agent ev{}; ev.handle = a.self; ev.other = a.target; e.emit(ev, ASTRATTO_EVT_AGENT_LOST); }
            // a provisional target that was never seen is not kept
            if (a.lastSeen < -1e8 && handleKind(a.target) == ASTRATTO_KIND_AGENT && a.lookValid) {
                const Agent* t = agents_.get(a.target);
                if (t && t->team != a.team && t->team != 0) a.target = ASTRATTO_NO_HANDLE;
            }
        }
    }

    // ---- ears
    for (const astratto_cmd_noise& n : noises_) {
        for (astratto_handle h : order_) {
            Agent& a = *agents_.get(h);
            if (n.team != 0 && n.team == a.team) continue;
            const float dx = n.x - a.x, dy = n.y - a.y, dz = n.z - a.z, d = std::sqrt(dx * dx + dy * dy + dz * dz);
            if (d > n.radius || d > a.hearRange) continue;
            a.lastHeard = now; a.heardX = n.x; a.heardY = n.y; a.heardZ = n.z;
            astratto_evt_agent ev{}; ev.handle = h; ev.value = static_cast<int32_t>(n.loudness * 100); ev.extra = static_cast<int32_t>(d * 10);
            e.emit(ev, ASTRATTO_EVT_AGENT_HEARD);
        }
    }
    noises_.clear();

    // ---- minds
    for (astratto_handle h : order_) {
        Agent& a = *agents_.get(h);
        a.actionTime += dt;
        a.thinkTimer -= dt;
        a.pause -= dt;
        const Policy* p = policies_.get(a.policy);
        if (a.thinkTimer <= 0) { a.thinkTimer += p ? p->think : 0.2f; if (a.thinkTimer < 0) a.thinkTimer = 0; decide(e, a); }
        plan(e, a, w);
        if (w != nullptr && a.moving && a.navSeen != w->navVersion()) { a.navSeen = w->navVersion(); if (!a.route.empty()) a.wantPath = true; }
    }

    // ---- routes for those that want one, in parallel
    if (w != nullptr) {
        std::vector<size_t> want;
        for (size_t i = 0; i < order_.size(); i++) { Agent& a = *agents_.get(order_[i]); a.repathTimer -= dt; if (a.wantPath && a.moving && a.repathTimer <= 0) want.push_back(i); }
        const NavTuning& tune = e.tuning();
        pool.parallelFor(want.size(), [&](size_t k, unsigned worker) {
            Agent& a = *agents_.get(order_[want[k]]);
            PathQuery q;
            q.sx = a.x; q.sy = a.y; q.sz = a.z; q.tx = a.destX; q.ty = a.destY; q.tz = a.destZ;
            q.maxExpand = 5000;
            q.doors = (a.flags & ASTRATTO_AGENT_DOORS) != 0;
            q.goalY = true;
            q.mantle = (a.flags & ASTRATTO_AGENT_MANTLE) != 0;
            uint32_t expansions = 0;
            if (!finders_[worker]->path(*w, tune, q, a.route, expansions)) a.route.clear();
        });
        for (size_t k : want) {
            Agent& a = *agents_.get(order_[k]);
            a.wantPath = false; a.routeAt = 0; a.repathTimer = 0.4f; a.progressBest = 1e9f; a.progressTimer = 0;
            a.navSeen = w->navVersion();
            if (a.route.empty()) {
                const float dx = a.destX - a.x, dz = a.destZ - a.z;
                if (dx * dx + dz * dz > 0.5f && ++a.fails >= 3) {
                    a.stuck = true; a.moving = false;
                    astratto_evt_agent ev{}; ev.handle = a.self; e.emit(ev, ASTRATTO_EVT_AGENT_STUCK);
                }
            }
        }
        e.counters().path_requests += want.size();
    }

    // ---- bodies follow
    for (astratto_handle h : order_) follow(e, *agents_.get(h), w, dt, phys);
}

bool AiService::snapshot(Engine&, uint32_t kind, std::vector<uint8_t>& out) {
    if (kind != ASTRATTO_SNAPSHOT_AGENTS) return false;
    agents_.each([&](astratto_handle h, const Agent& a) {
        astratto_snap_agent s{};
        s.handle = h; s.target = a.target;
        s.x = a.x; s.y = a.y; s.z = a.z; s.yaw = a.yaw;
        s.tx = a.targetX; s.ty = a.targetY; s.tz = a.targetZ;
        const Policy* p = policies_.get(a.policy);
        s.action = p && a.current >= 0 ? static_cast<int32_t>(p->actions[static_cast<size_t>(a.current)].id) : -1;
        s.sees = a.sees ? 1u : 0u; s.moving = a.moving ? 1u : 0u; s.stuck = a.stuck ? 1u : 0u;
        s.route_cells = static_cast<uint32_t>(a.route.size() / 3 - std::min(a.route.size() / 3, a.routeAt));
        appendRecord(out, s, ASTRATTO_SNAP_AGENT);
    });
    return true;
}

}  // namespace astratto
