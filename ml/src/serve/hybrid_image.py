"""Hybrid image retriever — RRF fuse 3 signals + MMR diversify.

Signals:
  - CLIP image embedding (FAISS items_image)
  - Vietnamese bi-encoder caption (reuse HybridRetriever items.faiss, PRODUCT only)
  - Tag Jaccard (Gemini tags ∩ product.tags)

Phase 2 polish:
  - Tag cache at init (avoid re-reading meta per request)
  - Parallel CLIP + text retrieval via ThreadPoolExecutor
  - Configurable thresholds from config.py
  - Latency tracking in results
"""
from __future__ import annotations

import concurrent.futures
import logging
import time
from typing import Optional

import numpy as np
from PIL import Image

from .. import config as C
from ..models.image_encoder import encode_one
from .image_index import get_image_index, search_image, get_all_meta

log = logging.getLogger(__name__)


def jaccard(a: set, b: set) -> float:
    if not a or not b:
        return 0.0
    inter = len(a & b)
    union = len(a | b)
    return inter / union if union else 0.0


# Màu được xử lý RIÊNG qua dominant_color (sạch, 1 màu chuẩn). Loại token màu khỏi tag-Jaccard để
# màu phụ/accent (vd "black" trên giày trắng phối đen) KHÔNG tạo hit kéo hàng sai màu vào pool —
# đây là nguồn rò chính của bug "gửi ảnh giày đen, hàng khác màu vẫn lọt".
_COLOR_WORDS = frozenset({
    "đen", "black", "than", "charcoal", "ebony",
    "trắng", "white", "kem", "cream", "beige", "sữa", "ivory",
    "xám", "gray", "grey", "ghi", "bạc", "silver",
    "đỏ", "red", "crimson", "burgundy",
    "cam", "orange", "coral",
    "vàng", "yellow", "gold",
    "hồng", "pink", "mận",
    "tím", "purple", "violet",
    "nâu", "brown", "tan", "cafe", "coffee",
    "xanh", "green", "olive", "rêu", "lục", "lá",
    "blue", "navy", "chàm", "sky", "biển", "dương",
})


def _strip_colors(tokens) -> list[str]:
    return [t for t in tokens if t and t.lower() not in _COLOR_WORDS]


def rrf_fuse(rankings: list[list[str]], k: int = 60) -> dict[str, float]:
    """Reciprocal Rank Fusion. Input: list of ranked id lists. Output: dict id -> score."""
    scores: dict[str, float] = {}
    for ranking in rankings:
        for rank, pid in enumerate(ranking):
            scores[pid] = scores.get(pid, 0.0) + 1.0 / (k + rank)
    return scores


def mmr(candidates: list[dict], embeddings: dict[str, np.ndarray],
        top_k: int, lam: float = 0.7) -> list[dict]:
    """Greedy MMR. candidates already sorted by relevance desc."""
    if not candidates:
        return []
    selected: list[dict] = []
    pool = list(candidates)
    rel = {c["item_id"]: i for i, c in enumerate(pool)}

    # Initial pick = highest relevance
    selected.append(pool.pop(0))

    while pool and len(selected) < top_k:
        best_idx = -1
        best_score = -1e9
        for i, c in enumerate(pool):
            ev = embeddings.get(c["item_id"])
            if ev is None:
                sim = 0.0
            else:
                sim = max(
                    float(np.dot(ev, embeddings[s["item_id"]]))
                    if embeddings.get(s["item_id"]) is not None else 0.0
                    for s in selected
                )
            relevance = 1.0 - rel[c["item_id"]] / max(1, len(rel))
            score = lam * relevance - (1 - lam) * sim
            if score > best_score:
                best_score = score
                best_idx = i
        selected.append(pool.pop(best_idx))

    return selected


