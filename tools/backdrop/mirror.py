"""
Mirroring a baked texture horizontally, albedo and normals together.

WHY THIS EXISTS AT ALL. The cliff art is a LEFT-hand cliff: solid stone on its u = 0
side, ragged alpha edge on its u = 1 side. The right-hand side of the column needs that
edge on its own inner side, i.e. mirrored.

`DiveRenderer` USED TO get the right-hand WALL out of the same file by drawing it
rotated 180 degrees, which is the one transform the engine applies to the geometry and
to the normal vectors together (`normal_map.vert` builds
`normalRotation = rotMatrix(rotation + cameraAngle)`).

That worked for the wall and CANNOT work for the cliff top, because 180 degrees is a
horizontal mirror AND a vertical flip, and a summit pointing downwards is not a summit.
There is no horizontal-only mirror available at the draw site: `drawNormalMap` takes no
uv arguments, so swapping uMin/uMax would mirror the albedo and leave the normals lit
from the wrong side of every bump, and a negative width flips the quad geometrically
while leaving `normalRotation` alone with the same result (plus reversed winding).

So the mirror happens HERE, in the bake, where both maps can be transformed correctly
and the result is checked before it ships.

AND IT IS NOW THE WALL'S ROUTE TOO, not just the summit's. The half turn also flipped v,
so the two sides ran their tiles in opposite directions and the seams did not line up.
Both walls and both crests are baked mirrors today and `DiveRenderer` draws all four at
ANGLE 0 - see `DiveRenderer.drawColumnWalls`, whose doc says so in as many words. Do not
reintroduce a rotated draw on the strength of the paragraph above; it is history.

THE NORMAL MAP IS NOT JUST FLIPPED. A tangent-space normal is stored as `(v + 1) / 2`,
so mirroring the image about x has to negate the vector's x component as well as move
the texel: a bump lit from the left must come out lit from the right. In eight-bit
storage that negation is exactly `255 - r`, with no rounding error at all —
`s' = 1 - r/255` decodes to `2*s' - 1 = -(2*r/255 - 1)`, which is `-n.x` exactly. Flip
the image and forget the negation and every bump on the right-hand cliff is lit from
the wrong side, which reads as bad art rather than as a bug.
"""
import numpy as np

# The stored byte value a channel of a unit normal is negated to. See the module doc:
# exact in eight bits, no rounding.
NORMAL_MIDPOINT_SUM = 255


def mirror_diffuse(rgba: np.ndarray) -> np.ndarray:
    """An albedo mirrored about its vertical axis. Colour is a scalar per texel; only position moves."""
    _check(rgba)
    return np.ascontiguousarray(rgba[:, ::-1, :])


def mirror_normal(rgba: np.ndarray) -> np.ndarray:
    """
    A tangent-space normal map mirrored about its vertical axis: texels move AND their
    x component is negated. y, z and alpha are untouched — a horizontal mirror leaves
    the surface's up-down slope and its facing alone.
    """
    _check(rgba)
    out = np.ascontiguousarray(rgba[:, ::-1, :])
    out[..., 0] = NORMAL_MIDPOINT_SUM - out[..., 0]
    return out


def decode(rgba: np.ndarray) -> np.ndarray:
    """The vectors a stored RGBA8 normal map hands the shader: `2 * (rgb/255) - 1`."""
    return (np.asarray(rgba, dtype=np.float64)[..., :3] / 255.0) * 2.0 - 1.0


def _check(rgba: np.ndarray) -> None:
    if rgba.ndim != 3 or rgba.shape[2] != 4:
        raise ValueError(f"expected an HxWx4 RGBA array, got shape {rgba.shape}")
    if rgba.dtype != np.uint8:
        raise ValueError(f"expected uint8, got {rgba.dtype} — the negation is only exact in eight bits")
