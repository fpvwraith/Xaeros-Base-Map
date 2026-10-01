package tools.argus.basemaps.tools;

import tools.argus.basemaps.pack.ImageSlicer;
import tools.argus.basemaps.pack.PackManifest;
import tools.argus.basemaps.pack.PackZip;
import tools.argus.basemaps.pack.Progress;
import tools.argus.basemaps.pack.WebTilePackBuilder;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Standalone pack converter (no Minecraft needed). Usage:
 * <pre>
 *   pack     &lt;image.png|jpg&gt; &lt;out folder|out.zip&gt; [--bpp 1] [--center 0,0] [--tile 256] [--dimension overworld]
 *   webtiles &lt;tiles dir with x/y.png&gt; &lt;out folder|out.zip&gt; --range -30000,30000 [--range-z a,b] [--dimension overworld]
 *   zip      &lt;pack folder&gt; &lt;out.zip&gt;
 *   options: --threads N
 * </pre>
 * Via Gradle: {@code ./gradlew pack --args="webtiles path/to/tiles path/to/out --range -30000,30000"}.
 */
public final class PackTool {
    private PackTool() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            usage();
            System.exit(2);
        }
        Map<String, String> opt = new HashMap<>();
        for (int i = 3; i < args.length; i++) {
            if (!args[i].startsWith("--") || i + 1 >= args.length) {
                System.err.println("bad option: " + args[i]);
                System.exit(2);
            }
            opt.put(args[i].substring(2), args[++i]);
        }
        String cmd = args[0].toLowerCase(Locale.ROOT);
        Path in = Path.of(args[1]), out = Path.of(args[2]);
        int threads = Integer.parseInt(opt.getOrDefault("threads", String.valueOf(Math.max(1, Runtime.getRuntime().availableProcessors()))));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        Progress progress = new Progress();
        Thread reporter = new Thread(() -> {
            try {
                while (true) {
                    Thread.sleep(2000);
                    System.out.println("  " + progress);
                }
            } catch (InterruptedException ignored) { }
        });
        reporter.setDaemon(true);
        reporter.start();
        long start = System.nanoTime();
        try {
            boolean toZip = out.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip");
            Path folder = toZip && !cmd.equals("zip") ? out.resolveSibling(out.getFileName() + ".build") : out;
            PackManifest m = null;
            switch (cmd) {
                case "pack" -> {
                    double bpp = Double.parseDouble(opt.getOrDefault("bpp", "1"));
                    double[] c = pair(opt.getOrDefault("center", "0,0"));
                    int tile = Integer.parseInt(opt.getOrDefault("tile", "256"));
                    m = ImageSlicer.slice(in, folder, tile, bpp, c[0], c[1], opt.getOrDefault("dimension", "overworld"), pool, progress);
                }
                case "webtiles" -> {
                    if (!opt.containsKey("range")) throw new IllegalArgumentException("--range min,max is required");
                    double[] rx = pair(opt.get("range"));
                    double[] rz = pair(opt.getOrDefault("range-z", opt.get("range")));
                    var range = new WebTilePackBuilder.Range((long) Math.floor(rx[0]), (long) Math.ceil(rx[1]),
                        (long) Math.floor(rz[0]), (long) Math.ceil(rz[1]));
                    m = WebTilePackBuilder.build(in, folder, range, opt.getOrDefault("dimension", "overworld"), pool, progress);
                }
                case "zip" -> PackZip.zip(in, out, progress);
                default -> {
                    usage();
                    System.exit(2);
                }
            }
            if (toZip && !cmd.equals("zip")) {
                PackZip.zip(folder, out, new Progress());
                ImageSlicer.deleteTree(folder);
            }
            reporter.interrupt();
            System.out.printf(Locale.ROOT, "Done in %.1f s -> %s%n", (System.nanoTime() - start) / 1e9, out.toAbsolutePath());
            if (m != null) {
                System.out.println("  " + m.widthPx() + " x " + m.heightPx() + " px, " + m.levels() + " levels, tile " + m.tileSize()
                    + ", origin " + m.originX() + "," + m.originZ() + ", " + m.blocksPerPixel() + " blocks/px");
            }
            printStats(out);
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    private static void printStats(Path out) throws IOException {
        if (Files.isDirectory(out)) {
            long[] s = new long[2];
            Map<String, Long> perLevel = new java.util.TreeMap<>((a, b) -> Integer.parseInt(a) - Integer.parseInt(b));
            try (var walk = Files.walk(out)) {
                walk.filter(Files::isRegularFile).forEach(p -> {
                    try {
                        s[0]++;
                        s[1] += Files.size(p);
                        Path rel = out.relativize(p);
                        if (rel.getNameCount() == 3) perLevel.merge(rel.getName(0).toString(), 1L, Long::sum);
                    } catch (IOException ignored) { }
                });
            }
            System.out.printf(Locale.ROOT, "  %d files, %.1f MB, tiles per level %s%n", s[0], s[1] / 1048576.0, perLevel);
        } else if (Files.isRegularFile(out)) {
            System.out.printf(Locale.ROOT, "  %.1f MB%n", Files.size(out) / 1048576.0);
        }
    }

    private static double[] pair(String s) {
        String[] p = s.split(",");
        if (p.length != 2) throw new IllegalArgumentException("expected a,b but got " + s);
        return new double[] {Double.parseDouble(p[0].trim()), Double.parseDouble(p[1].trim())};
    }

    private static void usage() {
        System.err.println("""
            Basemaps pack tool
              pack     <image.png|jpg> <out folder|out.zip> [--bpp 1] [--center X,Z] [--tile 256] [--dimension overworld|nether|end]
              webtiles <tiles dir (x/y.png, 256px, 1 block/px)> <out folder|out.zip> --range MIN,MAX [--range-z MIN,MAX] [--dimension ...]
              zip      <pack folder> <out.zip>
              common:  --threads N""");
    }
}
