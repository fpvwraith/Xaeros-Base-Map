package tools.argus.basemaps.math;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Byte-budget LRU bookkeeping (no actual resources; the caller frees what {@link #evict} returns). Items used
 * at or after the "protect" stamp (i.e. on screen right now) are never evicted, so the budget can be exceeded
 * only by what is visible in a single frame. Not thread-safe.
 */
public final class LruBudget<K> {
    private final LinkedHashMap<K, long[]> items = new LinkedHashMap<>(256, 0.75f, true);   // value: {bytes, stamp}
    private long budget;
    private long used;

    public LruBudget(long budgetBytes) {
        setBudget(budgetBytes);
    }

    public void setBudget(long budgetBytes) {
        if (budgetBytes < 0) throw new IllegalArgumentException("budget must be >= 0");
        this.budget = budgetBytes;
    }

    public long budget() { return budget; }
    public long used() { return used; }
    public int size() { return items.size(); }
    public boolean contains(K key) { return items.containsKey(key); }

    /** Adds (or replaces) an item and marks it used at {@code stamp}. */
    public void add(K key, long bytes, long stamp) {
        if (bytes < 0) throw new IllegalArgumentException("bytes must be >= 0");
        long[] old = items.put(key, new long[] {bytes, stamp});
        if (old != null) used -= old[0];
        used += bytes;
    }

    /** Marks an item used (moves it to most-recent). */
    public void touch(K key, long stamp) {
        long[] v = items.get(key);
        if (v != null) v[1] = Math.max(v[1], stamp);
    }

    public void remove(K key) {
        long[] v = items.remove(key);
        if (v != null) used -= v[0];
    }

    /** Would adding {@code bytes} fit, after evicting everything not protected? */
    public boolean fits(long bytes, long protectFromStamp) {
        if (used + bytes <= budget) return true;
        long evictable = 0;
        for (long[] v : items.values()) if (v[1] < protectFromStamp) evictable += v[0];
        return used - evictable + bytes <= budget;
    }

    /**
     * Removes least-recently-used items not used at/after {@code protectFromStamp} until {@code used + extra}
     * fits in the budget. Returns the removed keys (oldest first) so the caller can free them.
     */
    public List<K> evict(long extra, long protectFromStamp) {
        List<K> out = new ArrayList<>();
        Iterator<Map.Entry<K, long[]>> it = items.entrySet().iterator();
        while (used + extra > budget && it.hasNext()) {
            Map.Entry<K, long[]> e = it.next();
            if (e.getValue()[1] >= protectFromStamp) continue;
            used -= e.getValue()[0];
            out.add(e.getKey());
            it.remove();
        }
        return out;
    }
}
