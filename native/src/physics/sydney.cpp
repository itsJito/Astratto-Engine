// Astratto Engine - Sydney's controllers: sensors, muscles, behaviours and the state machine that chooses between
// them. A port of the Java Sydney Engine (sydney.*), rule for rule; the comments that explain why are the Java ones.
#include <algorithm>
#include <cmath>

#include "physics/ragdoll.h"

namespace astratto {

using namespace rd;

namespace {
constexpr float PI = 3.14159265358979f;
inline float lerp(float a, float b, float t) { return a + (b - a) * t; }
inline float smooth(float t) { t = std::max(0.0f, std::min(1.0f, t)); return t * t * (3 - 2 * t); }
inline float hypot2(float a, float b) { return std::sqrt(a * a + b * b); }
inline float rad(float deg) { return deg * PI / 180.0f; }
constexpr float STANCE_HIP = -0.21f, STANCE_KNEE = 0.21f;
}  // namespace

// ------------------------------------------------------------------ profiles
SydneyConfig SydneyConfig::human() { return SydneyConfig(); }

SydneyConfig SydneyConfig::infected() {
    SydneyConfig c;
    c.maxSteps = 6; c.ankleLever = 0.17f; c.balanceGain = 2.4f; c.brace = 0.15f; c.holdWound = false; c.downMin = 0.4f; c.downMax = 1.6f; c.getUpTime = 1.0f;
    c.strength = 1.15f; c.fallTilt = 60; c.deathFade = 0.6f;
    c.style = INFECTED;
    c.getUpTime = 1.7f; c.getUpTimeout = 2.2f;
    return c;
}

SydneyConfig SydneyConfig::crawler() {
    SydneyConfig c = infected();
    c.getUpTime = 0.8f; c.getUpTimeout = 1.9f;
    c.style = CRAWLER; c.downMin = 0.2f; c.downMax = 0.8f; c.getUpTries = 8; c.getUpStrength = 1.4f;
    return c;
}

SydneyConfig SydneyConfig::giant() {
    SydneyConfig c;
    c.maxSteps = 8; c.brace = 0.3f; c.holdWound = false; c.strength = 1.6f; c.downMin = 0.6f; c.downMax = 2;
    c.style = INFECTED;
    return c;
}

void SydneyConfig::from(const astratto_sydney_profile& p) {
    for (int i = 0; i < 6; i++) { freq[i] = p.freq[i]; zeta[i] = p.zeta[i]; torque[i] = p.torque[i]; }
    strength = p.strength; stanceGain = p.stance_gain; standPosture = p.stand_posture; stanceHip = p.stance_hip; carryHip = p.carry_hip; carryPosture = p.carry_posture;
    ankleLever = p.ankle_lever; balanceGain = p.balance_gain; stepThreshold = p.step_threshold; stepTime = p.step_time; stepReach = p.step_reach; stepLift = p.step_lift;
    stepOvershoot = p.step_overshoot; stepLead = p.step_lead; stepForce = p.step_force;
    fallTilt = p.fall_tilt; fallHeight = p.fall_height; lostReach = p.lost_reach; recoverSettle = p.recover_settle;
    fallStrength = p.fall_strength; brace = p.brace; downStrength = p.down_strength; downMin = p.down_min; downPerForce = p.down_per_force; downMax = p.down_max; holdForce = p.hold_force;
    getUpTime = std::max(0.05f, p.get_up_time); getUpStrength = p.get_up_strength; getUpTimeout = p.get_up_timeout;
    deathFade = p.death_fade; deathPosture = p.death_posture; crawlHeight = p.crawl_height;
    maxSteps = static_cast<int>(p.max_steps); getUpTries = static_cast<int>(p.get_up_tries); holdWound = p.hold_wound != 0; style = static_cast<int>(p.style);
}

// ------------------------------------------------------------------ the rig
const int PhysicsRig::JOINTS[NJ][5] = {
    {PELVIS, NECK, HEAD_P, SydneyConfig::NECK, 0},
    {NECK, LS, LE, SydneyConfig::SHOULDER, 1}, {LS, LE, LW, SydneyConfig::ELBOW, 1},
    {NECK, RS, RE, SydneyConfig::SHOULDER, 2}, {RS, RE, RW, SydneyConfig::ELBOW, 2},
    {NECK, LH, LK, SydneyConfig::HIP, 3}, {LH, LK, LA, SydneyConfig::KNEE, 3},
    {NECK, RH, RK, SydneyConfig::HIP, 4}, {RH, RK, RA, SydneyConfig::KNEE, 4}};

namespace {
const float CARRIED[PhysicsRig::NJ] = {MASS[HEAD_P], MASS[LE] + MASS[LW], MASS[LW], MASS[RE] + MASS[RW], MASS[RW], MASS[LK] + MASS[LA], MASS[LA], MASS[RK] + MASS[RA], MASS[RA]};
}

PhysicsRig::PhysicsRig(Ragdoll* body) : r(body) {
    unit = r->unit;
    const float s = unit / VOX;
    massScale = s * s * s;
    float m = 0;
    for (float k : MASS) m += k;
    totalMass = m * massScale;
}

float PhysicsRig::mass(int i) const { return MASS[i] * massScale; }
float PhysicsRig::carried(int j) const { return CARRIED[j] * massScale; }
bool PhysicsRig::limbWorks(int limb) const { switch (limb) { case 1: return armL; case 2: return armR; case 3: return legL; case 4: return legR; default: return true; } }
bool PhysicsRig::footDown(int side) const { return side == 0 ? legL && r->grounded[LA] : legR && r->grounded[RA]; }
void PhysicsRig::force(int i, float fx, float fy, float fz) { r->fx[i] += fx; r->fy[i] += fy; r->fz[i] += fz; }
void PhysicsRig::bodyForce(float fx, float fy, float fz) {
    for (int i = 0; i < N; i++) { const float k = MASS[i] * massScale / totalMass; r->fx[i] += fx * k; r->fy[i] += fy * k; r->fz[i] += fz * k; }
}
void PhysicsRig::torque(int p, int j, int c, float tx, float ty, float tz) {
    const float cx = r->x[c] - r->x[j], cy = r->y[c] - r->y[j], cz = r->z[c] - r->z[j], c2 = cx * cx + cy * cy + cz * cz;
    const float qx = r->x[p] - r->x[j], qy = r->y[p] - r->y[j], qz = r->z[p] - r->z[j], q2 = qx * qx + qy * qy + qz * qz;
    if (c2 < 1e-8f || q2 < 1e-8f) return;
    const float fcx = (ty * cz - tz * cy) / c2, fcy = (tz * cx - tx * cz) / c2, fcz = (tx * cy - ty * cx) / c2;
    const float fpx = -(ty * qz - tz * qy) / q2, fpy = -(tz * qx - tx * qz) / q2, fpz = -(tx * qy - ty * qx) / q2;
    force(c, fcx, fcy, fcz);
    force(p, fpx, fpy, fpz);
    force(j, -fcx - fpx, -fcy - fpy, -fcz - fpz);
}

// ------------------------------------------------------------------ sensors
namespace {
float groundUnder(const VoxelWorld* w, const Ragdoll& r, float x, float y, float z) {
    if (w == nullptr) return r.lowestY();
    const int ix = static_cast<int>(std::floor(x)), iz = static_cast<int>(std::floor(z)), top = static_cast<int>(std::floor(y));
    for (int iy = top; iy >= std::max(0, top - 6); iy--) if (w->solid(ix, iy, iz)) return static_cast<float>(iy + 1);
    return r.lowestY();
}
constexpr int HOLD = 4;
}  // namespace

void SensorSystem::measure(PhysicsRig& rig, const VoxelWorld* w, const SydneyConfig& cfg) {
    Ragdoll& r = *rig.r;
    float mx = 0, my = 0, mz = 0, vx = 0, vy = 0, vz = 0;
    for (int i = 0; i < N; i++) {
        const float m = MASS[i];
        mx += r.x[i] * m; my += r.y[i] * m; mz += r.z[i] * m;
        vx += r.vx(i) * m; vy += r.vy(i) * m; vz += r.vz(i) * m;
    }
    const float M = rig.totalMass / rig.massScale;
    com[0] = mx / M; com[1] = my / M; com[2] = mz / M;
    comV[0] = vx / M; comV[1] = vy / M; comV[2] = vz / M;
    speed = std::sqrt(comV[0] * comV[0] + comV[1] * comV[1] + comV[2] * comV[2]);
    if (primed) jolt = std::max(jolt, std::sqrt((comV[0] - lastVx) * (comV[0] - lastVx) + (comV[1] - lastVy) * (comV[1] - lastVy) + (comV[2] - lastVz) * (comV[2] - lastVz)));
    lastVx = comV[0]; lastVy = comV[1]; lastVz = comV[2]; primed = true;

    const float ux = r.x[NECK] - r.x[PELVIS], uy = r.y[NECK] - r.y[PELVIS], uz = r.z[NECK] - r.z[PELVIS];
    const float ul = std::sqrt(ux * ux + uy * uy + uz * uz);
    if (ul > 1e-5f) { up[0] = ux / ul; up[1] = uy / ul; up[2] = uz / ul; }
    float rx = r.x[RS] - r.x[LS], ry = r.y[RS] - r.y[LS], rz = r.z[RS] - r.z[LS];
    const float d = rx * up[0] + ry * up[1] + rz * up[2];
    rx -= up[0] * d; ry -= up[1] * d; rz -= up[2] * d;
    const float rl = std::sqrt(rx * rx + ry * ry + rz * rz);
    if (rl > 1e-5f) { right[0] = rx / rl; right[1] = ry / rl; right[2] = rz / rl; }
    fwd[0] = up[1] * right[2] - up[2] * right[1]; fwd[1] = up[2] * right[0] - up[0] * right[2]; fwd[2] = up[0] * right[1] - up[1] * right[0];
    tilt = std::acos(std::max(-1.0f, std::min(1.0f, up[1])));

    heldL = rig.footDown(0) ? HOLD : std::max(0, heldL - 1); heldR = rig.footDown(1) ? HOLD : std::max(0, heldR - 1);
    footL = heldL > 0 && rig.legL; footR = heldR > 0 && rig.legR;
    feet = (footL ? 1 : 0) + (footR ? 1 : 0);
    otherContacts = 0;
    for (int i = 0; i < N; i++) if (r.grounded[i] && i != LA && i != RA) otherContacts++;
    groundY = groundUnder(w, r, com[0], com[1], com[2]);
    if (feet == 2) { supX = (r.x[LA] + r.x[RA]) * 0.5f; supZ = (r.z[LA] + r.z[RA]) * 0.5f; }
    else if (footL) { supX = r.x[LA]; supZ = r.z[LA]; }
    else if (footR) { supX = r.x[RA]; supZ = r.z[RA]; }
    else { supX = (r.x[LA] + r.x[RA]) * 0.5f; supZ = (r.z[LA] + r.z[RA]) * 0.5f; }
    stance = hypot2(r.x[RA] - r.x[LA], r.z[RA] - r.z[LA]);
    height = com[1] - groundY;
    omega = std::sqrt(rig.r->gravity / std::max(0.25f, height));
    cpX = com[0] + comV[0] / omega; cpZ = com[2] + comV[2] / omega;
    errX = cpX - supX; errZ = cpZ - supZ;
    err = hypot2(errX, errZ);
    errNorm = err / reach(cfg, errX, errZ);
}

float SensorSystem::reach(const SydneyConfig& cfg, float dx, float dz) const {
    const float l = hypot2(dx, dz);
    if (l < 1e-5f || feet < 2) return cfg.ankleLever * (feet == 0 ? 0.5f : 1.0f);
    const float along = std::fabs(dx * right[0] + dz * right[2]) / l;
    return cfg.ankleLever + 0.5f * stance * along;
}

// ------------------------------------------------------------------ the goal
void Goal::clear() {
    for (int j = 0; j < PhysicsRig::NJ; j++) gain[j] = -1;
    strength = 1; stance = false; posture = balance = lift = -1; postureOnGround = 0; height = 0;
    up[0] = 0; up[1] = 1; up[2] = 0;
    for (int i = 0; i < 4; i++) { reach[i].on = false; reachClaimed[i] = false; }
}
void Goal::joint(int j, float x, float y, float z, float g) { if (gain[j] >= 0) return; dir[j][0] = x; dir[j][1] = y; dir[j][2] = z; gain[j] = g; }
void Goal::setPosture(float ux, float uy, float uz, float g, float onGround) { if (posture >= 0) return; up[0] = ux; up[1] = uy; up[2] = uz; posture = g; postureOnGround = onGround; }
void Goal::setReach(int slot, int particle, int against, float x, float y, float z, float force, float hz) {
    if (reachClaimed[slot]) return;
    reachClaimed[slot] = true;
    Reach& r = reach[slot];
    r.on = true; r.particle = particle; r.against = against; r.x = x; r.y = y; r.z = z; r.force = force; r.hz = hz;
}
void Goal::finish() {
    for (int j = 0; j < PhysicsRig::NJ; j++) if (gain[j] < 0) gain[j] = 0;
    if (posture < 0) posture = 0;
    if (balance < 0) balance = 0;
    if (lift < 0) lift = 0;
}

// ------------------------------------------------------------------ the character
SydneyCharacter::SydneyCharacter(Ragdoll* r, const SydneyConfig& c) : rig(r), cfg(c) {
    scale = r->unit / VOX;
    sense.standHeight = 0.866f * scale + 0.08f * scale;
    standReset();
    r->driver = this;
    r->live = true;
}

void SydneyCharacter::setBody(uint32_t broken) {
    rig.armL = (broken & 1u) == 0; rig.armR = (broken & 2u) == 0; rig.legL = (broken & 4u) == 0; rig.legR = (broken & 8u) == 0; rig.head = (broken & 16u) == 0;
}

void SydneyCharacter::frame(int j, float* f) const {
    // HINGES_AGAINST_PARENT is off in the reference: every joint is posed against the trunk
    (void)j;
    f[0] = sense.right[0]; f[1] = sense.right[1]; f[2] = sense.right[2]; f[3] = sense.up[0]; f[4] = sense.up[1]; f[5] = sense.up[2]; f[6] = sense.fwd[0]; f[7] = sense.fwd[1]; f[8] = sense.fwd[2];
}

void SydneyCharacter::toLocal(const float* f, float wx, float wy, float wz, float* out) {
    out[0] = wx * f[0] + wy * f[1] + wz * f[2];
    out[1] = wx * f[3] + wy * f[4] + wz * f[5];
    out[2] = -(wx * f[6] + wy * f[7] + wz * f[8]);
}

void SydneyCharacter::local(float wx, float wy, float wz, float* out) const {
    out[0] = wx * sense.right[0] + wy * sense.right[1] + wz * sense.right[2];
    out[1] = wx * sense.up[0] + wy * sense.up[1] + wz * sense.up[2];
    out[2] = -(wx * sense.fwd[0] + wy * sense.fwd[1] + wz * sense.fwd[2]);
}

void SydneyCharacter::capture() {
    Ragdoll& r = *rig.r;
    float f[9];
    for (int j = 0; j < PhysicsRig::NJ; j++) {
        const int* J = PhysicsRig::JOINTS[j];
        const float dx = r.x[J[2]] - r.x[J[1]], dy = r.y[J[2]] - r.y[J[1]], dz = r.z[J[2]] - r.z[J[1]];
        const float l = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-5f) { pose[j][0] = 0; pose[j][1] = j == PhysicsRig::J_NECK ? 1.0f : -1.0f; pose[j][2] = 0; continue; }
        frame(j, f);
        toLocal(f, dx / l, dy / l, dz / l, pose[j]);
    }
}

