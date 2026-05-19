"""Image encoder — jina-clip-v2 multimodal multilingual.

Output: 1024-d L2-normalized vectors, cosine via inner product.
"""
from __future__ import annotations

import io
import logging
from typing import List, Optional

import numpy as np
import requests
import torch
from PIL import Image
from transformers import AutoModel

log = logging.getLogger(__name__)

CLIP_MODEL_NAME = "jinaai/jina-clip-v2"

_clip = None


def get_clip():
    """Load jina-clip-v2 via transformers.AutoModel (official way for image+text)."""
    global _clip
    if _clip is None:
        device = "cuda" if torch.cuda.is_available() else "cpu"
        log.info("Loading %s on %s", CLIP_MODEL_NAME, device)
        _clip = AutoModel.from_pretrained(CLIP_MODEL_NAME, trust_remote_code=True).to(device)
        _clip.eval()
        _clip._device = device  # save for encode helpers
    return _clip


def load_image_from_url(url: str, timeout: float = 15.0) -> Optional[Image.Image]:
    try:
        r = requests.get(url, timeout=timeout)
        if r.status_code != 200:
            return None
        return Image.open(io.BytesIO(r.content)).convert("RGB")
    except Exception as e:
        log.warning("load_image_from_url fail %s: %s", url, e)
        return None


def load_image_from_bytes(data: bytes) -> Optional[Image.Image]:
    try:
        return Image.open(io.BytesIO(data)).convert("RGB")
    except Exception as e:
        log.warning("load_image_from_bytes fail: %s", e)
        return None


def encode_images(images: List[Image.Image], batch_size: int = 32) -> np.ndarray:
    """Encode list of PIL images → (N, dim) L2-normalized float32."""
    model = get_clip()
    all_vecs = []
    with torch.no_grad():
        for i in range(0, len(images), batch_size):
            batch = images[i:i + batch_size]
            # jina-clip-v2 has encode_image(images, ...) returning numpy or tensor
            vecs = model.encode_image(batch)
            if isinstance(vecs, torch.Tensor):
                vecs = vecs.cpu().numpy()
            all_vecs.append(vecs)
    out = np.vstack(all_vecs).astype(np.float32)
    # L2 normalize (jina returns already-normalized typically, but enforce)
    norms = np.linalg.norm(out, axis=1, keepdims=True)
    norms = np.maximum(norms, 1e-12)
    return out / norms


def encode_one(image: Image.Image) -> np.ndarray:
    return encode_images([image], batch_size=1)[0]
