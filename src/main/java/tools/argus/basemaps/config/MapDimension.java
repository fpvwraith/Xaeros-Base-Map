package tools.argus.basemaps.config;

import com.google.gson.annotations.SerializedName;

/** Which of Xaero's dimension maps an image belongs to. */
public enum MapDimension {
    @SerializedName("overworld") OVERWORLD("Overworld", "minecraft:overworld"),
    @SerializedName("nether") NETHER("Nether", "minecraft:the_nether"),
    @SerializedName("end") END("End", "minecraft:the_end");

    public final String displayName;
    /** The world registry key id this matches, e.g. {@code minecraft:the_nether}. */
    public final String worldId;

    MapDimension(String displayName, String worldId) {
        this.displayName = displayName;
        this.worldId = worldId;
    }

    public boolean matches(String worldKeyId) {
        return worldId.equals(worldKeyId);
    }

    /** From a manifest value: "overworld", "nether" or "end" (anything else -> overworld). */
    public static MapDimension byKey(String key) {
        return switch (key == null ? "" : key) {
            case "nether" -> NETHER;
            case "end" -> END;
            default -> OVERWORLD;
        };
    }
}
