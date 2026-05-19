"""Image FAISS index loader + search."""
from __future__ import annotations

import logging
import pickle
from typing import Optional

import faiss
import numpy as np

from .. import config as C

log = logging.getLogger(__name__)

INDEX_PATH = C.CHECKPOINT_DIR / "items_image.faiss"
META_PATH = C.CHECKPOINT_DIR / "items_image_meta.pkl"

_index: Optional[faiss.Index] = None
_meta: Optional[list] = None


def get_image_index():
    global _index, _meta
    if _index is None:
        if not INDEX_PATH.exists() or not META_PATH.exists():
            log.warning("Image index not found at %s", INDEX_PATH)
            return None, None
        _index = faiss.read_index(str(INDEX_PATH))
        with open(META_PATH, "rb") as f:
            _meta = pickle.load(f)
        log.info("Image index loaded: %d vectors", _index.ntotal)
    return _index, _meta


def search_image(query_vec: np.ndarray, top_k: int = 30,
                 category_ids: list[int] | None = None) -> list[dict]:
    index, meta = get_image_index()
    if index is None:
        return []
    qv = query_vec.reshape(1, -1).astype(np.float32)
    # Over-fetch then filter
    fetch = top_k * 5 if category_ids else top_k
    fetch = min(fetch, index.ntotal)
    D, I = index.search(qv, fetch)
    results = []
    cat_set = set(category_ids) if category_ids else None
    for score, idx in zip(D[0], I[0]):
        if idx < 0:
            continue
        m = dict(meta[idx])
        if cat_set is not None and m.get("category_id") not in cat_set:
            continue
        m["score"] = float(score)
        results.append(m)
        if len(results) >= top_k:
            break
    return results


def get_all_meta() -> list[dict]:
    _, meta = get_image_index()
    return meta or []
