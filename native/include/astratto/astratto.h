/*
 * Astratto Engine - the native core's public interface.
 *
 * This header is the whole of the bridge between the engine (C++) and whatever is built on it (a game's gameplay may
 * be Java, C++ or anything else that can call C). It is plain C, so that any language with a foreign-function interface
 * can bind it, and it is versioned, so that a game built against one engine refuses to run on another instead of
 * misreading it.
 *
 * The rules of the boundary - they are what this file is shaped by:
 *
 *   1. The engine owns native memory and the authoritative state of what it simulates: the voxel worlds, the bodies,
 *      the agents, the sounds. Whatever the caller sees of that state is a read-only view or a snapshot.
 *   2. Nothing is asked of the engine one object at a time. The caller writes a batch of COMMANDS into a buffer and
 *      hands the whole batch over once a tick (astratto_frame). The engine answers with a batch of EVENTS. The only
 *      calls made outside a frame are the ones that create, view or release a thing (and the world edits that build a
 *      world, which are batched too).
 *   3. The engine never calls back into the caller. Not from a frame, not from a worker thread, not ever. Gameplay
 *      modules written in C++ are run by the engine inside a frame, on the caller's thread; modules in any other
 *      language are run by the caller between frames. Neither is ever entered from an engine worker thread.
 *   4. Every structure starts with its own size, and every function returns a status. That is what lets a structure
 *      grow in a later minor version without breaking a caller built against an older one.
 *   5. Every piece of memory crossing the boundary has a stated lifetime (see "Memory lifetimes" below).
 *
 * Threads. One thread drives an engine: every function here is called from the thread that created the engine
 * (the renderer's functions from the thread that holds its GL context, which is normally the same one). The engine's
 * own workers and its audio thread never run caller code.
 *
 * Memory lifetimes.
 *   - Command buffers are the caller's: read during the call they are passed to, never kept.
 *   - Events (astratto_frame_io.events) are the engine's: valid until the next astratto_frame on that engine.
 *   - Snapshots (astratto_snapshot_io.data) are the engine's: valid until the next astratto_snapshot of the same kind.
 *   - A world view (astratto_world_get_view) is the engine's memory itself, read-only: valid until the world is released
 *     or the engine destroyed. It changes as the world is edited - by the caller's edits, between calls, never during
 *     one of the caller's reads.
 *   - Strings returned (astratto_build_info, astratto_last_error) stay valid until the next call on that engine.
 *   - Data handed to a create call (a world's cells, a sound's samples, a texture's pixels, a mesh's vertices) is
 *     copied before the call returns; the caller may free it at once.
 *   - A handle is valid from the call that created it until the frame or call that releases it; using a released
 *     handle is refused (status ASTRATTO_ERR_HANDLE, or an ASTRATTO_EVT_ERROR event) - it never reaches freed memory,
 *     because every handle carries a generation that the engine checks.
 *
 * Byte order is the machine's (little-endian on every platform the engine builds for). All records in command and
 * event streams are a multiple of four bytes long and are read by copying, never in place: a reader assumes no
 * alignment beyond that.
 *
 * Copyright (c) Astratto North of LudusValley Recreations, under VEIN Enterprises.
 */
#ifndef ASTRATTO_H
#define ASTRATTO_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(ASTRATTO_BUILD)
#    define ASTRATTO_API __declspec(dllexport)
#  elif defined(ASTRATTO_STATIC)
#    define ASTRATTO_API
#  else
#    define ASTRATTO_API __declspec(dllimport)
#  endif
#else
#  define ASTRATTO_API __attribute__((visibility("default")))
#endif

/* ------------------------------------------------------------------ version */
/* MAJOR moves when a caller built against the old header would misbehave; MINOR when something was only added.
 * 2.0: worlds are the engine's (created, viewed, edited; no longer copied in and mirrored), handles, the simulation
 * services (physics, AI, audio), the renderer and gameplay modules. */
#define ASTRATTO_API_MAJOR 2
#define ASTRATTO_API_MINOR 0
#define ASTRATTO_API_VERSION ((uint32_t)((ASTRATTO_API_MAJOR << 16) | ASTRATTO_API_MINOR))

/* ------------------------------------------------------------------ status */
enum {
    ASTRATTO_OK = 0,
    ASTRATTO_ERR_ARGUMENT = -1,   /* a null pointer, a structure too small, a value out of range */
    ASTRATTO_ERR_VERSION = -2,    /* the caller was built against an incompatible major version */
    ASTRATTO_ERR_STATE = -3,      /* asked for before what it needs exists (a path before a world) */
    ASTRATTO_ERR_MEMORY = -4,
    ASTRATTO_ERR_STREAM = -5,     /* a malformed command stream; nothing after the bad record was run */
    ASTRATTO_ERR_INTERNAL = -6,
    ASTRATTO_ERR_HANDLE = -7,     /* a handle that is null, of the wrong kind, or already released */
    ASTRATTO_ERR_UNSUPPORTED = -8 /* not available in this build or on this machine (no audio device, no GL 4.5) */
};

typedef struct astratto_engine astratto_engine;

/* ------------------------------------------------------------------ handles */
/*
 * Everything the engine holds for the caller is named by a handle: 64 bits, of which the top eight say what kind of
 * thing it is, the next twenty-four are a generation and the low thirty-two an index. 0 is no handle. The caller
 * treats a handle as an opaque number.
 *
 * Handles are made two ways. Things whose making needs data handed over at once (a world, a sound, a module) are made
 * by a call that returns the handle. Things made in quantity during play (bodies, voices, agents, policies) are made
 * by a command in a frame; their handles are taken beforehand, in bulk, with astratto_handles_reserve - a reserved
 * handle is valid at once, refers to nothing until the command that makes it runs, and is released like any other.
 */
typedef uint64_t astratto_handle;
#define ASTRATTO_NO_HANDLE ((astratto_handle)0)
enum {
    ASTRATTO_KIND_WORLD = 1,
    ASTRATTO_KIND_RAGDOLL = 2,     /* the fifteen-particle human body, with Sydney's controllers */
    ASTRATTO_KIND_SOFTBODY = 3,    /* particles, sticks and limits of any shape (animals, carcasses) */
    ASTRATTO_KIND_MOVER = 4,       /* an upright body moved through the voxel world (a character controller) */
    ASTRATTO_KIND_VEHICLE = 5,     /* a car: rigid body, wheels, suspension, tyres, transmission */
    ASTRATTO_KIND_AGENT = 6,       /* an AI agent: route following, decisions, perception */
    ASTRATTO_KIND_SOUND = 7,       /* a decoded sample */
    ASTRATTO_KIND_VOICE = 8,       /* a sound playing */
    ASTRATTO_KIND_POLICY = 9,      /* a set of AI actions and how they are scored */
    ASTRATTO_KIND_MODULE = 10,     /* a gameplay module loaded from a library */
    ASTRATTO_KINDS = 11
};
#define ASTRATTO_HANDLE_KIND(h) ((uint32_t)((h) >> 56))

/* ------------------------------------------------------------------ making and ending an engine */
enum {
    ASTRATTO_CONFIG_AUDIO_DEVICE = 1u << 0,  /* open the system's audio output (without it audio is rendered on request only) */
    ASTRATTO_CONFIG_DETERMINISTIC = 1u << 1  /* one worker: results independent of thread timing (tests, replays) */
};

typedef struct astratto_config {
    uint32_t struct_size;
    uint32_t api_version;      /* ASTRATTO_API_VERSION the caller was compiled against */
    uint32_t worker_threads;   /* 0 = one per hardware thread, less one (the caller's own thread works too) */
    uint32_t flags;            /* ASTRATTO_CONFIG_* */
    uint32_t audio_rate;       /* 0 = 44100 */
    uint32_t audio_block;      /* frames per mixed block, 0 = 1024 */
} astratto_config;

ASTRATTO_API uint32_t astratto_api_version(void);
ASTRATTO_API const char* astratto_build_info(void);
/*
 * The size in bytes of one of this header's structures, by its name ("astratto_cmd_mover_input"), or 0 for a name it
 * does not know. Bindings written by hand check their layouts against it once, when they load the library.
 */
ASTRATTO_API uint32_t astratto_layout(const char* name);
/* Returns null on failure; astratto_create_status says why. */
ASTRATTO_API astratto_engine* astratto_create(const astratto_config* config);
ASTRATTO_API int32_t astratto_create_status(void);
ASTRATTO_API void astratto_destroy(astratto_engine* engine);
/* The text of the last error on this engine (never null; empty when there has been none). */
ASTRATTO_API const char* astratto_last_error(astratto_engine* engine);

/* Takes count fresh handles of a kind for things a later command will make. */
ASTRATTO_API int32_t astratto_handles_reserve(astratto_engine* engine, uint32_t kind, uint32_t count, astratto_handle* out);
/* Releases anything by its handle, now: a world, a sound, a body... What it held is freed; the handle is dead. */
ASTRATTO_API int32_t astratto_release(astratto_engine* engine, astratto_handle handle);

/* ------------------------------------------------------------------ the world: voxels */
/*
 * Block kinds are the game's, not the engine's: the engine is told only what it needs to know about each of the 256
 * possible ids, once, for every world it will hold. Flags the engine understands:
 */
