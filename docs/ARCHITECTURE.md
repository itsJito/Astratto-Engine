# Astratto Engine - architecture

Astratto is the technology under Absentialis, being turned into an engine that a future game can use without it.
This file is the plan and the record: how the engine and a game are divided, the one bridge between them, what has
crossed so far, and what is still to cross. Written for 1.2 (2026-10-14), when the split began.

## The division

| Side | Owns | Language |
|---|---|---|
| **Engine, native core** (`astratto/native`) | World state, resource management, rendering, physics and physical animation (Sydney), NeuralAI's perception / route search / plan execution, UltraEngine's audio processing. Native memory and the authoritative state of whatever it simulates. | C++17 |
| **Engine, Java** (`src/main/java/astratto`, and for now `absentialis.engine`, `absentialis.neuralai`, `absentialis.sydney`) | The bindings to the native core; reusable interface, persistence and tool code that has not moved yet. | Java 21 |
| **Game** (`absentialis.*` everything else) | Rules, inventory, HUD composition, missions, character goals, AI *policies* (what a mind wants), editor workflows, content. Configures and requests engine services; never executes them. | Java 21 |
| **Shaders** | Stay shader code, managed by the renderer. | GLSL |

Rules that the bridge is shaped by (they are restated in `astratto/native/include/astratto/astratto.h`):

1. **One bridge.** `astratto.h` is a plain-C, versioned interface (`ASTRATTO_API_MAJOR.MINOR`). A caller built for
   another major version is refused at `astratto_create`. Every structure starts with its own size so it can grow.
2. **Batched.** Nothing is asked of the engine an object at a time. The game writes a tick's commands into a buffer
   (`astratto.bridge.CommandBuffer`) and hands the batch over once a tick (`astratto_frame`); answers come back in
   the same call as a batch of events, in the order the questions were written.
3. **Snapshots, read-only.** State is read back as immutable blocks of plain data (`astratto_snapshot`). Pointers
   the engine returns stay valid until the next call that returns them; the caller never writes or frees them.
4. **No callbacks.** The engine never calls into Java - not from a frame, not from a worker thread.
5. **Engine memory is the engine's.** World data handed over is copied once; from then on the engine's copy changes
   only by command.

The Java binding uses LWJGL's foreign-call layer (`org.lwjgl.system.JNI`, already a dependency), so the native core
has no JNI code in it and can be bound from any language that can call C.

## What has crossed (1.2: stage 1)

| Service | Native (C++) | Java side | How it is held to the old behaviour |
|---|---|---|---|
| Voxel world (one byte a cell, block-kind table) | `world/voxel_world` | `absentialis.world.NativeLink`: uploads the world on the first tick of an operation; `World.set`/`setRaw` follow it across as `BLOCK_SET` commands; door locks as `DOOR_SEAL` / `DOORS_UNSEAL` | `native12`: knocking a wall through and rebuilding it changes native routes exactly as Java's |
| NeuralAI route search (grid A*: steps, drops, ladders, pull-ups, doors, hazards) | `nav/pathfinder`, run in parallel on a worker pool | `Steering` asks through `NativeLink.requestPath`; the route is in hand at the next tick | `native12`: 400 routes across the generated city, identical to `world.Nav.path` cell for cell |
| NeuralAI line of sight (as light goes, and as an eye sees: glass, doors, furniture heights, canopy) | `perception/sight` | `NativeLink.requestSight` (available; perception still calls the Java walk - see stage 2) | `native12`: 3000 lines, identical to `Physics.los` / `Physics.sight` |
| Counters snapshot | `astratto_snapshot(STATS)` | `NativeEngine.stats()` | `native12`, and the console's `NativeLink.describe()` |

Build: `astratto/native/build.bat` (MSVC, found with vswhere) or `./gradlew.bat buildNative`; `CMakeLists.txt` for
everything else. The native core builds and runs on its own: `astratto/bin/astratto_selftest.exe` makes a small world
of its own and checks the interface end to end (27 checks) without any game present.

**Fallback.** With no library on the machine (or `ABS_NATIVE=0`) the game runs exactly as 1.1 did: route searches are
made in Java. A route asked of the engine while nothing is running the engine's tick (a body steered outside an
operation's update) is not waited on - it is searched for in Java, and counted (`NativeLink.stalls`). The Java search
stays in the tree as the reference the native one is held to; that duplication is deliberate and temporary, and ends
when stage 2 moves the remaining callers.

**One world at a time.** The engine holds one world. Linking another lets the previous link go; a world that is
rebuilt in place (`CityGenerator`, `DevMap`, `World.restorePristine`) drops its link and is given over again on next use.

