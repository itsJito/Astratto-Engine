# Astratto Engine - notices

Copyright (c) Astratto North of LudusValley Recreations, under VEIN Enterprises. See `LICENSE.md` for the terms the
engine itself is used under.

The engine is distributed with, or fetches at build time, the following third-party software. Each keeps its own
licence; nothing below is relicensed by the engine.

| Component | Used for | Licence | Where |
|---|---|---|---|
| LWJGL 3.3.3 (core, GLFW, stb bindings) | window, GL context, input, foreign calls into the native core, font baking | BSD 3-Clause | Maven Central `org.lwjgl:*` |
| GLFW 3.4 (inside LWJGL's natives) | platform window and input | zlib/libpng | bundled in `lwjgl-glfw` natives |
| stb_truetype (inside LWJGL's natives) | baking TrueType glyphs | MIT / public domain | bundled in `lwjgl-stb` natives |
| JLayer 1.0.1 (`javazoom:jlayer`) | MP3 decoding in UltraEngine | LGPL 2.1 (used unmodified, as a separate jar) | Maven Central |
| DejaVu Sans (`resources/fonts/DejaVuSans.ttf`) | the engine's default UI font | Bitstream Vera / DejaVu licence (permissive) | `resources/fonts/LICENSE-DejaVu.txt` |
| JUnit 5 (tests only, not shipped) | engine test suite | EPL 2.0 | Maven Central |

The native core (`native/`) is the engine's own code and uses only the C++17 standard library and the platform's
system libraries (OpenGL through the context the platform layer creates, the Windows multimedia API or a null audio
device elsewhere). It loads OpenGL entry points through the function the platform layer hands it, so it links no GL
loader library.
