const float GLASS_SOLID = 0.92;
in vec3 vWorld;
in vec2 vST;
in vec3 vLight;
flat in int vLayer;
flat in int vVar;
flat in int vFlags;
flat in int vNormal;
uniform sampler2DArray uAtlas;
const vec3 NORMALS[7] = vec3[](vec3(1,0,0), vec3(-1,0,0), vec3(0,1,0), vec3(0,-1,0), vec3(0,0,1), vec3(0,0,-1), vec3(0,1,0));
float hash3(vec3 p) { p = fract(p * 0.3183099 + vec3(0.71, 0.113, 0.419)); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
vec3 gAtlasUVW;
vec4 atlasTexel(out vec3 bp) {
    vec3 N = NORMALS[vNormal];
    vec2 uv;
    if (vNormal == 2 || vNormal == 3) uv = vST;
    else { float sg = (vNormal == 0 || vNormal == 5) ? -1.0 : 1.0; uv = vec2(vST.x * sg, -vST.y); }
    bp = floor(vWorld - N * 0.02);
    int layer = vLayer + min(int(hash3(bp) * float(vVar)), vVar - 1);
    gAtlasUVW = vec3(uv, float(layer));
    return texture(uAtlas, gAtlasUVW);
}
// 1.1: which texels of a pane are casement and which are glass is read from the texture as it was painted (level 0),
// never from a mip. At a distance the mips average the two: the casement's alpha fell under GLASS_SOLID and it
// dropped out of the G-buffer - the frame vanished, the pane "ate" the wall it sat in - and at the distances where
// the averaged alpha sat near the threshold the two passes disagreed from frame to frame, which was the flicker.
float paneAlpha() { return textureLod(uAtlas, gAtlasUVW, 0.0).a; }
