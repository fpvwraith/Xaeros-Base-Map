package tools.argus.basemaps.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a "Base Map" button to the Escape menu without colliding with other mods' extra buttons (e.g. Basilisk's
 * "Atlas", which also goes below the last button). The slot is chosen from the bounds of every other widget at
 * the end of init, and re-checked before every frame, so buttons added later by other mods (their own
 * AFTER_INIT handlers or late TAIL injections) push ours to the next free slot instead of overlapping it.
 */
public final class PauseMenuButton {
    private static final int WIDTH = 98, HEIGHT = 20;

    private PauseMenuButton() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PauseScreen menu) || !menu.showsPauseMenu()) return;
            Button button = Button.builder(Component.literal("Base Map"), b -> client.gui.setScreen(new BasemapsScreen(screen)))
                .bounds(0, 0, WIDTH, HEIGHT)
                .build();
            place(screen, button);
            Screens.getWidgets(screen).add(button);
            // per-screen event; Fabric resets these on every re-init, so this never piles up
            ScreenEvents.beforeExtract(screen).register((s, graphics, mouseX, mouseY, delta) -> {
                if (!ButtonSlots.isFree(rect(button), s.width, s.height, others(s, button))) place(s, button);
            });
        });
    }

    private static void place(Screen screen, Button button) {
        ButtonSlots.Rect r = ButtonSlots.pick(screen.width, screen.height, WIDTH, HEIGHT, others(screen, button));
        button.setPosition(r.x(), r.y());
    }

    private static List<ButtonSlots.Rect> others(Screen screen, AbstractWidget self) {
        List<ButtonSlots.Rect> out = new ArrayList<>();
        for (AbstractWidget w : Screens.getWidgets(screen)) {
            if (w == self || !w.visible) continue;
            out.add(rect(w));
        }
        return out;
    }

    private static ButtonSlots.Rect rect(AbstractWidget w) {
        return new ButtonSlots.Rect(w.getX(), w.getY(), w.getWidth(), w.getHeight());
    }
}
