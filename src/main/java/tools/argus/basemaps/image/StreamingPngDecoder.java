package tools.argus.basemaps.image;

import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

/**
 * Minimal streaming PNG decoder that subsamples while it inflates, holding only two rows of the source image.
 *
 * <p>Why it exists: the JDK's ImageIO PNG and JPEG readers refuse any image with more than
 * {@code Integer.MAX_VALUE - 2} pixels (about 46340 x 46340), even when source subsampling is requested, so a
 * 60000 x 60000 map cannot be read through ImageIO at all. This decoder handles those PNGs. It supports every
 * standard colour type and bit depth, plus tRNS transparency; interlaced (Adam7) PNGs are rejected.
 */
public final class StreamingPngDecoder {
    private static final byte[] SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

    private StreamingPngDecoder() { }

    /** IHDR fields. */
    public record Header(int width, int height, int bitDepth, int colorType, boolean interlaced) { }

    /** Receives decoded rows (ARGB, one int per kept pixel). The array is reused between calls. */
    @FunctionalInterface
    public interface RowSink {
        void row(int y, int[] argb) throws IOException;
    }

    /** Reads only the PNG header. */
    public static Header readHeader(Path file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 64))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            for (int i = 0; i < 8; i++) if (sig[i] != SIGNATURE[i]) throw new IOException("not a PNG file");
            in.readInt();
            if (!readType(in).equals("IHDR")) throw new IOException("PNG does not start with IHDR");
            int w = in.readInt(), h = in.readInt(), depth = in.readUnsignedByte(), type = in.readUnsignedByte();
            in.readUnsignedByte();
            in.readUnsignedByte();
            return new Header(w, h, depth, type, in.readUnsignedByte() != 0);
        }
    }

    /**
     * Decodes {@code file}, keeping pixel (x, y) only when both are multiples of {@code factor}.
     *
     * @return a TYPE_INT_ARGB image of ceil(w / factor) x ceil(h / factor), or null if cancelled
     */
    public static BufferedImage decodeSubsampled(Path file, int factor, BooleanSupplier cancelled) throws IOException {
        if (factor < 1) throw new IllegalArgumentException("factor must be >= 1");
        Header h = readHeader(file);
        BufferedImage out = new BufferedImage((h.width() + factor - 1) / factor, (h.height() + factor - 1) / factor,
            BufferedImage.TYPE_INT_ARGB);
        boolean done = decodeRows(file, factor, (y, row) -> out.setRGB(0, y, out.getWidth(), 1, row, 0, out.getWidth()), cancelled);
        return done ? out : null;
    }

    /**
     * Streams the image top to bottom, calling {@code sink} for every kept row (every {@code factor}-th row,
     * every {@code factor}-th pixel; factor 1 = every pixel). Holds only two source rows in memory.
     *
     * @return false if cancelled
     */
    public static boolean decodeRows(Path file, int factor, RowSink sink, BooleanSupplier cancelled) throws IOException {
        if (factor < 1) throw new IllegalArgumentException("factor must be >= 1");
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file), 1 << 16))) {
            byte[] sig = new byte[8];
            in.readFully(sig);
            for (int i = 0; i < 8; i++) if (sig[i] != SIGNATURE[i]) throw new IOException("not a PNG file");

            int width = 0, height = 0, bitDepth = 0, colorType = -1;
            byte[] palette = null;
            byte[] trns = null;
            while (true) {
                int length = in.readInt();
                String type = readType(in);
                if (length < 0) throw new IOException("bad chunk length");
                if (type.equals("IHDR")) {
                    width = in.readInt();
                    height = in.readInt();
                    bitDepth = in.readUnsignedByte();
                    colorType = in.readUnsignedByte();
                    int compression = in.readUnsignedByte(), filter = in.readUnsignedByte(), interlace = in.readUnsignedByte();
                    skip(in, length - 13 + 4);
                    if (width <= 0 || height <= 0) throw new IOException("bad PNG size");
                    if (compression != 0 || filter != 0) throw new IOException("unsupported PNG compression/filter method");
                    if (interlace != 0) throw new IOException("interlaced PNG - re-save it without interlacing");
                    checkFormat(colorType, bitDepth);
                } else if (type.equals("PLTE")) {
                    palette = new byte[length];
                    in.readFully(palette);
                    skip(in, 4);
                } else if (type.equals("tRNS")) {
                    trns = new byte[length];
                    in.readFully(trns);
                    skip(in, 4);
                } else if (type.equals("IDAT")) {
                    if (colorType < 0) throw new IOException("IDAT before IHDR");
                    if (colorType == 3 && palette == null) throw new IOException("palette PNG without PLTE");
                    return decodeImageData(new IdatStream(in, length), width, height, bitDepth, colorType, palette, trns, factor, sink, cancelled);
                } else if (type.equals("IEND")) {
                    throw new IOException("PNG has no image data");
                } else {
                    skip(in, (long) length + 4);   // ancillary chunk + CRC
                }
            }
        }
    }

    private static void checkFormat(int colorType, int bitDepth) throws IOException {
        boolean ok = switch (colorType) {
            case 0 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16;
            case 3 -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8;
            case 2, 4, 6 -> bitDepth == 8 || bitDepth == 16;
            default -> false;
        };
        if (!ok) throw new IOException("unsupported PNG colour type " + colorType + " / bit depth " + bitDepth);
    }

    private static int channels(int colorType) {
        return switch (colorType) {
            case 0, 3 -> 1;
            case 4 -> 2;
            case 2 -> 3;
            case 6 -> 4;
            default -> throw new IllegalStateException();
        };
    }

    private static boolean decodeImageData(InputStream idat, int width, int height, int bitDepth, int colorType,
                                           byte[] palette, byte[] trns, int factor, RowSink sink, BooleanSupplier cancelled) throws IOException {
        int channels = channels(colorType);
        long rowBytesL = ((long) width * channels * bitDepth + 7) / 8;
        if (rowBytesL > Integer.MAX_VALUE - 16) throw new IOException("PNG rows too wide");
        int rowBytes = (int) rowBytesL;
        int bpp = Math.max(1, channels * bitDepth / 8);   // filter byte distance

        int outW = (width + factor - 1) / factor;
        int[] outRow = new int[outW];

        int[] paletteArgb = null;
        if (colorType == 3) {
            int n = palette.length / 3;
            paletteArgb = new int[256];
            for (int i = 0; i < n && i < 256; i++) {
                int a = trns != null && i < trns.length ? trns[i] & 255 : 255;
                paletteArgb[i] = (a << 24) | ((palette[i * 3] & 255) << 16) | ((palette[i * 3 + 1] & 255) << 8) | (palette[i * 3 + 2] & 255);
            }
        }
        int trnsGray = -1, trnsR = -1, trnsG = -1, trnsB = -1;
        if (trns != null && colorType == 0 && trns.length >= 2) trnsGray = ((trns[0] & 255) << 8) | (trns[1] & 255);
        if (trns != null && colorType == 2 && trns.length >= 6) {
            trnsR = ((trns[0] & 255) << 8) | (trns[1] & 255);
            trnsG = ((trns[2] & 255) << 8) | (trns[3] & 255);
            trnsB = ((trns[4] & 255) << 8) | (trns[5] & 255);
        }

        Inflater inflater = new Inflater();
        try (InflaterInputStream z = new InflaterInputStream(idat, inflater, 1 << 16)) {
            byte[] prev = new byte[rowBytes], cur = new byte[rowBytes];
            for (int y = 0; y < height; y++) {
                if ((y & 255) == 0 && cancelled.getAsBoolean()) return false;
                int filter = z.read();
                if (filter < 0) throw new EOFException("PNG image data ends early (row " + y + ")");
                readFully(z, cur, rowBytes);
                unfilter(filter, cur, prev, rowBytes, bpp);
                if (y % factor == 0) {
                    for (int ox = 0; ox < outW; ox++) {
                        outRow[ox] = pixel(cur, ox * factor, bitDepth, colorType, paletteArgb, trnsGray, trnsR, trnsG, trnsB);
                    }
                    sink.row(y / factor, outRow);
                }
                byte[] t = prev;
                prev = cur;
                cur = t;
            }
        } finally {
            inflater.end();
        }
        return true;
    }

    static void unfilter(int filter, byte[] cur, byte[] prev, int n, int bpp) throws IOException {
        switch (filter) {
            case 0 -> { }
            case 1 -> { for (int i = bpp; i < n; i++) cur[i] += cur[i - bpp]; }
            case 2 -> { for (int i = 0; i < n; i++) cur[i] += prev[i]; }
            case 3 -> {
                for (int i = 0; i < bpp; i++) cur[i] += (byte) ((prev[i] & 255) >>> 1);
                for (int i = bpp; i < n; i++) cur[i] += (byte) (((cur[i - bpp] & 255) + (prev[i] & 255)) >>> 1);
            }
            case 4 -> {
                for (int i = 0; i < bpp; i++) cur[i] += prev[i];   // paeth(0, up, 0) = up
                for (int i = bpp; i < n; i++) {
                    int a = cur[i - bpp] & 255, b = prev[i] & 255, c = prev[i - bpp] & 255;
                    int p = a + b - c, pa = Math.abs(p - a), pb = Math.abs(p - b), pc = Math.abs(p - c);
                    cur[i] += (byte) (pa <= pb && pa <= pc ? a : pb <= pc ? b : c);
                }
            }
            default -> throw new IOException("bad PNG filter type " + filter);
        }
    }

    /** Raw sample {@code index} of a row (full bit precision). */
    private static int sample(byte[] row, long index, int bitDepth) {
        switch (bitDepth) {
            case 8: return row[(int) index] & 255;
            case 16: return ((row[(int) (index * 2)] & 255) << 8) | (row[(int) (index * 2 + 1)] & 255);
            default: {
                long bit = index * bitDepth;
                int b = row[(int) (bit >>> 3)] & 255;
                int shift = 8 - bitDepth - (int) (bit & 7);
                return (b >>> shift) & ((1 << bitDepth) - 1);
            }
        }
    }

    private static int to8(int v, int bitDepth) {
        return switch (bitDepth) {
            case 1 -> v * 255;
            case 2 -> v * 85;
            case 4 -> v * 17;
            case 16 -> v >>> 8;
            default -> v;
        };
    }

    private static int pixel(byte[] row, int x, int bitDepth, int colorType, int[] paletteArgb,
                             int trnsGray, int trnsR, int trnsG, int trnsB) {
        switch (colorType) {
            case 0: {
                int v = sample(row, x, bitDepth), g = to8(v, bitDepth);
                int a = v == trnsGray ? 0 : 255;
                return (a << 24) | (g << 16) | (g << 8) | g;
            }
            case 2: {
                long i = (long) x * 3;
                int r = sample(row, i, bitDepth), g = sample(row, i + 1, bitDepth), b = sample(row, i + 2, bitDepth);
                int a = r == trnsR && g == trnsG && b == trnsB ? 0 : 255;
                return (a << 24) | (to8(r, bitDepth) << 16) | (to8(g, bitDepth) << 8) | to8(b, bitDepth);
            }
            case 3:
                return paletteArgb[sample(row, x, bitDepth)];
            case 4: {
                long i = (long) x * 2;
                int g = to8(sample(row, i, bitDepth), bitDepth), a = to8(sample(row, i + 1, bitDepth), bitDepth);
                return (a << 24) | (g << 16) | (g << 8) | g;
            }
            default: {   // 6
                long i = (long) x * 4;
                return (to8(sample(row, i + 3, bitDepth), bitDepth) << 24) | (to8(sample(row, i, bitDepth), bitDepth) << 16)
                    | (to8(sample(row, i + 1, bitDepth), bitDepth) << 8) | to8(sample(row, i + 2, bitDepth), bitDepth);
            }
        }
    }

    private static String readType(DataInputStream in) throws IOException {
        byte[] t = new byte[4];
        in.readFully(t);
        return new String(t, StandardCharsets.ISO_8859_1);
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException("PNG image data ends early");
            off += r;
        }
    }

    private static void skip(DataInputStream in, long n) throws IOException {
        while (n > 0) {
            long s = in.skip(n);
            if (s <= 0) {
                if (in.read() < 0) throw new EOFException("PNG ends early");
                s = 1;
            }
            n -= s;
        }
    }

    /** Concatenated payload of consecutive IDAT chunks (CRCs skipped, not verified). */
    private static final class IdatStream extends InputStream {
        private final DataInputStream in;
        private long remaining;
        private boolean done;

        IdatStream(DataInputStream in, int firstLength) {
            this.in = in;
            this.remaining = firstLength;
        }

        private boolean advance() throws IOException {
            while (remaining == 0 && !done) {
                StreamingPngDecoder.skip(in, 4);   // CRC of the previous IDAT
                int length = in.readInt();
                String type = readType(in);
                if (!type.equals("IDAT")) {
                    done = true;
                    return false;
                }
                remaining = length;
            }
            return !done;
        }

        @Override
        public int read() throws IOException {
            if (!advance()) return -1;
            remaining--;
            return in.read();
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (len == 0) return 0;
            if (!advance()) return -1;
            int r = in.read(b, off, (int) Math.min(len, remaining));
            if (r < 0) throw new EOFException("PNG ends inside IDAT");
            remaining -= r;
            return r;
        }
    }
}
