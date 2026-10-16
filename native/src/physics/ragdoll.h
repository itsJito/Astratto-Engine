// Astratto Engine - Sydney: the fifteen-particle human body and the controllers that keep it on its feet.
//
// A port of the Java Sydney Engine (the game's character.Ragdoll solver, and sydney.SensorSystem, MotorController,
// BehaviorController, BehaviorCoordinator, SydneyCharacter) to the native core. The solver is Verlet: particles,
// rigid bones, joint ranges as distance limits, one-sided hinges, the body's own bulk, voxel collision with Coulomb
// friction. The controllers sense the body, decide what it should try (stand, step, brace, fall, lie, get up, die)
// and drive bounded muscle torques toward it; the solver decides what happens. Every rule and number is the Java one,
// in the same order, so that the same blow does the same thing.
#pragma once

#include <cstdint>

#include "astratto/astratto.h"
#include "world/voxel_world.h"

namespace astratto {

/** The world, as much as a falling body needs of it (null = no world: nothing to land on). */
using SolidFn = bool (*)(const VoxelWorld* w, int x, int y, int z);

namespace rd {
constexpr int HEAD_P = 0, NECK = 1, PELVIS = 2, LS = 3, LE = 4, LW = 5, RS = 6, RE = 7, RW = 8, LH = 9, LK = 10, LA = 11, RH = 12, RK = 13, RA = 14, N = 15;
constexpr float VOX = 1.8f / 32.0f;           // metres per rig voxel at scale 1
constexpr float THIGH = 6, SHIN = 6;
constexpr float STEP = 1.0f / 90.0f;
constexpr float ACTIVE_TIME = 8, BLEND_TIME = 0.08f, FRICTION = 0.9f;
extern const float MASS[N];
}  // namespace rd

struct SydneyConfig;
class SydneyCharacter;

class Ragdoll {
public:
    explicit Ragdoll(float scale);

    float gravity = 22.0f;                     // m/s^2: the physics service's ragdoll gravity

    float x[rd::N], y[rd::N], z[rd::N];
    float px[rd::N], py[rd::N], pz[rd::N];
    float fx[rd::N] = {}, fy[rd::N] = {}, fz[rd::N] = {};
    float invMass[rd::N];
    bool grounded[rd::N] = {};
    float unit;                                // metres per rig voxel for this body
    float age = 0, quiet = 0;
    bool settled = false, live = false;
    SydneyCharacter* driver = nullptr;
    float maxStretch = 0, maxHyper = 0;
    int selfContacts = 0;

    float vx(int i) const { return (x[i] - px[i]) / rd::STEP; }
    float vy(int i) const { return (y[i] - py[i]) / rd::STEP; }
    float vz(int i) const { return (z[i] - pz[i]) / rd::STEP; }
    void kick(int i, float dvx, float dvy, float dvz) { px[i] -= dvx * rd::STEP; py[i] -= dvy * rd::STEP; pz[i] -= dvz * rd::STEP; }

    /** Seeds the particles from an animated pose (world positions) and a base velocity. */
    void fromPose(const float* p, float vx, float vy, float vz);
    void standAt(float wx, float wy, float wz, float yaw);
    void shift(float dx, float dy, float dz);
    float unbury(const VoxelWorld* w);
    void impulse(int region, float vx, float vy, float vz);
    void carry(float vx, float vy, float vz, float share);
    void wake();
    int disturb(float wx, float wy, float wz, float radius, float vx, float vy, float vz);
    /** Advances by a frame's worth of fixed steps. Returns the steps run. */
    int update(const VoxelWorld* w, float dt);
    float lowestY() const;
    /** The way the trunk faces, as an actor's yaw (actor forward is (-sin yaw, -cos yaw)). */
    float yaw();
    void forward();
    float fwd[3] = {0, 0, -1};

private:
    void step(const VoxelWorld* w);
    void settle();
    float d(int a, int b) const;
    void pull(int a, int b, float min, float max);
    void hinges();
    void hinge(int a, int mid, int b, float sign);
    void selfCollide();
    void friction(int i);
    void collide(const VoxelWorld* w, int i);

