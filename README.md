# Xaero's Base Map

Put any image under your Xaero's World Map: an old map, a server map, a highway chart, whatever. Your own explored map draws on top, and the image fills in everywhere else.

Unofficial addon. You need Fabric, Fabric API and Xaero's World Map 1.45+ for Minecraft 1.21.11.

## How to use

1. Drop the jar in your `mods` folder and launch once.
2. Put a PNG or JPG in the new `.minecraft/basemaps` folder.
3. In game, press Esc and click **Base Map**. Turn the image on, then set where its centre is (default 0, 0) and how many blocks one pixel covers.

**Under** fills in behind your map. **Over** draws on top of it, with whatever transparency you like.

## Big maps

Images of any size work, even one block per pixel across tens of thousands of blocks. The first time you turn one on it gets cut into tiles in the background, then only the bit you're looking at is loaded. For really huge images use PNG.

## Building

`./gradlew build` on JDK 21. See `libs/README.md` for the Xaero jar the build needs.

MIT licence.
