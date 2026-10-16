// Astratto Engine - movers, soft bodies and cars. Ports of the game's entity.Physics (movement), sydney.SoftBody and
// vehicle.* (CarVehicle, CarRigidBodyAdapter, CarSuspension, CarTireSolver, CarTransmission), rule for rule.
#include "physics/bodies.h"

#include <algorithm>
#include <cmath>

#include "world/raycast.h"

namespace astratto {

namespace {
inline int fl(float v) { return static_cast<int>(std::floor(v)); }
constexpr float PI = 3.14159265358979f;
constexpr int STAIR_DX[4] = {0, -1, 0, 1}, STAIR_DZ[4] = {1, 0, -1, 0};
constexpr float STAIR_LIP = 0.08f, STAIR_LIFT = 0.62f, MOVER_STEP = 1.0f / 120.0f;
const float OFFX[8] = {1, -1, 0, 0, 0.7071f, 0.7071f, -0.7071f, -0.7071f}, OFFZ[8] = {0, 0, 1, -1, 0.7071f, -0.7071f, 0.7071f, -0.7071f};

inline bool solidAt(const VoxelWorld* w, int x, int y, int z) { return w != nullptr ? w->solid(x, y, z) : y < 0; }
inline bool stairCell(const VoxelWorld* w, int x, int y, int z) { return w != nullptr && (w->def(x, y, z).flags & ASTRATTO_BLOCK_STEP) != 0; }
inline bool backing(const VoxelWorld* w, int x, int y, int z) { if (w == nullptr) return false; const auto& b = w->def(x, y, z); return (b.flags & ASTRATTO_BLOCK_SOLID) && b.shape == 0; }

/** Which way a tread rises: toward the next tread of its flight (the same rule the mesher draws it by). */
int stairDir(const VoxelWorld* w, int x, int y, int z) {
    for (int d = 0; d < 4; d++) if (stairCell(w, x + STAIR_DX[d], y + 1, z + STAIR_DZ[d])) return d;
    for (int d = 0; d < 4; d++) if (stairCell(w, x - STAIR_DX[d], y - 1, z - STAIR_DZ[d])) return d;
    for (int d = 0; d < 4; d++) {
        const int hx = x + STAIR_DX[d], hz = z + STAIR_DZ[d], lx = x - STAIR_DX[d], lz = z - STAIR_DZ[d];
        if (backing(w, hx, y, hz) && !solidAt(w, hx, y + 1, hz) && !solidAt(w, lx, y, lz)) return d;
    }
    for (int d = 0; d < 4; d++) { const int lx = x - STAIR_DX[d], lz = z - STAIR_DZ[d]; if (!solidAt(w, lx, y, lz) && solidAt(w, lx, y - 1, lz)) return d; }
    return 0;
}
}  // namespace

// ------------------------------------------------------------------ movers
float Mover::stairHeight(const VoxelWorld* w, int bx, int by, int bz, float px, float pz, float r) {
    const int d = stairDir(w, bx, by, bz);
    const int dx = STAIR_DX[d], dz = STAIR_DZ[d];
    const float t = (dx != 0 ? (dx > 0 ? px - static_cast<float>(bx) : static_cast<float>(bx + 1) - px) : (dz > 0 ? pz - static_cast<float>(bz) : static_cast<float>(bz + 1) - pz)) + std::max(0.0f, r - 0.1f);
    if (t <= 0) return static_cast<float>(by);
    return static_cast<float>(by) + std::min(1.0f, t + 0.125f);
}

bool Mover::bodyFree(const VoxelWorld* w, float x, float y, float z, float r, float h) {
    const int x0 = fl(x - r), x1 = fl(x + r - 1e-4f), y0 = fl(y), y1 = fl(y + h - 1e-4f), z0 = fl(z - r), z1 = fl(z + r - 1e-4f);
    for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++) {
        if (!solidAt(w, bx, by, bz)) continue;
        if (stairCell(w, bx, by, bz)) { if (y + 2e-3f < stairHeight(w, bx, by, bz, x, z, r)) return false; }
        else return false;
    }
    return true;
}

float Mover::stairRise(const VoxelWorld* w, float x, float y, float z, float r, float h, float lip) {
    const int x0 = fl(x - r), x1 = fl(x + r - 1e-4f), y0 = fl(y), y1 = fl(y + h - 1e-4f), z0 = fl(z - r), z1 = fl(z + r - 1e-4f);
    float rise = 0;
    for (int bx = x0; bx <= x1; bx++) for (int by = y0; by <= y1; by++) for (int bz = z0; bz <= z1; bz++) {
        if (!solidAt(w, bx, by, bz)) continue;
        if (!stairCell(w, bx, by, bz)) {
            if (by == y0 && static_cast<float>(by + 1) - y <= lip && (by + 1 > y1 || !solidAt(w, bx, by + 1, bz) || stairCell(w, bx, by + 1, bz))) { rise = std::max(rise, static_cast<float>(by + 1) - y); continue; }
            return -1;
        }
        rise = std::max(rise, stairHeight(w, bx, by, bz, x, z, r) - y);
    }
    return rise;
}

float Mover::stairBelow(const VoxelWorld* w, float x, float y, float z, float r) {
    const int bx = fl(x), bz = fl(z);
    for (int by = fl(y - 1e-3f); by >= fl(y - 0.75f); by--) {
        if (!solidAt(w, bx, by, bz)) continue;
        return stairCell(w, bx, by, bz) ? stairHeight(w, bx, by, bz, x, z, r) : NAN;
    }
    return NAN;
}

bool Mover::climbStair(const VoxelWorld* w, float nx, float nz, float r, float hh) {
    const float lip = !std::isnan(stairBelow(w, x, y + 0.05f, z, r)) ? 0.5f : STAIR_LIP;
    float rise = stairRise(w, nx, y, nz, r, hh, lip);
    if (rise <= 0 || rise > std::max(STAIR_LIFT, step)) return false;
    rise += 3e-3f;
    if (!bodyFree(w, nx, y + rise, nz, r, hh)) return false;
    y += rise; x = nx; z = nz; stepped += rise;
    return true;
}

