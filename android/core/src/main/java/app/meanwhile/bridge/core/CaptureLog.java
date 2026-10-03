package app.meanwhile.bridge.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/** Bounded in-memory history of capture attempts, newest first, for the diagnostics screen. */
public final class CaptureLog {
    private final int capacity;
    private final Deque<CaptureEngine.Result> entries = new ArrayDeque<>();

    public CaptureLog(int capacity) {
        this.capacity = capacity;
    }

    public synchronized void add(CaptureEngine.Result r) {
        entries.addFirst(r);
        while (entries.size() > capacity) entries.removeLast();
    }

    public synchronized List<CaptureEngine.Result> recent() {
        return new ArrayList<>(entries);
    }

    public synchronized CaptureEngine.Result last() {
        return entries.peekFirst();
    }
}
