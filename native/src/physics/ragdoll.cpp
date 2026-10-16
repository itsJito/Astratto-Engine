// Astratto Engine - the ragdoll solver. A port of the game's character.Ragdoll, rule for rule.
#include "physics/ragdoll.h"

#include <algorithm>
#include <cmath>

namespace astratto {

using namespace rd;

namespace rd {
const float MASS[N] = {5, 18, 14, 3, 2, 1.2f, 3, 2, 1.2f, 7, 4.5f, 3, 7, 4.5f, 3};
}

namespace {

const float RADIUS[N] = {0.20f, 0.14f, 0.16f, 0.12f, 0.08f, 0.07f, 0.12f, 0.08f, 0.07f, 0.13f, 0.09f, 0.08f, 0.13f, 0.09f, 0.08f};
const float REST[N][3] = {
    {0, 29, 0}, {0, 24, 0}, {0, 12, 0}, {-6, 23, 0}, {-6, 18, 0}, {-6, 13, 0}, {6, 23, 0}, {6, 18, 0}, {6, 13, 0},
    {-2, 12, 0}, {-2, 6, 0}, {-2, 0, 0}, {2, 12, 0}, {2, 6, 0}, {2, 0, 0}};
const int STICKS[][2] = {
    {HEAD_P, NECK}, {NECK, PELVIS}, {NECK, LS}, {NECK, RS}, {LS, RS}, {PELVIS, LH}, {PELVIS, RH}, {LH, RH},
    {LS, LH}, {RS, RH}, {LS, RH}, {RS, LH}, {LS, PELVIS}, {RS, PELVIS}, {NECK, LH}, {NECK, RH},
    {LS, LE}, {LE, LW}, {RS, RE}, {RE, RW}, {LH, LK}, {LK, LA}, {RH, RK}, {RK, RA}};
constexpr int NSTICKS = sizeof(STICKS) / sizeof(STICKS[0]);
const float LIMITS[][4] = {
    {HEAD_P, LS, 6.6f, 9.6f}, {HEAD_P, RS, 6.6f, 9.6f},
    {LS, LW, 3.6f, 9.85f}, {RS, RW, 3.6f, 9.85f},
    {LH, LA, 5.2f, 11.8f}, {RH, RA, 5.2f, 11.8f},
    {LK, LS, 7.4f, 99}, {RK, RS, 7.4f, 99},
    {LE, RS, 8.2f, 99}, {RE, LS, 8.2f, 99},
    {LE, PELVIS, 6.0f, 99}, {RE, PELVIS, 6.0f, 99},
    {LK, RK, 2.6f, 14}, {LA, RA, 2.2f, 99}, {LW, PELVIS, 3.5f, 99}, {RW, PELVIS, 3.5f, 99}, {HEAD_P, PELVIS, 15.5f, 99}};
constexpr int NLIMITS = sizeof(LIMITS) / sizeof(LIMITS[0]);

// the tuning the Java solver exposes as statics
constexpr float SELF_PUSH = 0.5f, SELF_OVERLAP = 0.78f;
constexpr float AIR = 0.9965f, SLIDE = 0.85f, ROLL = 0.985f, FAST_FROM = 2.0f, FAST_FULL = 4.2f;

struct Linked {
    bool m[N][N] = {};
    Linked() {
        for (const auto& s : STICKS) { m[s[0]][s[1]] = true; m[s[1]][s[0]] = true; }
        for (const auto& l : LIMITS) { m[static_cast<int>(l[0])][static_cast<int>(l[1])] = true; m[static_cast<int>(l[1])][static_cast<int>(l[0])] = true; }
    }
};
const Linked LINKED;

inline float dist3(const float* a, const float* b) { const float dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2]; return std::sqrt(dx * dx + dy * dy + dz * dz); }
inline int fl(float v) { return static_cast<int>(std::floor(v)); }
inline bool solid(const VoxelWorld* w, int x, int y, int z) { return w->solid(x, y, z); }

}  // namespace

