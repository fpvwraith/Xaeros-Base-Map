package tools.argus.basemaps.math;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class LruBudgetTest {
    private static final long TILE = 256 * 256 * 4;   // one 256px RGBA tile

    @Test
    void accountsBytes() {
        LruBudget<String> b = new LruBudget<>(10 * TILE);
        b.add("a", TILE, 1);
        b.add("b", TILE, 1);
        assertEquals(2 * TILE, b.used());
        b.add("a", 2 * TILE, 2);   // replace
        assertEquals(3 * TILE, b.used());
        b.remove("b");
        b.remove("nope");
        assertEquals(2 * TILE, b.used());
        assertEquals(1, b.size());
    }

    @Test
    void evictsLeastRecentlyUsedFirst() {
        LruBudget<String> b = new LruBudget<>(3 * TILE);
        b.add("a", TILE, 1);
        b.add("b", TILE, 2);
        b.add("c", TILE, 3);
        b.touch("a", 4);   // a is now the most recent
        List<String> out = b.evict(TILE, 4);   // make room for one more, protect frame 4
        assertEquals(List.of("b"), out);
        assertEquals(2 * TILE, b.used());
        assertTrue(b.contains("a"));
    }

    @Test
    void neverEvictsWhatIsOnScreenNow() {
        LruBudget<String> b = new LruBudget<>(2 * TILE);
        b.add("a", TILE, 10);
        b.add("b", TILE, 10);
        assertFalse(b.fits(TILE, 10));          // both visible in the current frame
        assertTrue(b.evict(TILE, 10).isEmpty());
        assertTrue(b.fits(TILE, 11));           // next frame they are evictable
        assertEquals(1, b.evict(TILE, 11).size());
    }

    @Test
    void staysFlatUnderAStreamOfNewTiles() {
        LruBudget<Integer> b = new LruBudget<>(64 * TILE);
        for (int i = 0; i < 100_000; i++) {
            long frame = i / 16;   // 16 new tiles per frame
            if (b.fits(TILE, frame)) {
                b.evict(TILE, frame);
                b.add(i, TILE, frame);
            }
            assertTrue(b.used() <= 64 * TILE);
        }
        assertEquals(64, b.size());
    }

    @Test
    void shrinkingTheBudgetEvictsOnNextCall() {
        LruBudget<String> b = new LruBudget<>(4 * TILE);
        for (String k : new String[] {"a", "b", "c", "d"}) b.add(k, TILE, 1);
        b.setBudget(TILE);
        assertEquals(List.of("a", "b", "c"), b.evict(0, 5));
        assertEquals(TILE, b.used());
    }
}
