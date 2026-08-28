"""
The sandbank bake's swizzle, and the reflectance gate that stands behind it.

Every array here is SYNTHETIC. `assets/` is gitignored, so a test that opened the real
source art would be red on a clean clone - which is the rule every other file in this
directory already follows.
"""
import pathlib

import numpy as np
import pytest
from PIL import Image

from build_backdrop import SourceError, bake_sandbank, swizzle_yz
from spritesheet.colour import linear_to_srgb, srgb_to_linear, to_u8
from spritesheet.resample import decode_normals


def encode_normals(vectors: np.ndarray) -> np.ndarray:
    """The inverse of `decode_normals`: unit vectors -> sRGB-encoded uint8, as the artist ships them."""
    return to_u8(linear_to_srgb((np.asarray(vectors, dtype=np.float64) + 1.0) * 0.5))


def test_the_swizzle_turns_a_y_up_source_z_dominant():
    # assets/sandbank/normal.png measures a decoded mean of (-0.008, +0.900, +0.381):
    # Y-DOMINANT, which is what a seabed rendered TOP-DOWN gives. Our engine's normal
    # maps are tangent-space with +Z out of the screen (both shipped rock maps are
    # Z-dominant), so shipping this unchanged lights the seabed like a wall.
    v = np.array([-0.008, 0.900, 0.381])
    v = v / np.linalg.norm(v)
    encoded = encode_normals(np.tile(v, (8, 8, 1)))

    out = swizzle_yz(decode_normals(encoded))
    mean = out.reshape(-1, 3).mean(axis=0)

    assert abs(mean[2]) > abs(mean[0]), f"z must dominate x, got {mean}"
    assert abs(mean[2]) > abs(mean[1]), f"z must dominate y, got {mean}"
    assert mean[1] > 0.0, f"a seabed's surfaces face UP, so mean y must be positive, got {mean}"


def test_the_swizzle_preserves_unit_length():
    # A permutation cannot change a norm. Asserted over random unit vectors so that a
    # future "improvement" which SCALES instead of permuting fails here.
    rng = np.random.default_rng(3)
    v = rng.normal(size=(2000, 3))
    v /= np.linalg.norm(v, axis=-1, keepdims=True)

    out = swizzle_yz(v)

    assert np.allclose(np.linalg.norm(out, axis=-1), 1.0, atol=1e-12)


def test_the_swizzle_does_not_flip_the_sign_of_up():
    # +y = up / shallower is this project's settled convention - iridescence.frag:258,
    # and PearlNormalMap.kt:174-214's two-build capture probe at EPT_DEPTH=140. Getting
    # the sign backwards runs the light the wrong way across every dune, which reads as
    # bad art rather than as a bug. This is the assertion that would have caught the
    # alternative (and WRONG) reading of radiance_cascades.frag.
    up = np.array([0.0, 0.0, 1.0])          # a floor rendered top-down: +Y in world, +Z here
    swizzled = swizzle_yz(np.array([up]))

    assert swizzled[0][1] == pytest.approx(1.0), "an up-facing source surface must stay +y after the swizzle"


def _write_synthetic_source(directory: pathlib.Path, rgb: tuple, size=(2000, 500)) -> None:
    w, h = size
    v = np.array([-0.008, 0.900, 0.381])
    v = v / np.linalg.norm(v)
    normal = np.dstack([
        encode_normals(np.tile(v, (h, w, 1))),
        np.full((h, w), 255, dtype=np.uint8),
    ])
    diffuse = np.dstack([
        np.tile(np.array(rgb, dtype=np.uint8), (h, w, 1)),
        np.full((h, w), 255, dtype=np.uint8),
    ])
    directory.mkdir(parents=True, exist_ok=True)
    Image.fromarray(diffuse, mode="RGBA").save(directory / "diffuse.png")
    Image.fromarray(normal, mode="RGBA").save(directory / "normal.png")


def test_the_bake_is_byte_reproducible(tmp_path, monkeypatch):
    # Same regression test the diver's bake has, and the reason write_png is
    # deterministic (compress_level=9, optimize=False, metadata sorted): re-running an
    # unchanged bake must produce no git diff at all. Compared as WRITTEN PNG BYTES, not
    # as arrays - identical arrays through a non-deterministic writer would still churn
    # the committed files, which is the failure this is here to catch.
    import build_backdrop

    src = tmp_path / "sandbank"
    _write_synthetic_source(src, (177, 170, 159))
    monkeypatch.setattr(build_backdrop, "SANDBANK_DIR", src)

    ambient = 0.5 * srgb_to_linear(np.array((0.22, 0.20, 0.18)))
    meta = {"ept:sandbank_size": "2000x500", "ept:source_sha256": "0123456789abcdef"}

    written = []
    for run in ("a", "b"):
        baked = bake_sandbank(0.6, ambient)
        out = tmp_path / run
        for key, name in build_backdrop.SANDBANK_OUT.items():
            build_backdrop.write_png(out / name, baked[key], meta)
        written.append({name: (out / name).read_bytes() for name in build_backdrop.SANDBANK_OUT.values()})

    assert written[0] == written[1], "a second bake of unchanged sources must be byte-identical"


def test_the_reflectance_gate_bites_on_a_near_black_source(tmp_path, monkeypatch):
    # texture_multiply_blend.frag REPLACES a sub-floor albedo with flat vec3(0.02) grey -
    # not darkens it, FLATTENS it, which is how 0f07303 got a razor-sharp seam out of a
    # colour that merely sat near the boundary. The gate must stop the bake, not ship it.
    import build_backdrop

    src = tmp_path / "sandbank"
    _write_synthetic_source(src, (1, 1, 1))
    monkeypatch.setattr(build_backdrop, "SANDBANK_DIR", src)

    with pytest.raises(SourceError) as caught:
        bake_sandbank(0.0, np.zeros(3))

    assert "SAND" in str(caught.value), "the message must say SAND, or a technician goes looking at the cliff"
    assert "AMBIENT_FRACTION" in str(caught.value)
