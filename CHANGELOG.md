# Changelog

## HOODIE - hardening pass on Astratto Engine 2.0 (API 2.1, unchanged)

HOODIE puts stability, measurement and quality gates ahead of new features. The C interface, its structures and its
version are unchanged: a game built against 2.1 runs on this core without a rebuild.

### Fixed

- **Route search heap could outgrow its array.** `Pathfinder` sized its open-list heap to the node cap (6,000), but a
  node is pushed again every time a cheaper way to it is found, so the heap's entry count is not bounded by the node
  count. A search with enough re-pushes would have written past the end of the array. The heap now grows when it has
  to. Routes are the same cell for cell, since growing changes no ordering.
- **A C++ module that released itself crashed the engine.** A module that sent `RELEASE` for its own handle from
  `init`, `update` or `event` had its library unloaded and its host freed while its own code was still running. Releases
  asked while module code is running are now deferred: the module is called no more, then shut down and unloaded once
  the call returns. A release asked from inside a module's own `shutdown` is refused with an error event, not run twice.
- **Exceptions could cross into module code.** The host functions a module calls (`command`, `reserve`, `snapshot`,
  `log`) let engine exceptions (out of memory, say) unwind through the module's C frames. Each now returns a status
  (`ASTRATTO_ERR_MEMORY` / `ASTRATTO_ERR_INTERNAL`) instead.
- **A throw on a worker thread ended the process.** `JobPool::parallelFor` let an exception escape a worker thread
  (`std::terminate`), and one thrown on the calling thread left the workers running on a dead batch. The first
  exception is now kept, the batch is abandoned, every worker leaves it, and only then is the exception rethrown on the
  caller's thread, where the C interface turns it into a status.
- **`astratto_world_set` was unguarded.** The one C entry point without the exception guard can allocate when a
  runtime set relights. It is now guarded like every other entry point; a `try` costs nothing until it throws.
- **Engine shutdown order.** Modules are now shut down while every service is still whole, so a module's `shutdown`
  may still write commands safely.
- **`gradlew` was not executable** in the repository (mode 644), so `./gradlew` failed on a fresh Linux or macOS
  checkout. Both wrappers (engine and starter game) are now mode 755.

### Changed

- **Chunk mesher: about 1.6-1.8x faster, byte for byte the same mesh.** Measured with the new benchmark (256 columns
  of a 256 x 64 x 256 world, 299,543 quads): roughly 320-430 ms before and 195-240 ms after on the same machine,
  depending on load. The output digest is identical (`f4e2219bad497433`), and the starter game's acceptance run draws
  the same 2,214 quads. How:
  - A branch-light first pass marks, for every cell of the column, which of its six faces could show (a cube face whose
    neighbour does not hide it). The full face rules (translucency, cut-outs, leaves, liquids, ambient occlusion, light)
    then run only where a bit is set: about 1,600 faces in 44,000 cells per column, rather than a hard-to-predict
    branch at every cell.
  - Faces whose surroundings lie wholly inside the column and the world read their neighbours by direct index, without
    per-lookup bounds checks.
  - Vertices are appended with `resize` and `memcpy`.
- **Warnings are errors** on Windows (`/W4 /WX`). The existing warnings were fixed: shadowed locals in `ragdoll.cpp`,
  `sydney.cpp` and the self-test, and narrowing in `VoxelWorld::clear`. CMake has `-DASTRATTO_WERROR=ON`.
