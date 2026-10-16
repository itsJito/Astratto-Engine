# Astratto Engine - architecture

The Astratto Engine is the technology Absentialis was built on, made into an engine that another game can use without
it. This file is the plan and the record: how an engine and a game are divided, the one bridge between them, what each
of the eight steps of the 2.0 split delivered, how each is held to its promise, and what is still open.

History: 1.2 (2026-10-14) began the split with a native core holding a copy of the world and answering route searches;
1.3 moved chunk meshing. 2.0 (2026-10-16) is the split itself, recorded below step by step.

## The division

| Side | Owns | Language |
|---|---|---|
| **Engine, native core** (`native/`) | The worlds (cells, light, doors) - authoritative; rendering (every GPU resource and pass); physics (character bodies, Sydney's ragdolls and soft bodies, cars); AI (perception, route search, walkability, decisions, plan execution); audio (mixing, positional effects, occlusion, reverb, dynamics); gameplay modules written in C++. | C++17 |
| **Engine, Java** (`java/`) | The binding to the core (`astratto.bridge`); the module runner (`astratto.game`); the platform layer (window, input); reusable systems that decide *where* things are and hand the core the result (`astratto.foliage`, `astratto.decal`, `astratto.props`); 2D, fonts, voxel models, math, events, tools; the Java reference implementations the native ones were held to (`astratto.ai`, `astratto.audio`). | Java 21 |
| **Shaders** (`shaders/`) | Stay shader code: GLSL files, bundled in the jar, handed to the renderer by name. | GLSL |
| **Game** (anything else - Absentialis, or `templates/new-game`) | Rules, inventory, HUD, missions, content, saves, AI *policies* (what a mind wants), what blocks mean and look like. It configures and commands engine services; it never executes them. | Java and/or C++ |

## The bridge

`native/include/astratto/astratto.h` is the whole interface: plain C, versioned (`ASTRATTO_API_MAJOR.MINOR`, now
**2.1**), bindable from any language with a foreign-function interface. Its rules:

1. **Batched.** A caller writes a tick's commands into one buffer and hands it over once (`astratto_frame`); the answers
   and what happened come back as one stream of events. Only creating, viewing and releasing things are calls of their
   own.
2. **The engine owns the state.** Worlds, bodies, agents, sounds and modules live in native memory; the caller sees
   them through read-only views (`astratto_world_get_view` - the engine's memory itself, not a copy) and snapshots.
3. **Handles.** Everything is named by a 64-bit handle carrying its kind and a generation; a released or forged handle
   is refused (a status, or an `EVT_ERROR` event), never dereferenced.
4. **Explicit lifetimes.** Commands: read during the call. Events: until the next frame. Snapshots: until the next
   snapshot of that kind. Views: until the world is released. Data given to a create call: copied.
5. **No callbacks.** The engine never calls the caller. C++ gameplay modules are called by the engine only inside
   `astratto_frame`, on the caller's thread; Java modules are called by the Java runner between frames. No engine worker
   thread ever leaves native code.
6. **Growable.** Every structure starts with its size; unknown command records are stepped over by their size; a
   caller built for another major version is refused at `astratto_create`.

The Java binding (`astratto.bridge.AstrattoNative`) calls the C functions through LWJGL's foreign-call layer - there is
no JNI code in the native core. When the library loads, the size of every structure the binding writes or reads is
checked against the library's own (`astratto_layout`); a library built from another header is refused.

### One tick

```
Java modules' update ──commands──> astratto_frame:  1. commands in order (changes now, questions kept)
                                                     2. C++ modules' update (on this thread)
                                                     3. physics, AI, audio - fixed rates, for dt seconds
                                                     4. questions answered on the workers, in the order asked
                                                     5. C++ modules' event
Java modules' events, afterFrame  <──events──────────  (returned)
astratto_render_frame (GL thread): camera, environment, lights, models, layers, particles, 2D
```

## The eight steps of 2.0

### 1. One engine directory, with no game in it

`theastrattoengine/` holds `native/`, `java/`, `shaders/`, `resources/`, `tools/`, `tests/`, `docs/` and
`templates/new-game/`, and builds on its own. The engine depends on nothing of Absentialis:

- No engine source names the game (`BoundaryTest.noJavaCodeNamesTheGame`, `noNativeCodeNamesTheGame`; comments may
  record where code came from, code may not), every Java class is in `astratto.*`, and no build file reaches outside
  the directory.
- The engine reads no game files: NeuralAI's Java tuning and the gun-audio profiles are read from resources the game
  names (`NeuralConfig.source(...)`, `WeaponAudioProfile.resource`); Absentialis names its `/content/*.json` in
  `Main` (`BoundaryTest.theEngineReadsNoGameFiles`). The game's domain events (raids, factions, extraction, missions)
  moved out of the engine to `absentialis.events.Events`; the engine keeps the event bus.
- Absentialis includes the engine as a composite build and depends on `astratto:theastrattoengine:2.0.0` like any
  other library.

### 2. The renderer is native

`native/src/render/` is the hybrid global-illumination renderer, pass for pass the one Absentialis grew in Java: chunk
meshing of the engine's own world on the engine's workers, the GPU voxel volume and the irradiance probes traced
through it, shadow cascades, G-buffer, compute light culling, GTAO, SSR, lighting, volumetric fog, the forward pass
(glass, water, see-through models, particles), TAA, bloom, auto exposure, tone mapping and grading, and the 2D layer.
2.1 added the **layers** (`render/layers.cpp`): foliage and props drawn instanced and decals drawn over surfaces, into
the G-buffer and the first shadow cascades. The Java foliage, decal and prop systems keep their placement rules - where
a plant grows, where a stain runs - and hand the renderer meshes and instance lists (`initNative`, `frame`).

Every GPU resource (textures, meshes, instance buffers, targets, programs) is created, used and freed natively; the
GLSL stays in `shaders/`. Held by: `IntegrationTest.theNativeRendererDrawsAnEngineWorld` (an engine world meshed and
drawn with all three layers into a real GL 4.5 context, read back); in Absentialis, `ABS_NATIVE_RENDER=1` draws the
city through it - the `car` scenario's frames match the Java renderer's with the foliage, decals and props in place.

### 3. The world and the simulation are the engine's

Worlds are created, edited and lit by the engine (`astratto_world_create`, edit records, `ASTRATTO_WORLD_LIGHT`); the
caller reads them through the view. In Absentialis a linked `World` lets its arrays go and reads the engine's memory
(gates `v20`: the city's cells and light, edits, relighting and handing back all agree with the Java code they
replaced). The interface carries entity handles, batched commands and events, the memory lifetimes above, and a real
simulation: `astratto_frame` runs physics, AI and audio for `dt` at fixed internal rates, so a run does not depend on
how it is cut into frames (native self-test: "a body's fall is the same whether its second is cut into 120 frames or
24"; `InteropTest.snapshotsAreCopiesTheCallerOwns` checks the simulated time and steps).

### 4. Native AI with game-supplied behaviour

`native/src/ai/`: an agent perceives with the engine's own sight lines (as an eye sees: glass, doors, furniture,
canopy) and hears noises; decides with a **policy** - actions scored by considerations over sixteen game-set facts and
what the engine knows (target seen, distance, time since seen or heard, ...), with inertia; and carries out movement
actions with the native pathfinder and walkability checks (`nav/`, also offered as `WALK_QUERY`), re-planning when the
world changes. Policies are data a game builds; an action of kind `CUSTOM` is carried out by the game's Java or C++
module (the engine only reports that it was chosen). Held by the native self-test (an agent behind a wall does not see;
told by a fact, it routes through the gap, sees, approaches and arrives) and `IntegrationTest`.

### 5. Physics and audio are native services

- `physics/sydney.cpp`, `ragdoll.cpp`: Sydney's fifteen-particle ragdoll and its controllers (balance, stepping,
  falling, bracing, getting up, dying), with profiles for humans, infected, crawlers and giants, and soft bodies.
- `physics/bodies.cpp`: character bodies (movers) and cars (rigid body, suspension, tyres, transmission, surfaces).
- `audio/`: UltraEngine's mixer on its own thread - five buses and a master, distance, panning, air absorption,
  occlusion through the world, a shared reverb sized from the geometry round the listener, compressor and limiter - or
  rendered on request without a device.

Motion commands (`MOVER_INPUT`, `RAGDOLL_HIT`, `VEHICLE_INPUT`, ...), pose snapshots, collision events (`LANDED`,
`VEHICLE_IMPACT`, `BLOCK_HIT`, `RAGDOLL_STATE`) and audio cues (`VOICE_PLAY`, `LISTENER`) cross the interface. Held by
the native self-test (75 checks: physics, Sydney, cars, AI, audio, modules, handles, and the hardening checks - large
route searches, the same frames on one thread and on several, command streams of noise, handle churn, modules that
release themselves) and `IntegrationTest`.

### 6. Gameplay in Java or C++

- Java: `astratto.game.GameModule` (init, update, events by kind, afterFrame, shutdown), run by `astratto.game.Game`.
- C++: `native/include/astratto/astratto_module.h` (init, update, event, shutdown), a shared library loaded with
  `astratto_module_load` / `Game.loadNative`; `native/modules/example` is the one to copy. It links nothing of the
  engine and talks to it only through the host it is handed.

Both write commands and read events, snapshots and the view. The rule that native worker threads never call Java is
kept by construction (there is no callback in the interface - `BoundaryTest.theInterfaceHasNoCallbacksIntoTheCaller`,
`theNativeCoreNeverCallsJava`) and a `Game` refuses to be ticked from any thread but its own
(`InteropTest.gameplayModulesInJavaAndCppRunInTheStatedOrder`, which also checks the order and that both kinds of module
see the same events).

### 7. Independently buildable and relocatable

- `./gradlew buildNative`: `native/build.bat` (Visual C++, found with vswhere) on Windows, CMake elsewhere
  (`native/CMakeLists.txt`), into `native/bin/<os>-<arch>/`, then the self-test.
- `./gradlew build`: the jar carries the native library (`astratto/natives/<os>-<arch>/`), the shaders and the
  resources; the binding extracts the library for the machine it runs on. Javadoc and sources jars are built too.
- Dependencies are declared in `build.gradle` (LWJGL core, GLFW, stb, OpenGL bindings; JLayer; JUnit for tests); the
  native core needs only the C++17 standard library and the system's libraries. `LICENSE.md` and `NOTICE.md` (every
  third-party component and its licence) ship in the jar.
