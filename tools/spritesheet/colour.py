"""
Colour transfer functions and float-array resizing.

Resampling happens in LINEAR light, never in sRGB space: averaging gamma-encoded
values darkens the result, and the diver downscales 5.16x, so the error is not
subtle. Both source sets carry sRGB + gAMA chunks, so both must be linearized
before any arithmetic touches them.
"""
import numpy as np
from PIL import Image


def srgb_to_linear(c: np.ndarray) -> np.ndarray:
    """IEC 61966-2-1 sRGB -> linear. Input and output in [0,1]."""
    c = np.asarray(c, dtype=np.float64)
    return np.where(c <= 0.04045, c / 12.92, ((c + 0.055) / 1.055) ** 2.4)


def linear_to_srgb(c: np.ndarray) -> np.ndarray:
    """Inverse of `srgb_to_linear`. Input and output in [0,1]."""
    c = np.clip(np.asarray(c, dtype=np.float64), 0.0, None)
    return np.where(c <= 0.0031308, c * 12.92, 1.055 * np.power(c, 1.0 / 2.4) - 0.055)


def resize_plane(plane: np.ndarray, size: tuple) -> np.ndarray:
    """
    Resize one 2-D float plane. `size` is (width, height), matching Pillow.

    LANCZOS because the bake is a large downscale and box filtering would alias the
    fins; it rings slightly, so every caller clips afterwards.
    """
    img = Image.fromarray(np.asarray(plane, dtype=np.float32), mode="F")
    return np.asarray(img.resize(size, Image.LANCZOS), dtype=np.float32)


def resize_channels(arr: np.ndarray, size: tuple) -> np.ndarray:
    """Resize an HxWxC float array plane by plane."""
    arr = np.asarray(arr, dtype=np.float32)
    return np.stack(
        [resize_plane(arr[..., i], size) for i in range(arr.shape[-1])],
        axis=-1,
    )


def to_u8(arr: np.ndarray) -> np.ndarray:
    """Clip to [0,1] and round-half-up to uint8. Rounds, never truncates."""
    return np.clip(np.asarray(arr, dtype=np.float64) * 255.0 + 0.5, 0, 255).astype(np.uint8)
