package tools.argus.basemaps.pack;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read access to a tile pack: a folder, or a .zip read in place (never extracted). Thread-safe. */
public interface TileSource extends Closeable {
    PackManifest manifest();

    /** Encoded tile bytes, or null if the tile does not exist (= transparent). */
    byte[] read(int level, int x, int y) throws IOException;

    /** Opens a pack folder or .zip file. */
    static TileSource open(Path path) throws IOException {
        if (Files.isDirectory(path)) return new Dir(path);
        return new Zip(path);
    }

    /** True if {@code dir} looks like a pack folder. */
    static boolean isPackDir(Path dir) {
        return Files.isRegularFile(dir.resolve(PackManifest.FILE_NAME));
    }

    final class Dir implements TileSource {
        private final Path root;
        private final PackManifest manifest;

        public Dir(Path root) throws IOException {
            this.root = root;
            this.manifest = PackManifest.parse(Files.readString(root.resolve(PackManifest.FILE_NAME), StandardCharsets.UTF_8));
        }

        @Override public PackManifest manifest() { return manifest; }

        @Override
        public byte[] read(int level, int x, int y) throws IOException {
            try {
                return Files.readAllBytes(root.resolve(manifest.tilePath(level, x, y)));
            } catch (NoSuchFileException e) {
                return null;
            }
        }

        @Override public void close() { }
    }

    /** Zip pack. basemap.json may be at the root or inside one top-level folder. */
    final class Zip implements TileSource {
        private final ZipFile zip;
        private final String prefix;
        private final PackManifest manifest;

        public Zip(Path file) throws IOException {
            this.zip = new ZipFile(file.toFile());
            try {
                String best = null;
                for (Enumeration<? extends ZipEntry> en = zip.entries(); en.hasMoreElements(); ) {
                    String n = en.nextElement().getName();
                    if ((n.equals(PackManifest.FILE_NAME) || n.endsWith("/" + PackManifest.FILE_NAME))
                        && (best == null || n.length() < best.length())) best = n;
                    if (n.equals(PackManifest.FILE_NAME)) break;
                }
                if (best == null) throw new IOException("zip has no " + PackManifest.FILE_NAME);
                this.prefix = best.substring(0, best.length() - PackManifest.FILE_NAME.length());
                try (InputStream in = zip.getInputStream(zip.getEntry(best))) {
                    this.manifest = PackManifest.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException e) {
                zip.close();
                throw e;
            }
        }

        /** True if the zip contains a basemap.json (cheap check used when scanning the folder). */
        public static boolean looksLikePack(Path file) {
            try (ZipFile z = new ZipFile(file.toFile())) {
                if (z.getEntry(PackManifest.FILE_NAME) != null) return true;
                for (Enumeration<? extends ZipEntry> en = z.entries(); en.hasMoreElements(); ) {
                    if (en.nextElement().getName().endsWith("/" + PackManifest.FILE_NAME)) return true;
                }
                return false;
            } catch (IOException e) {
                return false;
            }
        }

        @Override public PackManifest manifest() { return manifest; }

        @Override
        public byte[] read(int level, int x, int y) throws IOException {
            ZipEntry e = zip.getEntry(prefix + manifest.tilePath(level, x, y));
            if (e == null) return null;
            try (InputStream in = zip.getInputStream(e)) {
                return in.readAllBytes();
            }
        }

        @Override public void close() throws IOException { zip.close(); }
    }
}
