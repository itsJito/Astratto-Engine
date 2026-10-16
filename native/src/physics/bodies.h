// Astratto Engine - the bodies the physics service moves besides the ragdoll: an upright character controller, soft
// bodies of any shape, and cars.
#pragma once

#include <cstdint>
#include <vector>

#include "astratto/astratto.h"
#include "world/voxel_world.h"

namespace astratto {

/** What a surface does to a tyre (ASTRATTO_CMD_SURFACE). */
struct Surface { float adhesion = 0.95f, rolling = 0.15f, drag = 0; };

// ------------------------------------------------------------------ the character controller
/**
 * An upright box moved through the voxel world: the movement law of the game's entity.Physics. Each axis is moved on
 * its own and stopped by solid cells; stairs are walked (carried up the line of the treads, and down them without
 * stepping off each into the air) and nothing else is stepped onto; a body put inside something is lifted or pushed
 * out to the nearest free place; ladders are climbed. Large moves are cut into steps of 0.4 m or less.
 */
struct Mover {
    float x = 0, y = 0, z = 0, vx = 0, vy = 0, vz = 0;
    float radius = 0.3f, height = 1.8f, crouchHeight = 1.2f, step = 0.6f;
    uint32_t flags = 0;
    // input
    float wishX = 0, wishY = 0, wishZ = 0, jump = 0, accel = 60;
    bool crouch = false, climb = false;
    // state
    bool onGround = false, climbing = false;
    float fallSpeed = 0, stepped = 0, acc = 0;

    float h() const { return crouch ? crouchHeight : height; }
    /** Advances by dt at the mover's fixed rate. Returns the landing speed if it came down this frame (0 if not). */
    float update(const VoxelWorld* w, float dt, float gravity, int* steps = nullptr);

    static bool bodyFree(const VoxelWorld* w, float x, float y, float z, float r, float h);
    static float stairHeight(const VoxelWorld* w, int bx, int by, int bz, float px, float pz, float r);

private:
    void tick(const VoxelWorld* w, float dt, float gravity);
    bool move(const VoxelWorld* w, float dt, float gravity);
    bool subStep(const VoxelWorld* w, float dt, float gravity);
    static float stairRise(const VoxelWorld* w, float x, float y, float z, float r, float h, float lip);
    bool climbStair(const VoxelWorld* w, float nx, float nz, float r, float h);
    static float stairBelow(const VoxelWorld* w, float x, float y, float z, float r);
    void depenetrate(const VoxelWorld* w, float r, float h);
    static bool inClimb(const VoxelWorld* w, float x, float y, float z);
};

// ------------------------------------------------------------------ soft bodies
/** The game's sydney.SoftBody: particles, rigid sticks, limits; Verlet at 90 Hz; voxel collision with a little bounce. */
struct SoftBody {
    static constexpr float STEP = 1.0f / 90.0f;
    int n = 0;
    std::vector<float> x, y, z, px, py, pz, fx, fy, fz, radius, invMass;
    std::vector<uint8_t> grounded;
    std::vector<uint32_t> stickA, stickB, limA, limB;
    std::vector<float> stickLen, limMin, limMax;
    float acc = 0, still = 0, age = 0;
    bool settled = false;
    float slide = 0.80f, bounce = 0.18f, air = 0.992f;

    void particle(int i, float wx, float wy, float wz, float r, float massKg);
    float dist(int a, int b) const;
    void velocity(float vx, float vy, float vz);
    void impulse(int i, float dvx, float dvy, float dvz);
    void impulseAt(float wx, float wy, float wz, float dvx, float dvy, float dvz, float reach);
    void wake() { settled = false; still = 0; }
    float centre(int axis) const;
    int update(const VoxelWorld* w, float dt, float gravity);

private:
    void step(const VoxelWorld* w, float gravity);
    void solve(int a, int b, float min, float max, float stiff);
    void collide(const VoxelWorld* w, int i);
};

// ------------------------------------------------------------------ cars
/**
 * The game's vehicle model: one dynamic rigid body carried on four independently simulated wheel contacts, at a
 * fixed 50 Hz. Steering turns the front wheels and the car turns because those tyres then push sideways; the
 * accelerator asks the transmission for thrust at the driven wheels, and whether it becomes speed is their grip's
 * affair. Suspension, tyres, gravity, drag and collisions are all impulses on the body, integrated once a step.
 */
struct Vehicle {
    static constexpr float FIXED_DT = 0.02f, STEER_SMOOTH = 0.2f, GRAVITY = 9.81f, G_STEP = GRAVITY * FIXED_DT, DAMP_K = 0.17f;

