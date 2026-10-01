# Basemaps

A small client-side Fabric mod for Minecraft 1.21.11 that puts your own maps on **Xaero's World Map**,
lined up with world coordinates. The maps can be huge: a whole server at 1 block per pixel
(60000 x 60000 for +-30k, 140000 x 140000 for +-70k). Only the part on screen is loaded, at the
detail your zoom needs, so memory use stays flat however big the map is.

**Needs:** Fabric Loader, Fabric API, Xaero's World Map 1.45.x. XaeroPlus is optional and works alongside it.
Meteor is **not** needed. The mod never uses the network. It only reads files in its own folder.

## The folder

On first launch the mod creates a folder called `basemaps` in your game directory, for example
`.minecraft/basemaps/` or `<Prism instance>/minecraft/basemaps/`. Three kinds of source go in it:

| What | Example | Notes |
|---|---|---|
| A single image (`.png`, `.jpg`, `.jpeg`) | `server-map.png` | Cut into tiles once, in the background, the first time you switch it on (see below) |
| A **tile pack folder** | `argus-30k-1to1/` containing `basemap.json` | Read directly, nothing to prepare |
| A **tile pack zip** | `argus-30k-1to1.zip` | Read in place. It is never extracted |

Settings are saved in `basemaps/basemaps.json`, with one entry per file or folder name.
`basemaps/.cache/` holds the tiles cut from single images. You can delete it at any time, and images
are cut again when needed.

## Using it

1. Press **Esc** and click **Basemaps**. It goes below the pause-menu buttons, or into the next free spot
   (beside them, then a corner) if another mod's button is already there.
2. Your sources are listed on the left (`[pack]` marks tile packs). **New sources start switched off.**
   Pick one and set **Show on map** to ON.
3. For an image, set the centre and the scale. A pack is already placed by its own `basemap.json`.
   Then open the world map.

The bottom of the screen shows how much texture memory is in use, and whether the Xaero hook is active.
It shows as active once you have opened the world map at least once. **Open folder** opens the
folder. **Reload** re-reads the folder and `basemaps.json`.

| Setting | Meaning |
|---|---|
| Show on map | on/off |
| Centre X / Centre Z (images) | the world block coordinate at the **centre** of the image (default 0, 0) |
| Blocks per pixel (images) | how many blocks wide one image pixel is (default 1). Fractions like `0.5` are fine |
| Offset X / Offset Z (packs) | blocks to shift the pack from where its manifest puts it (default 0) |
| Opacity | 0-100 % |
| Dim | Overworld / Nether / End. The map only shows on that dimension's map (packs default to their manifest) |
| Draw | **Under map** or **Over map** (see below) |

A number field turns red when you type something invalid. The old value is kept until the input is valid.

### Placement of single images

The image is centred on (Centre X, Centre Z), and each pixel covers *blocks per pixel* blocks. The top of
the image is north (-Z) and the left is west (-X), the same way up as Xaero's map.

**Worked example.** A 7500 x 7500 image of -30,000 .. +30,000: the area is 60,000 blocks across, and
60,000 / 7500 = **8 blocks per pixel**. The middle of the area is 0,0, so **Centre 0, 0**. The screen
shows the covered range under the settings (here X -30000 .. 30000, Z -30000 .. 30000). A 60000 x 60000
image of the same area is **1 block per pixel**, still with Centre 0, 0.

### Draw modes

- **Under map**: drawn before Xaero's map tiles. It only shows where you **haven't explored**, including the
  unexplored parts of regions you've partly flown through.
  - **How:** Xaero normally builds its zoomed-out tiles on an opaque black background, which used to
    show as black boxes over the basemap. Basemaps makes that background transparent instead.
  - **Cache:** Basemaps stores those zoomed-out tiles in a separate `cache-basemaps` folder next to
    Xaero's own `cache` folder. Xaero rebuilds them from its saved map the first time you view each
    area, and its original cache is left untouched.
- **Over map**: drawn on top of Xaero's map tiles at the chosen opacity. Waypoints, the player arrow and
  chunk highlights (including XaeroPlus highlights) still draw on top of it.

When sources overlap in the same mode, they are drawn in name order, so later names end up on top.

## How big maps stay fast

- Every source is a **tile pyramid**. Level 0 is full resolution in 256 x 256 tiles. Each further level
  halves the image, until the whole image fits in one tile.
- **Level choice:** the mod picks the coarsest level whose pixels are still no bigger than one screen
  pixel. Zoomed in past 1:1 it uses level 0, and pixels show as crisp squares (nearest-neighbour).
- **What gets loaded:** only tiles that touch the visible part of the map. They are decoded on
  background threads and uploaded a few per frame.
- **No holes while loading:** while a sharp tile is still loading, the nearest coarser tile that is
  already loaded is stretched over its area. Coarse tiles load first, so the map never has holes and
  sharpens as you watch.
