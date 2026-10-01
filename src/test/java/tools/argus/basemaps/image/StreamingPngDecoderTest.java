package tools.argus.basemaps.image;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.IndexColorModel;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/** Cross-checks the streaming PNG decoder against ImageIO on images ImageIO can still read. */
class StreamingPngDecoderTest {
    @TempDir Path dir;

    private Path write(BufferedImage img, String name) throws IOException {
        Path p = dir.resolve(name);
        assertTrue(ImageIO.write(img, "png", p.toFile()));
        return p;
    }

    private static BufferedImage noise(int type, int w, int h, long seed) {
        BufferedImage img = new BufferedImage(w, h, type);
        Random r = new Random(seed);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                // mix smooth gradients (exercise Sub/Up/Paeth filters) with noise
                int c = r.nextInt(8) < 6 ? ((x * 3) << 16 | (y * 5) << 8 | (x + y)) : r.nextInt();
                img.setRGB(x, y, (r.nextInt(4) == 0 ? (r.nextInt(256) << 24) : 0xFF000000) | (c & 0xFFFFFF));
            }
        }
        return img;
    }

    private void assertSameRgb(BufferedImage type, int factor) throws IOException {
        Path p = write(type, "t" + type.getType() + "_" + factor + ".png");
        BufferedImage expectedFull = ImageIO.read(p.toFile());
        BufferedImage got = StreamingPngDecoder.decodeSubsampled(p, factor, () -> false);
        assertEquals((type.getWidth() + factor - 1) / factor, got.getWidth());
        assertEquals((type.getHeight() + factor - 1) / factor, got.getHeight());
        for (int y = 0; y < got.getHeight(); y++)
            for (int x = 0; x < got.getWidth(); x++)
                assertEquals(expectedFull.getRGB(x * factor, y * factor), got.getRGB(x, y),
                    "type " + type.getType() + " factor " + factor + " at " + x + "," + y);
    }

    @Test
    void rgbaAndRgbMatchImageIo() throws IOException {
        for (int f : new int[] {1, 2, 3, 7}) {
            assertSameRgb(noise(BufferedImage.TYPE_INT_ARGB, 301, 157, 1), f);   // several IDAT chunks
            assertSameRgb(noise(BufferedImage.TYPE_INT_RGB, 123, 77, 2), f);
        }
    }

    @Test
    void palettesIncludingTransparencyAndSubByteDepthsMatchImageIo() throws IOException {
        // 8-bit palette
        assertSameRgb(noise(BufferedImage.TYPE_BYTE_INDEXED, 200, 90, 3), 3);
        // 1-bit palette (black/white)
        assertSameRgb(noise(BufferedImage.TYPE_BYTE_BINARY, 131, 70, 4), 2);
        // 4-bit palette with a transparent entry (written as tRNS)
        byte[] r = new byte[16], g = new byte[16], b = new byte[16], a = new byte[16];
        for (int i = 0; i < 16; i++) { r[i] = (byte) (i * 16); g[i] = (byte) (255 - i * 16); b[i] = (byte) (i * 7); a[i] = (byte) (i == 5 ? 0 : 255); }
        IndexColorModel cm = new IndexColorModel(4, 16, r, g, b, a);
        BufferedImage pal4 = new BufferedImage(97, 55, BufferedImage.TYPE_BYTE_BINARY, cm);
        Random rnd = new Random(5);
        for (int y = 0; y < 55; y++) for (int x = 0; x < 97; x++) pal4.getRaster().setSample(x, y, 0, rnd.nextInt(16));
        assertSameRgb(pal4, 1);
        assertSameRgb(pal4, 4);
    }

    @Test
    void greyscaleUsesRawSampleValues() throws IOException {
        BufferedImage g8 = new BufferedImage(90, 40, BufferedImage.TYPE_BYTE_GRAY);
        BufferedImage g16 = new BufferedImage(90, 40, BufferedImage.TYPE_USHORT_GRAY);
        Random rnd = new Random(6);
        for (int y = 0; y < 40; y++) for (int x = 0; x < 90; x++) {
            g8.getRaster().setSample(x, y, 0, rnd.nextInt(256));
            g16.getRaster().setSample(x, y, 0, rnd.nextInt(65536));
        }
        for (BufferedImage src : new BufferedImage[] {g8, g16}) {
            Path p = write(src, "g" + src.getType() + ".png");
            BufferedImage got = StreamingPngDecoder.decodeSubsampled(p, 3, () -> false);
            int shift = src.getType() == BufferedImage.TYPE_USHORT_GRAY ? 8 : 0;
            for (int y = 0; y < got.getHeight(); y++) for (int x = 0; x < got.getWidth(); x++) {
                int v = src.getRaster().getSample(x * 3, y * 3, 0) >> shift;
                assertEquals(0xFF000000 | v << 16 | v << 8 | v, got.getRGB(x, y));
            }
        }
    }

    @Test
    void rejectsNonPngAndTruncatedData() throws IOException {
        Path junk = dir.resolve("junk.png");
        Files.write(junk, "definitely not a png".getBytes(StandardCharsets.US_ASCII));
        assertThrows(IOException.class, () -> StreamingPngDecoder.decodeSubsampled(junk, 1, () -> false));

        Path good = write(noise(BufferedImage.TYPE_INT_RGB, 200, 200, 7), "good.png");
        byte[] bytes = Files.readAllBytes(good);
        Path cut = dir.resolve("cut.png");
        Files.write(cut, java.util.Arrays.copyOf(bytes, bytes.length / 2));
        assertThrows(IOException.class, () -> StreamingPngDecoder.decodeSubsampled(cut, 1, () -> false));
    }

    /**
     * A 50000 x 46000 PNG (2.3 gigapixels, 1-bit so the file stays tiny): ImageIO refuses it, the loader falls
     * back to the streaming decoder and downsamples it under the cap.
     */
    @Test
    void imageAboveImageIoLimitLoadsThroughFallback() throws IOException {
        int w = 50000, h = 46000, whiteRows = 1000;
        assertTrue((long) w * h > ImageLoader.IMAGEIO_PIXEL_LIMIT);
        Path p = dir.resolve("huge.png");
        writeHugeBilevelPng(p, w, h, whiteRows);

        assertThrows(Exception.class, () -> ImageIO.read(p.toFile()));   // the reason the fallback exists

        ImageLoader.Probe probe = ImageLoader.probe(p);   // the header is still readable
        assertEquals(w, probe.width());
        assertEquals(tools.argus.basemaps.pack.ImageSlicer.Mode.STREAM_PNG, tools.argus.basemaps.pack.ImageSlicer.mode(probe, false));

        BufferedImage img = StreamingPngDecoder.decodeSubsampled(p, 49, () -> false);
        assertEquals((w + 48) / 49, img.getWidth());
        assertEquals((h + 48) / 49, img.getHeight());
        assertEquals(0xFFFFFFFF, img.getRGB(0, 20));    // source (0, 980): white
        assertEquals(0xFF000000, img.getRGB(1, 20));    // source (49, 980): black
        assertEquals(0xFF000000, img.getRGB(0, 21));    // source (0, 1029): black
    }

    /** 1-bit greyscale; pixels 0..7 of the first {@code whiteRows} rows are white, everything else black. */
    private static void writeHugeBilevelPng(Path p, int w, int h, int whiteRows) throws IOException {
        ByteArrayOutputStream idat = new ByteArrayOutputStream();
        try (DeflaterOutputStream z = new DeflaterOutputStream(idat, new Deflater(Deflater.BEST_SPEED), 1 << 16)) {
            byte[] row = new byte[1 + (w + 7) / 8];
            for (int y = 0; y < h; y++) {
                row[1] = (byte) (y < whiteRows ? 0xFF : 0x00);
                z.write(row);
            }
        }
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(p))) {
            out.write(new byte[] {(byte) 137, 80, 78, 71, 13, 10, 26, 10});
            ByteArrayOutputStream ihdr = new ByteArrayOutputStream();
            DataOutputStream d = new DataOutputStream(ihdr);
            d.writeInt(w); d.writeInt(h); d.writeByte(1); d.writeByte(0); d.writeByte(0); d.writeByte(0); d.writeByte(0);
            chunk(out, "IHDR", ihdr.toByteArray());
            chunk(out, "IDAT", idat.toByteArray());
            chunk(out, "IEND", new byte[0]);
        }
    }

    private static void chunk(DataOutputStream out, String type, byte[] data) throws IOException {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeInt(data.length);
        out.write(t);
        out.write(data);
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeInt((int) crc.getValue());
    }

}