enum {
    ASTRATTO_BLOCK_SOLID = 1u << 0,      /* a body cannot be where it is */
    ASTRATTO_BLOCK_OPAQUE = 1u << 1,     /* nothing is seen through it, and light stops at it */
    ASTRATTO_BLOCK_CUTOUT = 1u << 2,     /* mesh or grating: solid, but seen through */
    ASTRATTO_BLOCK_CLIMB = 1u << 3,      /* something to hold (a ladder) */
    ASTRATTO_BLOCK_STEP = 1u << 4,       /* a stair tread: a step up onto it is walked, not jumped */
    ASTRATTO_BLOCK_LIQUID = 1u << 5,     /* waded through, slowly */
    ASTRATTO_BLOCK_HAZARD = 1u << 6,     /* never routed over or through */
    ASTRATTO_BLOCK_DOOR = 1u << 7,       /* a door leaf: passable to those who open doors unless it is sealed */
    ASTRATTO_BLOCK_SKY_DIM = 1u << 8,    /* daylight coming down through it loses two of its fifteen levels (water, leaves) */
    ASTRATTO_BLOCK_LIGHT_PASS = 1u << 9, /* (only compared) a change to or from it relights the cells round it */
    ASTRATTO_BLOCK_NO_COLLIDE_AI = 1u << 10 /* bodies driven by agents pass it (laundry on a line) */
};
enum { ASTRATTO_MATERIAL_OTHER = 0, ASTRATTO_MATERIAL_GLASS = 1, ASTRATTO_MATERIAL_FOLIAGE = 2 };

typedef struct astratto_block_def {
    uint32_t flags;
    uint16_t hide_mask;   /* for shaped blocks: bit n set = the shape hides a line crossing sixteenth n of its height */
    uint8_t material;     /* ASTRATTO_MATERIAL_* */
    uint8_t shape;        /* 0 = a full cube; anything else = furniture or a fitting (see hide_mask) */
    uint8_t emission;     /* light it gives off, 0..15 */
    uint8_t surface;      /* what a tyre or a foot finds on it: an index into the surface table (ASTRATTO_CMD_SURFACE) */
    uint8_t absorb;       /* how much of a sound striking it it swallows, 0 (stone) .. 255 (cloth) */
    uint8_t reserved;
} astratto_block_def;

typedef struct astratto_block_table {
    uint32_t struct_size;
    uint32_t count;                    /* at most 256; ids not given are air */
    const astratto_block_def* defs;
} astratto_block_table;

/* What the blocks mean, for every world of this engine. Set before the first world is made; setting it again applies to all. */
ASTRATTO_API int32_t astratto_world_block_table(astratto_engine* engine, const astratto_block_table* table);

/*
 * A world. Cells are one byte each and light is one byte each (sky light in the high four bits, block light in the
 * low four), both laid out column by column: index = (x * size_z + z) * size_y + y.
 * Limits: size_x and size_z at most 1024, size_y at most 2048.
 */
enum {
    ASTRATTO_WORLD_LIGHT = 1u << 0     /* compute all light at once when it is made */
};
typedef struct astratto_world_desc {
    uint32_t struct_size;
    uint32_t size_x, size_y, size_z;
    uint32_t below_floor_block;        /* the id reported for any cell under y = 0 */
    uint32_t flags;                    /* ASTRATTO_WORLD_* */
    const uint8_t* blocks;             /* the cells to start with (copied); null = all air */
    uint64_t block_bytes;
    const uint8_t* light;              /* light to start with (copied); null = none (see ASTRATTO_WORLD_LIGHT) */
    uint64_t light_bytes;
} astratto_world_desc;

ASTRATTO_API int32_t astratto_world_create(astratto_engine* engine, const astratto_world_desc* desc, astratto_handle* out);

/* The world's own memory, read-only. Valid until the world is released. */
typedef struct astratto_world_view {
    uint32_t struct_size;
    uint32_t size_x, size_y, size_z;
    const uint8_t* blocks;
    const uint8_t* light;
    const uint8_t* pristine;           /* the cells as they were when last saved (ASTRATTO_EDIT_PRISTINE_SAVE); null until then */
    uint64_t cells;                    /* size_x * size_y * size_z */
    uint64_t edits;                    /* moves with every change to a cell */
    uint32_t nav_version;              /* moves whenever a change could open or close a way through */
    uint32_t light_version;            /* moves whenever any light is recomputed */
} astratto_world_view;

ASTRATTO_API int32_t astratto_world_get_view(astratto_engine* engine, astratto_handle world, astratto_world_view* out);

/*
 * Edits: a batch of records, applied at once and in order (this is how a world is built and changed between frames;
 * the same records are accepted in a frame's command stream, where they apply to the active world). Every record
 * begins with an astratto_record (below).
 */
typedef struct astratto_record {
    uint32_t type;
    uint32_t bytes;   /* the size of the whole record, header included; a multiple of four */
} astratto_record;

enum {
    ASTRATTO_EDIT_BLOCK = 1,           /* astratto_cmd_block_set */
    ASTRATTO_EDIT_FILL = 5,            /* astratto_edit_fill: a box of cells set raw */
    ASTRATTO_EDIT_LIGHT_ALL = 6,       /* bare record: compute every cell's light */
    ASTRATTO_EDIT_LIGHT_BOX = 7,       /* astratto_edit_box: relight a box of columns (and fifteen cells round it) */
    ASTRATTO_EDIT_LIGHT_FILL = 8,      /* astratto_edit_light_fill: set every cell's light byte to one value (tests) */
    ASTRATTO_EDIT_PRISTINE_SAVE = 9,   /* bare record: remember the cells as they are now */
    ASTRATTO_EDIT_PRISTINE_RESTORE = 10, /* bare record: put the remembered cells back (light is not recomputed) */
    ASTRATTO_EDIT_MARK_DIRTY = 11,     /* astratto_edit_box: the renderer re-meshes these columns */
    ASTRATTO_EDIT_LIGHT_SET = 12,      /* astratto_edit_light_set: one cell's light byte */
    ASTRATTO_EDIT_CLEAR = 13,          /* bare record: every cell air, light none, doors unsealed */
    ASTRATTO_EDIT_COLUMN = 19          /* astratto_edit_column + size_y bytes: one whole column of cells, set raw */
};

/* block: the id in the low eight bits; ASTRATTO_SET_RAW in the flags above them sets the cell and nothing more. */
enum { ASTRATTO_SET_RAW = 1u << 8 };
typedef struct astratto_cmd_block_set { astratto_record head; int32_t x, y, z; uint32_t block; } astratto_cmd_block_set;
typedef struct astratto_edit_fill { astratto_record head; int32_t x0, y0, z0, x1, y1, z1; uint32_t block; uint32_t reserved; } astratto_edit_fill;
typedef struct astratto_edit_box { astratto_record head; int32_t x0, z0, x1, z1; } astratto_edit_box;
typedef struct astratto_edit_light_fill { astratto_record head; uint32_t value; uint32_t reserved; } astratto_edit_light_fill;
typedef struct astratto_edit_light_set { astratto_record head; int32_t x, y, z; uint32_t value; } astratto_edit_light_set;
typedef struct astratto_edit_column { astratto_record head; int32_t x, z; } astratto_edit_column;

/*
 * A runtime block set (no ASTRATTO_SET_RAW): the cell changes; if the light it gives or stops changed, the cells round
 * it are relit at once; the renderer is told the columns it touches; the nav version moves if a way opened or shut.
 */
ASTRATTO_API int32_t astratto_world_edit(astratto_engine* engine, astratto_handle world, const uint8_t* records, uint32_t bytes);
/* One cell, raw or not, without a record: the same as a one-record astratto_world_edit (generators set millions). */
ASTRATTO_API int32_t astratto_world_set(astratto_engine* engine, astratto_handle world, int32_t x, int32_t y, int32_t z, uint32_t block);

/* ------------------------------------------------------------------ commands (caller -> engine, in a frame) */
/*
 * A frame's command stream is a run of records. Unknown commands from a newer minor version are skipped by their
 * own size. World edit records (ASTRATTO_EDIT_*) may appear in it too and apply to the active world.
 *
 * Numbering: 1-31 world and queries, 32-63 physics, 64-95 AI, 96-127 audio, 128-159 modules; events likewise.
 */
enum {
    ASTRATTO_CMD_BLOCK_SET = ASTRATTO_EDIT_BLOCK,
    ASTRATTO_CMD_DOOR_SEAL = 2,       /* astratto_cmd_door_seal */
    ASTRATTO_CMD_NAV_TUNING = 3,      /* astratto_cmd_nav_tuning */
    ASTRATTO_CMD_DOORS_UNSEAL = 4,    /* a bare astratto_record: every door is unsealed (a new set of doors follows) */
    ASTRATTO_CMD_WORLD_ACTIVATE = 14, /* astratto_cmd_handle: the world routes, sight, bodies and agents are in from now on */
    ASTRATTO_CMD_RELEASE = 15,        /* astratto_cmd_handle: release a thing at this point of the frame */
    ASTRATTO_CMD_PATH_REQUEST = 16,   /* astratto_cmd_path_request -> ASTRATTO_EVT_PATH_RESULT */
    ASTRATTO_CMD_SIGHT_REQUEST = 17,  /* astratto_cmd_sight_request -> ASTRATTO_EVT_SIGHT_RESULT */
    ASTRATTO_CMD_WALK_QUERY = 18,     /* astratto_cmd_walk_query -> ASTRATTO_EVT_WALK_RESULT */
    ASTRATTO_CMD_RAY_QUERY = 20       /* astratto_cmd_ray_query -> ASTRATTO_EVT_RAY_RESULT */
};