Ragdoll::Ragdoll(float scale) {
    unit = VOX * scale;
    for (int i = 0; i < NSTICKS; i++) rest_[i] = dist3(REST[STICKS[i][0]], REST[STICKS[i][1]]);
    for (int i = 0; i < N; i++) {
        invMass[i] = 1.0f / (MASS[i] * scale * scale * scale);
        x[i] = y[i] = z[i] = px[i] = py[i] = pz[i] = tx_[i] = ty_[i] = tz_[i] = 0;
    }
}

void Ragdoll::fromPose(const float* p, float vx, float vy, float vz) {
    for (int i = 0; i < N; i++) {
        x[i] = p[i * 3]; y[i] = p[i * 3 + 1]; z[i] = p[i * 3 + 2];
        tx_[i] = x[i]; ty_[i] = y[i]; tz_[i] = z[i];
        px[i] = x[i] - vx * STEP; py[i] = y[i] - vy * STEP; pz[i] = z[i] - vz * STEP;
    }
    age = 0; quiet = 0; settled = false; acc_ = 0;
}

void Ragdoll::standAt(float wx, float wy, float wz, float yaw) {
    const float c = std::cos(yaw), sn = std::sin(yaw), s = unit / VOX;
    for (int i = 0; i < N; i++) {
        const bool knee = i == LK || i == RK, foot = i == LA || i == RA;
        const float lx = REST[i][0] * unit, lz = (REST[i][2] - (knee ? 1.25f : 0)) * unit, ly = (REST[i][1] - (foot ? 0 : knee ? 0.132f : 0.264f)) * unit;
        x[i] = wx + lx * c + lz * sn; z[i] = wz - lx * sn + lz * c; y[i] = wy + ly + 0.08f * s;
        px[i] = tx_[i] = x[i]; py[i] = ty_[i] = y[i]; pz[i] = tz_[i] = z[i];
    }
    age = BLEND_TIME; quiet = 0; settled = false; acc_ = 0;
}

void Ragdoll::shift(float dx, float dy, float dz) {
    for (int i = 0; i < N; i++) { x[i] += dx; y[i] += dy; z[i] += dz; px[i] += dx; py[i] += dy; pz[i] += dz; tx_[i] += dx; ty_[i] += dy; tz_[i] += dz; }
}

float Ragdoll::unbury(const VoxelWorld* w) {
    if (w == nullptr) return 0;
    float lift = 0;
    const float s = unit / VOX;
    for (int i = 0; i < N; i++) {
        const float rr = RADIUS[i] * s * 0.6f;
        const int bx = fl(x[i]), by = fl(y[i] - rr), bz = fl(z[i]);
        if (!solid(w, bx, by, bz)) continue;
        int top = by;
        if (solid(w, bx, top + 1, bz)) top++;
        if (solid(w, bx, top + 1, bz)) continue;
        lift = std::max(lift, static_cast<float>(top + 1) + rr + 0.01f - y[i]);
    }
    lift = std::min(lift, 1.35f);
    if (lift > 0) shift(0, lift, 0);
    return lift;
}

void Ragdoll::impulse(int region, float vx, float vy, float vz) {
    static const int head[] = {HEAD_P, NECK}, arms[] = {LS, RS, LE, RE}, legs[] = {LK, RK, LH, RH}, torso[] = {NECK, PELVIS, LS, RS};
    const int* hit = torso;
    int n = 4;
    if (region == ASTRATTO_REGION_HEAD) { hit = head; n = 2; }
    else if (region == ASTRATTO_REGION_ARMS) hit = arms;
    else if (region == ASTRATTO_REGION_LEGS) hit = legs;
    for (int i = 0; i < N; i++) { px[i] -= vx * 0.35f * STEP; py[i] -= vy * 0.35f * STEP; pz[i] -= vz * 0.35f * STEP; }
    for (int k = 0; k < n; k++) { const int i = hit[k]; px[i] -= vx * 0.65f * STEP; py[i] -= vy * 0.65f * STEP; pz[i] -= vz * 0.65f * STEP; }
    if (settled) wake();
}

