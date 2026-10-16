// Astratto Engine - NeuralAI's eyes: is the line between two points clear?
#pragma once

#include "world/voxel_world.h"

namespace astratto {

/**
 * One exact walk along a line, cell by cell: every cell the line passes through is looked at, once. The cell the
 * line starts in and the cell it ends in are not obstacles - whoever is looking stands in one and what is looked at
 * is in the other.
 *
 * With {@code eye} it is what an eye can see through: a full block stops it unless it is glass or mesh; a shut door
 * stops it; furniture stops it at the heights where it has bulk; four cells of leaves stop it. Without, only
 * opaque blocks do.
 */
bool sightClear(const VoxelWorld& w, float ax, float ay, float az, float bx, float by, float bz, bool eye);

}  // namespace astratto
