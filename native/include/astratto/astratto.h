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
 *   - A world view (astratto_world_view) is the engine's memory itself, read-only: valid until the world is released
 *     or the engine destroyed. It changes as the world is edited - by the caller's edits, between calls, never during
 *     one of the caller's reads.
 *   - Strings returned (astratto_build_info, astratto_last_error) stay valid until the next call on that engine.
 *   - A handle is valid from the call that created it until the frame or call that releases it; using a released
 *     handle is refused (status ASTRATTO_ERR_HANDLE, or an ASTRATTO_EVT_ERROR event) - it never reaches freed memory,
 *     because every handle carries a generation that the engine checks.
 *
 * Byte order is the machine's (little-endian on every platform the engine builds for). All records in command and
 * event streams are aligned to four bytes.
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
 * Handles are made two ways. Things whose making needs data handed over at once (a world, a sound) are made by a
 * call that returns the handle. Things made in quantity during play (bodies, voices, agents) are made by a command in
 * a frame; their handles are taken beforehand, in bulk, with astratto_handles_reserve - a reserved handle is valid at
 * once, refers to nothing until the command that makes it runs, and is released like any other.
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
    ASTRATTO_KIND_WEAPON = 11,     /* a weapon's layered audio profile */
    ASTRATTO_KINDS = 12
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
    uint8_t reserved[3];
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

ASTRATTO_API int32_t astratto_world_view(astratto_engine* engine, astratto_handle world, astratto_world_view* out);

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
    ASTRATTO_EDIT_CLEAR = 13           /* bare record: every cell air, light none, doors unsealed */
};

/* block: the id in the low eight bits; ASTRATTO_SET_RAW in the flags above them sets the cell and nothing more. */
enum { ASTRATTO_SET_RAW = 1u << 8 };
typedef struct astratto_cmd_block_set { astratto_record head; int32_t x, y, z; uint32_t block; } astratto_cmd_block_set;
typedef struct astratto_edit_fill { astratto_record head; int32_t x0, y0, z0, x1, y1, z1; uint32_t block; uint32_t reserved; } astratto_edit_fill;
typedef struct astratto_edit_box { astratto_record head; int32_t x0, z0, x1, z1; } astratto_edit_box;
typedef struct astratto_edit_light_fill { astratto_record head; uint32_t value; uint32_t reserved; } astratto_edit_light_fill;
typedef struct astratto_edit_light_set { astratto_record head; int32_t x, y, z; uint32_t value; } astratto_edit_light_set;

/*
 * A runtime block set (no ASTRATTO_SET_RAW): the cell changes; if the light it gives or stops changed, the cells round
 * it are relit at once; the renderer is told the columns it touches; the nav version moves if a way opened or shut.
 */
ASTRATTO_API int32_t astratto_world_edit(astratto_engine* engine, astratto_handle world, const uint8_t* records, uint32_t bytes);

/* ------------------------------------------------------------------ commands (caller -> engine, in a frame) */
/*
 * A frame's command stream is a run of records. Unknown commands from a newer minor version are skipped by their
 * own size. World edit records (ASTRATTO_EDIT_*) may appear in it too and apply to the active world.
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
    ASTRATTO_CMD_WALK_QUERY = 18      /* astratto_cmd_walk_query -> ASTRATTO_EVT_WALK_RESULT */
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

/* ------------------------------------------------------------------ events (engine -> caller) */
enum {
    ASTRATTO_EVT_ERROR = 1,           /* astratto_evt_error: a command could not be carried out (the frame went on) */
    ASTRATTO_EVT_PATH_RESULT = 16,    /* astratto_evt_path_result, then cell_count * 3 int32 (x, y, z) */
    ASTRATTO_EVT_SIGHT_RESULT = 17,   /* astratto_evt_sight_result */
    ASTRATTO_EVT_WALK_RESULT = 18     /* astratto_evt_walk_result */
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

/* ------------------------------------------------------------------ one tick */
/*
 * Hands over a batch of commands and runs the engine for dt seconds: commands that change things are applied in the
 * order written; the simulation then advances (bodies, vehicles, agents, the audio it schedules); requests are answered
 * against the world as the batch and the step left it. On return 'events' points at the engine's own memory - the
 * answers and what happened, the answers in the order the requests were written - valid until the next astratto_frame.
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
enum { ASTRATTO_SNAPSHOT_STATS = 1 };

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
    float reserved_f;
} astratto_stats;

typedef struct astratto_snapshot_io {
    uint32_t struct_size;
    uint32_t kind;                     /* ASTRATTO_SNAPSHOT_* */
    const void* data;                  /* out: the engine's memory, valid until the next astratto_snapshot of this kind */
    uint64_t bytes;                    /* out */
} astratto_snapshot_io;

ASTRATTO_API int32_t astratto_snapshot(astratto_engine* engine, astratto_snapshot_io* io);

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
/* 2.0: meshes a column of one of the engine's own worlds (cells and light read from the world itself). */
ASTRATTO_API int32_t astratto_mesh_world_column(astratto_mesher* mesher, astratto_engine* engine, astratto_handle world, int32_t column_x, int32_t column_z, uint32_t flags, astratto_mesh_io* out);

#ifdef __cplusplus
}
#endif
#endif
