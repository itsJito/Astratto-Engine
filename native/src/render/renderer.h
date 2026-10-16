// Astratto Engine - the renderer (2.0): the hybrid global-illumination renderer, native. Every GPU resource it uses, the
// meshing of the engine's world on the engine's workers, the voxel volume and the irradiance probes traced through it,
// the shadow cascades, the G-buffer, light culling, ambient occlusion, reflections, the lighting, the volumetric fog, the
// forward pass for glass, water, see-through models and particles, temporal anti-aliasing, bloom, exposure and tone
// mapping, and a 2D layer for interfaces.
//
// It is the Java renderer that Absentialis grew (absentialis.render.HybridRenderer and its WorldRenderer, ProbeField and
// VoxelVolume) moved across pass for pass: the same programs (the engine's shader files), the same targets, the same
// order and the same uniforms, so that the same frame description draws the same picture. What the game used to do with
// GL calls of its own, it now says in a frame's render commands.
#pragma once

#include <cstdint>
#include <limits>
#include <memory>
#include <string>
#include <unordered_map>
#include <vector>

#include "astratto/astratto.h"
#include "render/chunk_mesher.h"
#include "render/gl.h"
#include "render/math.h"

namespace astratto {

class Engine;
class VoxelWorld;

/** A compiled GLSL program with its uniform locations cached. */
class Program {
    using GLint = gl::GLint;
public:
    Program() = default;
    bool build(const gl::Api& g, const std::string& vs, const std::string& fs, std::string& error);
    bool build(const gl::Api& g, const std::string& cs, std::string& error);
    void destroy(const gl::Api& g);
    void use() const;
    GLint loc(const char* name);
    void f1(const char* n, float v) { g_->Uniform1f(loc(n), v); }
    void f2(const char* n, float a, float b) { g_->Uniform2f(loc(n), a, b); }
    void f3(const char* n, float a, float b, float c) { g_->Uniform3f(loc(n), a, b, c); }
    void f4(const char* n, float a, float b, float c, float d) { g_->Uniform4f(loc(n), a, b, c, d); }
    void i1(const char* n, int v) { g_->Uniform1i(loc(n), v); }
    void i2(const char* n, int a, int b) { g_->Uniform2i(loc(n), a, b); }
    void fv(const char* n, const float* v, int count) { g_->Uniform1fv(loc(n), count, v); }
    void iv2(const char* n, const int* v, int count) { g_->Uniform2iv(loc(n), count, v); }
    void uiv(const char* n, const uint32_t* v, int count) { g_->Uniform1uiv(loc(n), count, v); }
    void mat3(const char* n, const float* m) { g_->UniformMatrix3fv(loc(n), 1, gl::FALSE_, m); }
    void mat4(const char* n, const float* m) { g_->UniformMatrix4fv(loc(n), 1, gl::FALSE_, m); }
    void mat4v(const char* n, const float* m, int count) { g_->UniformMatrix4fv(loc(n), count, gl::FALSE_, m); }
    void tex(const char* n, int unit, gl::GLenum target, gl::GLuint t);
    gl::GLuint id() const { return id_; }

private:
    const gl::Api* g_ = nullptr;
    gl::GLuint id_ = 0;
    std::unordered_map<std::string, GLint> locs_;
};

class Renderer {
public:
    Renderer() = default;
    ~Renderer();

    /** Loads GL, compiles every program from the shader sources given by name; false with error() on failure. */
    bool create(const astratto_renderer_desc& d, const std::unordered_map<std::string, std::string>& sources);
    const char* error() const { return error_.c_str(); }
    void fail(const std::string& e) { error_ = e; }

    int32_t texture(const astratto_texture_desc& d, uint32_t* out);
    int32_t mesh(const float* vertices, uint32_t count, uint32_t* out);
    int32_t release(uint32_t kind, uint32_t id);
    int32_t worldLook(const astratto_world_look& look);
    int32_t frame(Engine* engine, astratto_render_io& io);
    int32_t readPixels(uint32_t x, uint32_t y, uint32_t w, uint32_t h, uint8_t* out);

private:
    // ---- scalable settings (the four presets of HybridRenderer.preset)
    struct Quality {
        int shadowRes = 2048, cascades = 4, shadowedLights = 2, probeBudget = 8, probeRays = 32, volumetricSteps = 14, maxLights = 128;
        bool gtao = true, ssr = true, volumetrics = true, taa = true, bloom = true;
        float renderScale = 1, shadowDistance = 150;
    } q_;
    int presetLevel_ = -1;
    void preset(int level);

