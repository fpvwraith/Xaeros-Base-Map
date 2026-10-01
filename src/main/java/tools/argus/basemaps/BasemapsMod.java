package tools.argus.basemaps;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import tools.argus.basemaps.gui.PauseMenuButton;

/** Client entrypoint. No network access anywhere in this mod: it only reads the local basemaps folder. */
public final class BasemapsMod implements ClientModInitializer {
    public static final String MOD_ID = "basemaps";

    @Override
    public void onInitializeClient() {
        BasemapManager.INSTANCE.init(FabricLoader.getInstance().getGameDir().resolve("basemaps"));
        PauseMenuButton.register();
        // free all image textures when leaving a world/server (GPU work must happen on the render thread)
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> client.execute(BasemapManager.INSTANCE::releaseAll));
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> BasemapManager.INSTANCE.shutdown());
    }
}