- **The jar no longer picks up test-only libraries** from `native/bin` (the self-test's module is excluded).
- **Gradle:** the deprecated property syntax (`exceptionFormat 'full'`) that Gradle 10 removes now uses assignment. The
  build reports no deprecations.
- **Docs:** `README.md`, `docs/API.md` (module release rules; new section 8, *Quality gates*) and
  `docs/ARCHITECTURE.md` (check count, platform state) describe all of the above.

### Added

- **Hardening checks** (`native/tests/hardening.cpp`, part of the self-test, which grows from 65 to 75 checks):
  - 64 unbounded route searches over rough ground with doors, stairs, pools, lava and ladders, on four workers.
  - **Determinism:** 120 frames of 24 walking bodies and 192 route searches give identical event streams and body
    snapshots, byte for byte, when run again, and on four worker threads as on one.
  - **Command-stream fuzzing:** 1,500 frames of random records (random kinds, sizes and bodies, real handles mixed in,
    streams cut short, negative time steps). Each record is refused or run, never a crash, and the engine runs a clean
    frame afterwards.
  - **Handle churn:** 10,000 bodies made and released leave none behind. Their slots are reused under new
    generations, and all 10,000 old handles are refused.
  - The C interface refuses null engines, records and out-pointers.
  - Modules releasing themselves from `init`, `update` and `event` (with `native/tests/test_module.cpp`, a module built
    for this test alone).
- **Benchmark with budgets** (`native/tests/bench.cpp`, `native/tests/budgets.txt`). It times lighting a whole world,
  runtime relighting, meshing, 128 route searches (one thread and every worker), 500 walking bodies, the empty tick, and
  mixing 64 positional voices, all on fixed worlds and seeds. A median over its budget fails the run, it writes
  `results.json` for a history of runs, and it prints a digest of the mesher's output so that an optimisation can be
  shown to change nothing.
- **Build modes** (`native/build.bat`):
  - `checked`: the debug runtime, with every standard-container access bounds-checked, heap checks and run-time checks.
    The self-test passes all 75 checks in this mode. Reports go to stderr instead of a dialog.
  - `asan`: AddressSanitizer. It needs Visual Studio's "C++ AddressSanitizer" component and says so plainly when that
    is missing.
  - `bench`: builds, then runs the benchmark against the budgets.
- **CMake:** `ASTRATTO_SANITIZE` (ASan + UBSan with GCC/Clang, ASan with MSVC), `ASTRATTO_WERROR`, and the new
  targets (`astratto_bench`, `astratto_test_module`), registered with CTest.
- **Continuous integration** (`.github/workflows/ci.yml`), on every push and pull request:
  - Windows x64, Linux x64 and macOS arm64: the native core with warnings as errors, self-test, sanitizer self-test,
    benchmark against budgets, and the Java tests with fallbacks off.
  - Windows also runs the bounds-checked self-test.
  - Benchmark results and test reports are kept as artifacts of each run.
  - An advisory job runs the renderer test on Mesa's software OpenGL 4.5 (llvmpipe).
- **`ASTRATTO_HEADLESS=1`:** on a machine with no GPU, the one Java test that needs an OpenGL 4.5 context is reported
  *skipped*, never passed. Anywhere else a missing context is still a failure.

### Removed

- Nothing from the interface or the Java library. The remaining Java GL code (`astratto.render` and the GL paths of
  foliage, decals and props, with `lwjgl-opengl`) is still used by Absentialis's Java renderer. It goes when the game
  stops using that renderer, as `docs/ARCHITECTURE.md` records.

### Measured on this release

| Gate | Result |
|---|---|
| Native self-test, release (`/O2 /W4 /WX`) | 75 / 75 |
| Native self-test, bounds-checked debug runtime | 75 / 75 |
| Java tests (`./gradlew test`, fallbacks off) | 20 / 20 |
| Starter game acceptance (600 frames, off-screen, fallbacks off) | 11 / 11 |
| Benchmark against `budgets.txt` | 8 / 8 within budget |
| Compiler warnings (MSVC) | 0 |
| Gradle deprecations | 0 |

Benchmark medians on a 6-thread desktop (GTX 1050 Ti class): light a 256 x 64 x 256 world ~65-85 ms; one relighting
block set ~1.2-1.6 ms; mesh the whole world ~195-240 ms; 128 route searches ~6-9 ms on one thread and ~5-6 ms on every
worker; a frame of 500 walking bodies ~0.3 ms; empty tick ~1 µs; one second of 64 voices ~45-55 ms.

### Still open (not claimed by this release)

Production readiness is **not** declared. These items stand between this release and that declaration:

- **Linux and macOS:** CI now builds and tests both on every push, but they count as verified only once those jobs have
  passed. Off Windows the audio device is still the null device (audio is mixed on request).
- **Renderer pixel parity** with the reference renderer is not yet gated pixel for pixel. The software-GL CI job is the
  first step toward a gated comparison.
- **Runtime relighting** costs ~1.2-1.6 ms per light-changing block set and dirties up to 16 columns for re-meshing. A
  burst of such edits (an explosion) is the next frame-pacing target. Batching relights within a frame has to be shown
  to give the same light first.
- **Route searches** scale weakly across workers (~1.3x on 5 workers for 128 searches): the next CPU target after
  meshing.
- **Migration:** removing the Java GL code waits on Absentialis moving off its Java renderer.
- **Sign-off:** a vertical slice proving a complete game on Astratto alone, measured CPU/GPU/memory budgets for it,
  and written technical-director approval.
