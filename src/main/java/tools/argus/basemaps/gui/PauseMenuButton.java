package tools.argus.basemaps.gui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Adds a "Basemaps" button to the Escape menu without colliding with other mods' extra buttons (e.g. Basilisk's
 * "Atlas", which also goes below the last button). The slot is chosen from the bounds of every other widget at
 * the end of init, and re-checked before every render, so buttons added later by other mods (their own
 * AFTER_INIT handlers or late TAIL injections) push ours to the next free slot instead of overlapping it.
 */
public final class PauseMenuButton {
    private static final int WIDTH = 98, HEIGHT = 20;

    private PauseMenuButton() { }

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof GameMenuScreen menu) || !menu.shouldShowMenu()) return;
            ButtonWidget button = ButtonWidget.builder(Text.literal("Base Map"), b -> client.setScreen(new BasemapsScreen(screen)))
                .dimensions(0, 0, WIDTH, HEIGHT)
                .build();
            place(screen, button);
            Screens.getButtons(screen).add(button);
            // per-screen event; Fabric resets these on every re-init, so this never piles up
            ScreenEvents.beforeRender(screen).register((s, context, mouseX, mouseY, delta) -> {
                if (!ButtonSlots.isFree(rect(button), s.width, s.height, others(s, button))) place(s, button);
            });
        });
    }

    private static void place(Screen screen, ButtonWidget button) {
        ButtonSlots.Rect r = ButtonSlots.pick(screen.width, screen.height, WIDTH, HEIGHT, others(screen, button));
        button.setPosition(r.x(), r.y());
    }

    private static List<ButtonSlots.Rect> others(Screen screen, ClickableWidget self) {
        List<ButtonSlots.Rect> out = new ArrayList<>();
        for (ClickableWidget w : Screens.getButtons(screen)) {
            if (w == self || !w.visible) continue;
            out.add(rect(w));
        }
        return out;
    }

    private static ButtonSlots.Rect rect(ClickableWidget w) {
        return new ButtonSlots.Rect(w.getX(), w.getY(), w.getWidth(), w.getHeight());
    }
}