typedef struct astratto_cmd_handle { astratto_record head; astratto_handle handle; } astratto_cmd_handle;
/* A door cell that may not simply be opened (locked and still latched), or may again. */
typedef struct astratto_cmd_door_seal { astratto_record head; int32_t x, y, z; uint32_t sealed; } astratto_cmd_door_seal;
typedef struct astratto_cmd_nav_tuning { astratto_record head; float jump_cost, ladder_cost, mantle_cost; uint32_t reserved; } astratto_cmd_nav_tuning;

enum {
    ASTRATTO_PATH_DOORS = 1u << 0,     /* the walker opens doors (and pulls itself up ledges, with MANTLE) */
    ASTRATTO_PATH_GOAL_Y = 1u << 1,    /* the goal is on a particular floor (ty is meaningful) */
    ASTRATTO_PATH_MANTLE = 1u << 2
};
typedef struct astratto_cmd_path_request {
    astratto_record head;
    uint32_t request;                  /* the caller's own number for it; comes back with the result */
    uint32_t flags;
    float sx, sy, sz;
    float tx, ty, tz;
    uint32_t max_expand;
    uint32_t reserved;
} astratto_cmd_path_request;

enum {
    ASTRATTO_SIGHT_LIGHT = 0,          /* stops at opaque blocks only */
    ASTRATTO_SIGHT_EYE = 1             /* what an eye sees through: walls, shut doors, furniture and canopy stop it */
};
typedef struct astratto_cmd_sight_request {
    astratto_record head;
    uint32_t request;
    uint32_t mode;
    float ax, ay, az;
    float bx, by, bz;
} astratto_cmd_sight_request;

/* Walkability: what a route is made of, asked of a line or a cell. */
enum {
    ASTRATTO_WALK_CLEAR = 0,           /* is the straight line from a to b walkable at a's height (b's y unused) */
    ASTRATTO_WALK_CLEAR_BODY = 1,      /* the same, for a body of the given radius */
    ASTRATTO_WALK_STAND = 2,           /* can a body stand in cell (ax, ay, az) */
    ASTRATTO_WALK_STEP_Y = 3           /* the height a body walking into column (ax, az) from height ay stands at (-1 none) */
};
typedef struct astratto_cmd_walk_query {
    astratto_record head;
    uint32_t request;
    uint32_t kind;                     /* ASTRATTO_WALK_* */
    uint32_t doors;                    /* 1 = the walker opens doors */
    float radius;
    float ax, ay, az;
    float bx, by, bz;
} astratto_cmd_walk_query;

/* A ray through solid cells: where it first enters one, within max_dist (a bullet, a wheel, a camera). */
typedef struct astratto_cmd_ray_query {
    astratto_record head;
    uint32_t request;
    float max_dist;
    float ox, oy, oz;
    float dx, dy, dz;                  /* need not be unit length: it is normalised */
} astratto_cmd_ray_query;

/* ------------------------------------------------------------------ events (engine -> caller) */
enum {
    ASTRATTO_EVT_ERROR = 1,           /* astratto_evt_error: a command could not be carried out (the frame went on) */
    ASTRATTO_EVT_PATH_RESULT = 16,    /* astratto_evt_path_result, then cell_count * 3 int32 (x, y, z) */
    ASTRATTO_EVT_SIGHT_RESULT = 17,   /* astratto_evt_sight_result */
    ASTRATTO_EVT_WALK_RESULT = 18,    /* astratto_evt_walk_result */
    ASTRATTO_EVT_RAY_RESULT = 20      /* astratto_evt_ray_result */
};
enum { ASTRATTO_PATH_FOUND = 0, ASTRATTO_PATH_NONE = 1 };

typedef struct astratto_evt_error {
    astratto_record head;
    uint32_t command;                  /* the type of the command */
    int32_t status;                    /* ASTRATTO_ERR_* */
    astratto_handle handle;            /* the handle it was about, if any */
} astratto_evt_error;

typedef struct astratto_evt_path_result {
    astratto_record head;
    uint32_t request;
    uint32_t status;                   /* ASTRATTO_PATH_FOUND (cells follow; may stop short of the goal) or _NONE */
    uint32_t expansions;
    uint32_t cell_count;
} astratto_evt_path_result;

typedef struct astratto_evt_sight_result {
    astratto_record head;
    uint32_t request;
    uint32_t clear;                    /* 1 = nothing in the way */
} astratto_evt_sight_result;

typedef struct astratto_evt_walk_result {
    astratto_record head;
    uint32_t request;
    int32_t value;                     /* 1/0 for CLEAR, CLEAR_BODY and STAND; the height (or -1) for STEP_Y */
} astratto_evt_walk_result;

typedef struct astratto_evt_ray_result {
    astratto_record head;
    uint32_t request;
    float distance;                    /* -1 when nothing was struck */
    int32_t x, y, z;                   /* the cell struck */
    int32_t nx, ny, nz;                /* the face it was struck on */
    uint32_t block;
    uint32_t reserved;
} astratto_evt_ray_result;

/* ------------------------------------------------------------------ physics (commands 32-63, events 32-63) */
/*
 * One physics world: whatever bodies there are move through the active voxel world under one gravity, each kind at
 * its own fixed rate whatever the frame rate (ragdolls and soft bodies 90 Hz, cars 50 Hz, movers 120 Hz), so a run
 * is the same however it is cut into frames. Units are metres, seconds, kilograms; +y is up.
 *
 * Bodies are made by command, into handles reserved beforehand (ASTRATTO_KIND_MOVER, _RAGDOLL, _SOFTBODY, _VEHICLE).
 * What they are doing is read back from ASTRATTO_SNAPSHOT_BODIES; what happened to them comes back as events.
 */
enum {
    ASTRATTO_CMD_SURFACE = 32,          /* astratto_cmd_surface: what a surface does to a tyre */
    ASTRATTO_CMD_GRAVITY = 33,          /* astratto_cmd_gravity */
    ASTRATTO_CMD_MOVER_CREATE = 34,     /* astratto_cmd_mover_create */
    ASTRATTO_CMD_MOVER_INPUT = 35,      /* astratto_cmd_mover_input: how it wants to move this frame */
    ASTRATTO_CMD_MOVER_PLACE = 36,      /* astratto_cmd_body_place */
    ASTRATTO_CMD_RAGDOLL_CREATE = 37,   /* astratto_cmd_ragdoll_create */
    ASTRATTO_CMD_RAGDOLL_POSE = 38,     /* astratto_cmd_ragdoll_pose: the animated pose a body is handed over in */
    ASTRATTO_CMD_RAGDOLL_HIT = 39,      /* astratto_cmd_ragdoll_hit: a blow, answered by Sydney's controllers */
    ASTRATTO_CMD_RAGDOLL_IMPULSE = 40,  /* astratto_cmd_ragdoll_impulse: a change of velocity on a region, no controller */
    ASTRATTO_CMD_RAGDOLL_CONTROL = 41,  /* astratto_cmd_ragdoll_control: die, collapse, stay down, release, limbs */
    ASTRATTO_CMD_RAGDOLL_DISTURB = 42,  /* astratto_cmd_disturb: a blast or a boot near a body at rest */
    ASTRATTO_CMD_RAGDOLL_CARRY = 43,    /* astratto_cmd_ragdoll_carry: something large has hold of it */
    ASTRATTO_CMD_SYDNEY_PROFILE = 44,   /* astratto_cmd_sydney_profile: tuning for one kind of body */
    ASTRATTO_CMD_SOFTBODY_CREATE = 45,  /* astratto_cmd_softbody_create + particles, sticks, limits */
    ASTRATTO_CMD_SOFTBODY_IMPULSE = 46, /* astratto_cmd_softbody_impulse */
    ASTRATTO_CMD_VEHICLE_CREATE = 47,   /* astratto_cmd_vehicle_create + collision boxes */
    ASTRATTO_CMD_VEHICLE_INPUT = 48,    /* astratto_cmd_vehicle_input */
    ASTRATTO_CMD_VEHICLE_PLACE = 49,    /* astratto_cmd_body_place (yaw in vx) */
    ASTRATTO_CMD_BODY_IMPULSE = 50      /* astratto_cmd_body_impulse: a mover or a vehicle struck at a point */
};
enum {
    ASTRATTO_EVT_LANDED = 32,           /* astratto_evt_body: a mover or a car came down (value = speed) */
    ASTRATTO_EVT_RAGDOLL_STATE = 33,    /* astratto_evt_ragdoll_state: Sydney's state machine moved on */
    ASTRATTO_EVT_SETTLED = 34,          /* astratto_evt_body: a ragdoll or soft body came to rest */
    ASTRATTO_EVT_VEHICLE_IMPACT = 35,   /* astratto_evt_vehicle_impact */
    ASTRATTO_EVT_GEAR = 36,             /* astratto_evt_body: value = the new gear */
    ASTRATTO_EVT_BLOCK_HIT = 37         /* astratto_evt_vehicle_impact: a car went into a block at speed */
};

typedef struct astratto_cmd_surface {
    astratto_record head;
    uint32_t index;                    /* 0..63; block defs name one with their surface field */
    float adhesion;                    /* share of dry road grip, 1 = asphalt */
    float rolling;                     /* deceleration of a car freely rolling on it, m/s^2 */
    float drag;                        /* extra velocity-opposing drag, 1/s (sand, deep mud) */
} astratto_cmd_surface;