void SydneyCharacter::drive(Ragdoll& r, const VoxelWorld* w, float dt) {
    sense.measure(rig, w, cfg);
    if (!primed) { capture(); primed = true; }
    update(dt);
    motors();
    for (int i = 0; i < N; i++) {
        if (std::isnan(r.x[i]) || std::isnan(r.y[i]) || std::isnan(r.z[i]) || !std::isfinite(r.fx[i]) || std::isnan(r.fy[i]) || std::isnan(r.fz[i])) { invalid = true; r.fx[i] = r.fy[i] = r.fz[i] = 0; }
    }
    // death: when the last of the tone has drained the body is an ordinary ragdoll again and may come to rest
    if (outcome == DEAD_OUTCOME) { r.live = false; r.driver = nullptr; }
}

void SydneyCharacter::hit(int particle, float dx, float dy, float dz, float impulse, float force) {
    Ragdoll& r = *rig.r;
    const float l = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (l < 1e-5f || particle < 0 || particle >= N) return;
    dx /= l; dy /= l; dz /= l;
    float dv = impulse / rig.mass(particle);
    const float cap = 6;
    float rest = 0;
    if (dv > cap) { rest = (dv - cap) * rig.mass(particle); dv = cap; }
    r.kick(particle, dx * dv, dy * dv, dz * dv);
    if (rest > 0) { const float share = rest / (rig.mass(NECK) + rig.mass(PELVIS)); r.kick(NECK, dx * share, dy * share, dz * share); r.kick(PELVIS, dx * share, dy * share, dz * share); }
    wound = particle;
    if (r.settled) r.wake();
    onHit(force);
}

