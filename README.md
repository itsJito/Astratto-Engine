# Astratto Engine 2.0

A voxel game engine with a native core. The core (C++17) owns the world and runs rendering, physics, AI and audio;
a game writes its gameplay in Java, in C++, or both, and talks to the core through one versioned C interface.

```
theastrattoengine/
  native/            the native core: include/ (the C interface), src/, tests/, modules/example/ (a C++ gameplay module)
  java/              the Java library: bindings (astratto.bridge), the module runner (astratto.game), platform, 2D, tools
  shaders/           the renderer's GLSL, by file name
  resources/         the engine's own resources (the default UI font)
  tests/             boundary, interoperability and integration tests (JUnit; run with Java fallbacks disabled)
  tools/             tools/layouts: prints every structure's field offsets from the header
  docs/              ARCHITECTURE.md (how it is divided and why), API.md (how to use it)
  templates/new-game the starter game: copy it to begin a game
```

## Build

Requirements: a 64-bit JDK 21 (the build compiles with a Java 21 toolchain), and a C++17 compiler - Visual C++ 2019
or newer on Windows (found by `native/build.bat`, no CMake needed), CMake 3.16+ with GCC or Clang elsewhere. The native
renderer needs OpenGL 4.5.

```
./gradlew buildNative     # the native core, its self-test (75 checks) and the example C++ module -> native/bin/<platform>/
./gradlew build           # the jar (native library, shaders, resources inside), API docs, and the tests
./gradlew test            # boundary, interoperability and integration tests, with ASTRATTO_JAVA_FALLBACK=0
```

The directory can be copied anywhere: nothing in it refers to anything outside it. `build` builds the native core
first if `native/bin/<platform>/` is empty.

On Windows `native\build.bat checked` runs the self-test bounds-checked, `native\build.bat asan` under
AddressSanitizer, and `native\build.bat bench` runs the benchmark against `native/tests/budgets.txt`. CMake has
`-DASTRATTO_SANITIZE=ON` and `-DASTRATTO_WERROR=ON` for the same. CI (`.github/workflows/ci.yml`) runs all of it on
Windows, Linux and macOS; see `docs/API.md`, section 8.

## Start a game

```
cd templates/new-game
./gradlew run             # the starter game: an empty map, a player, an AI watcher, a ragdoll, a car, sound
./gradlew acceptance      # the same, off-screen for 600 frames with Java fallbacks off, checking every service
```

Copy `templates/new-game` wherever the game should live and set `astratto.engine` in its `gradle.properties` to this
directory. See `templates/new-game/README.md` and `docs/API.md`.

## Licence

See `LICENSE.md`; third-party components and their licences are listed in `NOTICE.md`.
