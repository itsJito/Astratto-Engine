/*
 * Astratto Engine - the interface a gameplay module written in C++ (or C, or anything that makes a shared library with
 * C linkage) implements. A game may write its logic in Java, in C++, or both: Java modules are run by the Java side
 * between frames (astratto.game.GameModule); C++ modules are loaded into the engine (astratto_module_load) and run by
 * it inside each frame. Both see the engine the same way - they write commands, read events and snapshots - and
 * neither is ever entered from an engine worker thread: a C++ module's functions are called on the thread that called
 * astratto_frame, at the points listed below, and nowhere else.
 *
 * A module library exports one function, astratto_module_entry, which fills in an astratto_module: its name and its
 * four functions. The engine hands each function an astratto_module_host: the module's whole view of the engine. A
 * module does not link against the engine library and calls nothing else of it.
 *
 *   init      once, when it is loaded; returns the module's own state (passed back to every other call)
 *   update    every frame, after the caller's commands and before the simulation; commands written here are run then
 *   event     every frame, after the simulation and the questions, with all of that frame's events
 *   shutdown  once, when its handle is released or the engine destroyed
 *
 * Memory: everything the host hands a module (events, snapshots, the world view) is the engine's and lives as the C
 * interface says (events until the next frame, a snapshot until the next of its kind). What a module allocates is its
 * own and it frees it in shutdown.
 */
#ifndef ASTRATTO_MODULE_H
#define ASTRATTO_MODULE_H

#include "astratto/astratto.h"

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  define ASTRATTO_MODULE_EXPORT __declspec(dllexport)
#else
#  define ASTRATTO_MODULE_EXPORT __attribute__((visibility("default")))
#endif

#define ASTRATTO_MODULE_ABI 1

typedef struct astratto_module_host {
    uint32_t struct_size;
    uint32_t api_version;                  /* ASTRATTO_API_VERSION of the engine */
    void* ctx;                             /* the engine's own: passed back to every function below */
    astratto_handle self;                  /* this module's handle */
    /* Runs one command record now, exactly as if the caller had written it at this point of the frame. */
    int32_t (*command)(void* ctx, const void* record, uint32_t bytes);
    int32_t (*reserve)(void* ctx, uint32_t kind, uint32_t count, astratto_handle* out);
    int32_t (*snapshot)(void* ctx, uint32_t kind, const void** data, uint64_t* bytes);
    /* The active world's memory, read-only (ASTRATTO_ERR_STATE when there is none). */
    int32_t (*world_view)(void* ctx, astratto_world_view* out);
    double (*time)(void* ctx);             /* seconds simulated */
    /* A line for whoever drives the engine: it comes back to the caller as an ASTRATTO_EVT_MODULE_LOG event. */
    void (*log)(void* ctx, const char* text);
} astratto_module_host;

typedef struct astratto_module {
    uint32_t struct_size;
    uint32_t abi;                          /* ASTRATTO_MODULE_ABI */
    const char* name;
    void* (*init)(const astratto_module_host* host, const char* config);
    void (*update)(void* self, const astratto_module_host* host, float dt);
    void (*event)(void* self, const astratto_module_host* host, const uint8_t* events, uint32_t bytes, uint32_t count);
    void (*shutdown)(void* self, const astratto_module_host* host);
} astratto_module;

/* What a module library exports. Returns ASTRATTO_OK, or ASTRATTO_ERR_VERSION when it was built for another major. */
typedef int32_t (*astratto_module_entry_fn)(uint32_t api_version, astratto_module* out);
#define ASTRATTO_MODULE_ENTRY "astratto_module_entry"

/* The event a module's log line comes back as: astratto_evt_module_log, then the text (not terminated). */
enum { ASTRATTO_EVT_MODULE_LOG = 128 };
typedef struct astratto_evt_module_log { astratto_record head; astratto_handle module; uint32_t length; uint32_t reserved; } astratto_evt_module_log;

#ifdef __cplusplus
}
#endif
#endif