bool Mover::inClimb(const VoxelWorld* w, float px0, float py0, float pz0) {
    if (w == nullptr) return false;
    const float dys[3] = {-0.6f, 0.0f, 0.9f};
    for (float dy : dys) if (w->def(fl(px0), fl(py0 + dy), fl(pz0)).flags & ASTRATTO_BLOCK_CLIMB) return true;
    return false;
}

void Mover::depenetrate(const VoxelWorld* w, float r, float hh) {
    const float rise = stairRise(w, x, y, z, r, hh, STAIR_LIP);
    if (rise > 0 && rise <= 1.01f && bodyFree(w, x, y + rise + 3e-3f, z, r, hh)) { y += rise + 3e-3f; return; }
    const float frac = y - std::floor(y);
    if (frac > 0.7f && bodyFree(w, x, std::ceil(y), z, r, hh)) { y = std::ceil(y); return; }
    if (bodyFree(w, x, y + 0.12f, z, r, hh)) { y += 0.05f; return; }
    for (float d = 0.1f; d <= 1.01f; d += 0.1f) for (int k = 0; k < 8; k++) {
        const float ox = OFFX[k] * d, oz = OFFZ[k] * d;
        if (bodyFree(w, x + ox, y, z + oz, r, hh)) { x += ox; z += oz; return; }
    }
}

bool Mover::subStep(const VoxelWorld* w, float dt, float gravity) {
    const float hh = h(), r = radius;
    bool blocked = false;
    const bool fly = (flags & ASTRATTO_MOVER_FLY) != 0;
    const bool onLadder = !fly && inClimb(w, x, y, z);
    climbing = onLadder && climb;
    if (fly) { /* no gravity */ }
    else if (climbing) {
        vy = (wishY >= 0 ? 1.0f : -1.0f) * 3.2f;
        if (wishY < 0 && !bodyFree(w, x, y - 0.1f, z, r, hh)) vy = 0;
        vx *= 0.55f; vz *= 0.55f;
    } else if (onLadder && !onGround && vy <= 0) {
        vy = std::max(vy - gravity * dt, -1.4f);
        fallSpeed = 0;
    } else vy -= gravity * dt;
    vy = std::max(vy, -40.0f);
    const bool wasGround = onGround, mayClimb = !fly && (wasGround || (vy <= 0.5f && !std::isnan(stairBelow(w, x, y + 0.3f, z, r))));
    const float nx = x + vx * dt;
    if (bodyFree(w, nx, y, z, r, hh)) x = nx;
    else if (!(mayClimb && !climbing && climbStair(w, nx, z, r, hh))) { blocked = true; vx = 0; }
    const float nz = z + vz * dt;
    if (bodyFree(w, x, y, nz, r, hh)) z = nz;
    else if (!(mayClimb && !climbing && climbStair(w, x, nz, r, hh))) { blocked = true; vz = 0; }
    if (wasGround && vy <= 0 && !climbing && !fly) {
        const float s = stairBelow(w, x, y, z, r);
        if (!std::isnan(s) && s < y - 2e-3f && y - s <= STAIR_LIFT && bodyFree(w, x, s + 3e-3f, z, r, hh)) { stepped -= y - s; y = s + 3e-3f; vy = 0; }
    }
    const float ny = y + vy * dt;
    onGround = false;
    if (bodyFree(w, x, ny, z, r, hh)) y = ny;
    else {
        if (vy < 0) {
            onGround = true;
            fallSpeed = std::max(fallSpeed, -vy);
            const float rise = stairRise(w, x, ny, z, r, hh, STAIR_LIP);
            if (rise > 0 && rise <= 1.01f && bodyFree(w, x, ny + rise + 3e-3f, z, r, hh)) y = ny + rise + 3e-3f;
            else {
                y = std::floor(ny) + 1.0f;
                if (!bodyFree(w, x, y, z, r, hh)) y = std::ceil(ny);
            }
        }
        vy = 0;
    }
    if (!bodyFree(w, x, y, z, r, hh)) depenetrate(w, r, hh);
    return blocked;
}

bool Mover::move(const VoxelWorld* w, float dt, float gravity) {
    const float vmax = std::max(std::fabs(vx), std::max(std::fabs(vz), std::fabs(vy) + gravity * dt));
    const int n = std::max(1, std::min(64, static_cast<int>(std::ceil(vmax * dt / 0.4f))));
    bool blocked = false, ground = false;
    for (int i = 0; i < n; i++) { blocked |= subStep(w, dt / static_cast<float>(n), gravity); ground |= onGround; }
    onGround = ground && vy <= 0;
    return blocked;
}

void Mover::tick(const VoxelWorld* w, float dt, float gravity) {
    const bool fly = (flags & ASTRATTO_MOVER_FLY) != 0;
    // the wanted velocity is reached at the controller's acceleration on the ground, a fifth of it in the air
    const float a = (onGround || fly ? accel : accel * 0.2f) * dt;
    auto approach = [a](float v, float want) { const float dv = want - v; return std::fabs(dv) <= a ? want : v + (dv > 0 ? a : -a); };
    const float hx = approach(vx, wishX), hz = approach(vz, wishZ);
    vx = hx; vz = hz;
    if (fly) vy = approach(vy, wishY);
    if (jump > 0 && onGround && !fly) { vy = jump; onGround = false; jump = 0; }
    move(w, dt, fly ? 0.0f : gravity);
}

float Mover::update(const VoxelWorld* w, float dt, float gravity, int* steps) {
    acc += std::min(dt, 0.25f);
    float landed = 0;
    int n = 0;
    while (acc >= MOVER_STEP) {
        acc -= MOVER_STEP;
        const float fall = -vy;
        const bool was = onGround;
        tick(w, MOVER_STEP, gravity);
        n++;
        if (!was && onGround && fall > landed) landed = fall;
    }
    if (steps) *steps = n;
    return landed;
}

