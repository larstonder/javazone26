"""
Output sizes, and the two engine rules that decide them. Pure integer maths, no I/O.

RULE 1 - VRAM IS BUCKETED, SQUARE AND EAGER.
`TextureBank.getOrCreateTextureArrayFor` buckets on `max(width, height)` against
`DEFAULT_CAPACITIES`, and `TextureArray.init` calls
`glTexStorage3D(..., textureSize, textureSize, maxCapacity)` - a SQUARE array with
EVERY layer allocated up front. So a texture's cost is not `w*h*4`; it is either zero
(it lands in an array that already exists) or the whole array (it does not).

An array is REUSED only when all of format, filter, wrapping and maxMipLevels match
AND `max(w, h) > arraySize / 2` - the bytecode's own half-size test, which stops a
small texture from squatting in a large array. That inequality is strict, so a texture
whose largest side is exactly 1024 does NOT reuse the 2048 array. See `reuses_array`.

RULE 2 - A TEXTURE ONLY OWNS A CORNER OF ITS LAYER.
`TextureArray.upload` does `glTexSubImage3D(..., texture.width, texture.height, ...)`
into a `textureSize x textureSize` layer and sets `uMax = width/textureSize`,
`vMax = height/textureSize`. Everything past that is never written by anything. A
LINEAR sample taken just inside vMax reaches half a texel beyond it, into that
never-written region - and for a vertically tiled quad `v -> vMax` is EXACTLY the tile
seam. `height == arraySize` is what makes `vMax` exactly 1.0 and the seam clean; it is
the reason the rock is baked 2048 rows tall rather than at its native 889.
"""
import math

# TextureBank.DEFAULT_CAPACITIES, read off the engine bytecode: (texSize, capacity)
# per format. SRGBA8 and RGBA8 carry the same capacities at every size.
CAPACITIES = {128: 100, 256: 100, 512: 50, 1024: 50, 2048: 15, 4096: 10, 8192: 5}
BUCKETS = tuple(sorted(CAPACITIES))

BYTES_PER_TEXEL = 4


def bucket_for(size: int) -> int:
    """Smallest engine texture bucket that fits `size`."""
    for bucket in BUCKETS:
        if bucket >= size:
            return bucket
    raise ValueError(f"{size} px exceeds the largest texture bucket ({BUCKETS[-1]})")


def array_bytes(bucket: int) -> int:
    """VRAM a texture array of that bucket costs the instant its first layer arrives."""
    return bucket * bucket * BYTES_PER_TEXEL * CAPACITIES[bucket]


def reuses_array(max_dim: int, array_size: int) -> bool:
    """
    Does a texture whose largest side is `max_dim` land in an existing `array_size`
    array? The engine's test is `arraySize >= max_dim and max_dim > arraySize / 2`,
    with integer division - both halves matter, and the second is strict.
    """
    return array_size >= max_dim and max_dim > array_size // 2


def fit_height(src_w: int, src_h: int, height: int) -> tuple:
    """
    Scale to exactly `height` rows, keeping the source aspect. Width rounds to nearest
    so the texel grid stays as square as an integer width allows; the render side reads
    the committed width back out rather than assuming it.
    """
    if src_w < 1 or src_h < 1 or height < 1:
        raise ValueError(f"bad size {src_w}x{src_h} -> height {height}")
    return max(1, int(round(src_w * height / src_h))), height


def fit_max_dim(src_w: int, src_h: int, max_dim: int) -> tuple:
    """Scale so the LARGEST side is exactly `max_dim`, keeping the source aspect."""
    if src_w < 1 or src_h < 1 or max_dim < 1:
        raise ValueError(f"bad size {src_w}x{src_h} -> max {max_dim}")
    scale = max_dim / max(src_w, src_h)
    return max(1, int(round(src_w * scale))), max(1, int(round(src_h * scale)))


def texels_per_metre(texels: int, metres: float) -> float:
    """Sampling density of a baked axis once it is drawn at a world size."""
    if metres <= 0:
        raise ValueError(f"world size must be positive, got {metres}")
    return texels / metres


def tiles_across(span_metres: float, tile_metres: float) -> int:
    """
    Whole tiles needed to cover `span_metres`, never fewer than one and never
    fractional: a fractional count cuts the texture mid-feature, and `texture.frag`
    tiles with `fract(texCoord * tiling)` so the cut lands wherever the quad ends.
    """
    if tile_metres <= 0:
        raise ValueError(f"tile size must be positive, got {tile_metres}")
    return max(1, math.ceil(span_metres / tile_metres))