typedef struct astratto_cmd_gravity { astratto_record head; float gravity; float ragdoll_gravity; } astratto_cmd_gravity;

enum {
    ASTRATTO_MOVER_FLY = 1u << 0,       /* no gravity (a spectator, a test probe) */
    ASTRATTO_MOVER_CROUCH = 1u << 1,    /* the body is shorter (input flag) */
    ASTRATTO_MOVER_AGENT = 1u << 2      /* driven by an agent: passes ASTRATTO_BLOCK_NO_COLLIDE_AI blocks */
};
typedef struct astratto_cmd_mover_create {
    astratto_record head;
    astratto_handle handle;
    float x, y, z;                     /* the point between the feet */
    float radius, height;              /* half its width; its full height */
    float step;                        /* the highest step walked onto without a jump (0.6 is half a block and a bit) */
    uint32_t flags;                    /* ASTRATTO_MOVER_* */
    float crouch_height;
} astratto_cmd_mover_create;

typedef struct astratto_cmd_mover_input {
    astratto_record head;
    astratto_handle handle;
    float vx, vz;                      /* the velocity it wants along the ground, m/s (vy too when flying) */
    float vy;
    float jump;                        /* 0, or the upward speed of a jump to start now if it is on the ground */
    float accel;                       /* how fast it reaches the wanted velocity on the ground, m/s^2 (0 = 60) */
    uint32_t flags;                    /* ASTRATTO_MOVER_CROUCH */
} astratto_cmd_mover_input;

typedef struct astratto_cmd_body_place {
    astratto_record head;
    astratto_handle handle;
    float x, y, z;
    float vx, vy, vz;                  /* for a vehicle: vx is its heading (radians, 0 faces +z) and the rest unused */
} astratto_cmd_body_place;

typedef struct astratto_cmd_body_impulse {
    astratto_record head;
    astratto_handle handle;
    float x, y, z;                     /* where (world) */
    float jx, jy, jz;                  /* newton seconds */
} astratto_cmd_body_impulse;

/* The fifteen particles of a person (character space: +y up, faces -z). */
enum {
    ASTRATTO_RD_HEAD = 0, ASTRATTO_RD_NECK = 1, ASTRATTO_RD_PELVIS = 2,
    ASTRATTO_RD_L_SHOULDER = 3, ASTRATTO_RD_L_ELBOW = 4, ASTRATTO_RD_L_WRIST = 5,
    ASTRATTO_RD_R_SHOULDER = 6, ASTRATTO_RD_R_ELBOW = 7, ASTRATTO_RD_R_WRIST = 8,
    ASTRATTO_RD_L_HIP = 9, ASTRATTO_RD_L_KNEE = 10, ASTRATTO_RD_L_ANKLE = 11,
    ASTRATTO_RD_R_HIP = 12, ASTRATTO_RD_R_KNEE = 13, ASTRATTO_RD_R_ANKLE = 14,
    ASTRATTO_RD_PARTICLES = 15
};
/* Sydney's built-in profiles (ASTRATTO_CMD_SYDNEY_PROFILE replaces or adds to them, up to 16). */
enum { ASTRATTO_SYDNEY_HUMAN = 0, ASTRATTO_SYDNEY_INFECTED = 1, ASTRATTO_SYDNEY_CRAWLER = 2, ASTRATTO_SYDNEY_GIANT = 3 };
enum {
    ASTRATTO_RAGDOLL_ACTIVE = 1u << 0,  /* Sydney drives it (balance, steps, falls, getting up); without, it is limp */
    ASTRATTO_RAGDOLL_STAND = 1u << 1    /* made standing in its rest pose at (x, y, z) facing yaw (else from a RAGDOLL_POSE) */
};
typedef struct astratto_cmd_ragdoll_create {
    astratto_record head;
    astratto_handle handle;
    float scale;                       /* 1 = a person 1.8 m tall */
    uint32_t profile;                  /* ASTRATTO_SYDNEY_* */
    uint32_t flags;                    /* ASTRATTO_RAGDOLL_* */
    float x, y, z, yaw;
    float vx, vy, vz;
} astratto_cmd_ragdoll_create;

typedef struct astratto_cmd_ragdoll_pose {
    astratto_record head;
    astratto_handle handle;
    float vx, vy, vz;                  /* the velocity the body had */
    float p[ASTRATTO_RD_PARTICLES * 3];/* every particle's world position */
} astratto_cmd_ragdoll_pose;

typedef struct astratto_cmd_ragdoll_hit {
    astratto_record head;
    astratto_handle handle;
    uint32_t particle;                 /* ASTRATTO_RD_* struck */
    float dx, dy, dz;                  /* the direction of the blow */
    float impulse;                     /* newton seconds */
    float force;                       /* how hard it was felt (damage points of shove): how long the body is shaken */
} astratto_cmd_ragdoll_hit;

enum { ASTRATTO_REGION_TORSO = 0, ASTRATTO_REGION_HEAD = 1, ASTRATTO_REGION_ARMS = 2, ASTRATTO_REGION_LEGS = 3 };
typedef struct astratto_cmd_ragdoll_impulse {
    astratto_record head;
    astratto_handle handle;
    uint32_t region;                   /* ASTRATTO_REGION_* */
    float vx, vy, vz;                  /* m/s */
} astratto_cmd_ragdoll_impulse;

enum {
    ASTRATTO_CONTROL_DIE = 1,          /* the muscles let go over a moment; then it is a limp ragdoll and may settle */
    ASTRATTO_CONTROL_COLLAPSE = 2,     /* the legs are taken from under it */
    ASTRATTO_CONTROL_STAY_DOWN = 3,    /* value 1: it does not get up of its own accord; 0: it may again */
    ASTRATTO_CONTROL_RELEASE = 4,      /* Sydney stops driving it (the caller takes the body back into animation) */
    ASTRATTO_CONTROL_LIMBS = 5,        /* value: limbs that do not work (bits 0 left arm, 1 right arm, 2 left leg, 3 right leg, 4 head) */
    ASTRATTO_CONTROL_WAKE = 6          /* a body at rest is simulated again */
};
typedef struct astratto_cmd_ragdoll_control {
    astratto_record head;
    astratto_handle handle;
    uint32_t control;                  /* ASTRATTO_CONTROL_* */
    uint32_t value;
} astratto_cmd_ragdoll_control;

typedef struct astratto_cmd_disturb {
    astratto_record head;
    astratto_handle handle;            /* ASTRATTO_NO_HANDLE: every ragdoll and soft body in reach */
    float x, y, z, radius;
    float vx, vy, vz;
    uint32_t reserved;
} astratto_cmd_disturb;

typedef struct astratto_cmd_ragdoll_carry {
    astratto_record head;
    astratto_handle handle;
    float vx, vy, vz;
    float share;                       /* 0..1 of the way to that velocity, this frame */
} astratto_cmd_ragdoll_carry;

/* Every number Sydney balances, falls and gets up by, for one kind of body. Joint classes: spine, hip, knee, shoulder, elbow, neck. */
enum { ASTRATTO_STYLE_HUMAN = 0, ASTRATTO_STYLE_INFECTED = 1, ASTRATTO_STYLE_CRAWLER = 2 };
typedef struct astratto_sydney_profile {
    float freq[6], zeta[6], torque[6];
    float strength, stance_gain, stand_posture, stance_hip, carry_hip, carry_posture;
    float ankle_lever, balance_gain, step_threshold, step_time, step_reach, step_lift, step_overshoot, step_lead, step_force;
    float fall_tilt, fall_height, lost_reach, recover_settle;
    float fall_strength, brace, down_strength, down_min, down_per_force, down_max, hold_force;
    float get_up_time, get_up_strength, get_up_timeout;
    float death_fade, death_posture, crawl_height;
    uint32_t max_steps, get_up_tries, hold_wound, style;   /* style: ASTRATTO_STYLE_* */
} astratto_sydney_profile;
typedef struct astratto_cmd_sydney_profile {
    astratto_record head;
    uint32_t index;                    /* 0..15 */
    uint32_t reserved;
    astratto_sydney_profile profile;
} astratto_cmd_sydney_profile;

typedef struct astratto_softbody_particle { float x, y, z, radius, mass; } astratto_softbody_particle;
typedef struct astratto_softbody_stick { uint32_t a, b; } astratto_softbody_stick;
typedef struct astratto_softbody_limit { uint32_t a, b; float min, max; } astratto_softbody_limit;   /* shares of the present distance */
typedef struct astratto_cmd_softbody_create {
    astratto_record head;
    astratto_handle handle;
    uint32_t particles, sticks, limits; /* followed by that many of each, in that order */
    float slide, bounce, air;          /* 0 = the defaults (0.80, 0.18, 0.992) */
    float vx, vy, vz;
    uint32_t reserved;
} astratto_cmd_softbody_create;

typedef struct astratto_cmd_softbody_impulse {
    astratto_record head;
    astratto_handle handle;
    int32_t particle;                  /* -1: every particle, strongest nearest (x, y, z) within reach */
    float x, y, z, reach;
    float dvx, dvy, dvz;
} astratto_cmd_softbody_impulse;

