package tools.argus.basemaps.gui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ButtonSlotsTest {
    private static final int W = 427, H = 240, BW = 98, BH = 20;
    private static final int CX = W / 2;

    /** The vanilla pause grid (204 wide, rows of 20 + 4 gap), bottom row = "Disconnect". */
    private static List<ButtonSlots.Rect> vanilla() {
        List<ButtonSlots.Rect> r = new ArrayList<>();
        int top = 50;
        r.add(new ButtonSlots.Rect(CX - 102, top, 204, 20));
        for (int row = 1; row <= 3; row++) {
            r.add(new ButtonSlots.Rect(CX - 102, top + row * 24, 98, 20));
            r.add(new ButtonSlots.Rect(CX + 4, top + row * 24, 98, 20));
        }
        r.add(new ButtonSlots.Rect(CX - 102, top + 4 * 24, 204, 20));   // y 146..166
        return r;
    }

    private static void assertFree(ButtonSlots.Rect slot, List<ButtonSlots.Rect> others) {
        for (ButtonSlots.Rect o : others) assertFalse(slot.intersects(o, 0), slot + " overlaps " + o);
        assertTrue(slot.inside(W, H), slot + " off screen");
    }

    @Test
    void vanillaMenuGoesCentredBelowTheLastButton() {
        ButtonSlots.Rect s = ButtonSlots.pick(W, H, BW, BH, vanilla());
        assertEquals(new ButtonSlots.Rect(CX - 49, 170, BW, BH), s);
        assertFree(s, vanilla());
    }

    @Test
    void anotherModAlreadyBelowPushesUsToTheRight() {
        List<ButtonSlots.Rect> others = vanilla();
        others.add(new ButtonSlots.Rect(CX - 102, 170, 204, 20));   // e.g. Basilisk "Atlas" below the grid
        ButtonSlots.Rect s = ButtonSlots.pick(W, H, BW, BH, others);
        // lowest widget is now Atlas: below it (y 194) is still free on a 240-high screen
        assertEquals(194, s.y());
        assertFree(s, others);
    }

    @Test
    void noRoomBelowFallsBackToSameRowRightThenCorners() {
        List<ButtonSlots.Rect> others = vanilla();
        others.add(new ButtonSlots.Rect(CX - 102, 170, 204, 20));
        others.add(new ButtonSlots.Rect(CX - 102, 194, 204, 20));   // two more rows of other mods' buttons
        others.add(new ButtonSlots.Rect(CX - 102, 218, 204, 20));   // lowest ends at 238: nothing fits below
        ButtonSlots.Rect s = ButtonSlots.pick(W, H, BW, BH, others);
        assertEquals(CX + 102 + ButtonSlots.GAP, s.x());   // right of the lowest row (319 + 98 <= 427)
        assertEquals(218, s.y());
        assertFree(s, others);
    }

    @Test
    void cornersWhenTheMiddleIsFull() {
        List<ButtonSlots.Rect> others = new ArrayList<>();
        others.add(new ButtonSlots.Rect(0, 100, W, 140));   // a widget covering the whole lower part, full width
        ButtonSlots.Rect s = ButtonSlots.pick(W, H, BW, BH, others);
        assertEquals(new ButtonSlots.Rect(ButtonSlots.MARGIN, ButtonSlots.MARGIN, BW, BH), s);   // top-left
        // a block reaching the right edge and nearly the bottom: no room below or to its right -> bottom-left corner
        List<ButtonSlots.Rect> mid = List.of(new ButtonSlots.Rect(120, 20, W - 120, 200));
        assertEquals(new ButtonSlots.Rect(ButtonSlots.MARGIN, H - ButtonSlots.MARGIN - BH, BW, BH),
            ButtonSlots.pick(W, H, BW, BH, mid));
        // bottom-left also taken, bottom-right overlapped by the block -> top-left
        List<ButtonSlots.Rect> bl = List.of(new ButtonSlots.Rect(120, 20, W - 120, 200), new ButtonSlots.Rect(0, 210, 90, 30));
        assertEquals(new ButtonSlots.Rect(ButtonSlots.MARGIN, ButtonSlots.MARGIN, BW, BH), ButtonSlots.pick(W, H, BW, BH, bl));
    }

    @Test
    void candidateOrderIsBelowRightThenCorners() {
        List<ButtonSlots.Rect> c = ButtonSlots.candidates(W, H, BW, BH, vanilla());
        assertEquals(5, c.size());
        assertEquals(170, c.get(0).y());                                         // below the lowest
        assertEquals(146, c.get(1).y());                                         // right of the lowest row
        assertEquals(new ButtonSlots.Rect(8, H - 28, BW, BH), c.get(2));         // bottom-left
        assertEquals(new ButtonSlots.Rect(W - 8 - BW, H - 28, BW, BH), c.get(3)); // bottom-right
        assertEquals(new ButtonSlots.Rect(8, 8, BW, BH), c.get(4));              // top-left
    }

    @Test
    void freeCheckDetectsOverlapAndOffScreen() {
        List<ButtonSlots.Rect> others = List.of(new ButtonSlots.Rect(10, 10, 50, 20));
        assertFalse(ButtonSlots.isFree(new ButtonSlots.Rect(40, 20, BW, BH), W, H, others));
        assertFalse(ButtonSlots.isFree(new ButtonSlots.Rect(60, 10, BW, BH), W, H, others));   // touching counts
        assertTrue(ButtonSlots.isFree(new ButtonSlots.Rect(70, 10, BW, BH), W, H, others));
        assertFalse(ButtonSlots.isFree(new ButtonSlots.Rect(W - 50, 10, BW, BH), W, H, others));
        assertFalse(ButtonSlots.isFree(new ButtonSlots.Rect(0, H - 10, BW, BH), W, H, others));
    }

    @Test
    void emptyScreenStillGetsASlot() {
        ButtonSlots.Rect s = ButtonSlots.pick(W, H, BW, BH, List.of());
        assertTrue(s.inside(W, H));
    }
}
