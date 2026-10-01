package tools.argus.basemaps.pack;

import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe progress counter for slicing / pack building, read by the UI. */
public final class Progress {
    private final AtomicLong done = new AtomicLong();
    private final AtomicLong total = new AtomicLong(1);
    private volatile String phase = "starting";
    private volatile boolean cancelled;

    public void phase(String p) { phase = p; }
    public String phase() { return phase; }
    public void total(long t) { total.set(Math.max(1, t)); }
    public void step(long n) { done.addAndGet(n); }
    public long done() { return done.get(); }
    public long total() { return total.get(); }
    public void cancel() { cancelled = true; }
    public boolean cancelled() { return cancelled; }

    public int percent() {
        return (int) Math.min(100, done.get() * 100 / total.get());
    }

    @Override
    public String toString() {
        return phase + " " + percent() + "% (" + done.get() + "/" + total.get() + ")";
    }
}