// ------------------------------------------------------------------ soft bodies
void SoftBody::particle(int i, float wx, float wy, float wz, float r, float massKg) {
    x[i] = px[i] = wx; y[i] = py[i] = wy; z[i] = pz[i] = wz;
    radius[i] = r; invMass[i] = 1.0f / std::max(0.01f, massKg);
}

float SoftBody::dist(int a, int b) const { const float dx = x[a] - x[b], dy = y[a] - y[b], dz = z[a] - z[b]; return std::sqrt(dx * dx + dy * dy + dz * dz); }

void SoftBody::velocity(float vx, float vy, float vz) { for (int i = 0; i < n; i++) { px[i] = x[i] - vx * STEP; py[i] = y[i] - vy * STEP; pz[i] = z[i] - vz * STEP; } wake(); }

void SoftBody::impulse(int i, float dvx, float dvy, float dvz) { px[i] -= dvx * STEP; py[i] -= dvy * STEP; pz[i] -= dvz * STEP; wake(); }

void SoftBody::impulseAt(float wx, float wy, float wz, float dvx, float dvy, float dvz, float reach) {
    for (int i = 0; i < n; i++) {
        const float dx = x[i] - wx, dy = y[i] - wy, dz = z[i] - wz, k = std::max(0.25f, 1 - std::sqrt(dx * dx + dy * dy + dz * dz) / std::max(0.01f, reach));
        impulse(i, dvx * k, dvy * k, dvz * k);
    }
}

float SoftBody::centre(int axis) const {
    const std::vector<float>& v = axis == 0 ? x : axis == 1 ? y : z;
    float s = 0;
    for (int i = 0; i < n; i++) s += v[i];
    return n == 0 ? 0 : s / static_cast<float>(n);
}

int SoftBody::update(const VoxelWorld* w, float dt, float gravity) {
    if (settled) return 0;
    acc += std::min(dt, 0.1f);
    int k = 0;
    while (acc >= STEP && !settled) { acc -= STEP; step(w, gravity); k++; }
    return k;
}

void SoftBody::step(const VoxelWorld* w, float gravity) {
    age += STEP;
    float moved = 0;
    for (int i = 0; i < n; i++) {
        float vx = (x[i] - px[i]) * air, vy = (y[i] - py[i]) * air, vz = (z[i] - pz[i]) * air;
        if (grounded[i]) { vx *= slide; vz *= slide; }
        px[i] = x[i]; py[i] = y[i]; pz[i] = z[i];
        x[i] += vx + fx[i] * invMass[i] * STEP * STEP;
        y[i] += vy + (fy[i] * invMass[i] - gravity) * STEP * STEP;
        z[i] += vz + fz[i] * invMass[i] * STEP * STEP;
        fx[i] = fy[i] = fz[i] = 0;
    }
    for (int it = 0; it < 6; it++) {
        for (size_t s = 0; s < stickA.size(); s++) solve(static_cast<int>(stickA[s]), static_cast<int>(stickB[s]), stickLen[s], stickLen[s], 1);
        for (size_t l = 0; l < limA.size(); l++) solve(static_cast<int>(limA[l]), static_cast<int>(limB[l]), limMin[l], limMax[l], 0.8f);
        for (int i = 0; i < n; i++) collide(w, i);
    }
    for (int i = 0; i < n; i++) { const float dx = x[i] - px[i], dy = y[i] - py[i], dz = z[i] - pz[i]; moved = std::max(moved, dx * dx + dy * dy + dz * dz); }
    if (moved < 2.5e-7f) { still += STEP; if (still > 0.35f && age > 0.5f) settled = true; } else still = 0;
    if (age > 14) settled = true;
}

void SoftBody::solve(int a, int b, float min, float max, float stiff) {
    float dx = x[b] - x[a], dy = y[b] - y[a], dz = z[b] - z[a], d = std::sqrt(dx * dx + dy * dy + dz * dz);
    if (d < 1e-6f) { dx = 0; dy = 1e-3f; dz = 0; d = 1e-3f; }
    const float want = d < min ? min : d > max ? max : d;
    if (want == d) return;
    const float wa = invMass[a], wb = invMass[b], k = (d - want) / d / (wa + wb) * stiff;
    x[a] += dx * k * wa; y[a] += dy * k * wa; z[a] += dz * k * wa;
    x[b] -= dx * k * wb; y[b] -= dy * k * wb; z[b] -= dz * k * wb;
}

void SoftBody::collide(const VoxelWorld* w, int i) {
    const float r = radius[i];
    grounded[i] = 0;
    if (w == nullptr) return;
    int ix = fl(x[i]), iz = fl(z[i]);
    const int fy0 = fl(y[i] - r);
    if (w->solid(ix, fy0, iz)) {
        if (!w->solid(ix, fy0 + 1, iz)) {
            const float top = static_cast<float>(fy0 + 1) + r;
            const float vy = y[i] - py[i];
            y[i] = top; grounded[i] = 1;
            if (vy < -0.012f) py[i] = top + vy * bounce;
        } else {
            x[i] = px[i]; z[i] = pz[i];
            if (w->solid(fl(x[i]), fy0, fl(z[i])) && w->solid(fl(x[i]), fy0 + 1, fl(z[i]))) y[i] = py[i];
        }
        ix = fl(x[i]); iz = fl(z[i]);
    } else if (w->solid(ix, fl(y[i] - r - 0.02f), iz)) grounded[i] = 1;
    const int iy = fl(y[i]);
    if (w->solid(ix, fl(y[i] + r), iz) && !w->solid(ix, iy, iz)) y[i] = std::floor(y[i] + r) - r - 1e-3f;
    if (w->solid(fl(x[i] + r), iy, iz)) x[i] = std::floor(x[i] + r) - r - 1e-3f;
    else if (w->solid(fl(x[i] - r), iy, iz)) x[i] = std::floor(x[i] - r) + 1 + r + 1e-3f;
    if (w->solid(ix, iy, fl(z[i] + r))) z[i] = std::floor(z[i] + r) - r - 1e-3f;
    else if (w->solid(ix, iy, fl(z[i] - r))) z[i] = std::floor(z[i] - r) + 1 + r + 1e-3f;
}

