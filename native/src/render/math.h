// Astratto Engine - rendering: the 4x4 matrices and frustum the renderer computes with. Column-major (the GL layout),
// with the same arithmetic as the Java renderer's astratto.math.Mat4 and Frustum (single precision, the tangent in
// double), so that a frame described the same way is set up the same way.
#pragma once

#include <cmath>
#include <cstring>

namespace astratto {

struct Mat4 {
    float m[16];

    Mat4() { identity(); }

    Mat4& identity() { std::memset(m, 0, sizeof m); m[0] = m[5] = m[10] = m[15] = 1; return *this; }
    Mat4& set(const Mat4& o) { std::memcpy(m, o.m, sizeof m); return *this; }
    Mat4& set(const float* v) { std::memcpy(m, v, sizeof m); return *this; }

    Mat4& ortho(float l, float r, float b, float t, float n, float f) {
        std::memset(m, 0, sizeof m);
        m[0] = 2 / (r - l); m[5] = 2 / (t - b); m[10] = -2 / (f - n);
        m[12] = -(r + l) / (r - l); m[13] = -(t + b) / (t - b); m[14] = -(f + n) / (f - n); m[15] = 1;
        return *this;
    }

    /** this = this * o */
    Mat4& mul(const Mat4& o) {
        float r[16];
        const float *a = m, *b = o.m;
        for (int c = 0; c < 4; c++)
            for (int rr = 0; rr < 4; rr++) r[c * 4 + rr] = a[rr] * b[c * 4] + a[4 + rr] * b[c * 4 + 1] + a[8 + rr] * b[c * 4 + 2] + a[12 + rr] * b[c * 4 + 3];
        std::memcpy(m, r, sizeof m);
        return *this;
    }

    Mat4& lookAt(float ex, float ey, float ez, float cx, float cy, float cz, float ux, float uy, float uz) {
        float fx = cx - ex, fy = cy - ey, fz = cz - ez, fl = static_cast<float>(std::sqrt(static_cast<double>(fx * fx + fy * fy + fz * fz)));
        fx /= fl; fy /= fl; fz /= fl;
        float sx = fy * uz - fz * uy, sy = fz * ux - fx * uz, sz = fx * uy - fy * ux, sl = static_cast<float>(std::sqrt(static_cast<double>(sx * sx + sy * sy + sz * sz)));
        sx /= sl; sy /= sl; sz /= sl;
        float vx = sy * fz - sz * fy, vy = sz * fx - sx * fz, vz = sx * fy - sy * fx;
        m[0] = sx; m[4] = sy; m[8] = sz; m[12] = -(sx * ex + sy * ey + sz * ez);
        m[1] = vx; m[5] = vy; m[9] = vz; m[13] = -(vx * ex + vy * ey + vz * ez);
        m[2] = -fx; m[6] = -fy; m[10] = -fz; m[14] = fx * ex + fy * ey + fz * ez;
        m[3] = 0; m[7] = 0; m[11] = 0; m[15] = 1;
        return *this;
    }

