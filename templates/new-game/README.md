# New game (Astratto Engine starter)

The smallest game that uses all of the engine: a window drawn by the native renderer, an empty map built from code,
a player, an AI watcher, a ragdoll, a car, a falling crate, positional sound, and gameplay written both in Java
(`src/main/java/starter`) and in C++ (the engine's example module, a radio the watcher hears).

```
./gradlew run          # play: WASD to walk, mouse to look, Space to jump, Shift to run, Esc to quit
./gradlew acceptance   # 600 frames off-screen, Java fallbacks disabled, then a check of every service
```

## Making it yours

1. Copy this directory to where the game will live.
2. In `gradle.properties`, set `astratto.engine` to the path of `theastrattoengine` (relative or absolute).
3. Rename the package and `rootProject.name`, and start replacing the modules:

| File | What it is |
|---|---|
| `StarterGame.java` | the main loop: window, engine, modules, renderer; the automated checks |
| `EmptyMap.java` | the blocks (what they mean to the engine, how they look) and the map built from them |
| `PlayerModule.java` | a Java module moving a body the engine simulates, from input |
| `WatcherModule.java` | an AI policy written in Java and carried out natively (perception, decisions, routes) |
| `ToysModule.java` | physics: Sydney's ragdoll, a car, a soft body |
| `SoundModule.java` | sounds made from code and played through the engine's mixer |
| `View.java` | one frame described to the native renderer |

Gameplay in C++: copy `native/modules/example/example_module.cpp` from the engine, build it as a shared library
against `native/include` (it links nothing of the engine), and load it with `game.loadNative(path, config)`.
See the engine's `docs/API.md`.