// ------------------------------------------------------------------ cars: the rigid body
void Vehicle::updateRotation() {
    const float l = std::sqrt(qw * qw + qx * qx + qy * qy + qz * qz);
    qw /= l; qx /= l; qy /= l; qz /= l;
    const float xx = qx * qx, yy = qy * qy, zz = qz * qz, xy = qx * qy, xz = qx * qz, yz = qy * qz, wxq = qw * qx, wyq = qw * qy, wzq = qw * qz;
    r[0] = 1 - 2 * (yy + zz); r[1] = 2 * (xy - wzq); r[2] = 2 * (xz + wyq);
    r[3] = 2 * (xy + wzq); r[4] = 1 - 2 * (xx + zz); r[5] = 2 * (yz - wxq);
    r[6] = 2 * (xz - wyq); r[7] = 2 * (yz + wxq); r[8] = 1 - 2 * (xx + yy);
}

void Vehicle::toWorld(float lx, float ly, float lz, float* out) const {
    out[0] = px + r[0] * lx + r[1] * ly + r[2] * lz;
    out[1] = py + r[3] * lx + r[4] * ly + r[5] * lz;
    out[2] = pz + r[6] * lx + r[7] * ly + r[8] * lz;
}

void Vehicle::velocityAt(float x, float y, float z, float* out) const {
    const float rx = x - px, ry = y - py, rz = z - pz;
    out[0] = vx + wy * rz - wz * ry;
    out[1] = vy + wz * rx - wx * rz;
    out[2] = vz + wx * ry - wy * rx;
}

void Vehicle::invInertia(float tx, float ty, float tz, float* out) const {
    float lx = r[0] * tx + r[3] * ty + r[6] * tz, ly = r[1] * tx + r[4] * ty + r[7] * tz, lz = r[2] * tx + r[5] * ty + r[8] * tz;
    lx *= invIx; ly *= invIy; lz *= invIz;
    out[0] = r[0] * lx + r[1] * ly + r[2] * lz;
    out[1] = r[3] * lx + r[4] * ly + r[5] * lz;
    out[2] = r[6] * lx + r[7] * ly + r[8] * lz;
}

void Vehicle::applyImpulse(float x, float y, float z, float jx, float jy, float jz) {
    vx += jx * invMass; vy += jy * invMass; vz += jz * invMass;
    const float rx = x - px, ry = y - py, rz = z - pz;
    float t[3];
    invInertia(ry * jz - rz * jy, rz * jx - rx * jz, rx * jy - ry * jx, t);
    wx += t[0]; wy += t[1]; wz += t[2];
}

float Vehicle::invMassAt(float x, float y, float z, float nx, float ny, float nz) const {
    const float rx = x - px, ry = y - py, rz = z - pz;
    const float cx = ry * nz - rz * ny, cy = rz * nx - rx * nz, cz = rx * ny - ry * nx;
    float t[3];
    invInertia(cx, cy, cz, t);
    const float ax = t[1] * rz - t[2] * ry, ay = t[2] * rx - t[0] * rz, az = t[0] * ry - t[1] * rx;
    return invMass + ax * nx + ay * ny + az * nz;
}

void Vehicle::integrate(float dt) {
    px += vx * dt; py += vy * dt; pz += vz * dt;
    const float hx = wx * dt * 0.5f, hy = wy * dt * 0.5f, hz = wz * dt * 0.5f;
    const float nw = qw - hx * qx - hy * qy - hz * qz;
    const float nx = qx + hx * qw + hy * qz - hz * qy;
    const float ny = qy + hy * qw + hz * qx - hx * qz;
    const float nz = qz + hz * qw + hx * qy - hy * qx;
    qw = nw; qx = nx; qy = ny; qz = nz;
    updateRotation();
}

