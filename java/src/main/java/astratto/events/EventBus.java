package astratto.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Synchronous typed domain-event bus (GDD 20.4). An event is a notification of an already
 * validated fact; listeners update projections, perception, telemetry or issue explicit commands
 * to the owning service rather than mutating shared state directly.
 */
public final class EventBus {
    private final Map<Class<?>, List<Consumer<Object>>> subs = new HashMap<>();
    private final List<Object> log = new ArrayList<>();
    public boolean keepLog;

    @SuppressWarnings("unchecked")
    public <T> void on(Class<T> type, Consumer<T> handler) {
        subs.computeIfAbsent(type, k -> new ArrayList<>()).add(o -> handler.accept((T) o));
    }

    public void emit(Object event) {
        if (keepLog) { log.add(event); if (log.size() > 2000) log.remove(0); }
        List<Consumer<Object>> l = subs.get(event.getClass());
        if (l == null) return;
        for (Consumer<Object> c : new ArrayList<>(l)) c.accept(event);
    }

    public List<Object> log() { return log; }
    public void clear() { subs.clear(); log.clear(); }
}
