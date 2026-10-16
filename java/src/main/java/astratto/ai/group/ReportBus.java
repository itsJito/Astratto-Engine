package astratto.ai.group;

import astratto.ai.api.Vec3;
import java.util.ArrayList;
import java.util.List;

/**
 * Knowledge moves between people as messages, and a message has a way of travelling. A shout carries as far as a
 * voice does and arrives in under a second; a radio call reaches anyone on the net a moment later, however far; a
 * hand signal needs to be seen. Whoever a message does not reach goes on acting on what they personally know.
 *
 * What is delivered is the sender's estimate - a point, an error, how sure they were - never where the target is
 * when the message lands. A line of dialogue may accompany a report; it is not the report.
 */
public final class ReportBus {
    public enum Channel { SHOUT, RADIO, VISUAL_SIGNAL }

    public record Report(long id, long source, long squad, Channel channel, long targetId, Vec3 position, Vec3 velocity, double radius, double confidence, String kind,
                         Vec3 sentFrom, double sentAt, double deliverAt, double range) {}

    /** Decides, at the moment of delivery, whether one listener actually gets one message. */
    public interface Reach { boolean reaches(Report r, long listener); }
    public interface Sink { void deliver(Report r, long listener); }

    private final List<Report> pending = new ArrayList<>();
    private long nextId = 1;
    /** Counters (gates): sent, delivered to a listener, and listeners a message failed to reach. */
    public int sent, delivered, missed;

    public Report send(long source, long squad, Channel channel, long targetId, Vec3 position, Vec3 velocity, double radius, double confidence, String kind, Vec3 from, double now, double delay, double range) {
        Report r = new Report(nextId++, source, squad, channel, targetId, position, velocity, radius, confidence, kind, from, now, now + delay, range);
        pending.add(r);
        sent++;
        return r;
    }

    /** Hands every message whose time has come to each member of its squad it can reach. Order is by send order: deterministic. */
    public void deliverDue(double now, java.util.function.LongFunction<List<Long>> membersOf, Reach reach, Sink sink) {
        if (pending.isEmpty()) return;
        List<Report> due = new ArrayList<>();
        for (Report r : pending) if (now >= r.deliverAt()) due.add(r);
        pending.removeAll(due);
        for (Report r : due) for (long m : membersOf.apply(r.squad())) {
            if (m == r.source()) continue;
            if (reach.reaches(r, m)) { sink.deliver(r, m); delivered++; } else missed++;
        }
    }

    public int pending() { return pending.size(); }
    public void clear() { pending.clear(); }
}