void Vehicle::collide(const VoxelWorld* w, float restitution, float friction, Report& out) {
    impact = 0;
    const size_t n = local.size() / 3;
    float pushX = 0, pushY = 0, pushZ = 0;
    int pushes = 0;
    float wp[3], vel[3];
    for (size_t i = 0; i < n; i++) {
        toWorld(local[i * 3], local[i * 3 + 1], local[i * 3 + 2], wp);
        const float x = wp[0], y = wp[1], z = wp[2];
        const int cx = fl(x), cy = fl(y), cz = fl(z);
        if (!w->solid(cx, cy, cz)) { prev[i * 3] = x; prev[i * 3 + 1] = y; prev[i * 3 + 2] = z; continue; }
        float nx = 0, ny = 0, nz = 0, depth = 0;
        bool found = false;
        if (prevValid) {
            const float ox = prev[i * 3], oy = prev[i * 3 + 1], oz = prev[i * 3 + 2];
            float best = 3.4e38f;
            if (ox <= cx && !w->solid(cx - 1, cy, cz)) { const float d0 = x - cx; if (d0 < best) { best = d0; nx = -1; ny = 0; nz = 0; found = true; } }
            if (ox >= cx + 1 && !w->solid(cx + 1, cy, cz)) { const float d0 = cx + 1 - x; if (d0 < best) { best = d0; nx = 1; ny = 0; nz = 0; found = true; } }
            if (oy <= cy && !w->solid(cx, cy - 1, cz)) { const float d0 = y - cy; if (d0 < best) { best = d0; nx = 0; ny = -1; nz = 0; found = true; } }
            if (oy >= cy + 1 && !w->solid(cx, cy + 1, cz)) { const float d0 = cy + 1 - y; if (d0 < best) { best = d0; nx = 0; ny = 1; nz = 0; found = true; } }
            if (oz <= cz && !w->solid(cx, cy, cz - 1)) { const float d0 = z - cz; if (d0 < best) { best = d0; nx = 0; ny = 0; nz = -1; found = true; } }
            if (oz >= cz + 1 && !w->solid(cx, cy, cz + 1)) { const float d0 = cz + 1 - z; if (d0 < best) { best = d0; nx = 0; ny = 0; nz = 1; found = true; } }
            if (found) depth = best;
        }
        if (!found) {
            float best = 3.4e38f;
            if (!w->solid(cx, cy + 1, cz)) { const float d0 = cy + 1 - y; if (d0 < best) { best = d0; nx = 0; ny = 1; nz = 0; } }
            if (!w->solid(cx - 1, cy, cz)) { const float d0 = x - cx; if (d0 < best) { best = d0; nx = -1; ny = 0; nz = 0; } }
            if (!w->solid(cx + 1, cy, cz)) { const float d0 = cx + 1 - x; if (d0 < best) { best = d0; nx = 1; ny = 0; nz = 0; } }
            if (!w->solid(cx, cy, cz - 1)) { const float d0 = z - cz; if (d0 < best) { best = d0; nx = 0; ny = 0; nz = -1; } }
            if (!w->solid(cx, cy, cz + 1)) { const float d0 = cz + 1 - z; if (d0 < best) { best = d0; nx = 0; ny = 0; nz = 1; } }
            if (!w->solid(cx, cy - 1, cz)) { const float d0 = y - cy; if (d0 < best) { best = d0; nx = 0; ny = -1; nz = 0; } }
            if (best == 3.4e38f) { nx = 0; ny = 1; nz = 0; best = 0.5f; }
            depth = best;
        }
        velocityAt(x, y, z, vel);
        const float vn = vel[0] * nx + vel[1] * ny + vel[2] * nz;
        if (vn < 0) {
            const float k = invMassAt(x, y, z, nx, ny, nz);
            const float e = vn < -2.5f ? restitution : 0;
            const float j = -(1 + e) * vn / k;
            applyImpulse(x, y, z, nx * j, ny * j, nz * j);
            const float dv = j * invMass;
            if (dv > impact) { impact = dv; impactX = x; impactY = y; impactZ = z; }
            if (-vn > 3) {
                astratto_evt_vehicle_impact b{};
                b.dv = -vn; b.x = x; b.y = y; b.z = z; b.bx = cx; b.by = cy; b.bz = cz;
                if (out.blocks.size() < 16) out.blocks.push_back(b);
            }
            velocityAt(x, y, z, vel);
            const float tvn = vel[0] * nx + vel[1] * ny + vel[2] * nz;
            float tx = vel[0] - nx * tvn, ty = vel[1] - ny * tvn, tz = vel[2] - nz * tvn;
            const float tl = std::sqrt(tx * tx + ty * ty + tz * tz);
            if (tl > 1e-3f) {
                tx /= tl; ty /= tl; tz /= tl;
                const float kt = invMassAt(x, y, z, tx, ty, tz);
                const float jt = std::min(tl / kt, friction * j);
                applyImpulse(x, y, z, -tx * jt, -ty * jt, -tz * jt);
            }
        }
        const float pd = std::min(depth, 0.6f);
        if (std::fabs(nx * pd) > std::fabs(pushX)) pushX = nx * pd;
        if (std::fabs(ny * pd) > std::fabs(pushY)) pushY = ny * pd;
        if (std::fabs(nz * pd) > std::fabs(pushZ)) pushZ = nz * pd;
        pushes++;
    }
    if (pushes > 0) {
        px += pushX * 0.8f; py += pushY * 0.8f; pz += pushZ * 0.8f;
        for (size_t i = 0; i < n; i++) {
            toWorld(local[i * 3], local[i * 3 + 1], local[i * 3 + 2], wp);
            if (!w->solid(fl(wp[0]), fl(wp[1]), fl(wp[2]))) { prev[i * 3] = wp[0]; prev[i * 3 + 1] = wp[1]; prev[i * 3 + 2] = wp[2]; }
        }
    }
    prevValid = true;
}

// ------------------------------------------------------------------ cars: the car
void Vehicle::create(const astratto_vehicle_desc& desc, const float* boxData) {
    d = desc;
    if (d.gears < 1) d.gears = 1;
    boxes.assign(boxData, boxData + static_cast<size_t>(d.box_count) * 6);
    if (d.box_count == 0) {
        // the game's default chassis: a body and a cabin
        const float clear = d.wheel_radius * 0.75f, waist = clear + (d.height - clear) * 0.55f;
        const float b0[6] = {0, (clear + waist) / 2, 0, d.width / 2, (waist - clear) / 2, d.length / 2};
        const float b1[6] = {0, (waist + d.height) / 2, -d.length * 0.06f, d.width / 2 - 0.08f, (d.height - waist) / 2, d.length * 0.30f};
        boxes.insert(boxes.end(), b0, b0 + 6);
        boxes.insert(boxes.end(), b1, b1 + 6);
    }
    const float yaw = d.turn_mass > 0 ? d.turn_mass : d.mass * (d.width * d.width + d.length * d.length) / 12.0f;
    mass = d.mass; invMass = 1.0f / d.mass;
    invIy = 1.0f / yaw;
    invIx = 1.0f / (d.mass * (d.height * d.height + d.length * d.length) / 12.0f * 1.15f);
    invIz = 1.0f / (d.mass * (d.width * d.width + d.height * d.height) / 12.0f * 1.6f);
    local.clear();
    for (size_t k = 0; k + 5 < boxes.size(); k += 6) {
        const float* b = &boxes[k];
        const int nz = std::max(2, static_cast<int>(std::lround(b[5] * 2 / 0.9f))), nx = std::max(2, static_cast<int>(std::lround(b[3] * 2 / 0.9f)));
        for (int iy = 0; iy <= 1; iy++) for (int ix = 0; ix <= nx; ix++) for (int iz = 0; iz <= nz; iz++) {
            if (ix != 0 && ix != nx && iz != 0 && iz != nz) continue;
            local.push_back(b[0] - b[3] + 2 * b[3] * static_cast<float>(ix) / static_cast<float>(nx) - d.com_x);
            local.push_back(b[1] - b[4] + 2 * b[4] * static_cast<float>(iy) - d.com_y);
            local.push_back(b[2] - b[5] + 2 * b[5] * static_cast<float>(iz) / static_cast<float>(nz) - d.com_z);
        }
        for (int iy = 0; iy <= 1; iy++) { local.push_back(b[0] - d.com_x); local.push_back(b[1] - b[4] + 2 * b[4] * static_cast<float>(iy) - d.com_y); local.push_back(b[2] - d.com_z); }
    }
    prev.assign(local.size(), 0);
    prevValid = false;
    for (int i = 0; i < 4; i++) {
        Wheel& w = wheels[i];
        w = Wheel();
        w.index = i; w.front = i < 2;
        w.ax = d.wheels[i * 3]; w.ay = d.wheels[i * 3 + 1]; w.az = d.wheels[i * 3 + 2];
        w.length = d.susp_upper - d.susp_lower;
    }
}

