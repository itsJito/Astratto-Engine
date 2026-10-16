// Astratto Engine - the renderer's layers (2.1): foliage, props and decals, drawn into the G-buffer with the world and,
// for what is big enough, into the first shadow cascades. This is the GPU half of the Java systems of the same names
// (astratto.foliage.Foliage, astratto.props.Props, astratto.decal.Decals): they decide where things are; the meshes,
// instance buffers, programs and draws are here. Same programs (foliage.*, props.*, decal.*), same uniforms, same order
// within the G-buffer pass (decals, foliage, props), same state.
#include <algorithm>
#include <cstring>

#include "render/renderer.h"

namespace astratto {

using namespace gl;

namespace {
constexpr uint32_t VF = 10;    // floats a vertex, every layer kind
uint32_t instanceFloats(uint32_t kind) { return kind == ASTRATTO_LAYER_FOLIAGE ? 12u : kind == ASTRATTO_LAYER_PROP ? 8u : 0u; }
}  // namespace

int32_t Renderer::layerMesh(uint32_t kind, const float* v, uint32_t count, uint32_t* out) {
    if (kind < ASTRATTO_LAYER_FOLIAGE || kind > ASTRATTO_LAYER_DECAL) { error_ = "unknown layer kind"; return ASTRATTO_ERR_ARGUMENT; }
    if (v == nullptr && count > 0) { error_ = "no vertices"; return ASTRATTO_ERR_ARGUMENT; }
    LayerMesh m;
    m.kind = kind;
    m.verts = count;
    m.minX = m.minZ = 1e30f; m.maxX = m.maxZ = -1e30f;
    for (uint32_t i = 0; i < count; i++) {
        const float x = v[i * VF], z = v[i * VF + 2];
        m.minX = std::min(m.minX, x); m.maxX = std::max(m.maxX, x); m.minZ = std::min(m.minZ, z); m.maxZ = std::max(m.maxZ, z);
    }
    g_.GenVertexArrays(1, &m.vao);
    g_.GenBuffers(1, &m.vbo);
    g_.BindVertexArray(m.vao);
    g_.BindBuffer(ARRAY_BUFFER, m.vbo);
    g_.BufferData(ARRAY_BUFFER, static_cast<GLsizeiptr>(std::max<uint32_t>(1, count)) * VF * 4, v, STATIC_DRAW);
    g_.VertexAttribPointer(0, 3, FLOAT, FALSE_, VF * 4, reinterpret_cast<const void*>(0));
    g_.VertexAttribPointer(1, 3, FLOAT, FALSE_, VF * 4, reinterpret_cast<const void*>(12));
    g_.VertexAttribPointer(2, 4, FLOAT, FALSE_, VF * 4, reinterpret_cast<const void*>(24));
    for (GLuint a = 0; a < 3; a++) g_.EnableVertexAttribArray(a);
    const uint32_t inst = instanceFloats(kind);
    if (inst != 0) {
        g_.GenBuffers(1, &m.ibo);
        g_.BindBuffer(ARRAY_BUFFER, m.ibo);
        m.cap = 64;
        g_.BufferData(ARRAY_BUFFER, static_cast<GLsizeiptr>(m.cap) * inst * 4, nullptr, DYNAMIC_DRAW);
        for (GLuint a = 0; a < inst / 4; a++) {
            g_.VertexAttribPointer(3 + a, 4, FLOAT, FALSE_, static_cast<GLsizei>(inst * 4), reinterpret_cast<const void*>(static_cast<uintptr_t>(a * 16)));
            g_.EnableVertexAttribArray(3 + a);
            g_.VertexAttribDivisor(3 + a, 1);
        }
    }
    g_.BindVertexArray(0);
    g_.BindBuffer(ARRAY_BUFFER, 0);
    const uint32_t id = nextMesh_++;
    layerMeshes_[id] = m;
    *out = id;
    return ASTRATTO_OK;
}

int32_t Renderer::instances(uint32_t mesh, const float* data, uint32_t count, uint32_t flags) {
    auto it = layerMeshes_.find(mesh);
    if (it == layerMeshes_.end()) { error_ = "not a layer mesh"; return ASTRATTO_ERR_HANDLE; }
    LayerMesh& m = it->second;
    const uint32_t inst = instanceFloats(m.kind);
    if (inst == 0) { error_ = "a decal mesh has no instances"; return ASTRATTO_ERR_ARGUMENT; }
    if (data == nullptr && count > 0) { error_ = "no instance data"; return ASTRATTO_ERR_ARGUMENT; }
    m.flags = flags;
    m.instances = count;
    if (count == 0) return ASTRATTO_OK;
    g_.BindBuffer(ARRAY_BUFFER, m.ibo);
    if (count > m.cap) { m.cap = std::max(count, m.cap * 2); g_.BufferData(ARRAY_BUFFER, static_cast<GLsizeiptr>(m.cap) * inst * 4, nullptr, DYNAMIC_DRAW); }
    g_.BufferSubData(ARRAY_BUFFER, 0, static_cast<GLsizeiptr>(count) * inst * 4, data);
    g_.BindBuffer(ARRAY_BUFFER, 0);
    return ASTRATTO_OK;
}

void Renderer::freeLayer(LayerMesh& m) {
    if (m.vbo) g_.DeleteBuffers(1, &m.vbo);
    if (m.ibo) g_.DeleteBuffers(1, &m.ibo);
    if (m.vao) g_.DeleteVertexArrays(1, &m.vao);
    m = LayerMesh();
}

void Renderer::foliageUniforms(Program& s, const float* pv) {
    s.use();
    s.mat4("uPV", pv);
    s.f4("uWind", foliage_.wind_x, foliage_.wind_z, foliage_.time, foliage_.gust);
    s.f3("uCam", cam_.x, cam_.y, cam_.z);
    s.f2("uFade", foliage_.fade_start, foliage_.fade_end);
    s.f1("uSnow", foliage_.snow);
    s.i1("uPushN", static_cast<int>(std::min<uint32_t>(8, foliage_.push_count)));
    g_.Uniform4fv(s.loc("uPush"), 8, foliage_.push);
}

int Renderer::drawLayer(uint32_t kind, bool shadowOnly) {
    int calls = 0;
    for (auto& kv : layerMeshes_) {
        const LayerMesh& m = kv.second;
        if (m.kind != kind || m.instances == 0 || m.verts == 0) continue;
        if (shadowOnly && !(m.flags & ASTRATTO_INSTANCES_SHADOW)) continue;
        g_.BindVertexArray(m.vao);
        g_.DrawArraysInstanced(TRIANGLES, 0, static_cast<GLsizei>(m.verts), static_cast<GLsizei>(m.instances));
        calls++;
    }
    g_.BindVertexArray(0);
    return calls;
}

void Renderer::layersShadow(int cascade) {
    if (cascade >= 2) return;
    const float* pv = shadowVP_[cascade].m;
    if (doFoliage_) {
        foliageUniforms(folS_, pv);
        drawLayer(ASTRATTO_LAYER_FOLIAGE, true);
    }
    if (doProps_) {
        propS_.use();
        propS_.mat4("uPV", pv);
        propS_.f3("uCam", cam_.x, cam_.y, cam_.z);
        propS_.f1("uDist", props_.shadow_distance);
        g_.Enable(CULL_FACE);
        drawLayer(ASTRATTO_LAYER_PROP, false);
    }
}

void Renderer::layersGBuffer() {
    // decals: the surface colour only, blended by coverage; normal, material, light and depth stay the surface's
    if (doDecals_) {
        decal_.use();
        decal_.mat4("uPV", pvJ_.m);
        decal_.f3("uCam", cam_.x, cam_.y, cam_.z);
        decal_.f2("uFade", decals_.fade_start, decals_.fade_end);
        decal_.f1("uSnow", decals_.snow);
        auto dt = textures_.find(decals_.texture);
        decal_.tex("uTex", 0, TEXTURE_2D_ARRAY, dt == textures_.end() ? 0 : dt->second.id);
        auto at = textures_.find(decals_.atlas != 0 ? decals_.atlas : atlasTex_);
        decal_.tex("uAtlas", 1, TEXTURE_2D_ARRAY, at == textures_.end() ? 0 : at->second.id);
        g_.Enable(BLEND);
        g_.BlendFunc(SRC_ALPHA, ONE_MINUS_SRC_ALPHA);
        g_.DepthMask(FALSE_);
        g_.ColorMaski(0, TRUE_, TRUE_, TRUE_, FALSE_);
        g_.ColorMaski(1, FALSE_, FALSE_, FALSE_, FALSE_);
        g_.ColorMaski(2, FALSE_, FALSE_, FALSE_, FALSE_);
        g_.Enable(POLYGON_OFFSET_FILL);
        g_.PolygonOffset(-1.5f, -3.0f);
        g_.Disable(CULL_FACE);
        const float reach = decals_.fade_end + 16;
        decalQuads_ = 0;
        for (auto& kv : layerMeshes_) {
            const LayerMesh& m = kv.second;
            if (m.kind != ASTRATTO_LAYER_DECAL || m.verts == 0) continue;
            // a patch wholly beyond the fade distance is not drawn
            const float dx = std::max({m.minX - cam_.x, 0.0f, cam_.x - m.maxX}), dz = std::max({m.minZ - cam_.z, 0.0f, cam_.z - m.maxZ});
            if (dx * dx + dz * dz > reach * reach) continue;
            g_.BindVertexArray(m.vao);
            g_.DrawArrays(TRIANGLES, 0, static_cast<GLsizei>(m.verts));
            decalQuads_ += m.verts / 6;
        }
        if (!decalVerts_.empty()) {
            const size_t n = decalVerts_.size() / VF;
            if (decalVao_ == 0) {
                g_.GenVertexArrays(1, &decalVao_);
                g_.GenBuffers(1, &decalVbo_);
                g_.BindVertexArray(decalVao_);
                g_.BindBuffer(ARRAY_BUFFER, decalVbo_);
                decalCap_ = 640 * 6;
                g_.BufferData(ARRAY_BUFFER, static_cast<GLsizeiptr>(decalCap_ * VF * 4), nullptr, STREAM_DRAW);
                g_.VertexAttribPointer(0, 3, FLOAT, FALSE_, VF * 4, reinterpret_cast<const void*>(0));
                g_.VertexAttribPointer(1, 3, FLOAT, FALSE_, VF * 4, reinterpret_cast<const void*>(12));
                g_.VertexAttribPointer(2, 4, FLOAT, FALSE_, VF * 4, reinterpret_cast<const void*>(24));
                for (GLuint a = 0; a < 3; a++) g_.EnableVertexAttribArray(a);
            }
            g_.BindVertexArray(decalVao_);
            g_.BindBuffer(ARRAY_BUFFER, decalVbo_);
            if (n > decalCap_) { decalCap_ = std::max(n, decalCap_ * 2); g_.BufferData(ARRAY_BUFFER, static_cast<GLsizeiptr>(decalCap_ * VF * 4), nullptr, STREAM_DRAW); }
            g_.BufferSubData(ARRAY_BUFFER, 0, static_cast<GLsizeiptr>(decalVerts_.size() * 4), decalVerts_.data());
            g_.DrawArrays(TRIANGLES, 0, static_cast<GLsizei>(n));
            decalQuads_ += static_cast<uint32_t>(n / 6);
        }
        g_.BindVertexArray(0);
        g_.Disable(POLYGON_OFFSET_FILL);
        g_.ColorMaski(0, TRUE_, TRUE_, TRUE_, TRUE_);
        g_.ColorMaski(1, TRUE_, TRUE_, TRUE_, TRUE_);
        g_.ColorMaski(2, TRUE_, TRUE_, TRUE_, TRUE_);
        g_.Disable(BLEND);
        g_.DepthMask(TRUE_);
        g_.Enable(CULL_FACE);
    }
    if (doFoliage_) {
        foliageUniforms(folG_, pvJ_.m);
        g_.Enable(CULL_FACE);
        drawLayer(ASTRATTO_LAYER_FOLIAGE, false);
    }
    if (doProps_) {
        propG_.use();
        propG_.mat4("uPV", pvJ_.m);
        propG_.f3("uCam", cam_.x, cam_.y, cam_.z);
        propG_.f1("uDist", props_.draw_distance);
        g_.Enable(CULL_FACE);
        drawLayer(ASTRATTO_LAYER_PROP, false);
    }
}

}  // namespace astratto
