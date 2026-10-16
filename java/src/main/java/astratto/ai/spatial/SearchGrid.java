package astratto.ai.spatial;

import astratto.ai.api.GameWorld;
import astratto.ai.api.Vec3;
import astratto.ai.cognition.TargetBelief;
import astratto.ai.runtime.NeuralConfig;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The search for someone who has gone out of sight, as a sparse probability over the ground they could have reached.
 *
 * The area round where the target was last believed to be is cut into cells a few metres across; each walkable
 * cell is a region that can be inspected. A cell holds how likely the target is to be there, how good a place to
 * hide it is, when anyone last looked into it and who (if anyone) is on the way to it. When the search starts the
 * likelihood sits where the target was lost, leaning the way it was heading. From then on it spreads, a little
 * each refresh, into neighbouring cells a person could actually have walked to - never through a wall - so the
 * possibilities widen as the trail goes cold. A cell that someone looks into and finds empty is knocked down, not
 * struck out: likelihood can flow back into it from next door, because a hider can move behind the searchers.
 *
 * A region is chosen by score(r) = 3*likelihood + 2*concealment + 2*expectedInformationGain - pathCost -
 * 3*reservationPenalty, each term brought to 0 .. 1 first; reservations expire by themselves and are dropped when
 * their owner dies, finds a path blocked, or sees the target.
 */
public final class SearchGrid implements SearchPlanner {
    public static final class Cell {
        public final long id;
        public final Vec3 centre;
        public double probability, concealment, lastObserved = -1e9, lastInspected = -1e9, reservedUntil = -1e9;
        public long reservedBy;
        final List<Cell> reach = new ArrayList<>();
        Cell(long id, Vec3 c) { this.id = id; this.centre = c; }
    }

    private final GameWorld world;
    private final NeuralConfig cfg;
    private final Map<Long, Cell> cells = new LinkedHashMap<>();
    public final long targetId;
    public Vec3 origin = Vec3.ZERO;
    public double builtAt = -1e9, radius;
    /** 0.9: the building this search has taken in (the target was lost in it, or at its walls), and how many of its rooms are regions. Null in the open. */
    public GameWorld.Structure house;
    public int rooms, roomsCleared;
    public boolean houseCalled;
    private static final long ROOM = 1L << 62;
    public static boolean isRoom(long cellId) { return (cellId & ROOM) != 0; }
    /** Counters (gates). */
    public int inspections, reservationsMade, reservationConflicts, refreshes;

    public SearchGrid(GameWorld world, NeuralConfig cfg, long targetId) { this.world = world; this.cfg = cfg; this.targetId = targetId; }

    public List<Cell> cells() { return new ArrayList<>(cells.values()); }
    public Cell cell(long id) { return cells.get(id); }
    public boolean built() { return !cells.isEmpty(); }

    private long key(double x, double z) { return ((long) Math.floor(x / cfg.cell) << 32) ^ ((long) Math.floor(z / cfg.cell) & 0xFFFFFFFFL); }