void SydneyCharacter::onHit(float force) {
    settled = 0;
    stun = std::max(stun, std::min(1.2f, force / 110.0f));
    downFor = std::max(downFor, std::min(cfg.downMax, cfg.downMin + force * cfg.downPerForce));
    if (outcome == RECOVERED) outcome = NONE;
}

void SydneyCharacter::die() { alive = false; if (state != DEAD) { go(DEAD); limpT = 0; outcome = NONE; } }
void SydneyCharacter::collapse() { if (state != DEAD && state != FALL && state != DOWN) fallNow(); }

// ------------------------------------------------------------------ the coordinator
void SydneyCharacter::update(float dt) {
    const SensorSystem& s = sense;
    t += dt;
    stun = std::max(0.0f, stun - dt);
    airborne = s.feet == 0 && s.otherContacts == 0 ? airborne + dt : 0;
    if (airborne > longestAir) longestAir = airborne;
    if (airborne <= 0) flailT = 0;
    const bool legs = rig.legL && rig.legR;
    const bool toppled = s.tilt > rad(cfg.fallTilt) || s.height < s.standHeight * cfg.fallHeight;
    if (!alive && state != DEAD) die();

    switch (state) {
        case STAND: {
            if (!legs || toppled || airborne > 0.25f) { fallNow(); break; }
            over = s.errNorm > cfg.stepThreshold ? over + dt : 0;
            if (over > 0.04f && s.feet > 0) {
                over = 0;
                if (steps >= cfg.maxSteps || s.err > cfg.lostReach * scale || !stepBegin()) fallNow();
                else { steps++; go(STEP); }
                break;
            }
            settled = s.err < 0.07f * scale && s.speed < 0.3f && s.feet == 2 ? settled + dt : 0;
            if (settled >= cfg.recoverSettle && stun <= 0) { outcome = RECOVERED; recoveries++; }
            break;
        }
        case STEP:
            if (toppled) { fallNow(); break; }
            if (stepDone()) { standPlant(); go(STAND); }
            break;
        case FALL:
            quiet = s.speed < 0.7f ? quiet + dt : 0;
            if (((s.height < s.standHeight * 0.55f || t > 0.6f) && quiet > 0.3f) || t > 3.5f) { go(DOWN); quiet = 0; }
            break;
        case DOWN:
            if (t < downFor || stayDown) break;
            if (!legs && cfg.style != SydneyConfig::CRAWLER) { outcome = CRAWL; break; }
            if (tries >= cfg.getUpTries) { outcome = STUCK; break; }
            tries++;
            getUpBegin();
            go(GETUP);
            break;
        case GETUP: {
            if (cfg.style == SydneyConfig::CRAWLER) {
                const bool four = s.height > s.standHeight * cfg.crawlHeight * 0.72f && std::fabs(s.up[1]) < 0.62f && s.speed < 1.4f;
                if ((four && getUpProgress() > 0.75f) || getUpProgress() > 1.6f) { outcome = RECOVERED; recoveries++; }
                break;
            }
            if (!legs) { go(DOWN); downFor = 0.3f; break; }
            const bool upright = s.height > s.standHeight * 0.9f && s.tilt < rad(16) && s.feet > 0;
            if (upright && getUpProgress() > 0.7f) { steps = 0; settled = 0; standReset(); go(STAND); break; }
            if (t > cfg.getUpTime + cfg.getUpTimeout) { go(DOWN); downFor = 0.7f; }
            break;
        }
        case DEAD:
            if (limpT >= cfg.deathFade) outcome = DEAD_OUTCOME;
            break;
    }

    goal.clear();
    holding = false;
    goal.strength = stun > 0 && state != DEAD ? 0.4f : 1.0f;
    switch (state) {
        case STAND: holdGoals(); standGoals(dt); break;
        case STEP: stepGoals(dt); standGoals(dt); break;
        case FALL: if (airborne > 0.22f) flailGoals(dt); braceGoals(); fallGoals(); break;
        case DOWN: holdGoals(); lieGoals(); break;
        case GETUP: getUpGoals(dt); break;
        case DEAD: limpGoals(dt); break;
    }
    goal.finish();
}

