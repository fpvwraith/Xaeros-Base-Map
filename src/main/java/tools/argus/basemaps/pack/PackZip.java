package tools.argus.basemaps.pack;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Writes a pack folder as a store-only zip (PNG tiles are already compressed; Basemaps reads it in place). */
public final class PackZip {
    private PackZip() { }

    public static void zip(Path packDir, Path zipFile, Progress progress) throws IOException {
        if (!TileSource.isPackDir(packDir)) throw new IOException(packDir + " has no " + PackManifest.FILE_NAME);
        List<Path> files;
        try (var walk = Files.walk(packDir)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }
        progress.total(files.size());
        progress.phase("zipping");
        Path tmp = zipFile.resolveSibling(zipFile.getFileName() + ".tmp");
        try (OutputStream os = new BufferedOutputStream(Files.newOutputStream(tmp), 1 << 20);
             ZipOutputStream zip = new ZipOutputStream(os)) {
            zip.setMethod(ZipOutputStream.STORED);
            // manifest first so a reader finds it quickly
            Path manifest = packDir.resolve(PackManifest.FILE_NAME);
            put(zip, PackManifest.FILE_NAME, Files.readAllBytes(manifest));
            for (Path f : files) {
                if (f.equals(manifest)) continue;
                String name = packDir.relativize(f).toString().replace('\\', '/');
                put(zip, name, Files.readAllBytes(f));
                progress.step(1);
            }
        }
        Files.move(tmp, zipFile, StandardCopyOption.REPLACE_EXISTING);
        progress.phase("done");
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        CRC32 crc = new CRC32();
        crc.update(data);
        e.setMethod(ZipEntry.STORED);
        e.setSize(data.length);
        e.setCompressedSize(data.length);
        e.setCrc(crc.getValue());
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }
}
