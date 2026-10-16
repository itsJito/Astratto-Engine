// Astratto Engine - the OpenGL 4.5 the renderer draws with, loaded at run time through the loader the caller hands over
// (glfwGetProcAddress or the like - a plain C function). Nothing links against an OpenGL library: the same engine binary
// runs on any platform's driver, and a machine without GL 4.5 is told so (ASTRATTO_ERR_UNSUPPORTED) instead of crashing.
#pragma once

#include <cstddef>
#include <cstdint>

namespace astratto {
namespace gl {

typedef unsigned int GLenum;
typedef unsigned char GLboolean;
typedef unsigned int GLbitfield;
typedef int GLint;
typedef int GLsizei;
typedef unsigned int GLuint;
typedef float GLfloat;
typedef double GLdouble;
typedef char GLchar;
typedef std::ptrdiff_t GLsizeiptr;
typedef std::ptrdiff_t GLintptr;
typedef unsigned char GLubyte;

#if defined(_WIN32)
#  define ASTRATTO_GLAPI __stdcall
#else
#  define ASTRATTO_GLAPI
#endif

// ---- the constants used
enum : GLenum {
    FALSE_ = 0, TRUE_ = 1, NONE = 0, ZERO = 0, ONE = 1,
    DEPTH_BUFFER_BIT = 0x00000100, COLOR_BUFFER_BIT = 0x00004000,
    TRIANGLES = 0x0004, TRIANGLE_STRIP = 0x0005,
    LEQUAL = 0x0203, LESS = 0x0201,
    SRC_ALPHA = 0x0302, ONE_MINUS_SRC_ALPHA = 0x0303,
    FRONT = 0x0404, BACK = 0x0405, CCW = 0x0901,
    CULL_FACE = 0x0B44, DEPTH_TEST = 0x0B71, BLEND = 0x0BE2, SCISSOR_TEST = 0x0C11, POLYGON_OFFSET_FILL = 0x8037,
    UNPACK_ALIGNMENT = 0x0CF5, PACK_ALIGNMENT = 0x0D05,
    TEXTURE_2D = 0x0DE1, TEXTURE_3D = 0x806F, TEXTURE_2D_ARRAY = 0x8C1A,
    UNSIGNED_BYTE = 0x1401, INT = 0x1404, UNSIGNED_INT = 0x1405, FLOAT = 0x1406,
    RED = 0x1903, RGB = 0x1907, RGBA = 0x1908, RED_INTEGER = 0x8D94,
    NEAREST = 0x2600, LINEAR = 0x2601, LINEAR_MIPMAP_LINEAR = 0x2703, LINEAR_MIPMAP_NEAREST = 0x2701,
    TEXTURE_MAG_FILTER = 0x2800, TEXTURE_MIN_FILTER = 0x2801, TEXTURE_WRAP_S = 0x2802, TEXTURE_WRAP_T = 0x2803, TEXTURE_WRAP_R = 0x8072,
    REPEAT = 0x2901, CLAMP_TO_EDGE = 0x812F,
    TEXTURE_COMPARE_MODE = 0x884C, TEXTURE_COMPARE_FUNC = 0x884D, COMPARE_REF_TO_TEXTURE = 0x884E,
    R8 = 0x8229, R8UI = 0x8232, R16F = 0x822D, RGBA8 = 0x8058, RGB10_A2 = 0x8059, RGBA16F = 0x881A, R11F_G11F_B10F = 0x8C3A,
    DEPTH_COMPONENT24 = 0x81A6, DEPTH_COMPONENT32F = 0x8CAC,
    TEXTURE0 = 0x84C0,
    ARRAY_BUFFER = 0x8892, ELEMENT_ARRAY_BUFFER = 0x8893, SHADER_STORAGE_BUFFER = 0x90D2,
    STATIC_DRAW = 0x88E4, STREAM_DRAW = 0x88E0, DYNAMIC_DRAW = 0x88E8, DYNAMIC_COPY = 0x88EA,
    FRAGMENT_SHADER = 0x8B30, VERTEX_SHADER = 0x8B31, COMPUTE_SHADER = 0x91B9,
    COMPILE_STATUS = 0x8B81, LINK_STATUS = 0x8B82, INFO_LOG_LENGTH = 0x8B84,
    FRAMEBUFFER = 0x8D40, READ_FRAMEBUFFER = 0x8CA8, FRAMEBUFFER_COMPLETE = 0x8CD5,
    COLOR_ATTACHMENT0 = 0x8CE0, COLOR_ATTACHMENT1 = 0x8CE1, COLOR_ATTACHMENT2 = 0x8CE2, DEPTH_ATTACHMENT = 0x8D00,
    READ_WRITE = 0x88BA,
    SHADER_IMAGE_ACCESS_BARRIER_BIT = 0x00000020, TEXTURE_FETCH_BARRIER_BIT = 0x00000008, SHADER_STORAGE_BARRIER_BIT = 0x00002000,
    MAJOR_VERSION = 0x821B, MINOR_VERSION = 0x821C, VERSION = 0x1F02, RENDERER = 0x1F01,
    NEAREST_MIPMAP_NEAREST = 0x2700, NEAREST_MIPMAP_LINEAR = 0x2702, TEXTURE_MAX_LEVEL = 0x813D,
    TEXTURE_MAX_ANISOTROPY = 0x84FE, MAX_TEXTURE_MAX_ANISOTROPY = 0x84FF,
    BACK_LEFT = 0x0402
};

// ---- the functions used: return type, name, parameters
#define ASTRATTO_GL_FUNCTIONS(X) \
    X(void, Viewport, (GLint x, GLint y, GLsizei w, GLsizei h)) \
    X(void, Clear, (GLbitfield mask)) \
    X(void, ClearColor, (GLfloat r, GLfloat g, GLfloat b, GLfloat a)) \
    X(void, ClearDepth, (GLdouble d)) \
    X(void, Enable, (GLenum cap)) \
    X(void, Disable, (GLenum cap)) \
    X(void, DepthFunc, (GLenum f)) \
    X(void, DepthMask, (GLboolean m)) \
    X(void, DepthRange, (GLdouble n, GLdouble f)) \
    X(void, BlendFunc, (GLenum s, GLenum d)) \
    X(void, CullFace, (GLenum m)) \
    X(void, FrontFace, (GLenum m)) \
    X(void, PolygonOffset, (GLfloat f, GLfloat u)) \
    X(void, Scissor, (GLint x, GLint y, GLsizei w, GLsizei h)) \
    X(void, Finish, (void)) \
    X(void, ReadPixels, (GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum t, void* p)) \
    X(void, ReadBuffer, (GLenum m)) \
    X(void, DrawBuffer, (GLenum m)) \
    X(GLenum, GetError, (void)) \
    X(void, GetIntegerv, (GLenum p, GLint* v)) \
    X(const GLubyte*, GetString, (GLenum n)) \
    X(void, PixelStorei, (GLenum p, GLint v)) \
    X(void, GenTextures, (GLsizei n, GLuint* t)) \
    X(void, DeleteTextures, (GLsizei n, const GLuint* t)) \
    X(void, BindTexture, (GLenum target, GLuint t)) \
    X(void, ActiveTexture, (GLenum unit)) \
    X(void, TexParameteri, (GLenum target, GLenum p, GLint v)) \
    X(void, TexParameterf, (GLenum target, GLenum p, GLfloat v)) \
    X(void, GetFloatv, (GLenum p, GLfloat* v)) \
    X(void, TexImage2D, (GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLint border, GLenum fmt, GLenum type, const void* p)) \
    X(void, TexImage3D, (GLenum target, GLint level, GLint ifmt, GLsizei w, GLsizei h, GLsizei d, GLint border, GLenum fmt, GLenum type, const void* p)) \
    X(void, TexSubImage3D, (GLenum target, GLint level, GLint x, GLint y, GLint z, GLsizei w, GLsizei h, GLsizei d, GLenum fmt, GLenum type, const void* p)) \
    X(void, TexStorage2D, (GLenum target, GLsizei levels, GLenum ifmt, GLsizei w, GLsizei h)) \
    X(void, TexStorage3D, (GLenum target, GLsizei levels, GLenum ifmt, GLsizei w, GLsizei h, GLsizei d)) \
    X(void, ClearTexImage, (GLuint t, GLint level, GLenum fmt, GLenum type, const void* data)) \
    X(void, GenerateMipmap, (GLenum target)) \
    X(void, GenFramebuffers, (GLsizei n, GLuint* f)) \
    X(void, DeleteFramebuffers, (GLsizei n, const GLuint* f)) \
    X(void, BindFramebuffer, (GLenum target, GLuint f)) \
    X(void, FramebufferTexture2D, (GLenum target, GLenum att, GLenum textarget, GLuint t, GLint level)) \
    X(void, FramebufferTextureLayer, (GLenum target, GLenum att, GLuint t, GLint level, GLint layer)) \
    X(GLenum, CheckFramebufferStatus, (GLenum target)) \
    X(void, DrawBuffers, (GLsizei n, const GLenum* bufs)) \
    X(void, GenBuffers, (GLsizei n, GLuint* b)) \
    X(void, DeleteBuffers, (GLsizei n, const GLuint* b)) \
    X(void, BindBuffer, (GLenum target, GLuint b)) \
    X(void, BufferData, (GLenum target, GLsizeiptr size, const void* data, GLenum usage)) \
    X(void, BufferSubData, (GLenum target, GLintptr off, GLsizeiptr size, const void* data)) \
    X(void, BindBufferBase, (GLenum target, GLuint index, GLuint b)) \
    X(void, GenVertexArrays, (GLsizei n, GLuint* a)) \
    X(void, DeleteVertexArrays, (GLsizei n, const GLuint* a)) \
    X(void, BindVertexArray, (GLuint a)) \
    X(void, VertexAttribPointer, (GLuint i, GLint size, GLenum type, GLboolean norm, GLsizei stride, const void* off)) \
    X(void, EnableVertexAttribArray, (GLuint i)) \
    X(void, VertexAttribDivisor, (GLuint i, GLuint d)) \
    X(void, DrawArrays, (GLenum mode, GLint first, GLsizei count)) \
    X(void, DrawElements, (GLenum mode, GLsizei count, GLenum type, const void* off)) \
    X(void, DrawArraysInstanced, (GLenum mode, GLint first, GLsizei count, GLsizei instances)) \
    X(GLuint, CreateShader, (GLenum type)) \
    X(void, ShaderSource, (GLuint s, GLsizei n, const GLchar* const* src, const GLint* len)) \
    X(void, CompileShader, (GLuint s)) \
    X(void, GetShaderiv, (GLuint s, GLenum p, GLint* v)) \
    X(void, GetShaderInfoLog, (GLuint s, GLsizei max, GLsizei* len, GLchar* log)) \
    X(GLuint, CreateProgram, (void)) \
    X(void, AttachShader, (GLuint p, GLuint s)) \
    X(void, LinkProgram, (GLuint p)) \
    X(void, GetProgramiv, (GLuint p, GLenum q, GLint* v)) \
    X(void, GetProgramInfoLog, (GLuint p, GLsizei max, GLsizei* len, GLchar* log)) \
    X(void, DeleteShader, (GLuint s)) \
    X(void, DeleteProgram, (GLuint p)) \
    X(void, UseProgram, (GLuint p)) \
    X(GLint, GetUniformLocation, (GLuint p, const GLchar* name)) \
    X(void, Uniform1i, (GLint l, GLint v)) \
    X(void, Uniform1f, (GLint l, GLfloat v)) \
    X(void, Uniform2f, (GLint l, GLfloat a, GLfloat b)) \
    X(void, Uniform3f, (GLint l, GLfloat a, GLfloat b, GLfloat c)) \
    X(void, Uniform4f, (GLint l, GLfloat a, GLfloat b, GLfloat c, GLfloat d)) \
    X(void, Uniform2i, (GLint l, GLint a, GLint b)) \
    X(void, Uniform1fv, (GLint l, GLsizei n, const GLfloat* v)) \
    X(void, Uniform2iv, (GLint l, GLsizei n, const GLint* v)) \
    X(void, Uniform4fv, (GLint l, GLsizei n, const GLfloat* v)) \
    X(void, ColorMaski, (GLuint buf, GLboolean r, GLboolean g, GLboolean b, GLboolean a)) \
    X(void, Uniform1uiv, (GLint l, GLsizei n, const GLuint* v)) \
    X(void, UniformMatrix3fv, (GLint l, GLsizei n, GLboolean t, const GLfloat* v)) \
    X(void, UniformMatrix4fv, (GLint l, GLsizei n, GLboolean t, const GLfloat* v)) \
    X(void, DispatchCompute, (GLuint x, GLuint y, GLuint z)) \
    X(void, MemoryBarrier, (GLbitfield b)) \
    X(void, BindImageTexture, (GLuint unit, GLuint t, GLint level, GLboolean layered, GLint layer, GLenum access, GLenum fmt))

/** The function table: one pointer a function, named as the GL names it without its prefix (gl.Clear(...)). */
struct Api {
#define ASTRATTO_GL_MEMBER(ret, name, params) ret (ASTRATTO_GLAPI* name) params = nullptr;
    ASTRATTO_GL_FUNCTIONS(ASTRATTO_GL_MEMBER)
#undef ASTRATTO_GL_MEMBER

    /** Looks every function up; false (and the first one missing in *missing) when the context lacks one. */
    bool load(void* (*loader)(const char*), const char** missing);
};

}  // namespace gl
}  // namespace astratto
