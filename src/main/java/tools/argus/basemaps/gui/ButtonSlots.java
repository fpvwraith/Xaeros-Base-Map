package tools.argus.basemaps.gui;

import java.util.ArrayList;
import java.util.List;

/**
 * Picks a free spot for the pause-menu button among other mods' buttons. Pure geometry (no Minecraft types).
 *
 * <p>Candidates, first free wins: below the lowest widget; to the right of the lowest widget's row; bottom-left
 * corner; bottom-right corner; top-left corner. "Free" = inside the screen and not touching any existing widget.
 */
public final class ButtonSlots {
    public static final int MARGIN = 8, GAP = 4;

    private ButtonSlots() { }

    public record Rect(int x, int y, int w, int h) {
        public boolean intersects(Rect o, int pad) {
            return x < o.x + o.w + pad && o.x < x + w + pad && y < o.y + o.h + pad && o.y < y + h + pad;
        }

        public boolean inside(int screenW, int screenH) {
            return x >= 0 && y >= 0 && x + w <= screenW && y + h <= screenH;
        }
    }

    /** Candidate slots in preference order. */
    public static List<Rect> candidates(int screenW, int screenH, int bw, int bh, List<Rect> others) {
        List<Rect> out = new ArrayList<>();
        Rect lowest = null;
        for (Rect r : others) if (lowest == null || r.y + r.h > lowest.y + lowest.h) lowest = r;
        if (lowest != null) {
            out.add(new Rect(lowest.x + lowest.w / 2 - bw / 2, lowest.y + lowest.h + GAP, bw, bh));
            // to the right of everything on the lowest button's row (for when there is no room below)
            int y = lowest.y, right = lowest.x + lowest.w;
            for (Rect r : others) if (r.y < y + bh && y < r.y + r.h) right = Math.max(right, r.x + r.w);
            out.add(new Rect(right + GAP, y, bw, bh));
        }
        out.add(new Rect(MARGIN, screenH - MARGIN - bh, bw, bh));
        out.add(new Rect(screenW - MARGIN - bw, screenH - MARGIN - bh, bw, bh));
        out.add(new Rect(MARGIN, MARGIN, bw, bh));
        return out;
    }

    public static boolean isFree(Rect slot, int screenW, int screenH, List<Rect> others) {
        if (!slot.inside(screenW, screenH)) return false;
        for (Rect r : others) if (slot.intersects(r, 1)) return false;
        return true;
    }

    /** First free candidate; if nothing is free, the top-left corner. */
    public static Rect pick(int screenW, int screenH, int bw, int bh, List<Rect> others) {
        List<Rect> c = candidates(screenW, screenH, bw, bh, others);
        for (Rect r : c) if (isFree(r, screenW, screenH, others)) return r;
        return c.get(c.size() - 1);
    }
}