/* A car's handling. Boxes (centre x y z, half extents x y z, car frame) follow the record: box_count * 6 floats. */
enum { ASTRATTO_DRIVE_REAR = 0, ASTRATTO_DRIVE_FRONT = 1, ASTRATTO_DRIVE_FOUR = 2 };
typedef struct astratto_vehicle_desc {
    float mass, width, length, height;
    float com_x, com_y, com_z, turn_mass;            /* turn_mass 0 = from the box */
    float engine_accel, max_velocity, max_reverse, first_gear_boost, second_gear_boost;
    float traction, traction_loss, traction_bias;
    float steering_lock, brake_decel, brake_bias;
    float susp_force, susp_damping, susp_upper, susp_lower, susp_bias, anti_dive;
    float wheel_radius, handbrake_tyre;
    float wheels[12];                                 /* FL, FR, RL, RR attachment points (car frame) */
    uint32_t gears, drive, rear_first, box_count;
} astratto_vehicle_desc;
typedef struct astratto_cmd_vehicle_create {
    astratto_record head;
    astratto_handle handle;
    astratto_vehicle_desc desc;
    float x, y, z, yaw;
} astratto_cmd_vehicle_create;

typedef struct astratto_cmd_vehicle_input {
    astratto_record head;
    astratto_handle handle;
    float throttle, brake, steer;      /* 0..1, 0..1, -1..1 */
    uint32_t handbrake, reverse, engine_on;
} astratto_cmd_vehicle_input;

typedef struct astratto_evt_body {
    astratto_record head;
    astratto_handle handle;
    float value;
    uint32_t reserved;
} astratto_evt_body;

enum { ASTRATTO_SYDNEY_STAND = 0, ASTRATTO_SYDNEY_STEP = 1, ASTRATTO_SYDNEY_FALL = 2, ASTRATTO_SYDNEY_DOWN = 3, ASTRATTO_SYDNEY_GETUP = 4, ASTRATTO_SYDNEY_DEAD = 5 };
enum { ASTRATTO_OUTCOME_NONE = 0, ASTRATTO_OUTCOME_RECOVERED = 1, ASTRATTO_OUTCOME_CRAWL = 2, ASTRATTO_OUTCOME_STUCK = 3, ASTRATTO_OUTCOME_DEAD = 4 };
typedef struct astratto_evt_ragdoll_state {
    astratto_record head;
    astratto_handle handle;
    uint32_t state, previous;          /* ASTRATTO_SYDNEY_* */
    uint32_t outcome;                  /* ASTRATTO_OUTCOME_* */
    uint32_t reserved;
} astratto_evt_ragdoll_state;

typedef struct astratto_evt_vehicle_impact {
    astratto_record head;
    astratto_handle handle;
    float dv;                          /* the change of speed it caused, m/s (BLOCK_HIT: the speed into the block) */
    float x, y, z;
    int32_t bx, by, bz;                /* the block struck (BLOCK_HIT) */
    uint32_t reserved;
} astratto_evt_vehicle_impact;

/* ------------------------------------------------------------------ AI (commands 64-95, events 64-95) */
/*
 * NeuralAI, native. An AGENT perceives (eyes: the engine's own sight lines, batched across the workers; ears: noises
 * the caller reports), decides (a POLICY: actions, each scored by its considerations; the best one is followed, with a
 * little inertia so a mind does not flicker between two) and acts (the action's movement plan: a route searched by the
 * engine's pathfinder and followed with its walkability checks, cutting corners where a body fits, re-planned when the
 * world changes or the body is stuck).
 *
 * What an agent WANTS is the game's: a policy is data the game builds, its facts are numbers the game sets, and an
 * action of kind CUSTOM is carried out by the game (Java between frames, or a C++ module inside one) - the engine only
 * says which one was chosen. An agent with a mover body moves it; without one it moves itself.
 */
enum {
    ASTRATTO_CMD_POLICY_CREATE = 64,    /* astratto_cmd_policy_create + actions + considerations */
    ASTRATTO_CMD_AGENT_CREATE = 65,     /* astratto_cmd_agent_create */
    ASTRATTO_CMD_AGENT_FACT = 66,       /* astratto_cmd_agent_fact: one of the 16 numbers the game tells it */
    ASTRATTO_CMD_AGENT_TARGET = 67,     /* astratto_cmd_agent_target: what it is after (a body, an agent, or a place) */
    ASTRATTO_CMD_AGENT_GOAL = 68,       /* astratto_cmd_agent_goal: the place an action of kind MOVE_TO_GOAL goes */
    ASTRATTO_CMD_AGENT_POLICY = 69,     /* astratto_cmd_agent_policy: a different mind */
    ASTRATTO_CMD_NOISE = 70,            /* astratto_cmd_noise: something was heard */
    ASTRATTO_CMD_AGENT_FORCE = 71       /* astratto_cmd_agent_force: this action, now, whatever the scores say (or -1 to stop) */
};
enum {
    ASTRATTO_EVT_AGENT_ACTION = 64,     /* astratto_evt_agent: value = the action chosen, extra = the one before */
    ASTRATTO_EVT_AGENT_SEES = 65,       /* astratto_evt_agent: other = what it now sees */
    ASTRATTO_EVT_AGENT_LOST = 66,       /* astratto_evt_agent: other = what it no longer sees */
    ASTRATTO_EVT_AGENT_ARRIVED = 67,    /* astratto_evt_agent: it got where its action was taking it */
    ASTRATTO_EVT_AGENT_STUCK = 68,      /* astratto_evt_agent: it cannot get there */
    ASTRATTO_EVT_AGENT_HEARD = 69       /* astratto_evt_agent: value = how loud */
};

enum {
    ASTRATTO_ACT_IDLE = 0,              /* stand still */
    ASTRATTO_ACT_WANDER = 1,            /* walk to places nearby, one after another */
    ASTRATTO_ACT_MOVE_TO_GOAL = 2,      /* go to the agent's goal */
    ASTRATTO_ACT_APPROACH = 3,          /* go to the target, stopping at the action's range */
    ASTRATTO_ACT_FLEE = 4,              /* get the action's range away from the target */
    ASTRATTO_ACT_INVESTIGATE = 5,       /* go where the target was last seen, or a noise was last heard */
    ASTRATTO_ACT_HOLD = 6,              /* stay, facing the target */
    ASTRATTO_ACT_CUSTOM = 7             /* the game's own: the engine chooses it, the game carries it out */
};
/* What a consideration reads. 0..15 are the facts the game sets; above them what the engine knows itself. */
enum {
    ASTRATTO_IN_FACT0 = 0,
    ASTRATTO_IN_TARGET_SEEN = 16,       /* 1 while the target is in sight, else 0 */
    ASTRATTO_IN_TARGET_DISTANCE = 17,   /* metres to the target (to where it was last seen when it is not) */
    ASTRATTO_IN_SINCE_SEEN = 18,        /* seconds since the target was last seen (1e6 never) */
    ASTRATTO_IN_SINCE_HEARD = 19,       /* seconds since something was heard */
    ASTRATTO_IN_TIME_IN_ACTION = 20,    /* seconds in the current action */
    ASTRATTO_IN_RANDOM = 21,            /* a number 0..1, new each decision */
    ASTRATTO_IN_GOAL_DISTANCE = 22,     /* metres to the goal */
    ASTRATTO_IN_HAS_TARGET = 23,        /* 1 when it has a target at all */
    ASTRATTO_IN_COUNT = 24
};
enum { ASTRATTO_CURVE_LINEAR = 0, ASTRATTO_CURVE_INVERSE = 1, ASTRATTO_CURVE_QUADRATIC = 2, ASTRATTO_CURVE_LOGISTIC = 3, ASTRATTO_CURVE_STEP = 4 };
/* score = curve((input - lo) / (hi - lo) clamped 0..1), then lifted toward 1 by (1 - weight): a weight of 0 ignores it. */
typedef struct astratto_consideration { uint32_t input, curve; float lo, hi, weight, reserved; } astratto_consideration;
typedef struct astratto_action {
    uint32_t id;                       /* the game's number for it; comes back in ASTRATTO_EVT_AGENT_ACTION */
    uint32_t kind;                     /* ASTRATTO_ACT_* */
    float base;                        /* the score before considerations */
    float range;                       /* APPROACH: how close; FLEE: how far; WANDER: how far from where it started */
    float speed;                       /* m/s while doing it */
    uint32_t first, count;             /* its considerations, in the list that follows the actions */
} astratto_action;
typedef struct astratto_cmd_policy_create {
    astratto_record head;
    astratto_handle handle;
    uint32_t actions, considerations;  /* followed by that many astratto_action, then astratto_consideration */
    float inertia;                     /* how much the current action's score is raised (0.1 = ten per cent) */
    float think;                       /* seconds between decisions (0 = 0.2) */
} astratto_cmd_policy_create;

enum { ASTRATTO_AGENT_DOORS = 1u << 0, ASTRATTO_AGENT_MANTLE = 1u << 1, ASTRATTO_AGENT_BLIND = 1u << 2 };
typedef struct astratto_cmd_agent_create {
    astratto_record head;
    astratto_handle handle;
    astratto_handle policy;
    astratto_handle body;              /* a mover it walks, or no handle (it moves itself) */
    float x, y, z;                     /* where it starts when it has no body */
    float eye;                         /* eye height over its feet */
    float view_range, view_cos;        /* how far it sees, and the cosine of half its field of view */
    float hear_range;
    uint32_t team;                     /* agents and bodies of another team are what it can see as targets */
    uint32_t flags;                    /* ASTRATTO_AGENT_* */
    uint32_t seed;
} astratto_cmd_agent_create;

