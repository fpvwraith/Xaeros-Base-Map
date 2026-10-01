package tools.argus.basemaps.math;

/** Mipmap helpers on packed ARGB int arrays. Pure math. */
public final class Mipmaps {
    public static final int MAX_LEVELS = 12;

    private Mipmaps() { }

    /**
     * Number of mip levels for a w x h texture such that every level is at least 1x1 when sizes are halved
     * with a right shift (Minecraft's {@code GpuTexture.getWidth(level)} is {@code width >> level}).
     */
    public static int levelCount(int width, int height) {
        int min = Math.min(width, height);
        if (min < 1) throw new IllegalArgumentException("texture must be at least 1x1");
        int levels = 32 - Integer.numberOfLeadingZeros(min);   // floor(log2(min)) + 1
        return Math.min(levels, MAX_LEVELS);
    }

    /**
     * Halves an ARGB image (w x h) to (w >> 1) x (h >> 1) with an alpha-weighted 2x2 box filter, so fully
     * transparent pixels do not darken their neighbours.
     */
    public static int[] halve(int[] src, int w, int h) {
        int dw = Math.max(1, w >> 1), dh = Math.max(1, h >> 1);
        int[] dst = new int[dw * dh];
        for (int y = 0; y < dh; y++) {
            int sy0 = Math.min(h - 1, y * 2), sy1 = Math.min(h - 1, y * 2 + 1);
            for (int x = 0; x < dw; x++) {
                int sx0 = Math.min(w - 1, x * 2), sx1 = Math.min(w - 1, x * 2 + 1);
                dst[y * dw + x] = average(src[sy0 * w + sx0], src[sy0 * w + sx1], src[sy1 * w + sx0], src[sy1 * w + sx1]);
            }
        }
        return dst;
    }

    static int average(int c0, int c1, int c2, int c3) {
        int a0 = c0 >>> 24, a1 = c1 >>> 24, a2 = c2 >>> 24, a3 = c3 >>> 24;
        int aSum = a0 + a1 + a2 + a3;
        if (aSum == 0) return 0;
        int r = (((c0 >> 16) & 255) * a0 + ((c1 >> 16) & 255) * a1 + ((c2 >> 16) & 255) * a2 + ((c3 >> 16) & 255) * a3 + aSum / 2) / aSum;
        int g = (((c0 >> 8) & 255) * a0 + ((c1 >> 8) & 255) * a1 + ((c2 >> 8) & 255) * a2 + ((c3 >> 8) & 255) * a3 + aSum / 2) / aSum;
        int b = ((c0 & 255) * a0 + (c1 & 255) * a1 + (c2 & 255) * a2 + (c3 & 255) * a3 + aSum / 2) / aSum;
        int a = (aSum + 2) / 4;
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