    // ---- one frame's description, as the commands gave it
    struct Env {
        float sun[3] = {0.3f, 0.8f, 0.4f}, daylight = 1, time = 0, rain = 0, wet = -1, snow = 0, overcast = -1, lightning = 0;
        float fog[3] = {0.6f, 0.62f, 0.64f}, fogDensity = 1.0f / 200, wallStart = 1e9f, wallEnd = 2e9f, skyHorizon = 0, skyZenith = 0;
        float tint[3] = {1, 1, 1}, ambientFloor = 0.0035f, fogOrigin[3] = {std::numeric_limits<float>::quiet_NaN(), 0, 0}, bloom = 0.05f;
    };
    struct Camera {
        Mat4 view, proj, vmProj;
        float x = 0, y = 0, z = 0, fx = 0, fy = 0, fz = -1, fovY = 1.2f, aspect = 1.6f, nearP = 0.05f, farP = 900, viewDistance = 260, dt = 1.0f / 60;
        bool reset = false, prepare = false;
    };
    struct Model { uint32_t mesh; uint32_t flags; float m[16]; float par[8]; };
    struct Quads { uint32_t texture; std::vector<float> v; };
    struct Grading { float death = 0, hurt = 0, infection = 0, flash = 0, bile = 0; };
    struct ModelMesh { gl::GLuint vao = 0, vbo = 0; uint32_t count = 0; bool translucent = false; };
    struct Texture { gl::GLuint id = 0; gl::GLenum target = 0; uint32_t layers = 1; };

    bool parse(const uint8_t* cmds, uint32_t bytes);
    void bindWorld(Engine* e, astratto_handle h);
    /** Builds again the programs that depend on the world's size and street level. */
    bool buildPrograms(int sx, int sy, int sz);

    // ---- resources
    gl::GLuint tex2D(gl::GLenum format, int w, int h, gl::GLenum filter, int levels);
    gl::GLuint fbo(gl::GLuint color, gl::GLuint depth);
    void resize(int w, int h);
    void disposeTargets();
    void ensureShadowMaps();

    // ---- the world on the GPU
    void meshColumns(Engine* e, VoxelWorld& w, int budget);
    void uploadColumn(int col, const std::vector<uint8_t>& data, bool opaquePass);
    int drawOpaque(Program& p, const Frustum& fr, float cx, float cz, float maxDist, bool nearFirst);
    void drawTranslucent(Program& p, const Frustum& fr, float cx, float cz, float maxDist);
    void voxelSync(VoxelWorld& w, int maxColumns);
    void buildPalette();
    void probesUpdate(int budget, int rays, float camX, float camZ);
    void probesInvalidate(int cx, int cz, bool spread);
    float probesSettledNear(float x, float z, float radius) const;

    // ---- passes
    void environment();
    void skyUniforms(Program& s);
    void probeUniforms(Program& s, int unit);
    void bankUniforms(Program& s);
    void shadowPass(VoxelWorld* w);
    void gbufferPass(VoxelWorld* w);
    void drawModels(Program& s, bool viewmodel, VoxelWorld* w);
    void cullLights();
    void aoPass();
    void ssrPass();
    void lightingPass();
    void fogPass();
    void forwardPass(VoxelWorld* w);
    void resolve(int windowW, int windowH);
    void quadsPass(int windowW, int windowH);
    void fullscreen();

    gl::Api g_;
    std::string error_;
    std::unordered_map<std::string, std::string> sources_;

    // programs
    Program gWorld_, gModel_, sWorld_, sModel_, lighting_, cull_, gtao_, aoBlur_, ssr_, vol_, fogApply_, fWorld_, fModel_, particle_, taa_,
            bloomDown_, bloomUp_, lum_, exposure_, tonemap_, copy_, ui_, probeCs_;

    // targets
    int rw_ = 0, rh_ = 0, winW_ = 0, winH_ = 0;
    float appliedScale_ = 0;
    gl::GLuint gFbo_ = 0, gAlbedo_ = 0, gNormal_ = 0, gMat_ = 0, gDepth_ = 0;
    gl::GLuint hdrAFbo_ = 0, hdrA_ = 0, hdrBFbo_ = 0, hdrBColorFbo_ = 0, hdrB_ = 0;
    gl::GLuint hist_[2] = {}, histFbo_[2] = {};
    int histIdx_ = 0;
    gl::GLuint aoRawFbo_ = 0, aoRaw_ = 0, aoFbo_ = 0, ao_ = 0, ssrFbo_ = 0, ssrTex_ = 0, fogFbo_ = 0, fogTex_ = 0;
    static constexpr int BLOOM_LEVELS = 6;
    gl::GLuint bloomTex_[BLOOM_LEVELS] = {}, bloomFbo_[BLOOM_LEVELS] = {};
    int bloomW_[BLOOM_LEVELS] = {}, bloomH_[BLOOM_LEVELS] = {};
    gl::GLuint lumTex_ = 0, lumFbo_ = 0, expoTex_[2] = {}, expoFbo_[2] = {};
    int expoIdx_ = 0;
    gl::GLuint shadowTex_ = 0, shadowFbo_[4] = {};
    int shadowRes_ = 0, shadowLayers_ = 0, cascadeAge_ = -1;
    gl::GLuint layerMat_ = 0, fsVao_ = 0, lightBuf_ = 0, tileBuf_ = 0, white_ = 0;
    int tilesX_ = 0, tilesY_ = 0;
    static constexpr int TILE = 16, MAX_LIGHTS = 256, MAX_BODIES = 24;