    /** this = inverse(src) by cofactors; identity when src is singular. */
    Mat4& invert(const Mat4& src) {
        const float* a = src.m;
        float inv[16];
        inv[0] = a[5] * a[10] * a[15] - a[5] * a[11] * a[14] - a[9] * a[6] * a[15] + a[9] * a[7] * a[14] + a[13] * a[6] * a[11] - a[13] * a[7] * a[10];
        inv[4] = -a[4] * a[10] * a[15] + a[4] * a[11] * a[14] + a[8] * a[6] * a[15] - a[8] * a[7] * a[14] - a[12] * a[6] * a[11] + a[12] * a[7] * a[10];
        inv[8] = a[4] * a[9] * a[15] - a[4] * a[11] * a[13] - a[8] * a[5] * a[15] + a[8] * a[7] * a[13] + a[12] * a[5] * a[11] - a[12] * a[7] * a[9];
        inv[12] = -a[4] * a[9] * a[14] + a[4] * a[10] * a[13] + a[8] * a[5] * a[14] - a[8] * a[6] * a[13] - a[12] * a[5] * a[10] + a[12] * a[6] * a[9];
        inv[1] = -a[1] * a[10] * a[15] + a[1] * a[11] * a[14] + a[9] * a[2] * a[15] - a[9] * a[3] * a[14] - a[13] * a[2] * a[11] + a[13] * a[3] * a[10];
        inv[5] = a[0] * a[10] * a[15] - a[0] * a[11] * a[14] - a[8] * a[2] * a[15] + a[8] * a[3] * a[14] + a[12] * a[2] * a[11] - a[12] * a[3] * a[10];
        inv[9] = -a[0] * a[9] * a[15] + a[0] * a[11] * a[13] + a[8] * a[1] * a[15] - a[8] * a[3] * a[13] - a[12] * a[1] * a[11] + a[12] * a[3] * a[9];
        inv[13] = a[0] * a[9] * a[14] - a[0] * a[10] * a[13] - a[8] * a[1] * a[14] + a[8] * a[2] * a[13] + a[12] * a[1] * a[10] - a[12] * a[2] * a[9];
        inv[2] = a[1] * a[6] * a[15] - a[1] * a[7] * a[14] - a[5] * a[2] * a[15] + a[5] * a[3] * a[14] + a[13] * a[2] * a[7] - a[13] * a[3] * a[6];
        inv[6] = -a[0] * a[6] * a[15] + a[0] * a[7] * a[14] + a[4] * a[2] * a[15] - a[4] * a[3] * a[14] - a[12] * a[2] * a[7] + a[12] * a[3] * a[6];
        inv[10] = a[0] * a[5] * a[15] - a[0] * a[7] * a[13] - a[4] * a[1] * a[15] + a[4] * a[3] * a[13] + a[12] * a[1] * a[7] - a[12] * a[3] * a[5];
        inv[14] = -a[0] * a[5] * a[14] + a[0] * a[6] * a[13] + a[4] * a[1] * a[14] - a[4] * a[2] * a[13] - a[12] * a[1] * a[6] + a[12] * a[2] * a[5];
        inv[3] = -a[1] * a[6] * a[11] + a[1] * a[7] * a[10] + a[5] * a[2] * a[11] - a[5] * a[3] * a[10] - a[9] * a[2] * a[7] + a[9] * a[3] * a[6];
        inv[7] = a[0] * a[6] * a[11] - a[0] * a[7] * a[10] - a[4] * a[2] * a[11] + a[4] * a[3] * a[10] + a[8] * a[2] * a[7] - a[8] * a[3] * a[6];
        inv[11] = -a[0] * a[5] * a[11] + a[0] * a[7] * a[9] + a[4] * a[1] * a[11] - a[4] * a[3] * a[9] - a[8] * a[1] * a[7] + a[8] * a[3] * a[5];
        inv[15] = a[0] * a[5] * a[10] - a[0] * a[6] * a[9] - a[4] * a[1] * a[10] + a[4] * a[2] * a[9] + a[8] * a[1] * a[6] - a[8] * a[2] * a[5];
        float det = a[0] * inv[0] + a[1] * inv[4] + a[2] * inv[8] + a[3] * inv[12];
        if (std::fabs(det) < 1e-20f) return identity();
        det = 1.0f / det;
        for (int i = 0; i < 16; i++) m[i] = inv[i] * det;
        return *this;
    }
};

/** View frustum planes from a projection * view matrix (the Java Frustum's arithmetic). */
struct Frustum {
    float p[6][4] = {};

    void update(const Mat4& pv) {
        const float* m = pv.m;
        set(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12]);
        set(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12]);
        set(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13]);
        set(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13]);
        set(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14]);
        set(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14]);
    }

    void set(int i, float a, float b, float c, float d) {
        float l = static_cast<float>(std::sqrt(static_cast<double>(a * a + b * b + c * c)));
        p[i][0] = a / l; p[i][1] = b / l; p[i][2] = c / l; p[i][3] = d / l;
    }

    bool aabb(float x0, float y0, float z0, float x1, float y1, float z1) const {
        for (const auto& q : p) {
            float x = q[0] >= 0 ? x1 : x0, y = q[1] >= 0 ? y1 : y0, z = q[2] >= 0 ? z1 : z0;
            if (q[0] * x + q[1] * y + q[2] * z + q[3] < 0) return false;
        }
        return true;
    }

    bool sphere(float x, float y, float z, float r) const {
        for (const auto& q : p) if (q[0] * x + q[1] * y + q[2] * z + q[3] < -r) return false;
        return true;
    }
};

}  // namespace astratto