    float tx_[rd::N], ty_[rd::N], tz_[rd::N];
    float rest_[32];
    float load_[rd::N] = {};
    float acc_ = 0;
};

// ------------------------------------------------------------------ Sydney
struct SydneyConfig {
    enum { SPINE = 0, HIP = 1, KNEE = 2, SHOULDER = 3, ELBOW = 4, NECK = 5, CLASSES = 6 };
    enum { HUMAN = 0, INFECTED = 1, CRAWLER = 2 };
    float freq[6] = {2.5f, 4.5f, 5.5f, 4.0f, 4.5f, 4.0f}, zeta[6] = {0.9f, 0.8f, 0.8f, 0.8f, 0.8f, 0.9f}, torque[6] = {1100, 700, 700, 160, 110, 70};
    float strength = 1, stanceGain = 3, standPosture = 1, stanceHip = 0.2f, carryHip = 0.2f, carryPosture = 1;
    float ankleLever = 0.13f, balanceGain = 1.8f, stepThreshold = 1.5f, stepTime = 0.2f, stepReach = 0.62f, stepLift = 0.1f, stepOvershoot = 0.1f, stepLead = 0.6f, stepForce = 900;
    int maxSteps = 4;
    float fallTilt = 52, fallHeight = 0.5f, lostReach = 1.0f, recoverSettle = 0.4f;
    float fallStrength = 0.3f, brace = 0.85f, downStrength = 0.08f, downMin = 0.9f, downPerForce = 0.02f, downMax = 3.5f;
    bool holdWound = true;
    float holdForce = 60;
    float getUpTime = 1.5f, getUpStrength = 1.9f, getUpTimeout = 1.9f;
    int getUpTries = 3;
    float deathFade = 0.5f, deathPosture = 0.25f;
    int style = HUMAN;
    float crawlHeight = 0.36f;

    static SydneyConfig human();
    static SydneyConfig infected();
    static SydneyConfig crawler();
    static SydneyConfig giant();
    void from(const astratto_sydney_profile& p);
};

/** The rig's view of the body: masses, the joint hierarchy, forces and torques, which limbs still work. */
struct PhysicsRig {
    static constexpr int NJ = 9;
    static constexpr int J_NECK = 0, J_L_SHOULDER = 1, J_L_ELBOW = 2, J_R_SHOULDER = 3, J_R_ELBOW = 4, J_L_HIP = 5, J_L_KNEE = 6, J_R_HIP = 7, J_R_KNEE = 8;
    static const int JOINTS[NJ][5];
    Ragdoll* r;
    float unit, massScale, totalMass;
    bool armL = true, armR = true, legL = true, legR = true, head = true;

    explicit PhysicsRig(Ragdoll* body);
    float mass(int i) const;
    float carried(int joint) const;
    bool limbWorks(int limb) const;
    bool footDown(int side) const;
    void force(int i, float fx, float fy, float fz);
    void bodyForce(float fx, float fy, float fz);
    void torque(int p, int j, int c, float tx, float ty, float tz);
};

struct SensorSystem {
    float com[3] = {}, comV[3] = {};
    float right[3] = {1, 0, 0}, up[3] = {0, 1, 0}, fwd[3] = {0, 0, -1};
    float supX = 0, supZ = 0, groundY = 0;
    int feet = 0, otherContacts = 0;
    bool footL = false, footR = false;
    float cpX = 0, cpZ = 0, errX = 0, errZ = 0, err = 0, errNorm = 0;
    float tilt = 0, height = 0, speed = 0, standHeight = 0, jolt = 0, stance = 0, omega = 0;
    float lastVx = 0, lastVy = 0, lastVz = 0;
    bool primed = false;
    int heldL = 0, heldR = 0;