void Ragdoll::carry(float vx, float vy, float vz, float share) {
    for (int i = 0; i < N; i++) {
        const float cx = (x[i] - px[i]) / STEP, cy = (y[i] - py[i]) / STEP, cz = (z[i] - pz[i]) / STEP;
        px[i] = x[i] - (cx + (vx - cx) * share) * STEP;
        py[i] = y[i] - (cy + std::max(0.0f, vy - cy) * share) * STEP;
        pz[i] = z[i] - (cz + (vz - cz) * share) * STEP;
    }
    age = std::min(age, 0.6f); quiet = 0; settled = false;
}

void Ragdoll::wake() { settled = false; quiet = 0; age = std::max(BLEND_TIME, std::min(age, 1.2f)); }

int Ragdoll::disturb(float wx, float wy, float wz, float radius, float vx, float vy, float vz) {
    int n = 0;
    float mean = 0;
    for (int i = 0; i < N; i++) mean += invMass[i] / N;
    for (int i = 0; i < N; i++) {
        const float dx = x[i] - wx, dy = y[i] - wy, dz = z[i] - wz, dd = std::sqrt(dx * dx + dy * dy + dz * dz);
        if (dd > radius) continue;
        const float k = (1 - dd / radius) * std::max(0.6f, std::min(2.2f, invMass[i] / mean));
        kick(i, vx * k, vy * k, vz * k);
        n++;
    }
    if (n > 0) wake();
    return n;
}

int Ragdoll::update(const VoxelWorld* w, float dt) {
    if (settled) return 0;
    acc_ += std::min(dt, 0.1f);
    int n = 0;
    while (acc_ >= STEP && !settled) { acc_ -= STEP; step(w); n++; }
    return n;
}

void Ragdoll::selfCollide() {
    const float s = unit / VOX;
    for (int a = 0; a < N; a++) for (int b = a + 1; b < N; b++) {
        if (LINKED.m[a][b]) continue;
        const float min = (RADIUS[a] + RADIUS[b]) * s * SELF_OVERLAP;
        float dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a];
        const float l2 = dx * dx + dy * dy + dz * dz;
        if (l2 >= min * min) continue;
        float l = std::sqrt(l2);
        if (l < 1e-4f) { dx = 1e-3f * static_cast<float>(a - b); dy = 1e-3f; l = 1.5e-3f; }
        const float k = (l - min) / l * SELF_PUSH, wa = invMass[a] / (invMass[a] + invMass[b]), wb = 1 - wa;
        x[a] += dx * k * wa; y[a] += dy * k * wa; z[a] += dz * k * wa;
        x[b] -= dx * k * wb; y[b] -= dy * k * wb; z[b] -= dz * k * wb;
        selfContacts++;
    }
}

