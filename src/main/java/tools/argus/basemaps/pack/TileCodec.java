package tools.argus.basemaps.pack;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Tile encode/decode helpers (plain JDK ImageIO). */
public final class TileCodec {
    static {
        ImageIO.setUseCache(false);
    }

    private TileCodec() { }

    /** Decoded tile pixels, packed ARGB, row-major. */
    public record Pixels(int width, int height, int[] argb) {
        public boolean isTransparent() { return TileCodec.isTransparent(argb); }
    }

    public static Pixels decode(byte[] encoded) throws IOException {
        BufferedImage img;
        try {
            img = ImageIO.read(new ByteArrayInputStream(encoded));
        } catch (RuntimeException e) {
            throw new IOException("corrupt tile: " + e, e);
        }
        if (img == null) throw new IOException("not a PNG/JPEG tile");
        int w = img.getWidth(), h = img.getHeight();
        return new Pixels(w, h, img.getRGB(0, 0, w, h, null, 0, w));
    }

    public static Pixels read(Path file) throws IOException {
        return decode(Files.readAllBytes(file));
    }

    public static boolean isTransparent(int[] argb) {
        for (int c : argb) if ((c >>> 24) != 0) return false;
        return true;
    }

    public static byte[] encodePng(int[] argb, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, argb, 0, w);
        ByteArrayOutputStream out = new ByteArrayOutputStream(w * h);
        if (!ImageIO.write(img, "png", out)) throw new IOException("no PNG writer");
        return out.toByteArray();
    }

    /** Writes a PNG tile atomically (temp file + move) so a half-written tile is never read. */
    public static void writePng(Path file, int[] argb, int w, int h) throws IOException {
        writeBytes(file, encodePng(argb, w, h));
    }

    public static void writeBytes(Path file, byte[] data) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName() + ".part");
        Files.write(tmp, data);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
    }

    /** Copies a w x h sub-rectangle at (x, y) out of a {@code stride}-wide ARGB array. */
    public static int[] crop(int[] src, int stride, int x, int y, int w, int h) {
        int[] out = new int[w * h];
        for (int row = 0; row < h; row++) System.arraycopy(src, (y + row) * stride + x, out, row * w, w);
        return out;
    }

    /** ARGB ints -> tightly packed RGBA bytes (Minecraft NativeImage.Format.RGBA byte order) in {@code dst}. */
    public static ByteBuffer toRgba(int[] argb, ByteBuffer dst) {
        int n = argb.length;
        int[] abgr = new int[n];
        for (int i = 0; i < n; i++) {
            int c = argb[i];
            abgr[i] = (c & 0xFF00FF00) | ((c >> 16) & 0xFF) | ((c & 0xFF) << 16);
        }
        dst.order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().put(abgr);   // little-endian ABGR = bytes R,G,B,A
        dst.position(0).limit(n * 4);
        return dst;
    }
}
