#include "include/head.glsl"
#include "include/world_tex.glsl"
void main() {
    vec3 bp;
    if (atlasTexel(bp).a < 0.5) discard;
}
