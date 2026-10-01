package tools.argus.basemaps.config;

import com.google.gson.annotations.SerializedName;

/** Where an image is drawn relative to Xaero's own map tiles. */
public enum DrawMode {
    /** Drawn before Xaero's tiles, so it only shows where the map is still unexplored. */
    @SerializedName("under") UNDER("Under map"),
    /** Drawn on top of Xaero's tiles, blended with the image's opacity. */
    @SerializedName("over") OVER("Over map");

    public final String displayName;

    DrawMode(String displayName) {
        this.displayName = displayName;
    }
}
