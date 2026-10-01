package tools.argus.basemaps.image;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Locale;

/** Image-file helpers (header probing). Plain JDK, never touches the network. */
public final class ImageLoader {
    /** The JDK PNG/JPEG readers throw for images with more pixels than this, whatever the region/subsampling. */
    public static final long IMAGEIO_PIXEL_LIMIT = Integer.MAX_VALUE - 2;

    static {
        ImageIO.setUseCache(false);   // decode from memory/files directly, no temp-file cache
    }

    private ImageLoader() { }

    /** Image header info, read without decoding pixels. */
    public record Probe(int width, int height, String format) {
        public boolean isPng() { return "png".equalsIgnoreCase(format); }
        public boolean isJpeg() { return "jpeg".equalsIgnoreCase(format) || "jpg".equalsIgnoreCase(format); }
        public long pixels() { return (long) width * height; }
    }

    /** File types picked up from the basemaps folder as single images. */
    public static boolean isImageFileName(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
    }

    /** Reads just the header (size + format). Cheap even for a 60000x60000 file. */
    public static Probe probe(Path file) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            if (in == null) throw new IOException("cannot open file");
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw new IOException("not a PNG/JPEG image (no decoder)");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                return new Probe(reader.getWidth(0), reader.getHeight(0), reader.getFormatName());
            } finally {
                reader.dispose();
            }
        } catch (RuntimeException e) {
            throw new IOException("unreadable image header: " + e, e);
        }
    }
}