- Documentation: `README.md`, `docs/API.md` (the guide), the header (the reference), Javadoc.
- `templates/new-game`: a starter game with an empty map built from code - nothing from the Absentialis repository.

### 8. Acceptance

`./gradlew acceptance` (in the engine) copies the engine's sources - no build output, no native binaries - to a new
directory (`-PacceptanceDir=...`, default under the system's temporary directory) and there builds the native core from
source, builds the engine and runs its tests, then runs the starter game off-screen for 600 frames with
`ASTRATTO_JAVA_FALLBACK=0` and checks that the world was meshed and drawn natively and the picture is a picture, that
the bodies, the ragdoll and the car moved, that the AI decided, heard and moved, that the mixer mixed, that the Java
modules ran every tick and received events, and that the C++ module ran inside the frames.

With Java fallbacks disabled, `NativeEngine.create`, `NativeRenderer.create` and `NativeMesher.create` throw instead
of returning null, so nothing passes on a Java path.

## What is not done, or not verified

| What | State |
|---|---|
| Other platforms | The core is written for them (platform code is behind `_WIN32` / POSIX: module loading, the audio device) and `CMakeLists.txt` builds it. CI builds and runs the self-test, sanitizers, benchmark and Java tests on Linux x64 and macOS arm64 for every push; until those jobs have passed, treat those platforms as unverified. Off Windows the audio device is the null device (audio is mixed on request). |
| Absentialis on the native services | Absentialis uses the native world (owned by the engine), routes, sight and meshing, and - with `ABS_NATIVE_RENDER=1` - the native renderer. Its characters, ragdolls, cars, AI and audio still run on its Java implementations (now in `astratto.ai`, `astratto.audio` and the game's own packages); moving the game onto the native services is the game's work, not the engine's. The native renderer is not yet the game's default because a world that is not linked to the engine (the hideout preview) is still drawn by the game's Java renderer. |
| Java GL code in the engine | `astratto.render` (Batch2D, Font, ModelRenderer, Shader, Screenshot) and the GL paths of Foliage, Decals and Props remain for Absentialis's Java renderer. The engine's own renderer and the starter game use none of them; they go when the game stops using its Java renderer, and `lwjgl-opengl` with them. |
| Pixel parity | The native renderer was moved pass for pass; frames compared by eye and by mean difference in play (the `car` scenario), not gated pixel for pixel. |