typedef struct astratto_cmd_agent_fact { astratto_record head; astratto_handle handle; uint32_t index; float value; } astratto_cmd_agent_fact;
typedef struct astratto_cmd_agent_target {
    astratto_record head;
    astratto_handle handle;
    astratto_handle target;            /* a mover, ragdoll, vehicle or agent; no handle = the place below */
    float x, y, z;
    uint32_t reserved;
} astratto_cmd_agent_target;
typedef struct astratto_cmd_agent_goal { astratto_record head; astratto_handle handle; float x, y, z; uint32_t reserved; } astratto_cmd_agent_goal;
typedef struct astratto_cmd_agent_policy { astratto_record head; astratto_handle handle; astratto_handle policy; } astratto_cmd_agent_policy;
typedef struct astratto_cmd_agent_force { astratto_record head; astratto_handle handle; int32_t action; uint32_t reserved; } astratto_cmd_agent_force;
/* A body that can be seen by agents of other teams (movers and agents are seen anyway: this marks a body as one). */
typedef struct astratto_cmd_noise { astratto_record head; float x, y, z, radius, loudness; uint32_t team; } astratto_cmd_noise;

typedef struct astratto_evt_agent {
    astratto_record head;
    astratto_handle handle;
    astratto_handle other;
    int32_t value, extra;
} astratto_evt_agent;

/* ------------------------------------------------------------------ audio (commands 96-127, events 96-127) */
/*
 * UltraEngine, native: a software mixer on its own thread. Voices are routed to five buses under a master; a
 * positional voice is attenuated with distance, panned from the listener's facing, muffled with distance and by
 * whatever of the active world stands between it and the listener (the engine's own sight lines), and sent into one
 * shared late reverb whose room is measured round the listener from the world's geometry. A compressor, a peak
 * limiter and a soft knee keep the sum in range. Sounds are decoded by the caller and handed over once.
 */
enum { ASTRATTO_BUS_SFX = 0, ASTRATTO_BUS_MUSIC = 1, ASTRATTO_BUS_VOICE = 2, ASTRATTO_BUS_AMBIENCE = 3, ASTRATTO_BUS_UI = 4, ASTRATTO_BUSES = 5 };
enum {
    ASTRATTO_CMD_LISTENER = 96,         /* astratto_cmd_listener */
    ASTRATTO_CMD_VOICE_PLAY = 97,       /* astratto_cmd_voice_play */
    ASTRATTO_CMD_VOICE_SET = 98,        /* astratto_cmd_voice_set */
    ASTRATTO_CMD_VOICE_STOP = 99,       /* astratto_cmd_voice_stop */
    ASTRATTO_CMD_AUDIO_MIX = 100        /* astratto_cmd_audio_mix: bus and master levels */
};
enum { ASTRATTO_EVT_VOICE_ENDED = 96 };   /* astratto_evt_body */

typedef struct astratto_sound_desc {
    uint32_t struct_size;
    uint32_t channels;                 /* 1 or 2 (stereo is mixed down for positional voices) */
    uint32_t rate;                     /* samples a second; resampled to the mixer's rate */
    uint32_t frames;
    const float* samples;              /* frames * channels, interleaved, -1..1 */
} astratto_sound_desc;
ASTRATTO_API int32_t astratto_sound_create(astratto_engine* engine, const astratto_sound_desc* desc, astratto_handle* out);

typedef struct astratto_cmd_listener { astratto_record head; float x, y, z, yaw; } astratto_cmd_listener;   /* yaw 0 faces -z */
enum { ASTRATTO_VOICE_LOOP = 1u << 0, ASTRATTO_VOICE_POSITIONAL = 1u << 1, ASTRATTO_VOICE_OCCLUDE = 1u << 2, ASTRATTO_VOICE_REVERB = 1u << 3 };
typedef struct astratto_cmd_voice_play {
    astratto_record head;
    astratto_handle voice;             /* reserved beforehand (ASTRATTO_KIND_VOICE) */
    astratto_handle sound;
    uint32_t bus, flags;               /* ASTRATTO_BUS_*, ASTRATTO_VOICE_* */
    float gain, pitch, pan;            /* pan only for voices that are not positional */
    float x, y, z, range;              /* positional: where, and how far it carries */
    float fade_in;                     /* seconds */
    uint32_t reserved;
} astratto_cmd_voice_play;
enum { ASTRATTO_SET_GAIN = 1u << 0, ASTRATTO_SET_PITCH = 1u << 1, ASTRATTO_SET_POSITION = 1u << 2, ASTRATTO_SET_FADE = 1u << 3 };
typedef struct astratto_cmd_voice_set {
    astratto_record head;
    astratto_handle voice;
    uint32_t what;                     /* ASTRATTO_SET_* */
    float gain, pitch, x, y, z;
    float fade_to, fade_time;
} astratto_cmd_voice_set;
typedef struct astratto_cmd_voice_stop { astratto_record head; astratto_handle voice; float fade; uint32_t reserved; } astratto_cmd_voice_stop;
typedef struct astratto_cmd_audio_mix { astratto_record head; float master; uint32_t muted; float bus[ASTRATTO_BUSES]; uint32_t bus_muted; } astratto_cmd_audio_mix;

/* Mixes frames of stereo into out (interleaved, -1..1) when the engine has no audio device; refused when it has one. */
ASTRATTO_API int32_t astratto_audio_render(astratto_engine* engine, float* out, uint32_t frames);

/* ------------------------------------------------------------------ one tick */
/*
 * Hands over a batch of commands and runs the engine for dt seconds, in this order:
 *   1. the caller's commands, in the order written (changes made at once, questions kept);
 *   2. each loaded C++ gameplay module's update, on this thread, its commands applied as the caller's were;
 *   3. the simulation: physics, then AI (perception, decisions, movement), then audio's frame work;
 *   4. the questions, answered against the world as everything above left it, shared out among the workers;
 *   5. each module is shown the frame's events.
 * On return 'events' points at the engine's own memory - the answers and what happened, the answers in the order the
 * requests were written - valid until the next astratto_frame.
 */
typedef struct astratto_frame_io {
    uint32_t struct_size;
    float dt;
    const uint8_t* commands;
    uint32_t command_bytes;
    uint32_t reserved;
    const uint8_t* events;             /* out */
    uint32_t event_bytes;              /* out */
    uint32_t event_count;              /* out */
} astratto_frame_io;

ASTRATTO_API int32_t astratto_frame(astratto_engine* engine, astratto_frame_io* io);

/* ------------------------------------------------------------------ snapshots */
/*
 * STATS: an astratto_stats. BODIES, AGENTS: a run of records (astratto_record headers, as events are), one a thing:
 * astratto_snap_mover, _ragdoll, _softbody (+ particle_count * 3 floats), _vehicle, astratto_snap_agent.
 * AUDIO: an astratto_audio_stats.
 */
enum { ASTRATTO_SNAPSHOT_STATS = 1, ASTRATTO_SNAPSHOT_BODIES = 2, ASTRATTO_SNAPSHOT_AGENTS = 3, ASTRATTO_SNAPSHOT_AUDIO = 4 };

typedef struct astratto_stats {
    uint32_t struct_size;
    uint32_t workers;
    uint64_t frames;
    uint64_t commands;
    uint64_t block_edits;
    uint64_t path_requests;
    uint64_t path_expansions;
    uint64_t paths_found;
    uint64_t sight_requests;
    uint32_t nav_version;              /* of the active world */
    uint32_t sealed_doors;
    float last_frame_ms;
    float worst_frame_ms;
    uint32_t world_x, world_y, world_z;
    uint32_t worlds;                   /* worlds held */
    uint64_t light_cells;              /* cells relit since start */
    uint64_t sim_steps;                /* fixed physics steps run */
    uint32_t live[ASTRATTO_KINDS];     /* things held, by kind */
    float sim_ms;                      /* time the last frame spent simulating */
    double sim_time;                   /* seconds simulated since start */
} astratto_stats;

enum { ASTRATTO_SNAP_MOVER = 1, ASTRATTO_SNAP_RAGDOLL = 2, ASTRATTO_SNAP_SOFTBODY = 3, ASTRATTO_SNAP_VEHICLE = 4, ASTRATTO_SNAP_AGENT = 5 };
typedef struct astratto_snap_mover {
    astratto_record head;
    astratto_handle handle;
    float x, y, z, vx, vy, vz;
    uint32_t grounded, crouched;
} astratto_snap_mover;
typedef struct astratto_snap_ragdoll {
    astratto_record head;
    astratto_handle handle;
    uint32_t state, outcome, settled, active;
    float com[3];
    float yaw;                         /* the way the trunk faces (for taking the body back into animation) */
    float p[ASTRATTO_RD_PARTICLES * 3];
    uint32_t grounded;                 /* a bit a particle */
    float stand_x, stand_z;            /* between the feet */
} astratto_snap_ragdoll;
typedef struct astratto_snap_softbody {
    astratto_record head;
    astratto_handle handle;
    uint32_t particles, settled;
    float cx, cy, cz, reserved;        /* then particles * 3 floats */
} astratto_snap_softbody;
typedef struct astratto_snap_vehicle {
    astratto_record head;
    astratto_handle handle;
    float matrix[16];                  /* car frame -> world, column-major, blended between the last two steps */
    float speed, forward_speed, steer_angle, engine_band;
    uint32_t gear, grounded_wheels, asleep, reserved;
    float wheel[4 * 4];                /* per wheel: compression, rotation, steer angle, sliding (1/0) */
} astratto_snap_vehicle;
typedef struct astratto_snap_agent {
    astratto_record head;
    astratto_handle handle;
    astratto_handle target;
    float x, y, z, yaw;                /* where it is, and the way it faces */
    float tx, ty, tz;                  /* where its target was last seen */
    int32_t action;                    /* the id of the action it is doing (-1 none) */
    uint32_t sees, moving, stuck, route_cells;
} astratto_snap_agent;