void Vehicle::place(float x, float y, float z, float yaw) {
    qw = std::cos(yaw / 2); qx = 0; qy = std::sin(yaw / 2); qz = 0;
    updateRotation();
    vx = vy = vz = wx = wy = wz = 0;
    px = py = pz = 0;
    float o[3];
    toWorld(d.com_x, d.com_y, d.com_z, o);
    px = x + o[0]; py = y + o[1] + 0.05f; pz = z + o[2];
    snapshot();
    asleep = false; sleepTimer = 0; prevValid = false;
}

void Vehicle::snapshot() { ppx = px; ppy = py; ppz = pz; pqw = qw; pqx = qx; pqy = qy; pqz = qz; }

float Vehicle::speed() const { return std::sqrt(vx * vx + vy * vy + vz * vz); }

int Vehicle::update(const VoxelWorld* w, const Surface* surfaces, float frameDt, Report& out) {
    if (w == nullptr) return 0;
    if (asleep) {
        if (throttle > 0 || brakeIn > 0 || std::fabs(steerIn) > 0.01f) { asleep = false; sleepTimer = 0; }
        else { accumulator = 0; return 0; }
    }
    accumulator += std::min(frameDt, 0.1f);
    int n = 0;
    while (accumulator >= FIXED_DT && n < 6) { step(w, surfaces, out); accumulator -= FIXED_DT; n++; }
    if (n == 6) accumulator = 0;
    return n;
}

float Vehicle::gearMax(int g) const { return g <= 0 ? d.max_reverse : d.max_velocity * static_cast<float>(g) / static_cast<float>(d.gears); }
float Vehicle::upshiftAt(int g) const { const float interval = d.max_velocity / static_cast<float>(d.gears); return gearMax(g - 1 <= 0 ? 0 : g - 1) * (g > 1 ? 1.0f : 0.0f) + 0.6667f * interval; }
float Vehicle::downshiftAt(int g) const { const float interval = d.max_velocity / static_cast<float>(d.gears); return (g >= 3 ? gearMax(g - 2) : 0) + 0.42f * interval; }

void Vehicle::transmission(float fwd, float dt) {
    sinceShift += dt;
    const int before = gear;
    const int gears = static_cast<int>(d.gears);
    if (reverse) { if (fwd < 0.8f) gear = 0; }
    else {
        if (gear == 0) { if (fwd > -0.8f) gear = 1; }
        else {
            while (gear < gears && fwd > upshiftAt(gear)) gear++;
            while (gear > 1 && fwd < downshiftAt(gear)) gear--;
        }
    }
    if (gear != before) sinceShift = 0;
    const float lo = gear <= 1 ? 0 : gearMax(gear - 1) * 0.55f, hi = gear == 0 ? d.max_reverse : gearMax(gear);
    band = std::max(0.0f, std::min(1.0f, (std::fabs(fwd) - lo) / std::max(0.5f, hi - lo)));
}

float Vehicle::driveAcceleration(float fwd) const {
    const float th = engineOn ? throttle : 0;
    if (th <= 0) return 0;
    const float cut = sinceShift < 0.18f ? 0.35f + 0.65f * (sinceShift / 0.18f) : 1.0f;
    if (gear == 0) {
        const float target = d.max_reverse, v = -fwd;
        if (target < 1e-3f) return 0;
        const float a = th * (target - v) * d.engine_accel * d.first_gear_boost * 0.8f / target;
        return -std::max(0.0f, a) * cut;
    }
    const float f = d.gears > 1 ? 1.0f - static_cast<float>(gear - 1) / static_cast<float>(d.gears - 1) : 0.0f;
    const float mult = 1.0f + 3.0f * f * f;
    const float target = gearMax(gear) * mult;
    if (std::fabs(target) < 1e-3f) return 0;
    const float accel = d.engine_accel * (gear == 1 ? d.first_gear_boost : gear == 2 ? d.second_gear_boost : 1.0f);
    const float a = th * (target - fwd) * accel / std::fabs(target);
    if (fwd >= d.max_velocity) return 0;
    return std::max(0.0f, a) * cut;
}

