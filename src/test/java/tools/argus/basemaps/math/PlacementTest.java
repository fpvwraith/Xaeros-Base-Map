package tools.argus.basemaps.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PlacementTest {
    private static final double EPS = 1e-9;

    @Test
    void defaultsCentreAtOriginOneBlockPerPixel() {
        Placement p = Placement.of(100, 60, 0, 0, 1);
        assertEquals(-50, p.minX(), EPS);
        assertEquals(50, p.maxX(), EPS);
        assertEquals(-30, p.minZ(), EPS);
        assertEquals(30, p.maxZ(), EPS);
        assertEquals(1, p.pixelSize(), EPS);
    }

    @Test
    void readmeExample7500pxAt8BlocksCoversPlusMinus30000() {
        Placement p = Placement.of(7500, 7500, 0, 0, 8);
        assertEquals(-30000, p.minX(), EPS);
        assertEquals(30000, p.maxX(), EPS);
        assertEquals(-30000, p.minZ(), EPS);
        assertEquals(30000, p.maxZ(), EPS);
        assertEquals(60000, p.width(), EPS);
    }

    @Test
    void offCentreAndFractionalResolution() {
        Placement p = Placement.of(200, 100, 1000, -500, 0.5);
        assertEquals(950, p.minX(), EPS);
        assertEquals(1050, p.maxX(), EPS);
        assertEquals(-525, p.minZ(), EPS);
        assertEquals(-475, p.maxZ(), EPS);
    }

    @Test
    void pixelZeroIsNorthWestCorner() {
        Placement p = Placement.of(4, 4, 0, 0, 16);
        double[] r = p.subRect(0, 0, 1, 1);
        assertArrayEquals(new double[] {-32, -32, -16, -16}, r, EPS);
        double[] last = p.subRect(3, 3, 1, 1);
        assertArrayEquals(new double[] {16, 16, 32, 32}, last, EPS);
    }

    @Test
    void oneToOneSixtyThousandIsPlusMinus30000() {
        Placement p = Placement.of(60000, 60000, 0, 0, 1);
        assertEquals(-30000, p.minX(), EPS);
        assertEquals(30000, p.maxZ(), EPS);
    }

    @Test
    void intersects() {
        Placement p = Placement.of(100, 100, 0, 0, 1);   // -50..50
        assertTrue(p.intersects(-10, -10, 10, 10));
        assertTrue(p.intersects(49, 49, 200, 200));
        assertFalse(p.intersects(50, 0, 60, 10));          // only touching the edge
        assertFalse(p.intersects(-200, -200, -51, -51));
    }
}