typedef struct astratto_audio_stats {
    uint32_t struct_size;
    uint32_t device;                   /* 1 when output goes to a device */
    uint32_t voices, sounds;
    uint64_t frames_mixed;
    float peak[ASTRATTO_BUSES];
    float out_peak;
    float room_decay, room_wet, listener_indoor;
    uint32_t limited, underruns;
    float mix_ms;                      /* time the last block took to mix */
} astratto_audio_stats;

typedef struct astratto_snapshot_io {
    uint32_t struct_size;
    uint32_t kind;                     /* ASTRATTO_SNAPSHOT_* */
    const void* data;                  /* out: the engine's memory, valid until the next astratto_snapshot of this kind */
    uint64_t bytes;                    /* out */
} astratto_snapshot_io;

ASTRATTO_API int32_t astratto_snapshot(astratto_engine* engine, astratto_snapshot_io* io);

/* ------------------------------------------------------------------ gameplay modules */
/*
 * A gameplay module written in C++ is a shared library exporting astratto_module_entry (see astratto_module.h). It is
 * loaded with this call and run by the engine inside every astratto_frame, on the caller's thread. Released, it is
 * shut down and unloaded. config is any text the module understands (copied).
 */
ASTRATTO_API int32_t astratto_module_load(astratto_engine* engine, const char* path, const char* config, astratto_handle* out);

/* ------------------------------------------------------------------ rendering: voxel meshing (1.1) */
/*
 * The renderer's chunk mesher, usable on its own. A MESHER is made from a render table and used by one thread at a
 * time; it holds nothing of the world. Each call hands it one column - sixteen cells square, with a border of one cell
 * on every side, the world's whole height - and its light, and gets back the column's vertex streams. (The renderer
 * further down meshes the engine's own worlds itself; this is for tools and for checking it.)
 *
 * Output vertices are 12 bytes (see render/chunk_mesher.h); four make a quad, the renderer draws quads.
 */
typedef struct astratto_mesher astratto_mesher;

enum {
    ASTRATTO_RB_CROSS = 1u << 0,        /* drawn as two crossed planes (grass, flames, webs) */
    ASTRATTO_RB_TRANSLUCENT = 1u << 1,  /* into the translucent stream (glass, water) */
    ASTRATTO_RB_LIQUID = 1u << 2,
    ASTRATTO_RB_CUTOUT = 1u << 3,       /* alpha-tested (leaves, mesh) */
    ASTRATTO_RB_EMISSIVE = 1u << 4,     /* its texture glows */
    ASTRATTO_RB_LEAF = 1u << 5,         /* foliage: leaves shade leaves, softly */
    ASTRATTO_RB_SWAY = 1u << 6,         /* moves in the wind (leaves, laundry) */
    ASTRATTO_RB_OCCLUDES = 1u << 7,     /* an opaque full cube: hides the faces against it and darkens corners */
    ASTRATTO_RB_DOOR = 1u << 8,         /* a door: left out unless asked for (in play its leaf is an entity) */
    ASTRATTO_RB_SOLID = 1u << 9,
    ASTRATTO_RB_STEP = 1u << 10,        /* a stair tread */
    ASTRATTO_RB_SEAT = 1u << 11,        /* a table or a desk: a chair beside it faces it */
    ASTRATTO_RB_PLANT = 1u << 12        /* a crossed plant the renderer may grow as foliage instead (ASTRATTO_MESH_NO_PLANTS) */
};
typedef struct astratto_render_block {
    uint16_t layer_top, layer_side, layer_bottom;   /* texture array layers (0..1023) */
    uint16_t flags;                                  /* ASTRATTO_RB_* */
    uint8_t var_top, var_side, var_bottom;           /* texture variants (1..4) */
    uint8_t shape;                                   /* 0 = a full cube, else an index into the shape table */
} astratto_render_block;

/* How a shaped block reads the way it faces from what is round it (no block stores a direction). */
enum {
    ASTRATTO_ORIENT_NONE = 0,
    ASTRATTO_ORIENT_BACKING = 1,   /* back to the first full cube of -z, +x, +z, -x; else scattered */
    ASTRATTO_ORIENT_DOOR = 2,      /* in the line of its wall */
    ASTRATTO_ORIENT_JOIN = 3,      /* a fence: along its run; both ways at a corner (two passes) */
    ASTRATTO_ORIENT_CHAIR = 4,     /* facing a table beside it; else scattered */
    ASTRATTO_ORIENT_PEW = 5,       /* across a row of its own kind */
    ASTRATTO_ORIENT_SCATTER = 6,   /* any way, the same for the same cell */
    ASTRATTO_ORIENT_STAIR = 7      /* rising toward the next tread of its flight */
};
typedef struct astratto_shape_def {
    uint8_t orient;                /* ASTRATTO_ORIENT_* */
    uint8_t joins;                 /* a fence joins onto it (posts, fittings, arms, fences) */
    uint8_t invisible;             /* geometry only for bodies and eyes, never drawn */
    uint8_t present;               /* 0 = no boxes at all */
    uint32_t first_box, box_count; /* into the box array: 8 bytes a box, x0 y0 z0 x1 y1 z1 (sixteenths) tex pad */
} astratto_shape_def;

typedef struct astratto_render_table {
    uint32_t struct_size;
    uint32_t block_count;                   /* at most 256 */
    const astratto_render_block* blocks;
    uint32_t shape_count;
    uint32_t box_count;
    const astratto_shape_def* shapes;
    const int8_t* boxes;
    uint32_t below_floor_block;             /* the id of every cell under y = 0 */
    uint32_t reserved;
} astratto_render_table;

enum { ASTRATTO_MESH_DOORS = 1u << 0, ASTRATTO_MESH_NO_PLANTS = 1u << 1 };
typedef struct astratto_mesh_io {
    uint32_t struct_size;
    uint32_t flags;                         /* ASTRATTO_MESH_* */
    uint32_t size_y;                        /* the world's height (at most 2048) */
    uint32_t reserved;
    int32_t origin_x, origin_z;             /* world position of the column's first cell */
    int32_t world_x, world_z;               /* the world's size: outside it is a hard edge */
    const uint8_t* blocks;                  /* 18 * size_y * 18 cells: index ((x + 1) * 18 + (z + 1)) * size_y + y, x and z -1..16 */
    const uint8_t* light;                   /* the same layout: sky light in the high four bits, block light in the low */
    const uint8_t* opaque;                  /* out */
    const uint8_t* translucent;             /* out */
    uint32_t opaque_bytes;                  /* out */
    uint32_t translucent_bytes;             /* out */
} astratto_mesh_io;

/* Returns null on a malformed table. */
ASTRATTO_API astratto_mesher* astratto_mesher_create(const astratto_render_table* table);
ASTRATTO_API void astratto_mesher_destroy(astratto_mesher* mesher);
/* The out pointers are the mesher's memory, valid until its next call. */
ASTRATTO_API int32_t astratto_mesh_column(astratto_mesher* mesher, astratto_mesh_io* io);
/* 2.0: meshes a column of one of the engine's own worlds (cells and light read from the world itself; blocks/light in out are ignored). */
ASTRATTO_API int32_t astratto_mesh_world_column(astratto_mesher* mesher, astratto_engine* engine, astratto_handle world, int32_t column_x, int32_t column_z, uint32_t flags, astratto_mesh_io* out);

/* ------------------------------------------------------------------ rendering: the renderer (2.0) */
/*
 * The hybrid global-illumination renderer, native: every GPU resource it uses, the meshing of the engine's world on
 * the engine's workers, the voxel volume and the irradiance probes traced through it, the shadow cascades, the
 * G-buffer, light culling, ambient occlusion, reflections, the lighting, the volumetric fog, the forward pass for
 * glass, water, see-through models and particles, temporal anti-aliasing, bloom, exposure and tone mapping, and a 2D
 * layer for interfaces. The GLSL is the renderer's own (shaders/ in the engine), handed over by name.
 *
 * It draws with OpenGL 4.5 into whatever context is current on the calling thread; it is given the context's
 * function loader (glfwGetProcAddress or the like - a C function, never a callback into the caller's language).
 * Every renderer call is made on that thread.
 *
 * A frame is a command stream like the engine's: a camera, an environment, lights, model instances, particles and 2D
 * quads, handed over once (astratto_render_frame). Models and textures are made once and named by number.
 */
typedef struct astratto_renderer astratto_renderer;
typedef void* (*astratto_gl_loader)(const char* name);

enum { ASTRATTO_QUALITY_LOW = 0, ASTRATTO_QUALITY_MEDIUM = 1, ASTRATTO_QUALITY_HIGH = 2, ASTRATTO_QUALITY_ULTRA = 3 };
typedef struct astratto_renderer_desc {
    uint32_t struct_size;
    uint32_t width, height;            /* the window, in pixels */
    uint32_t quality;                  /* ASTRATTO_QUALITY_* */
    astratto_gl_loader loader;
    float render_scale;                /* 0 = 1 */
    uint32_t flags;                    /* reserved */
} astratto_renderer_desc;