void Vehicle::suspension(Wheel& wh, const VoxelWorld* w, const Surface* surfaces, float fwd, float nt) {
    const float travel = d.susp_upper - d.susp_lower;
    const float radius = d.wheel_radius;
    float top[3];
    toWorld(wh.ax - d.com_x, wh.ay + d.susp_upper - d.com_y, wh.az - d.com_z, top);
    const float dx = -r[1], dy = -r[4], dz = -r[7];
    wh.normalImpulse = 0;
    const RayHit h = castRay(*w, top[0], top[1], top[2], dx, dy, dz, travel + radius);
    if (h.t < 0) {
        wh.grounded = false;
        wh.length += (travel - wh.length) * std::min(1.0f, nt * 0.35f);
        wh.compression = 0;
        return;
    }
    const float t = h.t;
    wh.grounded = true;
    wh.cx = top[0] + dx * t; wh.cy = top[1] + dy * t; wh.cz = top[2] + dz * t;
    wh.nx = static_cast<float>(h.nx); wh.ny = static_cast<float>(h.ny); wh.nz = static_cast<float>(h.nz);
    wh.block = h.block;
    wh.surface = surfaces[w->defOf(h.block).surface & 63];
    const float s = std::max(0.0f, std::min(travel, t - radius));
    wh.length = s;
    wh.compression = 1.0f - s / travel;
    const float axle = wh.front ? d.susp_bias : 1.0f - d.susp_bias;
    float spring = G_STEP * d.mass * nt * d.susp_force * wh.compression * axle * 2.0f;
    if (wh.front && brakeIn > 0 && fwd > 1) spring *= 1.0f + d.anti_dive * 0.3f * brakeIn * std::min(1.0f, fwd / 12.0f);
    float vel[3];
    velocityAt(wh.cx, wh.cy, wh.cz, vel);
    const float vn = vel[0] * wh.nx + vel[1] * wh.ny + vel[2] * wh.nz;
    const float damp = -vn * d.mass * d.susp_damping * nt * DAMP_K;
    float total = spring + damp;
    if (wh.compression > 0.97f && vn < 0) total += -vn * d.mass * 0.25f * 0.7f;
    if (total <= 0) return;
    total = std::min(total, d.mass * 3.5f);
    wh.normalImpulse = total;
    applyImpulse(wh.cx, wh.cy, wh.cz, wh.nx * total, wh.ny * total, wh.nz * total);
}

void Vehicle::tyre(Wheel& w, float perWheel, float nt, float dt) {
    const bool hb = handbrake && !w.front;
    if (!w.grounded) {
        w.adhesion = 0;
        w.state = hb || brakeIn > 0.5f ? 3 : 0;
        if (w.state == 3) w.rotationSpeed = 0;
        else if (driven(w.index) && throttle > 0) w.rotationSpeed += (reverse ? -1.0f : 1.0f) * throttle * 40.0f * dt;
        else w.rotationSpeed *= 1.0f - std::min(1.0f, dt * 0.8f);
        w.rotation += w.rotationSpeed * dt;
        w.slideTime = 0;
        return;
    }
    const float ca = std::cos(w.steerAngle), sa = std::sin(w.steerAngle);
    float fx = r[2] * ca + r[0] * sa, fy = r[5] * ca + r[3] * sa, fz = r[8] * ca + r[6] * sa;
    const float dn = fx * w.nx + fy * w.ny + fz * w.nz;
    fx -= w.nx * dn; fy -= w.ny * dn; fz -= w.nz * dn;
    const float fln = std::sqrt(fx * fx + fy * fy + fz * fz);
    if (fln < 1e-4f) { w.adhesion = 0; w.state = 0; return; }
    fx /= fln; fy /= fln; fz /= fln;
    const float sx = w.ny * fz - w.nz * fy, sy = w.nz * fx - w.nx * fz, sz = w.nx * fy - w.ny * fx;
    w.fx = fx; w.fy = fy; w.fz = fz; w.sx = sx; w.sy = sy; w.sz = sz;
    float vel[3];
    velocityAt(w.cx, w.cy, w.cz, vel);
    const float vf = vel[0] * fx + vel[1] * fy + vel[2] * fz, vs = vel[0] * sx + vel[1] * sy + vel[2] * sz;
    w.forwardVel = vf; w.sideVel = vs;
    const float staticLoad = G_STEP * d.mass * nt * 0.25f;
    const float load = std::min(staticLoad * 3, 0.5f * staticLoad + 0.5f * w.normalImpulse);
    const float frontGrip = 2.0f * d.traction_bias;
    const float mu = d.traction * w.surface.adhesion * (w.front ? frontGrip : 2.0f - frontGrip);
    const float adhesion = mu * load;
    w.adhesion = adhesion;
    const float share = mass / static_cast<float>(std::max(1, groundedWheels));
    float side = -vs * share;
    if (hb) side *= d.handbrake_tyre;
    const float drive = driven(w.index) ? perWheel * d.mass * dt : 0;
    float brakeMax = d.brake_decel * d.mass * dt * brakeIn * (w.front ? d.brake_bias : 1.0f - d.brake_bias) * 0.5f;
    if (hb) brakeMax = 3.4e38f;
    const float stopNow = std::fabs(vf) * share;
    const float sgn = vf > 0 ? 1.0f : vf < 0 ? -1.0f : 0.0f;
    const float brake = brakeMax <= 0 ? 0 : -sgn * std::min(stopNow, brakeMax);
    const float rollMax = w.surface.rolling * d.mass * dt * 0.25f;
    const float rolling = -sgn * std::min(stopNow, rollMax);
    float forward = drive + brake + rolling;
    const float demand = std::sqrt(forward * forward + side * side);
    w.demand = demand;
    bool locked = hb && std::fabs(vf) > 0.3f;
    if (demand <= adhesion || demand < 1e-6f) { w.state = 0; w.slideTime = 0; }
    else {
        const float hold = adhesion * d.traction_loss / demand;
        forward *= hold; side *= hold;
        const bool braking = std::fabs(brake) > std::fabs(drive) && std::fabs(brake) > std::fabs(side) * 0.8f;
        if (locked || (braking && brakeIn > 0.5f)) { w.state = 3; locked = true; }
        else if (std::fabs(drive) > std::fabs(side) && std::fabs(drive) > adhesion * 0.8f) w.state = 1;
        else w.state = 2;
        w.slideTime += dt;
    }
    if (locked && w.state == 0 && hb) w.state = 3;
    applyImpulse(w.cx, w.cy, w.cz, fx * forward + sx * side, fy * forward + sy * side, fz * forward + sz * side);
    const float rad = std::max(0.1f, d.wheel_radius);
    switch (w.state) {
        case 3: w.rotationSpeed = 0; break;
        case 1: {
            const float over = std::min(3.0f, demand / std::max(1e-3f, adhesion) - 1.0f);
            w.rotationSpeed = (vf + (drive > 0 ? 1.0f : drive < 0 ? -1.0f : 0.0f) * (3.0f + over * 9.0f)) / rad;
            break;
        }
        default: w.rotationSpeed = vf / rad; break;
    }
    w.rotation += w.rotationSpeed * dt;
}