void Ragdoll::step(const VoxelWorld* w) {
    age += STEP;
    float moved = 0;
    if (driver != nullptr) driver->drive(*this, w, STEP);
    const float g = gravity;
    for (int i = 0; i < N; i++) {
        float vx = x[i] - px[i], vy = y[i] - py[i], vz = z[i] - pz[i];
        const float speed = std::sqrt(vx * vx + vy * vy + vz * vz) / STEP, fast = std::max(0.0f, std::min(1.0f, (speed - FAST_FROM) / (FAST_FULL - FAST_FROM)));
        const float air = 0.985f + (AIR - 0.985f) * fast;
        vx *= air; vy *= air; vz *= air;
        if (grounded[i]) {
            const float sp = std::sqrt(vx * vx + vz * vz), lose = SLIDE * g * STEP * STEP;
            const float slide = sp <= lose ? 0 : (sp - lose) / sp * ROLL, keep = 0.72f + (slide - 0.72f) * fast;
            vx *= keep; vz *= keep;
        }
        px[i] = x[i]; py[i] = y[i]; pz[i] = z[i];
        const float k = invMass[i] * STEP * STEP;
        x[i] += vx + fx[i] * k; y[i] += vy - g * STEP * STEP + fy[i] * k; z[i] += vz + fz[i] * k;
        fx[i] = fy[i] = fz[i] = 0;
        load_[i] = 0;
    }
    if (age < BLEND_TIME) {
        const float k = (1 - age / BLEND_TIME) * 0.35f;
        for (int i = 0; i < N; i++) { tx_[i] += (x[i] - px[i]) * 0.6f; tz_[i] += (z[i] - pz[i]) * 0.6f; x[i] += (tx_[i] - x[i]) * k; y[i] += (ty_[i] - y[i]) * k; z[i] += (tz_[i] - z[i]) * k; }
    }
    for (int it = 0; it < 8; it++) {
        for (int s = 0; s < NSTICKS; s++) pull(STICKS[s][0], STICKS[s][1], rest_[s] * unit, rest_[s] * unit);
        for (const auto& l : LIMITS) pull(static_cast<int>(l[0]), static_cast<int>(l[1]), l[2] * unit, l[3] * unit);
        hinges();
        if ((it & 1) == 1) selfCollide();
        for (int i = 0; i < N; i++) collide(w, i);
    }
    maxStretch = 0;
    for (int s = 0; s < NSTICKS; s++) maxStretch = std::max(maxStretch, std::fabs(d(STICKS[s][0], STICKS[s][1]) / unit - rest_[s]));
    for (int i = 0; i < N; i++) moved = std::max(moved, std::fabs(x[i] - px[i]) + std::fabs(y[i] - py[i]) + std::fabs(z[i] - pz[i]));
    quiet = moved / STEP < 0.22f ? quiet + STEP : 0;
    if (!live && (age >= ACTIVE_TIME * 3 || (quiet > 0.7f && age > 0.5f))) settle();
}

void Ragdoll::settle() {
    settled = true;
    for (int i = 0; i < N; i++) { px[i] = x[i]; py[i] = y[i]; pz[i] = z[i]; }
}

float Ragdoll::d(int a, int b) const { const float dx = x[a] - x[b], dy = y[a] - y[b], dz = z[a] - z[b]; return std::sqrt(dx * dx + dy * dy + dz * dz); }

void Ragdoll::pull(int a, int b, float min, float max) {
    float dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a];
    float l = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (l < 1e-5f) { dy = 1e-3f; l = 1e-3f; }
    const float want = l < min ? min : l > max ? max : l;
    if (want == l) return;
    const float k = (l - want) / l, wa = invMass[a] / (invMass[a] + invMass[b]), wb = 1 - wa;
    x[a] += dx * k * wa; y[a] += dy * k * wa; z[a] += dz * k * wa;
    x[b] -= dx * k * wb; y[b] -= dy * k * wb; z[b] -= dz * k * wb;
}

void Ragdoll::forward() {
    const float rx = x[RS] - x[LS], ry = y[RS] - y[LS], rz = z[RS] - z[LS];
    const float ux = x[NECK] - x[PELVIS], uy = y[NECK] - y[PELVIS], uz = z[NECK] - z[PELVIS];
    fwd[0] = uy * rz - uz * ry; fwd[1] = uz * rx - ux * rz; fwd[2] = ux * ry - uy * rx;
    const float l = std::sqrt(fwd[0] * fwd[0] + fwd[1] * fwd[1] + fwd[2] * fwd[2]);
    if (l < 1e-6f) { fwd[0] = 0; fwd[1] = 0; fwd[2] = -1; return; }
    fwd[0] /= l; fwd[1] /= l; fwd[2] /= l;
}

float Ragdoll::yaw() { forward(); return std::atan2(-fwd[0], -fwd[2]); }