// ------------------------------------------------------------------ behaviours
void SydneyCharacter::standReset() {
    for (int k = 0; k < 4; k++) { standLeg[k][0] = 0; standLeg[k][1] = -1; standLeg[k][2] = (k & 1) == 0 ? STANCE_HIP : STANCE_KNEE; }
}

void SydneyCharacter::standPlant() {
    static const int LEG_JOINT[4] = {PhysicsRig::J_L_HIP, PhysicsRig::J_L_KNEE, PhysicsRig::J_R_HIP, PhysicsRig::J_R_KNEE};
    const Ragdoll& r = *rig.r;
    float f[9];
    for (int k = 0; k < 4; k++) {
        const int* J = PhysicsRig::JOINTS[LEG_JOINT[k]];
        const float dx = r.x[J[2]] - r.x[J[1]], dy = r.y[J[2]] - r.y[J[1]], dz = r.z[J[2]] - r.z[J[1]], l = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (l < 1e-5f) continue;
        frame(LEG_JOINT[k], f);
        toLocal(f, dx / l, dy / l, dz / l, standLeg[k]);
    }
}

void SydneyCharacter::standGoals(float dt) {
    using R = PhysicsRig;
    goal.stance = true;
    const float ease = std::min(1.0f, dt * (sense.speed < 0.45f ? 4.0f : 1.2f));
    for (int k = 0; k < 4; k++) {
        standLeg[k][0] += (0 - standLeg[k][0]) * ease; standLeg[k][1] += (-1 - standLeg[k][1]) * ease;
        standLeg[k][2] += (((k & 1) == 0 ? STANCE_HIP : STANCE_KNEE) - standLeg[k][2]) * ease;
    }
    const SensorSystem& s = sense;
    const float want = s.feet < 2 || s.speed > 0.75f ? 1 : s.speed > 0.35f ? (s.speed - 0.35f) / 0.4f : 0;
    standEffort = want > standEffort ? want : std::max(want, standEffort - dt * 2.5f);
    const float hip = lerp(cfg.stanceHip, cfg.carryHip, standEffort);
    goal.joint(R::J_L_HIP, standLeg[0][0], standLeg[0][1], standLeg[0][2], s.footL ? hip : 1); goal.joint(R::J_L_KNEE, standLeg[1][0], standLeg[1][1], standLeg[1][2], 1);
    goal.joint(R::J_R_HIP, standLeg[2][0], standLeg[2][1], standLeg[2][2], s.footR ? hip : 1); goal.joint(R::J_R_KNEE, standLeg[3][0], standLeg[3][1], standLeg[3][2], 1);
    goal.joint(R::J_NECK, 0, 1, 0, 0.8f);
    for (int j = R::J_L_SHOULDER; j <= R::J_R_ELBOW; j++) goal.joint(j, pose[j][0], pose[j][1], pose[j][2], 0.6f);
    goal.setPosture(0, 1, 0, lerp(cfg.standPosture, cfg.carryPosture, standEffort), 0);
    goal.setBalance(1);
}

bool SydneyCharacter::stepBegin() {
    const Ragdoll& r = *rig.r;
    const SensorSystem& s = sense;
    const float l = std::max(1e-4f, s.err), dx = s.errX / l, dz = s.errZ / l;
    const float dl = (r.x[LA] - s.supX) * dx + (r.z[LA] - s.supZ) * dz, dr = (r.x[RA] - s.supX) * dx + (r.z[RA] - s.supZ) * dz;
    stepSide = dl <= dr ? 0 : 1;
    if ((stepSide == 0 && !rig.legL) || (stepSide == 1 && !rig.legR)) stepSide = 1 - stepSide;
    if ((stepSide == 0 && !rig.legL) || (stepSide == 1 && !rig.legR)) return false;
    const int a = stepSide == 0 ? LA : RA, other = stepSide == 0 ? RA : LA;
    const float lat = (stepSide == 0 ? -1.0f : 1.0f) * 0.09f * scale;
    const float lead = cfg.stepLead;
    stepTx = s.cpX + (s.cpX - r.x[other]) * lead + dx * cfg.stepOvershoot * scale + s.right[0] * lat;
    stepTz = s.cpZ + (s.cpZ - r.z[other]) * lead + dz * cfg.stepOvershoot * scale + s.right[2] * lat;
    const float ox = stepTx - r.x[other], oz = stepTz - r.z[other], ol = hypot2(ox, oz), max = cfg.stepReach * scale;
    if (ol > max) { stepTx = r.x[other] + ox / ol * max; stepTz = r.z[other] + oz / ol * max; }
    stepSx = r.x[a]; stepSy = r.y[a]; stepSz = r.z[a];
    stepT = 0;
    return true;
}

