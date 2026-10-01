package tools.argus.basemaps;

import tools.argus.basemaps.config.ImageSettings;
import tools.argus.basemaps.image.ImageLoader;
import tools.argus.basemaps.pack.PackManifest;
import tools.argus.basemaps.pack.Progress;
import tools.argus.basemaps.pack.TileSource;

import java.nio.file.Path;

/**
 * One source in the basemaps folder (a single image, a pack folder or a pack zip) plus its runtime state.
 * Touched on the client/render thread only.
 */
public final class BasemapEntry {
    public enum Kind { IMAGE, PACK_DIR, PACK_ZIP }

    public enum State {
        /** Not opened yet (opens/slices when enabled and shown). */
        IDLE,
        /** Single image being sliced into the tile cache. */
        SLICING,
        /** Tile source open, drawing. */
        READY,
        FAILED
    }

    public final String name;
    public final Path path;
    public final Kind kind;
    public ImageSettings settings;

    // scan-time info
    ImageLoader.Probe probe;          // images
    long fileSize, fileMtime, stamp;
    String infoError;                 // unreadable file / bad manifest: never loads until the file changes
    String warning;                   // e.g. "big JPEG, slicing is slow"
    PackManifest manifest;            // packs: from the scan; images: once sliced

    // runtime
    State state = State.IDLE;
    String error;
    Progress slicing;
    int sliceGeneration;
    TileSource source;
    int sourceId;

    BasemapEntry(String name, Path path, Kind kind, ImageSettings settings) {
        this.name = name;
        this.path = path;
        this.kind = kind;
        this.settings = settings;
    }

    public boolean isPack() { return kind != Kind.IMAGE; }
    public State state() { return infoError != null ? State.FAILED : state; }
    public String error() { return infoError != null ? infoError : error; }
    public String warning() { return warning; }
    public ImageLoader.Probe probe() { return probe; }
    public PackManifest manifest() { return manifest; }
    public Progress slicing() { return slicing; }
}