    // the frame
    Camera cam_;
    Env env_;
    Grading grading_;
    std::vector<float> lights_;          // 12 floats a light
    int lightCount_ = 0;
    float bodies_[MAX_BODIES * 5] = {};
    int bodyCount_ = 0;
    std::vector<Model> models_;
    std::vector<float> particles_;       // 12 floats a particle
    std::vector<Quads> quads_;
    astratto_handle worldHandle_ = ASTRATTO_NO_HANDLE, pendingWorld_ = ASTRATTO_NO_HANDLE;
    uint32_t meshFlags_ = 0;
    bool hasCamera_ = false, hasClear_ = false;
    float clear_[4] = {0, 0, 0, 1};

    // matrices
    Mat4 projJ_, pv_, pvJ_, invPV_, invPVJ_, invProj_, invView_, prevPV_, vmPV_;
    Mat4 shadowVP_[4];
    float shadowVPFlat_[64] = {}, splits_[4] = {}, cascadeTexel_[4] = {};
    Frustum frustum_;
    uint64_t frameNo_ = 0;
    bool historyValid_ = false;
    float sunDir_[3] = {}, sunCol_[3] = {}, zenith_[3] = {}, horizon_[3] = {}, fogCol_[3] = {}, ambient_[3] = {};
    int lastSunBucket_ = INT32_MIN;

    // resources the caller made
    std::unordered_map<uint32_t, Texture> textures_;
    std::unordered_map<uint32_t, ModelMesh> meshes_;
    uint32_t nextTexture_ = 1, nextMesh_ = 1;
    std::vector<std::pair<uint64_t, ModelMesh>> graveyard_;     // released meshes, deleted a few frames later
    gl::GLuint particleVao_ = 0, particleVbo_ = 0, quadVao_ = 0, quadVbo_ = 0, quadEbo_ = 0;
    size_t particleCap_ = 0, quadCap_ = 0;

    // what the world looks like
    bool hasLook_ = false;
    uint32_t atlasTex_ = 0, noiseTex_ = 0, layers_ = 0;
    std::vector<astratto_render_block> lookBlocks_;
    std::vector<astratto_shape_def> lookShapes_;
    std::vector<int8_t> lookBoxes_;
    astratto_render_table lookTable_{};
    std::vector<float> palette_;           // 256 * 8
    float groundY_ = 24;
    std::vector<std::unique_ptr<ChunkMesher>> meshers_;

    // the world on the GPU
    VoxelWorld* world_ = nullptr;
    int sizeX_ = 0, sizeY_ = 0, sizeZ_ = 0, CX_ = 0, CZ_ = 0;
    std::vector<gl::GLuint> vaoO_, vboO_, vaoT_, vboT_;
    std::vector<int> quadsO_, quadsT_;
    std::vector<int> pending_;             // columns waiting to be meshed
    std::vector<uint8_t> queued_;
    std::vector<int> voxelDirty_;
    std::vector<uint8_t> voxelQueued_;
    gl::GLuint ebo_ = 0;
    static constexpr int MAX_QUADS = 1 << 20;
    gl::GLuint voxTex_ = 0, paletteTex_ = 0;
    uint32_t opaqueMask_[8] = {};
    bool voxFilled_ = false;
    std::vector<uint8_t> column_;
    std::vector<uint64_t> order_;
    struct ColumnOut { std::vector<uint8_t> o, t; bool ok = false; };
    std::vector<ColumnOut> meshed_;
    uint32_t columnsMeshed_ = 0, columnsDrawn_ = 0, quadsDrawn_ = 0, modelsDrawn_ = 0;
    // probes
    int PX_ = 0, PY_ = 0, PZ_ = 0;
    static constexpr int SPACING = 4, MAX_BATCH = 48, SETTLED = 6;
    gl::GLuint probeR_ = 0, probeG_ = 0, probeB_ = 0, probeVis_ = 0, probeMeta_ = 0;
    std::vector<int> passes_, signature_;
    std::vector<int64_t> lastFrame_;
    std::vector<uint8_t> everLit_;
    std::vector<float> score_;
    int currentSignature_ = 1;
    int64_t probeFrame_ = 0;
    int batch_[MAX_BATCH * 2] = {};
    float blend_[MAX_BATCH] = {};
    float rot_[9] = {};
    uint64_t rng_ = 7;
    int probesUpdated_ = 0, probesUnsettled_ = 0;
    void freeWorld();
};

}  // namespace astratto
