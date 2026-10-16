package astratto.ai.runtime;

import astratto.util.Json;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Every number NeuralAI is tuned with (profiles, squad roles, search weights), read from the classpath resource the
 * game names with {@link #source} before first use, with the defaults below when there is none or a key is missing.
 * The engine knows one profile, "default"; a game's file adds its own (one per faction, say).
 */
public final class NeuralConfig {
    // ---- scheduling (ticks per second)
    public double visionHz = 15, beliefHz = 10, decisionHz = 5, rolesHz = 2, searchHz = 2;
    // ---- vision
    public double gainPerSecond = 0.8, decayPerSecond = 0.22;
    public double lookAt = 0.2, investigate = 0.5, confirm = 1.0, lookAtDown = 0.1, investigateDown = 0.35, confirmDown = 0.8;
    public double nearDistance = 4, farDistance = 26, nearHalfAngleDeg = 105, farHalfAngleDeg = 55, closeSense = 2.2;
    public double crouchExposure = 0.65, proneExposure = 0.38, stillExposure = 0.8, movingExposure = 1.2, sprintExposure = 1.45, foliagePerCell = 0.55, darkFloor = 0.3;
    public double alertMultiplier = 2.4, combatMultiplier = 3.2;
    // ---- hearing
    public double hearErrorPerMetre = 0.14, hearUnambiguous = 5, threatSoundAlert = 0.8, quietSoundAlert = 0.3;
    // ---- beliefs
    public double assumedTargetSpeed = 2.4, maxUncertainty = 34, visibleRadius = 0.4, lostAfter = 4.5, forgetAfter = 75, reportExpiry = 22;
    // ---- search
    public double cell = 4, searchRadius = 26, wLikelihood = 3, wConcealment = 2, wInfoGain = 2, wPathCost = 1, wReservation = 3, emptyFactor = 0.12, diffusion = 0.22, reservationTtl = 14, inspectSeconds = 2.2;
    public double searchPatienceSeconds = 55;
    // ---- cover
    public double wProtection = 3, wFiringAngle = 2, wFlank = 1.5, wTravel = 1, wExposure = 2.5, coverRadius = 16, coverTtl = 7, pinnedRelocate = 5;
    // ---- roles
    public int maxFlankPerSide = 1, maxSuppress = 2;
    public double roleSeconds = 8, switchingCost = 0.35, flankStale = 14, flankDistance = 12;
    // ---- decisions
    public double commitSeconds = 1.1, hysteresis = 0.18, grenadeRadius = 7.5;
    // ---- reports
    public double shoutRange = 26, shoutDelay = 0.45, radioDelay = 1.1, radioRange = 260, signalRange = 30, signalDelay = 0.2;
    // ---- fire
    public double burstMin = 2, burstMax = 5, peekSeconds = 2.0, hideSeconds = 1.5, suppressSeconds = 7, speculativeError = 2.4;
    // ---- squadmates
    public double followBehind = 2.4, followSpacing = 1.5, followLeash = 26, holdLeash = 6, moveLeash = 40, sprintToCatchUp = 9;
    // ---- fire discipline (0.8): nobody opens fire beyond the distance their weapon is any use at; the player's own people are stricter still
    public double fireRangeHandgun = 26, fireRangeShotgun = 16, fireRangeAuto = 44, fireRangeRifle = 58, fireRangeScoped = 105, mateFireRange = 0.8;
    /** A squadmate told to hold fire still defends itself: against whoever hit it within this many seconds, or anyone hostile it can see closer than this. */
    public double holdFireHitSeconds = 4, holdFireCloseRange = 5;
    // ---- creatures (0.8, the infected): quicker to recognise what they see than a person, slower to let go of it
    public double creatureGain = 1.9, creatureLostAfter = 2.5, creatureSearchSeconds = 14, creatureNoticeSeconds = 0.7, creatureNoticeSpread = 1.1, creatureLookSeconds = 9, creatureUrgentConfidence = 0.45;

    /** Personality by faction: aggression, caution, teamPreference, searchPatience, reactionLatency (seconds). */
    public record Profile(double aggression, double caution, double teamPreference, double searchPatience, double reactionLatency, boolean radio, double preferredRange) {}
    public final Map<String, Profile> profiles = new LinkedHashMap<>();
    /** Role suitability biases by archetype: {role -> bias}. */
    public final Map<String, Map<String, Double>> roleBias = new LinkedHashMap<>();
    /** The bodies table (0.8): archetype -> {speed, runSpeed, hp, melee, sight, hearing, fovCos, infected}. Read by integration.Archetypes. */
    public final Map<String, Map<String, Object>> bodies = new LinkedHashMap<>();

    private static NeuralConfig instance;
    private static String resource;

    /** The classpath resource (a JSON file) the game tunes NeuralAI with; null = the defaults only. Takes effect at the next {@link #get}. */
    public static synchronized void source(String classpathResource) { resource = classpathResource; instance = null; }

    public static synchronized NeuralConfig get() {
        if (instance == null) instance = load();
        return instance;
    }

    public Profile profile(String faction) { Profile p = profiles.get(faction); return p != null ? p : profiles.get("default"); }

    public double bias(String archetype, String role) {
        Map<String, Double> m = roleBias.get(archetype);
        Double v = m == null ? null : m.get(role);
        return v == null ? 0 : v;
    }

    private static NeuralConfig load() {
        NeuralConfig c = new NeuralConfig();
        c.profiles.put("default", new Profile(0.5, 0.5, 0.5, 1, 0.35, false, 18));
        if (resource == null) return c;
        try (InputStream in = NeuralConfig.class.getResourceAsStream(resource)) {
            if (in == null) { System.err.println("[NeuralAI] " + resource + " not found: defaults only"); return c; }
            Map<String, Object> root = Json.parseObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            for (Map.Entry<String, Object> e : Json.obj(root, "tuning").entrySet()) {
                if (!(e.getValue() instanceof Number n)) continue;
                try {
                    java.lang.reflect.Field f = NeuralConfig.class.getField(e.getKey());
                    if (f.getType() == double.class) f.setDouble(c, n.doubleValue());
                    else if (f.getType() == int.class) f.setInt(c, n.intValue());
                } catch (ReflectiveOperationException ignored) { System.err.println("[NeuralAI] unknown tuning key " + e.getKey()); }
            }
            for (Map.Entry<String, Object> e : Json.obj(root, "profiles").entrySet()) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) e.getValue();
                Profile d = c.profiles.get("default");
                c.profiles.put(e.getKey(), new Profile(Json.num(m, "aggression", d.aggression()), Json.num(m, "caution", d.caution()), Json.num(m, "teamPreference", d.teamPreference()),
                        Json.num(m, "searchPatience", d.searchPatience()), Json.num(m, "reactionLatency", d.reactionLatency()), Json.bool(m, "radio", d.radio()), Json.num(m, "preferredRange", d.preferredRange())));
            }
            if (root.get("bodies") instanceof Map<?, ?>) for (Map.Entry<String, Object> e : Json.obj(root, "bodies").entrySet()) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) e.getValue();
                c.bodies.put(e.getKey(), m);
            }
            for (Map.Entry<String, Object> e : Json.obj(root, "roles").entrySet()) {
                @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) e.getValue();
                Map<String, Double> b = new LinkedHashMap<>();
                for (Map.Entry<String, Object> r : m.entrySet()) if (r.getValue() instanceof Number n) b.put(r.getKey(), n.doubleValue());
                c.roleBias.put(e.getKey(), b);
            }
        } catch (Exception ex) {
            System.err.println("[NeuralAI] neuralai.json could not be read (" + ex + "): built-in defaults in use");
        }
        return c;
    }
}