    struct Wheel {
        int index = 0;
        bool front = false, grounded = false;
        float ax = 0, ay = 0, az = 0;
        float cx = 0, cy = 0, cz = 0, nx = 0, ny = 1, nz = 0;
        int block = 0;
        Surface surface;
        float length = 0, compression = 0, normalImpulse = 0;
        float fx = 0, fy = 0, fz = 0, sx = 0, sy = 0, sz = 0, forwardVel = 0, sideVel = 0, steerAngle = 0;
        float rotation = 0, rotationSpeed = 0, adhesion = 0, demand = 0;
        int state = 0;                      // 0 normal, 1 spinning, 2 skidding, 3 fixed
        float slideTime = 0;
    };

    astratto_vehicle_desc d{};
    std::vector<float> boxes;               // 6 floats a box
    // the rigid body
    float px = 0, py = 0, pz = 0, qw = 1, qx = 0, qy = 0, qz = 0, vx = 0, vy = 0, vz = 0, wx = 0, wy = 0, wz = 0;
    float mass = 1000, invMass = 0.001f, invIx = 0, invIy = 0, invIz = 0;
    float r[9] = {1, 0, 0, 0, 1, 0, 0, 0, 1};
    std::vector<float> local, prev;
    bool prevValid = false;
    float impact = 0, impactX = 0, impactY = 0, impactZ = 0;
    // the car
    Wheel wheels[4];
    float throttle = 0, brakeIn = 0, steerIn = 0;
    bool handbrake = false, reverse = false, engineOn = true;
    int gear = 1;
    float sinceShift = 9, band = 0;
    float steering = 0, wheelAngle = 0, airTime = 0;
    int groundedWheels = 0;
    float accumulator = 0, sleepTimer = 0;
    bool asleep = false;
    float ppx = 0, ppy = 0, ppz = 0, pqw = 1, pqx = 0, pqy = 0, pqz = 0;
    uint32_t rng = 12345;

    struct Report { std::vector<astratto_evt_vehicle_impact> impacts, blocks; float landed = 0; int gearChanged = -1; };

    void create(const astratto_vehicle_desc& desc, const float* boxData);
    void place(float x, float y, float z, float yaw);
    int update(const VoxelWorld* w, const Surface* surfaces, float frameDt, Report& out);
    void matrix(float* m) const;
    float speed() const;
    float forwardSpeed() const { return vx * r[2] + vy * r[5] + vz * r[8]; }
    void applyImpulse(float x, float y, float z, float jx, float jy, float jz);

private:
    void step(const VoxelWorld* w, const Surface* surfaces, Report& out);
    void snapshot();
    void updateRotation();
    void toWorld(float lx, float ly, float lz, float* out) const;
    void velocityAt(float x, float y, float z, float* out) const;
    void invInertia(float tx, float ty, float tz, float* out) const;
    float invMassAt(float x, float y, float z, float nx, float ny, float nz) const;
    void integrate(float dt);
    void collide(const VoxelWorld* w, float restitution, float friction, Report& out);
    void suspension(Wheel& wh, const VoxelWorld* w, const Surface* surfaces, float fwd, float nt);
    void tyre(Wheel& wh, float perWheel, float nt, float dt);
    float gearMax(int g) const;
    float upshiftAt(int g) const;
    float downshiftAt(int g) const;
    void transmission(float fwd, float dt);
    float driveAcceleration(float fwd) const;
    bool driven(int wheel) const { return d.drive == ASTRATTO_DRIVE_FOUR || (d.drive == ASTRATTO_DRIVE_FRONT) == (wheel < 2); }
    int drivenWheels() const { return d.drive == ASTRATTO_DRIVE_FOUR ? 4 : 2; }
    float noise() { rng = rng * 1103515245u + 12345u; return static_cast<float>((rng >> 8) & 0xFFFF) / 65535.0f; }
};

}  // namespace astratto