void Vehicle::step(const VoxelWorld* w, const Surface* surfaces, Report& out) {
    const float dt = FIXED_DT, nt = dt * 50.0f;
    snapshot();
    const float target = std::max(-1.0f, std::min(1.0f, steerIn));
    steering += (target - steering) * STEER_SMOOTH * nt;
    steering = std::max(-1.0f, std::min(1.0f, steering));
    const float effective = (steering > 0 ? 1.0f : steering < 0 ? -1.0f : 0.0f) * steering * steering;
    wheelAngle = d.steering_lock * PI / 180.0f * effective;
    wheels[0].steerAngle = wheels[1].steerAngle = wheelAngle;
    wheels[2].steerAngle = wheels[3].steerAngle = 0;
    const float fwd = forwardSpeed();
    const int gearBefore = gear;
    transmission(fwd, dt);
    if (gear != gearBefore) out.gearChanged = gear;
    const float driveAcc = driveAcceleration(fwd);
    const float perWheel = driveAcc / static_cast<float>(drivenWheels());
    const bool wasAir = groundedWheels == 0;
    const float fall = -vy;
    int grounded = 0;
    for (Wheel& wh : wheels) { suspension(wh, w, surfaces, fwd, nt); if (wh.grounded) grounded++; }
    groundedWheels = grounded;
    if (grounded == 0) airTime += dt;
    else {
        if (wasAir && airTime > 0.25f) out.landed = std::max(out.landed, fall);
        airTime = 0;
    }
    if (d.rear_first) { for (int i = 2; i < 4; i++) tyre(wheels[i], perWheel, nt, dt); for (int i = 0; i < 2; i++) tyre(wheels[i], perWheel, nt, dt); }
    else for (Wheel& wh : wheels) tyre(wh, perWheel, nt, dt);
    vy -= GRAVITY * dt;
    const float sp = speed();
    if (sp > 0.01f) {
        const float air = d.width * d.height / d.mass * 0.24f;
        const float k = std::max(0.0f, 1.0f - air * sp * dt);
        vx *= k; vy *= k; vz *= k;
        float drag = 0;
        for (const Wheel& wh : wheels) if (wh.grounded) drag += wh.surface.drag * 0.25f;
        if (drag > 0) { const float kd = std::max(0.0f, 1.0f - drag * dt); vx *= kd; vz *= kd; }
    }
    const float spin = 1.0f - (grounded > 0 ? 0.35f : 0.12f) * dt;
    wx *= spin; wy *= spin; wz *= spin;
    integrate(dt);
    collide(w, 0.18f, 0.55f, out);
    if (impact > 1.2f) {
        astratto_evt_vehicle_impact e{};
        e.dv = impact; e.x = impactX; e.y = impactY; e.z = impactZ;
        out.impacts.push_back(e);
    }
    const bool idle = sp < 0.06f && std::fabs(wx) + std::fabs(wy) + std::fabs(wz) < 0.05f && throttle == 0 && grounded >= 3;
    sleepTimer = idle ? sleepTimer + dt : 0;
    if (sleepTimer > 1.5f) { asleep = true; vx = vy = vz = wx = wy = wz = 0; }
}

void Vehicle::matrix(float* m) const {
    const float a = asleep ? 1.0f : std::max(0.0f, std::min(1.0f, accumulator / FIXED_DT));
    const float x = ppx + (px - ppx) * a, y = ppy + (py - ppy) * a, z = ppz + (pz - ppz) * a;
    const float dot = pqw * qw + pqx * qx + pqy * qy + pqz * qz, s = dot < 0 ? -1.0f : 1.0f;
    float w = pqw + (qw * s - pqw) * a, qxx = pqx + (qx * s - pqx) * a, qyy = pqy + (qy * s - pqy) * a, qzz = pqz + (qz * s - pqz) * a;
    const float l = std::sqrt(w * w + qxx * qxx + qyy * qyy + qzz * qzz);
    w /= l; qxx /= l; qyy /= l; qzz /= l;
    const float xx = qxx * qxx, yy = qyy * qyy, zz = qzz * qzz, xy = qxx * qyy, xz = qxx * qzz, yz = qyy * qzz, wxx = w * qxx, wyy = w * qyy, wzz = w * qzz;
    m[0] = 1 - 2 * (yy + zz); m[1] = 2 * (xy + wzz); m[2] = 2 * (xz - wyy); m[3] = 0;
    m[4] = 2 * (xy - wzz); m[5] = 1 - 2 * (xx + zz); m[6] = 2 * (yz + wxx); m[7] = 0;
    m[8] = 2 * (xz + wyy); m[9] = 2 * (yz - wxx); m[10] = 1 - 2 * (xx + yy); m[11] = 0;
    const float ox = -d.com_x, oy = -d.com_y, oz = -d.com_z;
    m[12] = x + m[0] * ox + m[4] * oy + m[8] * oz;
    m[13] = y + m[1] * ox + m[5] * oy + m[9] * oz;
    m[14] = z + m[2] * ox + m[6] * oy + m[10] * oz;
    m[15] = 1;
    (void)PI;
}

}  // namespace astratto