bool SydneyCharacter::stepDone() const {
    const int a = stepSide == 0 ? LA : RA;
    return stepT >= cfg.stepTime || (stepT > cfg.stepTime * 0.55f && rig.r->grounded[a]);
}

void SydneyCharacter::stepGoals(float dt) {
    using R = PhysicsRig;
    stepT += dt;
    const float k = std::min(1.0f, stepT / cfg.stepTime), e = smooth(k);
    const int hip = stepSide == 0 ? R::J_L_HIP : R::J_R_HIP, knee = stepSide == 0 ? R::J_L_KNEE : R::J_R_KNEE, a = stepSide == 0 ? LA : RA;
    goal.joint(hip, 0, -0.85f, -0.5f, 0.15f);
    goal.joint(knee, 0, -0.9f, 0.4f, 0.15f);
    const float yy = stepSy + std::sin(k * PI) * cfg.stepLift * scale;
    goal.setReach(2 + stepSide, a, PELVIS, lerp(stepSx, stepTx, e), yy, lerp(stepSz, stepTz, e), cfg.stepForce * cfg.strength, 7);
}

void SydneyCharacter::braceGoals() {
    using R = PhysicsRig;
    const float b = cfg.brace;
    if (b <= 0.01f) return;
    const SensorSystem& s = sense;
    float fx = s.comV[0], fz = s.comV[2], l = hypot2(fx, fz);
    if (l < 0.15f) { fx = s.errX; fz = s.errZ; l = hypot2(fx, fz); }
    if (l < 1e-3f) { fx = s.fwd[0]; fz = s.fwd[2]; l = std::max(1e-3f, hypot2(fx, fz)); }
    fx /= l; fz /= l;
    float v[3];
    for (int side = 0; side < 2; side++) {
        const float out = (side == 0 ? -1.0f : 1.0f) * 0.3f;
        local(fx * 0.8f + s.right[0] * out, -0.55f, fz * 0.8f + s.right[2] * out, v);
        goal.joint(side == 0 ? R::J_L_SHOULDER : R::J_R_SHOULDER, v[0], v[1], v[2], b);
        local(fx * 0.9f, -0.35f, fz * 0.9f, v);
        goal.joint(side == 0 ? R::J_L_ELBOW : R::J_R_ELBOW, v[0], v[1], v[2], b * 0.8f);
    }
    local(s.up[0] - fx * 0.6f, s.up[1] + 0.3f, s.up[2] - fz * 0.6f, v);
    goal.joint(R::J_NECK, v[0], v[1], v[2], b);
}

void SydneyCharacter::fallGoals() {
    using R = PhysicsRig;
    const float k = cfg.fallStrength;
    goal.joint(R::J_L_HIP, 0, -0.75f, -0.65f, k); goal.joint(R::J_L_KNEE, 0, -0.8f, 0.6f, k);
    goal.joint(R::J_R_HIP, 0, -0.8f, -0.55f, k); goal.joint(R::J_R_KNEE, 0, -0.75f, 0.65f, k);
    goal.joint(R::J_NECK, 0, 1, -0.2f, k);
    for (int j = R::J_L_SHOULDER; j <= R::J_R_ELBOW; j++) goal.joint(j, pose[j][0], pose[j][1], pose[j][2], k * 0.5f);
    goal.setPosture(0, 1, 0, 0, 0);
    goal.setBalance(0);
}

void SydneyCharacter::flailGoals(float dt) {
    using R = PhysicsRig;
    flailT += dt;
    if (cfg.style != SydneyConfig::HUMAN && cfg.brace < 0.2f) {
        goal.joint(R::J_L_SHOULDER, -0.2f, -0.3f, -0.9f, 0.35f); goal.joint(R::J_R_SHOULDER, 0.2f, -0.3f, -0.9f, 0.35f);
        return;
    }
    const float rate = 7.0f + std::min(5.0f, flailT * 2.2f), ph = flailT * rate, s1 = std::sin(ph), c1 = std::cos(ph), k = 0.55f;
    goal.joint(R::J_L_HIP, 0, -0.82f, -0.55f * s1, k); goal.joint(R::J_L_KNEE, 0, -0.75f, 0.35f + 0.45f * std::max(0.0f, -c1), k);
    goal.joint(R::J_R_HIP, 0, -0.82f, 0.55f * s1, k); goal.joint(R::J_R_KNEE, 0, -0.75f, 0.35f + 0.45f * std::max(0.0f, c1), k);
    const float a1 = ph * 0.8f, a2 = ph * 0.8f + 2.1f;
    goal.joint(R::J_L_SHOULDER, -0.75f, 0.15f + 0.6f * std::sin(a1), -0.5f * std::cos(a1), k);
    goal.joint(R::J_R_SHOULDER, 0.75f, 0.15f + 0.6f * std::sin(a2), -0.5f * std::cos(a2), k);
    goal.joint(R::J_L_ELBOW, -0.4f, -0.3f + 0.4f * std::sin(a1 + 1), -0.7f, k * 0.8f);
    goal.joint(R::J_R_ELBOW, 0.4f, -0.3f + 0.4f * std::sin(a2 + 1), -0.7f, k * 0.8f);
}

void SydneyCharacter::lieGoals() {
    using R = PhysicsRig;
    const float k = cfg.downStrength * lieTone;
    goal.joint(R::J_L_HIP, 0, -1, -0.1f, k); goal.joint(R::J_L_KNEE, 0, -1, 0.15f, k);
    goal.joint(R::J_R_HIP, 0, -1, -0.1f, k); goal.joint(R::J_R_KNEE, 0, -1, 0.15f, k);
    goal.joint(R::J_NECK, 0, 1, 0, k);
    goal.joint(R::J_L_SHOULDER, -0.35f, -0.9f, 0, k); goal.joint(R::J_L_ELBOW, -0.2f, -0.9f, -0.3f, k);
    goal.joint(R::J_R_SHOULDER, 0.35f, -0.9f, 0, k); goal.joint(R::J_R_ELBOW, 0.2f, -0.9f, -0.3f, k);
    goal.setPosture(0, 1, 0, 0, 0);
    goal.setBalance(0);
}

