uniform usampler3D uVox;
uniform uint uOpaque[8];
const ivec3 WORLD = ivec3(WORLD_X, WORLD_Y, WORLD_Z);
bool opq(uint id) { return ((uOpaque[id >> 5u] >> (id & 31u)) & 1u) != 0u; }
uint voxel(ivec3 c) { return texelFetch(uVox, ivec3(c.y, c.z, c.x), 0).r; }
bool inWorld(ivec3 c) { return all(greaterThanEqual(c, ivec3(0))) && all(lessThan(c, WORLD)); }
/** Distance along the ray to the first cell boundary on each axis (a zero component never wins). */
vec3 ddaStart(vec3 a, vec3 d, ivec3 c, vec3 inv) {
    vec3 tm = (vec3(c) + step(vec3(0.0), d) - a) * sign(d) * inv;
    return mix(tm, vec3(1e30), lessThan(abs(d), vec3(1e-5)));
}
