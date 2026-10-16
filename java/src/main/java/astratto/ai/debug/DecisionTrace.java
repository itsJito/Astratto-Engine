package astratto.ai.debug;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/**
 * The last things a mind decided and why, newest last. It is what the overlay shows next to an NPC and what the
 * replay gate compares: two runs of one encounter from one seed must write the same trace.
 */
public final class DecisionTrace {
    public static int CAPACITY = 48;
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    /** Every line ever written, as a rolling hash: cheap to compare across two runs. */
    public long digest = 1469598103934665603L;
    public int count;

    public void add(double time, String what) {
        String line = String.format("%.2f %s", time, what);
        lines.addLast(line);
        while (lines.size() > CAPACITY) lines.removeFirst();
        for (int i = 0; i < line.length(); i++) { digest ^= line.charAt(i); digest *= 1099511628211L; }
        count++;
    }

    public List<String> lines() { return new ArrayList<>(lines); }
    public String last() { return lines.isEmpty() ? "" : lines.peekLast(); }
}