void Ragdoll::hinges() {
    forward();
    maxHyper = 0;
    hinge(LH, LK, LA, 1); hinge(RH, RK, RA, 1);
    hinge(LS, LE, LW, -1); hinge(RS, RE, RW, -1);
    const int legs[2][2] = {{LH, LK}, {RH, RK}};
    for (const auto& leg : legs) {
        const float back = -((x[leg[1]] - x[leg[0]]) * fwd[0] + (y[leg[1]] - y[leg[0]]) * fwd[1] + (z[leg[1]] - z[leg[0]]) * fwd[2]) - THIGH * unit * 0.55f;
        if (back > 0) { x[leg[1]] += fwd[0] * back; y[leg[1]] += fwd[1] * back; z[leg[1]] += fwd[2] * back; }
    }
}

void Ragdoll::hinge(int a, int mid, int b, float sign) {
    const float mx = (x[a] + x[b]) * 0.5f, my = (y[a] + y[b]) * 0.5f, mz = (z[a] + z[b]) * 0.5f;
    const float side = ((x[mid] - mx) * fwd[0] + (y[mid] - my) * fwd[1] + (z[mid] - mz) * fwd[2]) * sign;
    const float slack = 0.4f * unit;
    if (side >= -slack) return;
    const float push = (-slack - side) * sign;
    maxHyper = std::max(maxHyper, (-slack - side) / unit);
    x[mid] += fwd[0] * push * 0.66f; y[mid] += fwd[1] * push * 0.66f; z[mid] += fwd[2] * push * 0.66f;
    const int ends[2] = {a, b};
    for (int e : ends) { x[e] -= fwd[0] * push * 0.17f; y[e] -= fwd[1] * push * 0.17f; z[e] -= fwd[2] * push * 0.17f; }
}

void Ragdoll::friction(int i) {
    const float sx = x[i] - px[i], sz = z[i] - pz[i], sl = std::sqrt(sx * sx + sz * sz);
    const float lim = (i == LA || i == RA ? FRICTION : i == LW || i == RW ? FRICTION * 0.75f : FRICTION * 0.35f) * load_[i];
    if (sl <= lim) { x[i] = px[i]; z[i] = pz[i]; }
    else if (sl > 1e-7f) { const float k = (sl - lim * 0.8f) / sl; x[i] = px[i] + sx * k; z[i] = pz[i] + sz * k; }
}

void Ragdoll::collide(const VoxelWorld* w, int i) {
    const float r = RADIUS[i] * (unit / VOX);
    grounded[i] = false;
    if (w == nullptr) return;
    int ix = fl(x[i]), iz = fl(z[i]);
    const int fy = fl(y[i] - r);
    if (solid(w, ix, fy, iz)) {
        if (!solid(w, ix, fy + 1, iz)) {
            load_[i] += static_cast<float>(fy + 1) + r - y[i];
            y[i] = static_cast<float>(fy + 1) + r; grounded[i] = true;
            friction(i);
        } else {
            x[i] = px[i]; z[i] = pz[i];
            if (solid(w, fl(x[i]), fy, fl(z[i])) && solid(w, fl(x[i]), fy + 1, fl(z[i]))) y[i] = py[i];
        }
        ix = fl(x[i]); iz = fl(z[i]);
    } else if (solid(w, ix, fl(y[i] - r - 0.02f), iz)) { grounded[i] = true; friction(i); }
    const int iy = fl(y[i]);
    if (solid(w, ix, fl(y[i] + r), iz) && !solid(w, ix, iy, iz)) y[i] = std::floor(y[i] + r) - r - 1e-3f;
    if (solid(w, fl(x[i] + r), iy, iz)) x[i] = std::floor(x[i] + r) - r - 1e-3f;
    else if (solid(w, fl(x[i] - r), iy, iz)) x[i] = std::floor(x[i] - r) + 1 + r + 1e-3f;
    if (solid(w, ix, iy, fl(z[i] + r))) z[i] = std::floor(z[i] + r) - r - 1e-3f;
    else if (solid(w, ix, iy, fl(z[i] - r))) z[i] = std::floor(z[i] - r) + 1 + r + 1e-3f;
}

float Ragdoll::lowestY() const { float m = 3.4e38f; for (int i = 0; i < N; i++) m = std::min(m, y[i] - RADIUS[i] * (unit / VOX)); return m; }

}  // namespace astratto