class HybridImageRetriever:
    """Singleton — load lần đầu khi serve."""

    def __init__(self):
        log.info("HybridImageRetriever init")
        # Lazy load text retriever — only if hybrid signal needed
        self._text_retriever = None
        # In-memory tag cache: item_id -> list[str] (lowercase)
        self._tag_cache: dict[str, list[str]] = {}
        self._preload_tag_cache()

    def _preload_tag_cache(self):
        """Cache product tags from image index meta at startup — avoid re-reading per request."""
        try:
            for m in get_all_meta():
                pid = m.get("item_id", "")
                tags = m.get("tags") or []
                if pid:
                    self._tag_cache[pid] = [t.lower() for t in tags if t]
            log.info("Tag cache loaded: %d products", len(self._tag_cache))
        except Exception as e:
            log.warning("Tag cache load fail: %s", e)

    def _get_text_retriever(self):
        if self._text_retriever is None:
            from ..models.rag_retriever import HybridRetriever
            try:
                self._text_retriever = HybridRetriever()
                log.info("Text retriever loaded for hybrid image")
            except Exception as e:
                log.warning("Text retriever load fail: %s — text signal disabled", e)
                self._text_retriever = False
        return self._text_retriever if self._text_retriever else None

    def _clip_rank(self, image: Image.Image, retrieve_k: int,
                   category_ids: Optional[list[int]]) -> list[dict]:
        qv = encode_one(image)
        return search_image(qv, top_k=retrieve_k, category_ids=category_ids)

    def _text_rank(self, caption: str, retrieve_k: int,
                   item_id_pool: set[str]) -> list[dict]:
        if not caption:
            return []
        tr = self._get_text_retriever()
        if tr is None:
            return []
        try:
            results = tr.retrieve(caption, user_id=None, top_k=retrieve_k * 2,
                                  retrieve_k=retrieve_k * 2)
        except Exception as e:
            log.warning("text retrieve fail: %s", e)
            return []
        # Filter PRODUCT + within pool
        out = []
        for r in results:
            if r.get("item_type") != "PRODUCT":
                continue
            ikey = r.get("item_key", "")
            if not ikey.startswith("T_"):
                continue
            pid = ikey[2:]
            if pid in item_id_pool:
                out.append({"item_id": pid, "score": float(r.get("score", 0.0))})
            if len(out) >= retrieve_k:
                break
        return out

    def _tag_rank(self, gemini_tags: list[str], retrieve_k: int,
                  candidate_meta: list[dict]) -> list[dict]:
        if not gemini_tags:
            return []
        # Bỏ token màu khỏi Jaccard — màu do dominant_color lo riêng (xem _COLOR_WORDS).
        gt = set(_strip_colors([t.lower() for t in gemini_tags if t]))
        if not gt:
            return []
        scored = []
        for m in candidate_meta:
            pid = m.get("item_id", "")
            # Use cached tags (fast) → fallback to meta tags
            tags = self._tag_cache.get(pid) or m.get("tags") or []
            tags_list = list(tags) if isinstance(tags, set) else [t.lower() for t in tags if t]
            tags_set = set(_strip_colors(tags_list))
            j = jaccard(gt, tags_set)
            if j > 0:
                scored.append({"item_id": pid, "score": j})
        scored.sort(key=lambda x: -x["score"])
        return scored[:retrieve_k]

    def retrieve(self, image: Image.Image, caption: str = "",
                 gemini_tags: Optional[list[str]] = None,
                 category_ids: Optional[list[int]] = None,
                 top_k: int = 10, retrieve_k: int = 30,
                 user_id: Optional[str] = None,
                 dominant_color: str = "") -> list[dict]:
        t0 = time.time()
        gemini_tags = gemini_tags or []
        category_ids = category_ids or []
        img_color = (dominant_color or "").strip().lower()
        log.info("retrieve start: caption_len=%d tags=%d cat_ids=%d",
                 len(caption), len(gemini_tags), len(category_ids))

        # CLIP search WITHOUT filter first → check exact match
        clip_no_filter = self._clip_rank(image, retrieve_k, None)
        top_cosine = clip_no_filter[0]["score"] if clip_no_filter else 0.0
        log.info("clip no-filter done in %.2fs, hits=%d, top_cosine=%.4f",
                 time.time() - t0, len(clip_no_filter), top_cosine)

        EXACT_THRESHOLD = C.IMAGE_EXACT_THRESHOLD
        if top_cosine >= EXACT_THRESHOLD or not category_ids:
            clip_hits = clip_no_filter
        else:
            # Not exact → search ngay TRONG category để pool đủ
            clip_in_cat = self._clip_rank(image, retrieve_k, category_ids)
            log.info("clip in-category done, hits=%d (cat=%d)",
                     len(clip_in_cat), len(category_ids))
            if clip_in_cat:
                clip_hits = clip_in_cat
            else:
                log.info("Category in-search empty → fallback no-filter")
                clip_hits = clip_no_filter

        # Pool = clip_hits items (canonical info source)
        pool_meta = {h["item_id"]: h for h in clip_hits}
        pool_ids = set(pool_meta.keys())

        # Parallel: text retrieval via ThreadPool + tag rank on main thread (cheap, in-memory)
        pool_meta_list = list(pool_meta.values())
        with concurrent.futures.ThreadPoolExecutor(max_workers=2) as executor:
            text_future = executor.submit(self._text_rank, caption, retrieve_k, pool_ids)
            # Tag rank runs on main thread — fast, pure in-memory Jaccard
            tag_hits = self._tag_rank(gemini_tags, retrieve_k, pool_meta_list)
            text_hits = text_future.result()

        log.info("🔍 RRF blend: clip=%d text=%d tag=%d (total %.2fs)",
                 len(clip_hits), len(text_hits), len(tag_hits), time.time() - t0)

        rankings = [
            [h["item_id"] for h in clip_hits],
            [h["item_id"] for h in text_hits],
            [h["item_id"] for h in tag_hits],
        ]
        rrf_scores = rrf_fuse(rankings)

        # Normalize RRF to [0,1] for blend with CLIP cosine
        max_rrf = max(rrf_scores.values()) if rrf_scores else 1.0
        max_rrf = max(max_rrf, 1e-6)

        # High-confidence regime: when top CLIP cosine ≥ 0.90, trust CLIP order strongly
        # Tuned for jina-clip-v2 cosine distribution (in_catalog≥0.90, unrelated<0.70)
        top_cosine_pool = max((m.get("score", 0.0) for m in pool_meta.values()), default=0.0)
        if top_cosine_pool >= 0.90:
            w_clip, w_rrf = 0.7, 0.3   # CLIP dominates when image clearly matches
        elif top_cosine_pool >= 0.70:
            w_clip, w_rrf = 0.5, 0.5
        else:
            w_clip, w_rrf = 0.3, 0.7   # weak CLIP → trust agreement of text+tag

        # Final score = weighted blend (CLIP cosine already in [0,1])
        candidates = []
        for pid, meta in pool_meta.items():
            m = dict(meta)
            clip_cos = meta.get("score", 0.0)
            rrf_norm = rrf_scores.get(pid, 0.0) / max_rrf
            m["clip_score"] = clip_cos
            m["rrf_score"] = rrf_scores.get(pid, 0.0)
            m["score"] = w_clip * clip_cos + w_rrf * rrf_norm
            candidates.append(m)

        # Tín hiệu MÀU (sạch, phân tầng): màu thuần (dominant) full boost; sp đa màu (img_color nằm
        # trong colors, vd đen/trắng 50/50) boost nửa; sai hẳn → penalty. KHÔNG loại — an toàn recall.
        # Bổ trợ cho rerank màu ở BE.
        if img_color:
            BONUS_DOM, BONUS_SET, PENALTY = 0.15, 0.08, 0.10
            adjusted = 0
            for m in candidates:
                dom = (m.get("dominant_color") or "").strip().lower()
                cset = {(c or "").strip().lower() for c in (m.get("colors") or [])}
                if dom == img_color:
                    m["score"] += BONUS_DOM
                elif img_color in cset:
                    m["score"] += BONUS_SET
                elif dom or cset:          # có thông tin màu nhưng không khớp → sai màu
                    m["score"] -= PENALTY
                else:
                    continue               # chưa seed màu → bỏ qua, không phạt oan
                adjusted += 1
            log.info("color signal '%s' applied to %d/%d candidates", img_color, adjusted, len(candidates))

        candidates.sort(key=lambda x: -x["score"])
        log.info("blend weights: clip=%.1f rrf=%.1f (top_cosine_pool=%.3f)",
                 w_clip, w_rrf, top_cosine_pool)

        # MMR diversify with tag-Jaccard as similarity proxy (cheap, no re-encode)
        selected = self._mmr_tag(candidates, top_k=top_k, lam=0.7)

        # Personalization: rerank by SASRec next-item if user_id provided
        # Only when CLIP cosine isn't ultra-high (exact match — keep order)
        if user_id and top_cosine_pool < 0.95:
            try:
                from .personalized_rag import get_rag
                rag = get_rag()
                selected = rag.personalize_image_results(selected, user_id)
            except Exception as e:
                log.warning("personalize_image fail: %s", e)

        # Attach latency to results for tracking
        latency_ms = round((time.time() - t0) * 1000, 1)
        for c in selected:
            c["latency_ms"] = latency_ms
        log.info("retrieve done: %d results in %.1fms (user=%s)", len(selected), latency_ms, user_id or "-")

        return selected

    def _mmr_tag(self, candidates: list[dict], top_k: int, lam: float = 0.7) -> list[dict]:
        if len(candidates) <= top_k:
            return candidates[:top_k]
        selected = [candidates.pop(0)]
        while candidates and len(selected) < top_k:
            best_i, best_score = -1, -1e9
            n = len(candidates)
            for i, c in enumerate(candidates):
                rel = 1.0 - i / n
                ctags = set(c.get("tags") or [])
                sim = max(jaccard(ctags, set(s.get("tags") or [])) for s in selected)
                score = lam * rel - (1 - lam) * sim
                if score > best_score:
                    best_score, best_i = score, i
            selected.append(candidates.pop(best_i))
        return selected


_hybrid_instance: Optional[HybridImageRetriever] = None


def get_hybrid_image() -> HybridImageRetriever:
    global _hybrid_instance
    if _hybrid_instance is None:
        _hybrid_instance = HybridImageRetriever()
    return _hybrid_instance