    void measure(PhysicsRig& rig, const VoxelWorld* w, const SydneyConfig& cfg);
    float reach(const SydneyConfig& cfg, float dx, float dz) const;
};

struct Goal {
    struct Reach { bool on = false; int particle = 0, against = 0; float x = 0, y = 0, z = 0, force = 0, hz = 4; };
    float dir[PhysicsRig::NJ][3] = {};
    float gain[PhysicsRig::NJ] = {};
    float strength = 1;
    bool stance = false;
    float up[3] = {0, 1, 0};
    float posture = -1, postureOnGround = 0, balance = -1, lift = -1, height = 0;
    Reach reach[4];
    bool reachClaimed[4] = {};

    void clear();
    bool free(int j) const { return gain[j] < 0; }
    void joint(int j, float x, float y, float z, float g);
    void setPosture(float ux, float uy, float uz, float g, float onGround);
    void setBalance(float g) { if (balance < 0) balance = g; }
    void setLift(float g, float toHeight) { if (lift < 0) { lift = g; height = toHeight; } }
    bool reachFree(int slot) const { return !reachClaimed[slot]; }
    void setReach(int slot, int particle, int against, float x, float y, float z, float force, float hz);
    void finish();
};

class SydneyCharacter {
public:
    SydneyCharacter(Ragdoll* r, const SydneyConfig& cfg);

    enum State { STAND = 0, STEP = 1, FALL = 2, DOWN = 3, GETUP = 4, DEAD = 5 };
    enum Outcome { NONE = 0, RECOVERED = 1, CRAWL = 2, STUCK = 3, DEAD_OUTCOME = 4 };

    PhysicsRig rig;
    SensorSystem sense;
    Goal goal;
    SydneyConfig cfg;
    float scale;
    float pose[PhysicsRig::NJ][3] = {};
    bool alive = true, holding = false, primed = false, invalid = false;
    int wound = -1;

    // the coordinator
    State state = STAND;
    Outcome outcome = NONE;
    float t = 0, settled = 0, airborne = 0, quiet = 0, downFor = 1.2f, stun = 0, over = 0;
    int steps = 0, tries = 0, falls = 0, recoveries = 0;
    bool stayDown = false;
    float longestAir = 0;

    // the behaviours' own state
    float standLeg[4][3];
    float standEffort = 0;
    int stepSide = -1;
    float stepT = 0, stepSx = 0, stepSy = 0, stepSz = 0, stepTx = 0, stepTz = 0;
    float flailT = 0, lieTone = 1, limpT = 0;
    float getUpT = 0, getUpH0 = 0, getUpUp0[3] = {0, 1, 0};

    /** Called by the ragdoll before each fixed step: sense, decide, drive. */
    void drive(Ragdoll& r, const VoxelWorld* w, float dt);
    void hit(int particle, float dx, float dy, float dz, float impulse, float force);
    void die();
    void collapse();
    void setBody(uint32_t brokenLimbs);

private:
    void go(State s) { state = s; t = 0; }
    void fallNow() { falls++; quiet = 0; go(FALL); }
    void update(float dt);
    void capture();
    void onHit(float force);
    // behaviours
    void standGoals(float dt);
    void standPlant();
    void standReset();
    bool stepBegin();
    bool stepDone() const;
    void stepGoals(float dt);
    void braceGoals();
    void fallGoals();
    void flailGoals(float dt);
    void lieGoals();
    void holdGoals();
    void getUpBegin();
    float getUpProgress() const { return getUpT / cfg.getUpTime; }
    void getUpGoals(float dt);
    void scramble(float p);
    void limpGoals(float dt);
    // motors
    void motors();
    void posture();
    void balance();
    void lift();
    void reachFor(const Goal::Reach& e);
    void frame(int j, float* f) const;
    static void toLocal(const float* f, float wx, float wy, float wz, float* out);
    void local(float wx, float wy, float wz, float* out) const;
};

}  // namespace astratto