void SydneyCharacter::holdGoals() {
    using R = PhysicsRig;
    if (!cfg.holdWound || !alive || wound < 0) return;
    const int w = wound;
    const bool leftSide = w == LS || w == LE || w == LW || w == LH || w == LK || w == LA;
    const bool onLeftArm = w == LS || w == LE || w == LW, onRightArm = w == RS || w == RE || w == RW;
    int arm = onLeftArm ? 1 : onRightArm ? 0 : leftSide ? 0 : 1;
    if ((arm == 0 && !rig.armL) || (arm == 1 && !rig.armR)) arm = 1 - arm;
    if ((arm == 0 && (!rig.armL || onLeftArm)) || (arm == 1 && (!rig.armR || onRightArm))) return;
    if (!goal.reachFree(arm) || !goal.free(arm == 0 ? R::J_L_SHOULDER : R::J_R_SHOULDER)) return;
    const Ragdoll& r = *rig.r;
    goal.joint(arm == 0 ? R::J_L_SHOULDER : R::J_R_SHOULDER, 0, -1, 0, 0);
    goal.joint(arm == 0 ? R::J_L_ELBOW : R::J_R_ELBOW, 0, -1, 0, 0);
    goal.setReach(arm, arm == 0 ? LW : RW, NECK, r.x[w], r.y[w], r.z[w], cfg.holdForce * cfg.strength, 3);
    holding = true;
}

void SydneyCharacter::getUpBegin() {
    getUpT = 0; getUpH0 = sense.height;
    for (int i = 0; i < 3; i++) getUpUp0[i] = sense.up[i];
}

void SydneyCharacter::scramble(float p) {
    using R = PhysicsRig;
    const SensorSystem& s = sense;
    const Ragdoll& r = *rig.r;
    const float e = smooth(p / 0.7f), sc = scale;
    float ax = r.x[NECK] - r.x[PELVIS], az = r.z[NECK] - r.z[PELVIS], al = hypot2(ax, az);
    if (al < 0.05f * sc) { ax = getUpUp0[0]; az = getUpUp0[2]; al = hypot2(ax, az); }
    if (al < 1e-3f) { ax = 0; az = -1; al = 1; }
    ax /= al; az /= al;
    const float sx = -az, sz = ax;
    goal.stance = true;
    goal.setPosture(ax * 0.95f, 0.30f, az * 0.95f, 1, 1);
    goal.setBalance(0);
    goal.setLift(0.4f + 0.6f * e, s.standHeight * cfg.crawlHeight);
    const float lg = 0.55f + 0.4f * e;
    goal.joint(R::J_L_SHOULDER, -0.80f, -0.35f, 0.50f, lg); goal.joint(R::J_L_ELBOW, -0.25f, -0.55f, 0.80f, lg);
    goal.joint(R::J_R_SHOULDER, 0.80f, -0.35f, 0.50f, lg); goal.joint(R::J_R_ELBOW, 0.25f, -0.55f, 0.80f, lg);
    goal.joint(R::J_L_HIP, -0.75f, -0.45f, 0.50f, lg); goal.joint(R::J_L_KNEE, -0.2f, -0.6f, 0.78f, lg);
    goal.joint(R::J_R_HIP, 0.75f, -0.45f, 0.50f, lg); goal.joint(R::J_R_KNEE, 0.2f, -0.6f, 0.78f, lg);
    goal.joint(R::J_NECK, 0, 0.75f, -0.65f, 0.9f);
    const float gy = s.groundY + 0.06f * sc, pull = 460.0f * cfg.strength;
    if (rig.armL) goal.setReach(0, LW, NECK, r.x[NECK] + ax * 0.22f * sc - sx * 0.40f * sc, gy, r.z[NECK] + az * 0.22f * sc - sz * 0.40f * sc, pull, 5);
    if (rig.armR) goal.setReach(1, RW, NECK, r.x[NECK] + ax * 0.22f * sc + sx * 0.40f * sc, gy, r.z[NECK] + az * 0.22f * sc + sz * 0.40f * sc, pull, 5);
    if (rig.legL) goal.setReach(2, LA, PELVIS, r.x[PELVIS] - ax * 0.34f * sc - sx * 0.38f * sc, gy, r.z[PELVIS] - az * 0.34f * sc - sz * 0.38f * sc, pull, 5);
    if (rig.legR) goal.setReach(3, RA, PELVIS, r.x[PELVIS] - ax * 0.34f * sc + sx * 0.38f * sc, gy, r.z[PELVIS] - az * 0.34f * sc + sz * 0.38f * sc, pull, 5);
}

