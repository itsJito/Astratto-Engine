#include "include/head.glsl"
layout(location=0) in vec4 iPosSize;     // world position, size
layout(location=1) in vec4 iColor;       // rgb (HDR), alpha
layout(location=2) in vec4 iParams;      // rotation, kind, age 0..1, seed
uniform mat4 uPV;
uniform vec3 uRight, uUp;
out vec2 vQ;
out vec4 vColor;
out vec4 vParams;
out float vClipW;
void main() {
    vec2 q = vec2(gl_VertexID & 1, (gl_VertexID >> 1) & 1) * 2.0 - 1.0;
    int kind = int(iParams.y + 0.5);
    float cr = cos(iParams.x), sr = sin(iParams.x);
    // a streak or a drop is long and thin: its width is a share of its length (carried where the others carry a seed)
    vec2 lq = (kind == 5 || kind == 7) ? vec2(q.x * iParams.w, q.y) : q;
    vec2 rq = vec2(lq.x * cr - lq.y * sr, lq.x * sr + lq.y * cr);
    // flames are taller than they are wide and stay upright
    vec3 up = kind == 0 ? vec3(0.0, 1.35, 0.0) : uUp;
    vec3 p = iPosSize.xyz + (uRight * rq.x + up * rq.y) * iPosSize.w;
    // a ring lies on the ground whichever way the camera looks at it
    if (kind == 4) p = iPosSize.xyz + vec3(q.x, 0.0, q.y) * iPosSize.w;
    vQ = q; vColor = iColor; vParams = iParams;
    gl_Position = uPV * vec4(p, 1.0);
    vClipW = gl_Position.w;
}