    /** Lays the grid out round a belief: the target was lost here, heading that way, and could be anywhere within reach. */
    public void seed(TargetBelief b, double now) {
        cells.clear();
        origin = b.estimatedPosition();
        // 0.9: lost inside a building, or at its wall (a door they could have gone through): the building is part of the search
        house = world.structureAt(origin, 3);
        rooms = roomsCleared = 0; houseCalled = false;
        radius = Math.min(cfg.searchRadius, Math.max(10, b.radiusMeters() + 12));
        builtAt = now;
        double c = cfg.cell;
        int n = (int) Math.ceil(radius / c);
        Vec3 eye = origin.add(new Vec3(0, 1.5, 0));
        for (int ix = -n; ix <= n; ix++) for (int iz = -n; iz <= n; iz++) {
            double wx = (Math.floor(origin.x() / c) + ix + 0.5) * c, wz = (Math.floor(origin.z() / c) + iz + 0.5) * c;
            double d = Math.sqrt((wx - origin.x()) * (wx - origin.x()) + (wz - origin.z()) * (wz - origin.z()));
            if (d > radius) continue;
            // inside a building that is being searched as a building, its rooms are the regions - not one cell per column, which knows nothing of floors
            if (house != null && house.contains(new Vec3(wx, 0, wz))) continue;
            Vec3 stand = world.standNear(new Vec3(wx, origin.y(), wz));
            if (stand == null || Math.abs(stand.y() - origin.y()) > 4.5) continue;
            Cell cell = new Cell(key(wx, wz), stand);
            // likelihood: near where they were lost, and more so along the way they were going
            double near = Math.exp(-(d * d) / (2 * Math.pow(Math.max(4, b.radiusMeters() + 3), 2)));
            double along = b.direction().length() > 0.1 && d > 1 ? Math.max(0, b.direction().dot(stand.sub(origin).flat().normalized())) : 0;
            cell.probability = near * (1 + along * 1.2);
            // concealment: out of sight of where they vanished, and with something to get behind
            double hidden = world.rayClear(eye, stand.add(new Vec3(0, 0.9, 0))) ? 0 : 0.6;
            double shelter = Math.min(0.4, world.tacticalPointsNear(stand, c * 0.75).size() * 0.14);
            cell.concealment = Math.min(1, hidden + shelter);
            cells.put(cell.id, cell);
        }
        // 0.9: every room of the house, on every floor. Someone who went in could be in any of them: the rooms nearest where
        // they were lost are likeliest, the floor they were on more than the others, and a room hides a person better than a hall
        List<Cell> roomCells = new ArrayList<>();
        if (house != null) for (GameWorld.Room rm : house.rooms()) {
            Cell cell = new Cell(ROOM | rm.id(), rm.stand());
            double d3 = rm.stand().distance(origin), dy = Math.abs(rm.stand().y() - origin.y());
            cell.probability = 0.35 + 0.9 * Math.exp(-(d3 * d3) / (2 * 9 * 9)) * (dy < 2 ? 1 : 0.7);
            cell.concealment = rm.passage() ? 0.45 : 0.85;
            cells.put(cell.id, cell);
            roomCells.add(cell);
        }
        rooms = roomCells.size();
        // rooms are joined to the rooms beside and above them (likelihood moves through a house by its doors and stairs) and to the ground outside
        for (Cell a : roomCells) {
            for (Cell o : roomCells) if (o != a && a.centre.distanceXZ(o.centre) < 13 && Math.abs(a.centre.y() - o.centre.y()) < 5) a.reach.add(o);
            for (Cell o : cells.values()) if (!isRoom(o.id) && Math.abs(a.centre.y() - o.centre.y()) < 2 && a.centre.distanceXZ(o.centre) < c * 2.2) { a.reach.add(o); o.reach.add(a); }
        }
        // who can walk to whom: the only way likelihood is allowed to move
        for (Cell a : cells.values()) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0 || isRoom(a.id)) continue;
            Cell o = cells.get(key(a.centre.x() + dx * c, a.centre.z() + dz * c));
            if (o != null && world.walkable(a.centre, o.centre)) a.reach.add(o);
        }
        normalise();
    }

    private void normalise() {
        double max = 0;
        for (Cell c : cells.values()) max = Math.max(max, c.probability);
        if (max > 1e-9) for (Cell c : cells.values()) c.probability /= max;
    }

    /** Time passes with the target unseen: likelihood leaks along walkable links, and the grid forgets old looks. */
    public void refresh(double now) {
        refreshes++;
        Map<Long, Double> next = new LinkedHashMap<>();
        for (Cell c : cells.values()) next.merge(c.id, c.probability * (1 - cfg.diffusion), Double::sum);
        for (Cell c : cells.values()) {
            if (c.reach.isEmpty() || c.probability <= 0) continue;
            double share = c.probability * cfg.diffusion / c.reach.size();
            for (Cell o : c.reach) next.merge(o.id, share, Double::sum);
        }
        for (Cell c : cells.values()) c.probability = next.getOrDefault(c.id, 0.0);
        normalise();
    }

    /** Everything an observer can see into from where it stands is looked at; what is seen empty is marked so. */
    public void observe(long actorId, Vec3 eye, Vec3 forward, double range, double now) {
        Vec3 f = forward.flat().normalized();
        for (Cell c : cells.values()) {
            double d = eye.distanceXZ(c.centre);
            if (d > range || Math.abs(eye.y() - 1.2 - c.centre.y()) > 2.6) continue;      // a floor away is not looked into, however near on the map
            if (d > 2.5 && f.dot(c.centre.sub(eye).flat().normalized()) < 0.35) continue;
            if (!world.rayClear(eye, c.centre.add(new Vec3(0, 0.9, 0)))) continue;
            c.lastObserved = now;
            if (d <= cfg.cell * 1.6) markVisibleEmpty(actorId, c.id, now);
            else c.probability *= 0.6;                         // seen from a distance: less likely, not cleared
        }
    }

    @Override public long bestRegionFor(long actorId, TargetBelief belief, double now) {
        if (cells.isEmpty()) return -1;
        Vec3 from = world.actorPosition(actorId);
        Cell best = null;
        double bs = -1e9;
        for (Cell c : cells.values()) {
            if (c.probability < 0.02 && now - c.lastInspected < 60) continue;
            boolean taken = c.reservedBy != 0 && c.reservedBy != actorId && now < c.reservedUntil;
            // looking into a region tells you about its neighbours too: unseen cells round it are what is gained
            double gain = now - c.lastObserved > 20 ? 0.5 : 0;
            int fresh = 0;
            for (Cell o : c.reach) if (now - o.lastObserved > 20) fresh++;
            gain = Math.min(1, gain + fresh * 0.1);
            double path = Math.min(1, (from.distanceXZ(c.centre) + Math.abs(from.y() - c.centre.y()) * 2.5) / (cfg.searchRadius * 1.5));
            double s = cfg.wLikelihood * c.probability + cfg.wConcealment * c.concealment + cfg.wInfoGain * gain - cfg.wPathCost * path - cfg.wReservation * (taken ? 1 : 0);
            if (now - c.lastInspected < 25) s -= 2.5;                 // just checked: let it fill again first
            if (s > bs || s == bs && best != null && c.id < best.id) { bs = s; best = c; }
        }
        return best == null ? -1 : best.id;
    }

    @Override public boolean reserve(long regionId, long actorId, double until) {
        Cell c = cells.get(regionId);
        if (c == null) return false;
        double now = world.simulationTimeSeconds();
        if (c.reservedBy != 0 && c.reservedBy != actorId && now < c.reservedUntil) { reservationConflicts++; return false; }
        c.reservedBy = actorId; c.reservedUntil = until;
        reservationsMade++;
        return true;
    }

    @Override public void releaseActor(long actorId) { for (Cell c : cells.values()) if (c.reservedBy == actorId) { c.reservedBy = 0; c.reservedUntil = -1e9; } }

    @Override public void markVisibleEmpty(long actorId, long regionId, double now) {
        Cell c = cells.get(regionId);
        if (c == null) return;
        if (isRoom(regionId) && now - c.lastInspected > 20) roomsCleared++;
        c.probability *= cfg.emptyFactor;
        c.lastObserved = now; c.lastInspected = now;
        if (c.reservedBy == actorId) { c.reservedBy = 0; c.reservedUntil = -1e9; }
        inspections++;
    }

    /** How much likelihood is left anywhere: a search with nothing left to find is over. */
    public double remaining() { double s = 0; for (Cell c : cells.values()) s += c.probability; return cells.isEmpty() ? 0 : s / cells.size(); }
}