void SydneyCharacter::getUpGoals(float dt) {
    using R = PhysicsRig;
    getUpT += dt;
    const SensorSystem& s = sense;
    const Ragdoll& r = *rig.r;
    const float p = std::min(1.0f, getUpProgress()), e = smooth((p - 0.25f) / 0.75f), tuck = smooth(p / 0.3f);
    if (cfg.style == SydneyConfig::CRAWLER) { scramble(p); return; }
    const bool dead = cfg.style == SydneyConfig::INFECTED;
    goal.stance = true;
    const float hy = lerp(-0.35f, -1, e), hz = lerp(-0.94f, STANCE_HIP, e), ky = lerp(-0.9f, -1, e), kz = lerp(0.45f, STANCE_KNEE, e), lg = 0.5f + 0.5f * tuck;
    goal.joint(R::J_L_HIP, 0, hy, hz, lg); goal.joint(R::J_L_KNEE, 0, ky, kz, lg);
    goal.joint(R::J_R_HIP, 0, hy, hz, lg); goal.joint(R::J_R_KNEE, 0, ky, kz, lg);
    if (dead) {
        const float late = smooth((p - 0.72f) / 0.22f), heave = smooth((p - 0.34f) / 0.26f) * 0.55f + smooth((p - 0.74f) / 0.2f) * 0.45f;
        goal.joint(R::J_NECK, 0, lerp(0.30f, 1, late), lerp(-0.95f, -0.15f, late), 0.7f);
        const float ag = 0.10f + 0.45f * late, az2 = lerp(0.30f, -0.55f, late), ay = lerp(-0.95f, -0.75f, late);
        goal.joint(R::J_L_SHOULDER, -0.12f, ay, az2, ag); goal.joint(R::J_L_ELBOW, -0.05f, -0.95f, lerp(0.1f, -0.35f, late), ag);
        goal.joint(R::J_R_SHOULDER, 0.12f, ay, az2, ag); goal.joint(R::J_R_ELBOW, 0.05f, -0.95f, lerp(0.1f, -0.35f, late), ag);
        float fx = s.fwd[0], fz = s.fwd[2], fl = hypot2(fx, fz);
        if (fl < 0.2f) { fx = getUpUp0[0]; fz = getUpUp0[2]; fl = std::max(1e-3f, hypot2(fx, fz)); }
        fx /= fl; fz /= fl;
        const float bend = 0.85f * (1 - heave);
        const float k0 = smooth(p / 0.35f);
        const float tx = lerp(getUpUp0[0], fx * bend, k0), ty = lerp(getUpUp0[1], 1 - bend * 0.45f, k0), tz = lerp(getUpUp0[2], fz * bend, k0);
        const float tl = std::max(1e-4f, std::sqrt(tx * tx + ty * ty + tz * tz));
        goal.setPosture(tx / tl, ty / tl, tz / tl, 1, 0.8f);
    } else {
        goal.joint(R::J_NECK, 0, 1, 0, 0.8f);
        const float ag = 0.5f;
        goal.joint(R::J_L_SHOULDER, -0.25f, -0.9f, lerp(-0.45f, 0, e), ag); goal.joint(R::J_L_ELBOW, -0.1f, -0.95f, lerp(-0.3f, 0, e), ag);
        goal.joint(R::J_R_SHOULDER, 0.25f, -0.9f, lerp(-0.45f, 0, e), ag); goal.joint(R::J_R_ELBOW, 0.1f, -0.95f, lerp(-0.3f, 0, e), ag);
    }
    const float k = smooth(p / 0.6f);
    const float ux = lerp(getUpUp0[0], 0, k), uy = lerp(getUpUp0[1], 1, k), uz = lerp(getUpUp0[2], 0, k), ul = std::max(1e-4f, std::sqrt(ux * ux + uy * uy + uz * uz));
    goal.setPosture(ux / ul, uy / ul, uz / ul, 1, 0.8f);
    goal.setBalance(0.3f + 0.7f * e);
    goal.setLift(tuck, lerp(std::max(getUpH0, s.standHeight * 0.42f), s.standHeight, e));
    float rx = s.right[0], rz = s.right[2], rl = hypot2(rx, rz);
    if (rl < 0.2f) { rx = s.fwd[2]; rz = -s.fwd[0]; rl = std::max(1e-3f, hypot2(rx, rz)); }
    rx /= rl; rz /= rl;
    const float fy = s.groundY + 0.08f * scale, half = 0.11f * scale, pull = 520.0f * cfg.strength * (1 - 0.6f * e);
    const float bx = lerp(r.x[PELVIS], s.com[0], 0.5f), bz = lerp(r.z[PELVIS], s.com[2], 0.5f);
    if (rig.legL) goal.setReach(2, LA, PELVIS, bx - rx * half, fy, bz - rz * half, pull, 4);
    if (rig.legR) goal.setReach(3, RA, PELVIS, bx + rx * half, fy, bz + rz * half, pull, 4);
}

void SydneyCharacter::limpGoals(float dt) {
    using R = PhysicsRig;
    limpT += dt;
    const float fade = std::max(0.05f, cfg.deathFade), u = limpT / fade;
    const float legs = std::max(0.0f, 1 - u * 3) * 0.35f, trunk = std::max(0.0f, 1 - u) * 0.35f;
    goal.joint(R::J_L_HIP, 0, -1, 0, legs); goal.joint(R::J_L_KNEE, 0, -1, 0.2f, legs);
    goal.joint(R::J_R_HIP, 0, -1, 0, legs); goal.joint(R::J_R_KNEE, 0, -1, 0.2f, legs);
    goal.joint(R::J_NECK, 0, 1, 0, trunk);
    for (int j = R::J_L_SHOULDER; j <= R::J_R_ELBOW; j++) goal.joint(j, pose[j][0], pose[j][1], pose[j][2], trunk * 0.6f);
    goal.setPosture(0, 1, 0, trunk * cfg.deathPosture, 0);
    goal.setBalance(0);
}

// ------------------------------------------------------------------ the muscles
void SydneyCharacter::motors() {
    Ragdoll& r = *rig.r;
    const SensorSystem& s = sense;
    float fr[9];
    for (int j = 0; j < PhysicsRig::NJ; j++) {
        const float gain = goal.gain[j];
        if (gain <= 0) continue;
        const int* J = PhysicsRig::JOINTS[j];
        if (!rig.limbWorks(J[4])) continue;
        if (j == PhysicsRig::J_NECK && !rig.head) continue;
        const int p = J[0], jt = J[1], c = J[2], cls = J[3];
        const float cx = r.x[c] - r.x[jt], cy = r.y[c] - r.y[jt], cz = r.z[c] - r.z[jt];
        const float len = std::sqrt(cx * cx + cy * cy + cz * cz);
        if (len < 1e-5f) continue;
        const float ux = cx / len, uy = cy / len, uz = cz / len;
        const float* want = goal.dir[j];
        frame(j, fr);
        float wx = fr[0] * want[0] + fr[3] * want[1] - fr[6] * want[2], wy = fr[1] * want[0] + fr[4] * want[1] - fr[7] * want[2], wz = fr[2] * want[0] + fr[5] * want[1] - fr[8] * want[2];
        const float wl = std::sqrt(wx * wx + wy * wy + wz * wz);
        if (wl < 1e-5f) continue;
        wx /= wl; wy /= wl; wz /= wl;
        float ex = uy * wz - uz * wy, ey = uz * wx - ux * wz, ez = ux * wy - uy * wx;
        const float sn = std::sqrt(ex * ex + ey * ey + ez * ez), cs = ux * wx + uy * wy + uz * wz;
        const float ang = std::atan2(sn, cs);
        if (sn < 1e-5f) {
            if (cs > 0) { ex = ey = ez = 0; }
            else { ex = s.right[0]; ey = s.right[1]; ez = s.right[2]; }
        } else { ex /= sn; ey /= sn; ez /= sn; }
        const float vx = r.vx(c) - r.vx(jt), vy = r.vy(c) - r.vy(jt), vz = r.vz(c) - r.vz(jt);
        const float ox = (cy * vz - cz * vy) / (len * len), oy = (cz * vx - cx * vz) / (len * len), oz = (cx * vy - cy * vx) / (len * len);
        const bool stance = ((J[4] == 3 && s.footL) || (J[4] == 4 && s.footR)) && goal.stance;
        const float inertia = rig.carried(j) * len * len;
        const float w0 = 2 * PI * cfg.freq[cls];
        const float k = inertia * w0 * w0 * gain * (stance ? cfg.stanceGain : 1.0f), dmp = 2 * cfg.zeta[cls] * inertia * w0 * std::sqrt(gain) * (stance ? 1.25f : 1.0f);
        float tx = ex * ang * k - ox * dmp, ty = ey * ang * k - oy * dmp, tz = ez * ang * k - oz * dmp;
        const float along = tx * ux + ty * uy + tz * uz;
        tx -= ux * along; ty -= uy * along; tz -= uz * along;
        const float mag = std::sqrt(tx * tx + ty * ty + tz * tz), max = cfg.torque[cls] * cfg.strength * rig.massScale * std::min(1.0f, gain * 1.5f) * goal.strength;
        if (mag > max) { const float f = max / mag; tx *= f; ty *= f; tz *= f; }
        rig.torque(p, jt, c, tx, ty, tz);
    }
    posture();
    balance();
    lift();
    for (const auto& e : goal.reach) if (e.on) reachFor(e);
}

