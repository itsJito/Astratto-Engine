// Astratto Engine - NeuralAI's route search: A* over the cells of a voxel world a body can stand in.
#pragma once

#include <cstdint>
#include <vector>

#include "world/voxel_world.h"

namespace astratto {

struct NavTuning {
    float jumpCost = 7.0f;     // a one-block ledge that has to be jumped
    float ladderCost = 1.5f;   // each rung
    float mantleCost = 3.2f;   // each block of a pull-up
};

struct PathQuery {
    float sx, sy, sz, tx, ty, tz;
    int maxExpand;
    bool doors, goalY, mantle;
};

/**
 * One searcher: its scratch memory is its own, so each worker thread has one and a batch of searches runs in
 * parallel over a world nobody is changing. The search is exactly the one the game ran in Java before the engine
 * was split - same neighbours, same costs, same tie-breaks - so a route asked of either comes out cell for cell
 * the same (the game's "native12" gates hold it to that).
 */
class Pathfinder {
public:
    Pathfinder();

    /** Cells {x, y, z, ...} from the first step to the goal (or the nearest approach to it). False when boxed in. */
    bool path(const VoxelWorld& w, const NavTuning& tune, const PathQuery& q, std::vector<int32_t>& out, uint32_t& expansions);

    // walkability: what the search is made of, and what a caller may ask on its own
    static bool pass(const VoxelWorld& w, int x, int y, int z, bool doors);
    static bool stand(const VoxelWorld& w, int x, int y, int z, bool doors);
    static int stepY(const VoxelWorld& w, int x, int z, int y, bool doors);
    static bool climb(const VoxelWorld& w, int x, int y, int z);
    /** A body can walk the straight line from (sx, sy, sz) to (tx, tz): no walls, hazards, drops over three or steps over one that is not a stair. */
    static bool clear(const VoxelWorld& w, float sx, float sy, float sz, float tx, float tz, bool doors);
    /** The same for something with shoulders: the line and both its edges, r to either side. */
    static bool clearBody(const VoxelWorld& w, float sx, float sy, float sz, float tx, float tz, bool doors, float r);
    /** A cell a body has to go through squarely: solid on both sides of it at foot or head height (a doorway). */
    static bool choke(const VoxelWorld& w, int x, int y, int z);

private:
    static constexpr int CAP = 6000;
    static constexpr int SLOTS = 1 << 14;

    int find(int k, bool create);
    void push(int n);
    int pop();
    void relax(int from, int x, int y, int z, float cost, int gx, int gz);

    std::vector<int> nk_, parent_, heap_, slot_, slotGen_;
    std::vector<float> ng_, nf_;
    std::vector<uint8_t> closed_;
    int count_ = 0, heapN_ = 0, gen_ = 0;
};

}  // namespace astratto
