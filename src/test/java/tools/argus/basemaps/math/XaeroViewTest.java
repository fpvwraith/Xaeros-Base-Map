package tools.argus.basemaps.math;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks our copy of Xaero's GuiMap math. The formulas were read from the decompiled WM 1.45 GuiMap.render; these
 * tests pin the behaviour so the placement code (world - flooredCamera through Xaero's pose) stays consistent.
 */
class XaeroViewTest {
    private static final double EPS = 1e-9;

    @Test
    void fboScaleFloorsWhenZoomedInAndPassesThroughWhenZoomedOut() {
        assertEquals(1.0, XaeroView.fboScale(1.0), EPS);
        assertEquals(2.0, XaeroView.fboScale(2.7), EPS);
        assertEquals(8.0, XaeroView.fboScale(8.0), EPS);
        assertEquals(0.25, XaeroView.fboScale(0.25), EPS);
    }

    @Test
    void flooredCameraZoomedIn() {
        assertEquals(10, XaeroView.flooredCamera(10.9, 2.0));
        assertEquals(-11, XaeroView.flooredCamera(-10.1, 2.0));
        assertEquals(0, XaeroView.flooredCamera(0.0, 1.0));
    }

    @Test
    void flooredCameraZoomedOutSnapsToWholeFboPixels() {
        // fboScale 0.25: one FBO pixel = 4 blocks, camera snaps down to a multiple of 4
        assertEquals(8, XaeroView.flooredCamera(11.9, 0.25));
        assertEquals(-12, XaeroView.flooredCamera(-8.5, 0.25));
        // fboScale 1/3: one FBO pixel = 3 blocks
        assertEquals(9, XaeroView.flooredCamera(10.0, 1.0 / 3.0));
    }

    @Test
    void visibleBoundsAreCentredOnCamera() {
        double[] b = XaeroView.visibleBounds(100, -50, 1920, 1080, 2.0);
        assertEquals(100 - 480, b[0], EPS);
        assertEquals(100 + 480, b[2], EPS);
        assertEquals(-50 - 270, b[1], EPS);
        assertEquals(-50 + 270, b[3], EPS);
    }

    @Test
    void visibleBoundsUseXaerosIntegerHalving() {
        double[] b = XaeroView.visibleBounds(0, 0, 1001, 1001, 1.0);
        assertEquals(-500, b[0], EPS);
        assertEquals(501, b[2], EPS);
    }

    @Test
    void worldToFramebufferPutsCameraAtCentre() {
        double[] c = XaeroView.worldToFramebuffer(100, -50, 100, -50, 1920, 1080, 4.0);
        assertArrayEquals(new double[] {960, 540}, c, EPS);
        double[] east = XaeroView.worldToFramebuffer(110, -50, 100, -50, 1920, 1080, 4.0);
        assertEquals(1000, east[0], EPS);   // +10 blocks east = +40 px right
        double[] south = XaeroView.worldToFramebuffer(100, -40, 100, -50, 1920, 1080, 4.0);
        assertEquals(580, south[1], EPS);   // +Z (south) is down, as on Xaero's map
    }

    @Test
    void imageCornerMapsToWhereTheBoundsSay() {
        // image corner in local map-pass coordinates is world - flooredCamera; with camera on a whole block
        // and scale 1 that is exactly the offset from the screen centre
        Placement p = Placement.of(7500, 7500, 0, 0, 8);
        int fc = XaeroView.flooredCamera(-30000.0, XaeroView.fboScale(1.0));
        assertEquals(0.0, p.minX() - fc, EPS);   // camera on the NW corner -> corner at local 0
    }
}