void SydneyCharacter::posture() {
    if (goal.posture <= 0) return;
    const SensorSystem& s = sense;
    const float support = s.feet > 0 ? 1.0f : s.otherContacts > 0 ? goal.postureOnGround : 0.0f;
    if (support <= 0) return;
    const Ragdoll& r = *rig.r;
    const float* u = s.up;
    const float* want = goal.up;
    float ex = u[1] * want[2] - u[2] * want[1], ey = u[2] * want[0] - u[0] * want[2], ez = u[0] * want[1] - u[1] * want[0];
    const float sn = std::sqrt(ex * ex + ey * ey + ez * ez), cs = u[0] * want[0] + u[1] * want[1] + u[2] * want[2];
    const float ang = std::atan2(sn, cs);
    if (sn < 1e-5f) { if (cs > 0) return; ex = s.right[0]; ey = s.right[1]; ez = s.right[2]; } else { ex /= sn; ey /= sn; ez /= sn; }
    const float cx = r.x[NECK] - r.x[PELVIS], cy = r.y[NECK] - r.y[PELVIS], cz = r.z[NECK] - r.z[PELVIS], l2 = cx * cx + cy * cy + cz * cz;
    if (l2 < 1e-6f) return;
    const float vx = r.vx(NECK) - r.vx(PELVIS), vy = r.vy(NECK) - r.vy(PELVIS), vz = r.vz(NECK) - r.vz(PELVIS);
    const float ox = (cy * vz - cz * vy) / l2, oy = (cz * vx - cx * vz) / l2, oz = (cx * vy - cy * vx) / l2;
    const float inertia = (rig.mass(NECK) + rig.mass(HEAD_P) + rig.mass(LS) + rig.mass(RS)) * l2;
    const float w0 = 2 * PI * cfg.freq[SydneyConfig::SPINE];
    const float k = inertia * w0 * w0 * goal.posture, d = 2 * cfg.zeta[SydneyConfig::SPINE] * inertia * w0;
    float tx = ex * ang * k - ox * d, ty = ey * ang * k - oy * d, tz = ez * ang * k - oz * d;
    const float mag = std::sqrt(tx * tx + ty * ty + tz * tz), max = cfg.torque[SydneyConfig::SPINE] * cfg.strength * rig.massScale * support * goal.strength;
    if (mag > max) { const float f = max / mag; tx *= f; ty *= f; tz *= f; }
    const float fx = (ty * cz - tz * cy) / l2, fy = (tz * cx - tx * cz) / l2, fz = (tx * cy - ty * cx) / l2;
    rig.force(NECK, fx, fy, fz);
    if (s.feet > 0) { const float sh = 1.0f / static_cast<float>(s.feet); if (s.footL) rig.force(LH, -fx * sh, -fy * sh, -fz * sh); if (s.footR) rig.force(RH, -fx * sh, -fy * sh, -fz * sh); }
    else rig.force(PELVIS, -fx, -fy, -fz);
}

void SydneyCharacter::balance() {
    const SensorSystem& s = sense;
    if (goal.balance <= 0 || s.feet == 0) return;
    float px = s.errX * (1 + cfg.balanceGain), pz = s.errZ * (1 + cfg.balanceGain);
    const float l = hypot2(px, pz), max = s.reach(cfg, px, pz);
    if (l > max) { px *= max / l; pz *= max / l; }
    const float f = rig.totalMass * s.omega * s.omega * goal.balance * goal.strength;
    const float fx = -px * f, fz = -pz * f;
    rig.bodyForce(fx, 0, fz);
    const float sh = 1.0f / static_cast<float>(s.feet);
    if (s.footL) rig.force(LA, -fx * sh, 0, -fz * sh);
    if (s.footR) rig.force(RA, -fx * sh, 0, -fz * sh);
}

void SydneyCharacter::lift() {
    if (goal.lift <= 0) return;
    const Ragdoll& r = *rig.r;
    auto works = [this](int i) {
        switch (i) { case LA: case LK: return rig.legL; case RA: case RK: return rig.legR; case LW: return rig.armL; case RW: return rig.armR; default: return true; }
    };
    static const int sup[6] = {LA, RA, LK, RK, LW, RW};
    int n = 0;
    for (int i : sup) if (r.grounded[i] && works(i)) n++;
    if (n == 0) return;
    const float weight = rig.totalMass * rig.r->gravity;
    const float want = rig.totalMass * (60.0f * (goal.height - sense.height) - 14.0f * sense.comV[1]) + weight;
    const float f = std::max(0.0f, std::min(want, weight * cfg.getUpStrength * cfg.strength * goal.lift));
    static const int trunk[6] = {PELVIS, NECK, LS, RS, LH, RH};
    float m = 0;
    for (int i : trunk) m += MASS[i];
    for (int i : trunk) rig.force(i, 0, f * MASS[i] / m, 0);
    for (int i : sup) if (r.grounded[i] && works(i)) rig.force(i, 0, -f / static_cast<float>(n), 0);
}

void SydneyCharacter::reachFor(const Goal::Reach& e) {
    const Ragdoll& r = *rig.r;
    const int i = e.particle;
    const float m = rig.mass(i) * 2.5f, w0 = 2 * PI * e.hz;
    float fx = m * (w0 * w0 * (e.x - r.x[i]) - 2 * w0 * r.vx(i)), fy = m * (w0 * w0 * (e.y - r.y[i]) - 2 * w0 * r.vy(i)), fz = m * (w0 * w0 * (e.z - r.z[i]) - 2 * w0 * r.vz(i));
    const float mag = std::sqrt(fx * fx + fy * fy + fz * fz), max = e.force * rig.massScale;
    if (mag > max) { const float k = max / mag; fx *= k; fy *= k; fz *= k; }
    rig.force(i, fx, fy, fz);
    rig.force(e.against, -fx, -fy, -fz);
}

}  // namespace astratto
