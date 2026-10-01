package tools.argus.basemaps.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;
import tools.argus.basemaps.BasemapEntry;
import tools.argus.basemaps.BasemapManager;
import tools.argus.basemaps.config.DrawMode;
import tools.argus.basemaps.config.ImageSettings;
import tools.argus.basemaps.config.MapDimension;
import tools.argus.basemaps.image.ImageLoader;
import tools.argus.basemaps.math.Placement;
import tools.argus.basemaps.pack.PackManifest;
import tools.argus.basemaps.pack.Progress;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalDouble;
import java.util.function.DoubleConsumer;
import java.util.function.Function;

/** Lists the sources in the basemaps folder and edits their settings. Plain vanilla widgets. */
public final class BasemapsScreen extends Screen {
    private static final int WHITE = 0xFFFFFFFF, GREY = 0xFFA0A0A0, RED = 0xFFFF5555, GREEN = 0xFF55FF55,
        YELLOW = 0xFFFFFF55, FIELD_OK = 0xFFE0E0E0;
    private static final int MARGIN = 8, TOP = 26, ROW = 24;

    private static String lastSelected;

    private final Screen parent;
    private final BasemapManager manager = BasemapManager.INSTANCE;
    private boolean scanned;
    private boolean dirty;
    private int listOffset;
    private BasemapEntry selected;

    private int listX, listW, editorX, editorW, labelW, infoY;
    private final List<Label> labels = new ArrayList<>();

    private record Label(String text, int x, int y) { }

    private record Line(String text, int color) { }

