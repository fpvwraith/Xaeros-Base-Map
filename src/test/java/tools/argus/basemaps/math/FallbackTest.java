package tools.argus.basemaps.math;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FallbackTest {
    private static final double EPS = 1e-9;

    private final Map<String, Fallback.State> states = new HashMap<>();
    private final List<String> asked = new ArrayList<>();

    private Fallback.State lookup(int l, int x, int y) {
        String k = l + "/" + x + "/" + y;
        asked.add(k);
        return states.getOrDefault(k, Fallback.State.MISSING);
    }

    @Test
    void readyTileDrawsItselfWhole() {
        states.put("0/5/7", Fallback.State.READY);
        Fallback.Choice c = Fallback.resolve(0, 5, 7, 256, 256, 256, 4, this::lookup);
        assertEquals(new Fallback.Choice(0, 5, 7, 0, 0, 256, 256), c);
        assertEquals(List.of("0/5/7"), asked);
    }

    @Test
    void missingTileFallsBackToParentQuarter() {
        states.put("1/2/3", Fallback.State.READY);
        // child (5,7) at level 0 is the bottom-right quarter of parent (2,3): x odd, y odd
        Fallback.Choice c = Fallback.resolve(0, 5, 7, 256, 256, 256, 4, this::lookup);
        assertEquals(1, c.level());
        assertEquals(2, c.x());
        assertEquals(3, c.y());
        assertEquals(128, c.px0(), EPS);
        assertEquals(128, c.py0(), EPS);
        assertEquals(256, c.px1(), EPS);
        assertEquals(256, c.py1(), EPS);
        assertEquals(List.of("0/5/7", "1/2/3"), asked);   // asks the tile, then walks up
    }

    @Test
    void walksUpSeveralLevels() {
        states.put("3/0/1", Fallback.State.READY);
        // level 0 tile (6, 9): at level 3 -> (0, 1); offsets (6 mod 8) * 256 / 8 = 192, (9 mod 8) * 32 = 32
        Fallback.Choice c = Fallback.resolve(0, 6, 9, 256, 100, 256, 5, this::lookup);
        assertEquals(3, c.level());
        assertEquals(192, c.px0(), EPS);
        assertEquals(32, c.py0(), EPS);
        assertEquals(192 + 32, c.px1(), EPS);
        assertEquals(32 + 100 / 8.0, c.py1(), EPS);   // narrower edge child -> proportionally smaller crop
        assertEquals(List.of("0/6/9", "1/3/4", "2/1/2", "3/0/1"), asked);
    }

    @Test
    void emptyStopsTheWalkAndDrawsNothing() {
        states.put("0/4/4", Fallback.State.EMPTY);
        assertNull(Fallback.resolve(0, 4, 4, 256, 256, 256, 4, this::lookup));
        states.clear();
        asked.clear();
        states.put("1/2/2", Fallback.State.EMPTY);
        states.put("2/1/1", Fallback.State.READY);
        assertNull(Fallback.resolve(0, 4, 4, 256, 256, 256, 4, this::lookup));
        assertEquals(List.of("0/4/4", "1/2/2"), asked);
    }

    @Test
    void nothingLoadedAnywhereDrawsNothingButAsksTheWholeChain() {
        assertNull(Fallback.resolve(1, 3, 3, 256, 256, 256, 4, this::lookup));
        assertEquals(List.of("1/3/3", "2/1/1", "3/0/0"), asked);
    }
}