- **Memory:** textures are capped at `textureBudgetMB` in `basemaps.json` (default **256 MB**, about
  1000 tiles). Tiles that haven't been used for the longest time are freed first. Decoded tiles waiting
  to upload are limited to under 100 at a time (about 25 MB). Memory use does not depend on how big
  the source is.

## Single images: slicing

The first time you switch on an image, it is cut **once**, in the background, into
`basemaps/.cache/<file>-<size>-<modified time>/`. That folder uses the same layout as a tile pack. The
Basemaps screen shows progress, and the map appears when it finishes. The image is only cut again if the
file changes, and old cache folders for the same file are deleted.

- **PNG (non-interlaced), any size.** Streamed top to bottom, one band of 256 rows at a time. Memory is
  one band: width x 256 x 4 bytes, about 61 MB for 60000 px wide or 143 MB for 140000 px. This also works
  above Java's 2.1-gigapixel limit (e.g. 60000 x 60000).
- **Up to about 80 megapixels, any format** (for example a 7500 x 7500 JPEG). Read in one go and cut in
  seconds.
- **Bigger JPEGs, up to 2.1 gigapixels** (for example 40000 x 40000). Read in bands. Java's JPEG decoder
  has to start again from the top for every band, so this is **slow** (minutes to much longer), and the
  screen warns you.
- **JPEGs over 2.1 gigapixels cannot be read by Java at all.** The screen says to convert them to PNG or
  make a tile pack. The same goes for interlaced PNGs that big.

## Tile packs

A pack is a folder (or a `.zip` of that folder) containing `basemap.json` and tiles at `L/x/y.png`, where
`L` = 0 is full resolution and `x`, `y` count tiles from the top-left. Missing tiles are transparent.

```json
{"format":"basemaps-pack-1","tileSize":256,"blocksPerPixelAtLevel0":1,
 "originX":-30208,"originZ":-30208,"widthPx":60416,"heightPx":60416,"levels":9,
 "dimension":"overworld","tileExt":"png"}
```

`originX` / `originZ` are the world X of the left edge and the world Z of the top edge of level-0 pixel 0,0.
Level `L` is `ceil(width / 2^L)` pixels wide. Edge tiles may be smaller than `tileSize`. Zips should be
*stored* (not compressed), because the PNGs are already compressed. They are read in place either way.

### Making packs: the converter

The project includes a standalone converter. It doesn't need Minecraft, uses all CPU cores, and keeps
memory low by streaming.

```
./gradlew pack --args="<command> ..."

pack     <image.png|jpg> <out-folder | out.zip> [--bpp 1] [--center X,Z] [--tile 256] [--dimension overworld|nether|end]
webtiles <tiles-folder>  <out-folder | out.zip> --range MIN,MAX [--range-z MIN,MAX] [--dimension ...]
zip      <pack-folder>   <out.zip>
common:  --threads N
```

- `pack` cuts one big image, the same way the in-game slicer does.
- `webtiles` takes a folder of web-map tiles `<x>/<y>.png` at 1 block per pixel. Tile (x, y) covers
  world X `[x*256, x*256+256)` and Z `[y*256, y*256+256)`. The pack grid lines up with those tiles, so
  tiles fully inside `--range` are **copied byte for byte**. Tiles on the edge are cropped (pixels
  outside the range become transparent), and coarser levels are built by shrinking 2x2 children.
  Example:
  `./gradlew pack --args="webtiles path/to/tiles path/to/my-pack --range -30000,30000"`
- Output ending in `.zip` gives a store-only zip.

## basemaps.json

```json
{
  "textureBudgetMB": 256,
  "images": {
    "argus-30k-1to1": { "enabled": true, "offsetX": 0.0, "offsetZ": 0.0, "opacity": 100,
                        "dimension": "overworld", "mode": "under" },
    "server-map.png": { "enabled": false, "centerX": 0.0, "centerZ": 0.0, "blocksPerPixel": 8.0,
                        "opacity": 70, "dimension": "overworld", "mode": "over" }
  }
}
```

`textureBudgetMB` ranges from 32 to 4096. If the file is not valid JSON, it is renamed to
`basemaps.json.broken` and the mod starts again from defaults. Broken images or packs are skipped,
and the reason appears in red on the Basemaps screen and in the log.

## Building

```
export JAVA_HOME="/c/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot"
./gradlew build --no-daemon
```

This gives `build/libs/basemaps-<version>.jar` and runs the unit tests. The build compiles against the
Yarn-remapped Xaero World Map jar in `libs/`. That jar is compile-only and is not bundled.

## License

MIT, see [LICENSE](LICENSE). Basemaps is not affiliated with Xaero; it needs Xaero's World Map installed.