    public BasemapsScreen(Screen parent) {
        super(Component.literal("Xaero's Base Map"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        if (!scanned) {
            manager.scan();   // pick up files dropped in since the last visit
            scanned = true;
        }
        labels.clear();
        List<BasemapEntry> entries = manager.entries();
        selected = null;
        for (BasemapEntry e : entries) if (e.name.equals(lastSelected)) selected = e;
        if (selected == null && !entries.isEmpty()) selected = entries.get(0);
        lastSelected = selected == null ? null : selected.name;

        listX = MARGIN;
        listW = Math.max(90, Math.min(170, width / 3));
        editorX = listX + listW + 10;
        editorW = Math.max(120, Math.min(300, width - editorX - MARGIN));
        labelW = Math.min(100, editorW / 2 - 4);
        int buttonsY = height - 26;

        int rows = Math.max(1, (buttonsY - 6 - TOP - 22 - 12) / 22);
        if (listOffset > Math.max(0, entries.size() - rows)) listOffset = Math.max(0, entries.size() - rows);
        for (int i = 0; i < rows && listOffset + i < entries.size(); i++) {
            BasemapEntry e = entries.get(listOffset + i);
            String name = (e.settings.enabled ? "● " : "○ ") + (e.isPack() ? "[pack] " : "") + e.name;
            Button b = Button.builder(Component.literal(font.plainSubstrByWidth(name, listW - 10)), btn -> select(e))
                .bounds(listX, TOP + i * 22, listW, 20).build();
            b.active = e != selected;
            addRenderableWidget(b);
        }
        if (entries.size() > rows) {
            int half = (listW - 4) / 2, y = TOP + rows * 22;
            Button up = Button.builder(Component.literal("▲"), b -> { listOffset = Math.max(0, listOffset - rows); rebuildWidgets(); })
                .bounds(listX, y, half, 20).build();
            Button down = Button.builder(Component.literal("▼"), b -> { listOffset += rows; rebuildWidgets(); })
                .bounds(listX + half + 4, y, half, 20).build();
            up.active = listOffset > 0;
            down.active = listOffset + rows < entries.size();
            addRenderableWidget(up);
            addRenderableWidget(down);
        }

        if (selected != null) initEditor(selected);

        int bw = Math.min(100, (width - 2 * MARGIN - 8) / 3), bx = width / 2 - (3 * bw + 8) / 2;
        addRenderableWidget(Button.builder(Component.literal("Open folder"), b -> {
            if (manager.dir() != null) Util.getPlatform().openPath(manager.dir());
        }).bounds(bx, buttonsY, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Reload"), b -> {
            saveIfDirty();
            manager.reloadAll();
            rebuildWidgets();
        }).bounds(bx + bw + 4, buttonsY, bw, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose())
            .bounds(bx + 2 * (bw + 4), buttonsY, bw, 20).build());
    }

    private void initEditor(BasemapEntry e) {
        ImageSettings s = e.settings;
        int y = TOP;

        addRenderableWidget(CycleButton.onOffBuilder(s.enabled)
            .create(editorX, y, editorW, 20, Component.literal("Show on map"), (btn, value) -> {
                s.enabled = value;
                manager.settingsChanged(e);
                rebuildWidgets();
            }));
        y += ROW;

        if (e.isPack()) {
            numberField("Offset X", y, s.offsetX, ImageSettings::parseCoordinate, v -> s.offsetX = v);
            y += ROW;
            numberField("Offset Z", y, s.offsetZ, ImageSettings::parseCoordinate, v -> s.offsetZ = v);
            y += ROW;
        } else {
            numberField("Centre X", y, s.centerX, ImageSettings::parseCoordinate, v -> s.centerX = v);
            y += ROW;
            numberField("Centre Z", y, s.centerZ, ImageSettings::parseCoordinate, v -> s.centerZ = v);
            y += ROW;
            numberField("Blocks per pixel", y, s.blocksPerPixel, ImageSettings::parseBlocksPerPixel, v -> s.blocksPerPixel = v);
            y += ROW;
        }

        addRenderableWidget(new OpacitySlider(editorX, y, editorW, 20, s));
        y += ROW;

        int half = (editorW - 4) / 2;
        addRenderableWidget(CycleButton.<MapDimension>builder(d -> Component.literal(d.displayName), s.dimension)
            .withValues(MapDimension.values())
            .create(editorX, y, half, 20, Component.literal("Dim"), (btn, value) -> {
                s.dimension = value;
                manager.settingsChanged(e);
            }));
        addRenderableWidget(CycleButton.<DrawMode>builder(m -> Component.literal(m.displayName), s.mode)
            .withValues(DrawMode.values())
            .create(editorX + half + 4, y, editorW - half - 4, 20, Component.literal("Draw"), (btn, value) -> {
                s.mode = value;
                manager.settingsChanged(e);
            }));
        y += ROW;
        infoY = y + 2;
    }

    private void numberField(String label, int y, double value, Function<String, OptionalDouble> parser, DoubleConsumer apply) {
        labels.add(new Label(label, editorX, y + 6));
        EditBox field = new EditBox(font, editorX + labelW, y, editorW - labelW, 20, Component.literal(label));
        field.setMaxLength(24);
        field.setValue(ImageSettings.format(value));
        field.setResponder(text -> {
            OptionalDouble v = parser.apply(text);
            if (v.isPresent()) {
                field.setTextColor(FIELD_OK);
                apply.accept(v.getAsDouble());
                dirty = true;
            } else {
                field.setTextColor(RED);   // rejected: keep the last valid value
            }
        });
        addRenderableWidget(field);
    }

    private void select(BasemapEntry e) {
        saveIfDirty();
        lastSelected = e.name;
        rebuildWidgets();
    }

    private void saveIfDirty() {
        if (dirty) {
            manager.save();
            dirty = false;
        }
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        context.centeredText(font, title, width / 2, 9, WHITE);
        for (Label l : labels) context.text(font, l.text, l.x, l.y, WHITE, true);

        if (manager.entries().isEmpty()) {
            int x = listX, y = TOP;
            context.text(font, "Nothing here yet.", x, y, WHITE, true);
            y += 12;
            for (String line : new String[] {
                "Put .png / .jpg images, tile-pack folders or tile-pack .zip files in the basemaps folder",
                "(\"Open folder\" below), then press Reload.",
                "Folder: " + (manager.dir() == null ? "?" : manager.dir().toAbsolutePath())}) {
                for (var part : font.split(Component.literal(line), width - 2 * MARGIN)) {
                    context.text(font, part, x, y, GREY, true);
                    y += 10;
                }
            }
        } else if (selected != null) {
            renderInfo(context, selected);
        }

        String mem = String.format(Locale.ROOT, "Textures: %d / %d MB (%d tiles)", manager.textureBytes() >> 20,
            manager.config().textureBudgetMB, manager.textureTiles());
        context.text(font, mem, MARGIN, height - 48, GREY, true);
        String hook = manager.hookSeen() ? "Xaero map hook: active" : "Xaero map hook: not called yet - open the world map";
        context.text(font, font.plainSubstrByWidth(hook, width - 2 * MARGIN), MARGIN, height - 38,
            manager.hookSeen() ? GREEN : YELLOW, true);
    }

    private void renderInfo(GuiGraphicsExtractor context, BasemapEntry e) {
        ImageSettings s = e.settings;
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(e.name, WHITE));
        PackManifest m = e.manifest();
        if (e.isPack()) {
            if (m != null) {
                lines.add(new Line("Pack: " + m.widthPx() + " x " + m.heightPx() + " px, " + m.levels() + " levels, "
                    + ImageSettings.format(m.blocksPerPixel()) + " block/px", GREY));
                double ox = m.originX() + s.offsetX, oz = m.originZ() + s.offsetZ;
                lines.add(new Line(coverage(ox, oz, ox + m.widthPx() * m.blocksPerPixel(), oz + m.heightPx() * m.blocksPerPixel()), GREY));
            }
        } else {
            ImageLoader.Probe probe = e.probe();
            if (probe != null) {
                lines.add(new Line("Image: " + probe.width() + " x " + probe.height() + " px (" + probe.format() + ")", GREY));
                Placement p = Placement.of(probe.width(), probe.height(), s.centerX, s.centerZ, s.blocksPerPixel);
                lines.add(new Line(coverage(p.minX(), p.minZ(), p.maxX(), p.maxZ()), GREY));
            }
        }
        if (e.warning() != null) lines.add(new Line(e.warning(), YELLOW));
        switch (e.state()) {
            case IDLE -> lines.add(new Line(!s.enabled ? "Off"
                : e.isPack() || m != null ? "Loads when the world map shows it" : "Will be sliced into tiles when shown", GREY));
            case SLICING -> {
                Progress p = e.slicing();
                lines.add(new Line("Slicing into tiles: " + (p == null ? "" : p.percent() + "% (" + p.phase() + ")"), YELLOW));
            }
            case READY -> lines.add(new Line("Ready" + (e.isPack() ? "" : " (sliced, cached in basemaps/.cache)"), GREEN));
            case FAILED -> lines.add(new Line(e.error() == null ? "Failed" : e.error(), RED));
        }
        int y = infoY;
        for (Line l : lines) {
            for (var part : font.split(Component.literal(l.text), editorW)) {
                if (y > height - 60) return;
                context.text(font, part, editorX, y, l.color, true);
                y += 10;
            }
        }
    }

    private static String coverage(double minX, double minZ, double maxX, double maxZ) {
        return "X " + ImageSettings.format(Math.floor(minX)) + " .. " + ImageSettings.format(Math.ceil(maxX))
            + ",  Z " + ImageSettings.format(Math.floor(minZ)) + " .. " + ImageSettings.format(Math.ceil(maxZ));
    }

    @Override
    public void removed() {
        saveIfDirty();
    }

    @Override
    public void onClose() {
        saveIfDirty();
        minecraft.gui.setScreen(parent);
    }

    private final class OpacitySlider extends AbstractSliderButton {
        private final ImageSettings settings;

        OpacitySlider(int x, int y, int w, int h, ImageSettings settings) {
            super(x, y, w, h, Component.empty(), settings.opacity / 100.0);
            this.settings = settings;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            setMessage(Component.literal("Opacity: " + (int) Math.round(value * 100) + "%"));
        }

        @Override
        protected void applyValue() {
            settings.opacity = (int) Math.round(value * 100);
            dirty = true;
        }
    }
}
