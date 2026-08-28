"""
What a sprite set IS, declaratively.

The bake was written for the diver, whose source art is a matched pair of directories
(diffuse + normals) exported together from Blender. That shape was baked into the
orchestrator as two module-level constants and two hard-coded output names, which made
a second sprite set a rewrite rather than a data edit.

The generalisation is deliberately narrow: a set is an ORDERED LIST OF LAYERS over one
shared frame index, and everything the bake does per-layer is decided by `Layer.kind`.
Two properties of the diver that looked structural are actually per-set data:

  - **A set need not have both layers.** `assets/oxygen_sprites/` is normals ONLY - the
    blob's colour comes from `shaders/iridescence.frag` at draw time, so a diffuse sheet
    would be dead weight in a 15-layer texture array. The pairing and alpha-agreement
    gates therefore only apply to sets with two or more layers; see `bake`.

  - **A duplicate tail frame is not universal.** The diver's frame 42 re-exports frame 1
    (design spec S2), so `dropped_tail_frames = 1`. The oxygen loop's last frame is a
    real frame - measured, mean |delta| 1.75 against frame 1, and a 96->1 centroid step
    of 5.07 px against a 5.83 px mean - so dropping it would put a visible hitch in the
    loop. It is 0 there, and the flag that overrides it is a no-op rather than a lie.

Adding a set is adding an entry to `build_spritesheet.SPRITE_SETS`. Nothing else.
"""
from typing import NamedTuple

# Layer kinds. The kind is not cosmetic - it selects the resampling pipeline (sRGB
# round-trip vs. decode/renormalise/re-encode), the texture format the printed Kotlin
# claims, and which validation gates run. See `spritesheet.resample`.
DIFFUSE = "diffuse"
NORMAL = "normal"

# TextureFormat the engine must be told, per kind. A diffuse sheet stores sRGB-encoded
# bytes and wants the GPU to linearise them on sample; a normal sheet stores (v+1)/2
# LINEARLY and must be handed to the shader unchanged. Getting this backwards produces
# a plausible-looking image and wrong lighting - see resample.resample_normal.
TEXTURE_FORMAT = {DIFFUSE: "SRGBA8", NORMAL: "RGBA8"}


class Layer(NamedTuple):
    """One source directory and the one sheet it bakes into."""

    kind: str            # DIFFUSE or NORMAL - selects pipeline, format and gates
    source_dir: object   # pathlib.Path of *.png frames, sorted by name
    output_name: str     # file written into OUT_DIR, e.g. "diver-normal.png"
    asset_name: str      # the engine asset id, e.g. "diver_normal"

    @property
    def texture_format(self) -> str:
        return TEXTURE_FORMAT[self.kind]


class SpriteSet(NamedTuple):
    """A named group of layers baked together onto one shared grid."""

    name: str                  # CLI selector and QA subdirectory
    layers: tuple              # tuple[Layer, ...], at least one
    frame_height: int          # default cell height; --frame-height overrides
    dropped_tail_frames: int   # exported duplicates to drop; --keep-last-frame overrides
    frame_count_constant: str  # Kotlin constant name, e.g. "DIVER_FRAME_COUNT"

    def layers_of(self, kind: str) -> list:
        return [layer for layer in self.layers if layer.kind == kind]

    @property
    def bbox_layer(self) -> Layer:
        """
        The layer whose alpha defines the union content box for the WHOLE set.

        A normal layer is preferred, and for the diver that is not a stylistic choice:
        Pillow has no 16-bit RGBA path and truncates the 16-bit diffuse to its high
        byte, zeroing very low alpha, which yields a box one pixel narrow (628 vs 629).
        The normals are true 8-bit and read exactly. A normals-only set trivially uses
        its own; a hypothetical diffuse-only set falls back to the first layer, which is
        the best available rather than the correct one - note it if such a set is added.
        """
        normals = self.layers_of(NORMAL)
        return normals[0] if normals else self.layers[0]