## What has crossed (1.3: stage 2 - rendering)

The 1.3 brief asked for stage 2 to be rendering, matching the current output one to one. The part of rendering that is
CPU work and does not touch GL crossed: the voxel chunk mesher.

| Service | Native (C++) | Java side | How it is held to the old behaviour |
|---|---|---|---|
| Chunk meshing (greedy faces with per-corner AO and smooth light, crossed planes, shaped blocks turned by their neighbours) | `render/chunk_mesher` behind `astratto_mesher_create` / `astratto_mesh_column` (interface 1.1) | `astratto.bridge.NativeMesher` (names nothing of the game); `absentialis.engine.render.ChunkMesher` builds the render table from `Block`/`Shapes` once and hands each column over (18 x H x 18 cells and light) | `render13`: all 2710 columns of the city byte-identical, with doors drawn and plants removed as well; in play, frames native vs Java pixel-identical, or within 1/255 where probes settle a frame sooner |

**Not in the tick.** A mesher does not run inside `astratto_frame`: the renderer meshes on its own threads (the whole
world at load, changed columns after), in the hideout as well as on operations, with no engine world. So a mesher is
its own object, made from a render table and used by one thread at a time; a column is its unit of work. It holds no
world: light is computed in Java (`world.Lighting`) and travels with each column, which also keeps the mesher out of
the question of who owns the cells until that is settled.

**Fallback.** `ABS_NATIVE=0` (everything) or `ABS_NATIVE_MESH=0` (the mesher alone) mesh in Java; so does a machine
without the library. 1.47 ms a column native against 2.69 ms in Java, on one thread, copies included.

## What has not crossed, and the order it should go in

Every system was looked at for 1.2. In the order that the dependency graph allows:

| Stage | System | Now | To do |
|---|---|---|---|
| 2b | NeuralAI perception | Java (`neuralai.perception`, `Physics.sight` called inline, 34 call sites) | Batch a tick's sight lines through `requestSight`; perception reads last tick's answers. The native side exists. |
| 2b | Straight-line walkability (`Nav.clear`, `clearBody`, `stand`, `stepY`) | Java, called inline by steering | Port beside the pathfinder; steering asks with its route request. |
| 2b | World ownership | Java `World.blocks` is still the array the renderer and physics read; the native copy mirrors it | Allocate the cells natively and give Java a read-only view (direct buffer) - the mirror and its upload go away. |
| 3 | Sydney physical animation (`character.Ragdoll`, `sydney.SoftBody`, controllers) | Java | Port the Verlet solver and the motor controllers; game sends motion intents, reads pose snapshots. `SoftBody` was written for 1.2 with no game imports so that it moves as one piece. |
| 3 | Body / vehicle physics (`entity.Physics`, `vehicle.*`) | Java | After Sydney: one native physics world. |
| 4 | UltraEngine audio processing (mixing, occlusion, reverb) | Java (`engine.audio`) | Port the mixer and DSP; the game queues cues per tick. Decoding can stay where it is until then. |
| 5 | Rendering (`engine.render`: hybrid GI, probes, the GL passes) | Java + GLSL | Meshing crossed in 1.3 (above). The rest is the most tied to LWJGL's GL bindings and goes last. |

### Debt: engine packages that still name the game's classes

These 23 files are inside the engine's Java packages and import game classes. `boundary12` counts them and fails if
the number grows; it may only shrink.

- `engine/render`: `Atlas`, `ChunkMesher`, `HybridRenderer`, `ProbeField`, `VoxelVolume`, `WorldRenderer` - read
  `world.World` / `world.Block` directly. Cut by handing the renderer a block-kind table (as the native core is given)
  and a cell view instead of the game's classes.
- `neuralai/integration` (10 files: `Blows`, `Breach`, `Creatures`, `GameAdapter`, `Lines`, `NeuralHost`, `Relations`,
  `Routines`, `Steering`, `SydneyAdapter`) - this package *is* the adapter between NeuralAI and the game. It should move
  out of the engine into the game (`absentialis.ai`), leaving `neuralai.api` as the engine's interface.
- `sydney` (7 files: `AnimationBridge`, `BehaviorController`, `ContextualMotion`, `MotorController`, `PhysicsRig`,
  `SensorSystem`, `SydneyCharacter`) - depend on `character.Ragdoll` / `Rig` and `entity.Actor`. Cut when the humanoid
  ragdoll is expressed as a `SoftBody` rig (stage 3).

`src/main/java/astratto` (the bridge) names nothing of Absentialis; `boundary12` checks that too.
