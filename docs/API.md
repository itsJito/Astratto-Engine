# Astratto Engine 2.0 - using it

The reference is the C header, `native/include/astratto/astratto.h`: every function, structure and constant is
documented there, and every structure starts with its size and every function returns a status. The Java library
binds it one to one; its Javadoc (`./gradlew javadoc`, `build/docs/javadoc`) documents the Java side. This page is the
guide: how the pieces are used together.

## 1. The shape of a tick

```
            game thread                                   engine
  ------------------------------------      --------------------------------------------
  Java modules: update(ctx, dt)
     write commands  ---------------->      1. the batch, in order (changes now, questions kept)
                                            2. C++ modules: update (on this thread)
                                            3. physics -> AI -> audio, at fixed rates, for dt
                                            4. questions answered (on the workers), in the order asked
                                            5. C++ modules: event
     <---------------- events               (returned from astratto_frame)
  Java modules: events by kind, then afterFrame(ctx)
  renderer: one frame description -------> astratto_render_frame (same thread as the GL context)
```

Rules: one thread drives an engine; the engine never calls the caller (no callbacks, ever - a C++ module is called
by the engine only inside `astratto_frame`, on the caller's thread); state comes back as events, snapshots and
read-only views; everything the engine holds is named by a handle with a generation, so a stale handle is refused,
never dereferenced.

## 2. Java

```java
NativeEngine.Config cfg = new NativeEngine.Config();
cfg.audioDevice = true;                         // open the speakers (else mix on request: engine.renderAudio)
try (Game game = Game.create(cfg)) {             // throws when there is no native core and fallbacks are off
    NativeEngine e = game.engine();
    e.blockTable(myBlockDefs);                   // what each block id means: solid, opaque, glass, light, ...
    long world = e.createWorld(256, 64, 256, STONE, null, null, 0);
    CommandBuffer edits = new CommandBuffer();
    edits.fill(0, 0, 0, 255, 3, 255, STONE | AstrattoNative.SET_RAW);
    edits.lightAll();
    e.edit(world, edits);                        // the world is the engine's; Java reads it through e.view(world)
    game.activate(world);

    game.add(new MyPlayer());                    // GameModule: init / update / events / afterFrame / shutdown
    game.add(new MyEnemies());
    game.loadNative("libs/my_rules.dll", "difficulty 3");   // a C++ module (section 4)

    while (running) {
        game.tick(dt);                           // the whole tick above
        view.draw(...);                          // section 5
    }
}
```

A `GameModule` writes to `ctx.commands()` in `update`, overrides the `EventSink` methods it cares about
(`agentSees`, `ragdollState`, `landed`, `voiceEnded`, ...), and reads `ctx.bodies()` / `ctx.agents()` snapshots or
`ctx.view()` (the world's cells and light, read-only, no copy) whenever it likes. Things made in play are made by
command into handles reserved first: `long h = ctx.reserve(KIND_MOVER); ctx.commands().moverCreate(h, ...)`.

`ASTRATTO_JAVA_FALLBACK=0` (or `-Dastratto.javaFallback=false`) makes `NativeEngine.create`, `NativeRenderer.create`
and `NativeMesher.create` throw instead of returning null, so a run that passes is known to be native throughout.

## 3. The services

| Service | Make | Drive | Read back |
|---|---|---|---|
| World | `astratto_world_create`, edits (`EDIT_*`) | `CMD_BLOCK_SET`, `DOOR_SEAL`, edits in the frame | `astratto_world_get_view` (live, read-only), `nav_version`, `light_version` |
| Questions | - | `PATH_REQUEST`, `SIGHT_REQUEST`, `WALK_QUERY`, `RAY_QUERY` | `EVT_*_RESULT`, same frame, in the order asked |
| Character bodies | `MOVER_CREATE` | `MOVER_INPUT` (wanted velocity, jump, crouch), `MOVER_PLACE` | snapshot `BODIES`; `EVT_LANDED` |
| Sydney ragdolls | `RAGDOLL_CREATE` (standing, or from an animated pose) | `RAGDOLL_HIT`, `_IMPULSE`, `_CONTROL` (die, collapse, stay down, release, limbs), `_DISTURB`, `_CARRY`, `SYDNEY_PROFILE` | snapshot (15 particles, state, outcome); `EVT_RAGDOLL_STATE`, `EVT_SETTLED` |
| Soft bodies | `SOFTBODY_CREATE` (particles, sticks, limits) | `SOFTBODY_IMPULSE` | snapshot; `EVT_SETTLED` |
| Vehicles | `VEHICLE_CREATE` (27 handling numbers, wheels, boxes) | `VEHICLE_INPUT`, `VEHICLE_PLACE`, `BODY_IMPULSE`, `SURFACE` | snapshot (matrix, wheels, gear); `EVT_GEAR`, `EVT_VEHICLE_IMPACT`, `EVT_BLOCK_HIT` |
| AI | `POLICY_CREATE` (actions and their considerations), `AGENT_CREATE` | `AGENT_FACT` (16 numbers per agent), `_TARGET`, `_GOAL`, `_POLICY`, `_FORCE`, `NOISE` | snapshot `AGENTS`; `EVT_AGENT_ACTION`, `_SEES`, `_LOST`, `_ARRIVED`, `_STUCK`, `_HEARD` |
| Audio | `astratto_sound_create` (decoded samples, copied) | `LISTENER`, `VOICE_PLAY` (bus, loop, positional, occluded, reverb), `VOICE_SET`, `VOICE_STOP`, `AUDIO_MIX` | snapshot `AUDIO`; `EVT_VOICE_ENDED` |

**AI is configured, not coded, on the engine side.** A policy is a list of actions (idle, wander, move to goal,
approach, flee, investigate, hold, or `CUSTOM`), each scored by considerations over the agent's inputs - the 16 facts
the game sets, and what the engine knows (target seen, distance, time since seen or heard, ...). The engine perceives
with its own sight lines, picks the best-scoring action (with inertia), and carries out the movement ones natively.
A `CUSTOM` action is the game's: the engine reports that it was chosen, and a Java or C++ module carries it out.

## 4. Gameplay in C++

A module is a shared library exporting `astratto_module_entry` (`native/include/astratto/astratto_module.h`); it links
against nothing of the engine. `native/modules/example/example_module.cpp` is the one to copy. Its four functions -
`init`, `update`, `event`, `shutdown` - are called on the thread that called `astratto_frame`; it writes commands
through `host->command`, reads snapshots and the world view through the host, and logs through `host->log` (the line
comes back to the caller as `EVT_MODULE_LOG`). Load it with `astratto_module_load` (Java: `Game.loadNative`); release
its handle to shut it down.

## 5. Rendering

```java
NativeRenderer r = NativeRenderer.create(engine, width, height, AstrattoNative.QUALITY_HIGH, 1f);  // in a GL 4.5 context
int atlas = r.texture(TEX_ARRAY, 32, 32, layers, TEX_NEAREST | TEX_MIPMAP | TEX_REPEAT, pixels);
r.worldLook(atlas, noise, layers, renderBlocks, shapes, belowFloor, palette, layerMaterials, groundY, 0);
int crate = r.mesh(vertices, count);              // 10 floats a vertex: position, colour+alpha, normal
RenderCommands f = new RenderCommands();
// each frame:
f.world(worldHandle);  f.camera(view, proj, viewmodelProj, ...);  f.env(...);
f.pointLight(...);  f.model(crate, 0, matrix, r, g, b, a, emissive, light, roughness, metal);  f.particles(...);  f.rect(...);
r.frame(f, width, height);                        // meshing, GI probes, shadows, G-buffer, lighting, fog, TAA, bloom, tone map, 2D
```

The renderer meshes the engine's world itself (on the engine's workers) and re-meshes columns as edits touch them.

**Layers (2.1).** Foliage and props are layer meshes drawn instanced, decals are layer meshes drawn over surfaces:

```java
int grass = r.layerMesh(LAYER_FOLIAGE, plantVertices, n);      // 10 floats a vertex: position, normal, colour + sway
r.instances(grass, instanceBuffer, count, INSTANCES_SHADOW);   // 12 floats an instance; kept until set again
int stains = r.layerMesh(LAYER_DECAL, quadVertices, m);        // position, u v layer, tint
// each frame, to have them drawn:
f.foliage(windX, windZ, time, gust, fadeStart, fadeEnd, snow, pushers, pusherCount);
f.props(drawDistance, shadowDistance);
f.decals(decalTextureArray, 0, fadeStart, fadeEnd, snow, freshMarks, markVertexCount);
```

The engine's Java `Foliage`, `Props` and `Decals` systems do this for you: they place things by rules over a
`Source` the game implements, and with `initNative(renderer)` hand the renderer their meshes and lists; call their
`update` and `frame(commands)` each frame.
The shaders are the files in `shaders/`, bundled in the jar and handed to the renderer by name
(`ASTRATTO_SHADERS=<dir>` overrides them for live editing).

## 6. Memory and lifetimes (summary of the header)

- Command buffers: the caller's, read during the call only.
- Events: the engine's, valid until the next `astratto_frame`.
- Snapshots: the engine's, valid until the next snapshot of the same kind (the Java classes copy them out).
- World views: the engine's memory itself, read-only, valid until the world is released.
- Data handed to a create call (cells, samples, pixels, vertices): copied before the call returns.
- Handles: valid from the command or call that makes them until the one that releases them; afterwards refused.

## 7. Building the native core for another platform

`./gradlew buildNative` uses `native/build.bat` on Windows and CMake elsewhere (`native/CMakeLists.txt`), writing to
`native/bin/<os>-<arch>/`; `./gradlew build` bundles whatever is there into the jar under
`astratto/natives/<os>-<arch>/`, and the Java side extracts and loads the one for the machine it runs on. A jar that
should run on several platforms is built by running `buildNative` on each and copying the `native/bin/*` folders
together before `build`. `ASTRATTO_NATIVE=<file or dir>` points the Java side at a library explicitly.