/* Shaders are handed over by name before astratto_renderer_create (the names are the files under shaders/). */
ASTRATTO_API int32_t astratto_renderer_shader(astratto_engine* engine, const char* name, const char* source);
ASTRATTO_API int32_t astratto_renderer_create(astratto_engine* engine, const astratto_renderer_desc* desc, astratto_renderer** out);
ASTRATTO_API void astratto_renderer_destroy(astratto_renderer* renderer);
ASTRATTO_API const char* astratto_renderer_error(astratto_renderer* renderer);

/*
 * Textures: RGBA8 pixels, row by row from the top; a texture array is its layers one after another. Sampling: NEAREST
 * magnifies texel by texel (and, with MIPMAP, takes the nearest texel of a level); MIP_LINEAR blends between mip levels;
 * ANISOTROPIC samples up to eight times along a slanted surface where the machine can; ASTRATTO_TEX_MAX_LEVEL(n) stops the
 * mip chain at level n.
 */
enum { ASTRATTO_TEX_2D = 0, ASTRATTO_TEX_ARRAY = 1 };
enum { ASTRATTO_TEX_NEAREST = 1u << 0, ASTRATTO_TEX_MIPMAP = 1u << 1, ASTRATTO_TEX_REPEAT = 1u << 2, ASTRATTO_TEX_MIP_LINEAR = 1u << 3, ASTRATTO_TEX_ANISOTROPIC = 1u << 4 };
#define ASTRATTO_TEX_MAX_LEVEL(n) ((uint32_t)(((n) + 1) & 15) << 8)
typedef struct astratto_texture_desc {
    uint32_t struct_size;
    uint32_t kind, width, height, layers, flags;
    const uint8_t* pixels;
    uint64_t bytes;
} astratto_texture_desc;
ASTRATTO_API int32_t astratto_render_texture(astratto_renderer* renderer, const astratto_texture_desc* desc, uint32_t* out);
/* Model meshes: triangles of 40-byte vertices - position (3 floats), colour (4 floats, alpha < 1 = see-through), normal (3 floats). */
ASTRATTO_API int32_t astratto_render_mesh(astratto_renderer* renderer, const float* vertices, uint32_t vertex_count, uint32_t* out);
/* Releases a texture or a mesh (deferred until no frame in flight uses it). */
enum { ASTRATTO_RES_TEXTURE = 1, ASTRATTO_RES_MESH = 2 };
ASTRATTO_API int32_t astratto_render_release(astratto_renderer* renderer, uint32_t kind, uint32_t id);

/*
 * What the world looks like, for the renderer's own meshing of an engine world: the render table (as the mesher's),
 * the block texture array, a noise texture, and per block id its albedo and emitted light for the probes (8 floats: r
 * g b, unused, emitted r g b, unused), and per texture layer its roughness, metalness and emission (4 bytes).
 */
typedef struct astratto_world_look {
    uint32_t struct_size;
    uint32_t atlas;                    /* texture (ASTRATTO_TEX_ARRAY) */
    uint32_t noise;                    /* texture (ASTRATTO_TEX_2D, REPEAT) */
    uint32_t layers;
    const astratto_render_table* table;
    const float* palette;              /* 256 * 8 floats */
    const uint8_t* layer_materials;    /* layers * 4 bytes */
    float ground_y;                    /* the height street-level dirt and fog bands are measured from */
    uint32_t mesh_flags;               /* ASTRATTO_MESH_* for the renderer's own meshing (doors drawn, plants left out) */
} astratto_world_look;
ASTRATTO_API int32_t astratto_render_world_look(astratto_renderer* renderer, const astratto_world_look* look);

/* Render commands. */
enum {
    ASTRATTO_RCMD_CAMERA = 1,          /* astratto_rcmd_camera */
    ASTRATTO_RCMD_ENV = 2,             /* astratto_rcmd_env */
    ASTRATTO_RCMD_LIGHT = 3,           /* astratto_rcmd_light */
    ASTRATTO_RCMD_MODEL = 4,           /* astratto_rcmd_model */
    ASTRATTO_RCMD_PARTICLES = 5,       /* astratto_rcmd_particles + count * 12 floats */
    ASTRATTO_RCMD_QUADS = 6,           /* astratto_rcmd_quads + count * 4 vertices * 9 floats */
    ASTRATTO_RCMD_GRADING = 7,         /* astratto_rcmd_grading */
    ASTRATTO_RCMD_SHADOW_BODY = 8,     /* astratto_rcmd_body: a capsule that shadows local lights */
    ASTRATTO_RCMD_WORLD = 9,           /* astratto_rcmd_world: the engine world to draw (none: no world this frame) */
    ASTRATTO_RCMD_CLEAR = 10,          /* astratto_rcmd_clear: no world, just a colour (menus) */
    ASTRATTO_RCMD_QUALITY = 11         /* astratto_rcmd_quality: a preset, the switchable passes and the render scale (kept) */
};
typedef struct astratto_rcmd_camera {
    astratto_record head;
    float view[16], proj[16], viewmodel_proj[16];   /* column-major */
    float x, y, z, fx, fy, fz;          /* position and forward */
    float fov_y, aspect, near_plane, far_plane;
    float view_distance, dt;
    uint32_t reset;                     /* the view jumped (no history to blend with) */
    uint32_t prepare;                   /* a loading screen: the world meshed, the voxel volume filled and the probes lit as fast as they go */
} astratto_rcmd_camera;
typedef struct astratto_rcmd_env {
    astratto_record head;
    float sun[3], daylight, time, rain, wet, snow, overcast, lightning;
    float fog[3], fog_density, fog_wall_start, fog_wall_end, fog_sky_horizon, fog_sky_zenith;
    float tint[3], ambient_floor;
    float fog_origin[3], bloom;          /* fog_origin[0] NaN = the camera */
} astratto_rcmd_env;
enum { ASTRATTO_LIGHT_SHADOW = 1, ASTRATTO_LIGHT_SPOT = 2, ASTRATTO_LIGHT_STATIC = 4 };
typedef struct astratto_rcmd_light {
    astratto_record head;
    float x, y, z, radius, r, g, b;
    uint32_t flags;
    float dx, dy, dz, cos_outer;
} astratto_rcmd_light;
enum { ASTRATTO_MODEL_SHADOW_ONLY = 1, ASTRATTO_MODEL_NO_SHADOW = 2, ASTRATTO_MODEL_FORWARD = 4, ASTRATTO_MODEL_ADDITIVE = 8, ASTRATTO_MODEL_VIEWMODEL = 16 };
typedef struct astratto_rcmd_model {
    astratto_record head;
    uint32_t mesh, flags;               /* ASTRATTO_MODEL_* */
    float matrix[16];
    float r, g, b, a, emissive, light, roughness, metallic;
} astratto_rcmd_model;
typedef struct astratto_rcmd_particles { astratto_record head; uint32_t count, reserved; } astratto_rcmd_particles;
/* 2D: quads in pixels from the top-left; a vertex is x y u v r g b a mode (0 colour, 1 texture, 2 text: alpha from red). */
typedef struct astratto_rcmd_quads { astratto_record head; uint32_t texture, count; } astratto_rcmd_quads;
typedef struct astratto_rcmd_grading { astratto_record head; float death, hurt, infection, flash, bile, reserved; } astratto_rcmd_grading;
typedef struct astratto_rcmd_body { astratto_record head; float x, y, z, radius, height, reserved; } astratto_rcmd_body;
typedef struct astratto_rcmd_world { astratto_record head; astratto_handle world; } astratto_rcmd_world;
typedef struct astratto_rcmd_clear { astratto_record head; float r, g, b, a; } astratto_rcmd_clear;
enum { ASTRATTO_PASS_GTAO = 1u << 0, ASTRATTO_PASS_SSR = 1u << 1, ASTRATTO_PASS_VOLUMETRICS = 1u << 2, ASTRATTO_PASS_TAA = 1u << 3, ASTRATTO_PASS_BLOOM = 1u << 4 };
typedef struct astratto_rcmd_quality {
    astratto_record head;
    uint32_t preset;                   /* ASTRATTO_QUALITY_*: shadow and probe budgets, cascades, light counts */
    uint32_t passes;                   /* ASTRATTO_PASS_*: the passes that may be switched off */
    float render_scale;                /* 0 = 1 */
    uint32_t reserved;
} astratto_rcmd_quality;

typedef struct astratto_render_io {
    uint32_t struct_size;
    uint32_t width, height;             /* the window this frame */
    uint32_t reserved;
    const uint8_t* commands;
    uint32_t command_bytes;
    uint32_t reserved2;
    /* out */
    uint32_t columns_meshed, columns_drawn, quads_drawn, models_drawn;
    uint32_t lights, probes_updated, probes_unsettled, columns_pending;
    float cpu_ms, settled;              /* settled: share of probe regions round the camera that have converged */
} astratto_render_io;
ASTRATTO_API int32_t astratto_render_frame(astratto_renderer* renderer, astratto_engine* engine, astratto_render_io* io);
/* The window as it was last drawn: width * height * 4 bytes, RGBA, bottom row first. */
ASTRATTO_API int32_t astratto_render_read_pixels(astratto_renderer* renderer, uint32_t x, uint32_t y, uint32_t width, uint32_t height, uint8_t* out);

#ifdef __cplusplus
}
#endif
#endif
