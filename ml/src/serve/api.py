"""FastAPI ML serve — endpoints cho BE Java gọi.

Run:
    uvicorn src.serve.api:app --host 0.0.0.0 --port 8000 --reload
"""
from __future__ import annotations

import hashlib
import logging
import pickle
import time
from collections import OrderedDict
from threading import Lock
from typing import Optional

import numpy as np
import pandas as pd
import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field

from .. import config as C
from ..models.deepfm import DeepFM
from .personalized_rag import get_rag

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

app = FastAPI(title="FieldFinder ML API", version="1.0.0")


# ============== Image retrieve LRU cache ==============
# Cache full /retrieve/image response by hash(image_bytes + top_k + retrieve_k + caption + category_ids).
# Same image resubmit → near-instant return. TTL 1 hour. Max 256 entries.
_IMG_CACHE_MAX = 256
_IMG_CACHE_TTL_SEC = 3600
_img_cache: "OrderedDict[str, tuple[float, dict]]" = OrderedDict()
_img_cache_lock = Lock()


def _img_cache_key(img_bytes: bytes, caption: str, gemini_tags, category_ids,
                   top_k: int, retrieve_k: int) -> str:
    h = hashlib.sha256()
    h.update(img_bytes)
    h.update(b"|c=")
    h.update((caption or "").encode("utf-8"))
    h.update(b"|t=")
    h.update(",".join(sorted(gemini_tags or [])).encode("utf-8"))
    h.update(b"|ci=")
    h.update(",".join(map(str, sorted(category_ids or []))).encode("utf-8"))
    h.update(f"|k={top_k}|rk={retrieve_k}".encode("utf-8"))
    return h.hexdigest()


def _img_cache_get(key: str):
    with _img_cache_lock:
        entry = _img_cache.get(key)
        if entry is None:
            return None
        ts, val = entry
        if time.time() - ts > _IMG_CACHE_TTL_SEC:
            _img_cache.pop(key, None)
            return None
        _img_cache.move_to_end(key)
        return val


def _img_cache_put(key: str, val: dict):
    with _img_cache_lock:
        _img_cache[key] = (time.time(), val)
        _img_cache.move_to_end(key)
        while len(_img_cache) > _IMG_CACHE_MAX:
            _img_cache.popitem(last=False)


# ============== Schemas ==============
class RecommendNextRequest(BaseModel):
    user_id: str = Field(..., description="UUID của user")
    top_k: int = Field(10, ge=1, le=50)
    item_type: Optional[str] = Field(None, description="PITCH / PRODUCT / null")


class RecommendCTRRequest(BaseModel):
    user_id: str
    candidate_ids: list[str]   # list item_id thật (UUID hoặc int string)
    item_types: list[str]      # parallel với candidate_ids: "PITCH" | "PRODUCT"
    context: dict = Field(default_factory=dict)


class RetrieveRequest(BaseModel):
    query: str
    user_id: Optional[str] = None
    top_k: int = Field(10, ge=1, le=20)
    item_type: Optional[str] = None


class ImageRetrieveRequest(BaseModel):
    image_base64: str
    caption: Optional[str] = None
    gemini_tags: list[str] = Field(default_factory=list)
    category_ids: list[int] = Field(default_factory=list)
    user_id: Optional[str] = None
    top_k: int = Field(10, ge=1, le=20)
    retrieve_k: int = Field(30, ge=10, le=100)
    item_type: Optional[str] = None


class ItemResult(BaseModel):
    item_id: str
    item_type: str
    item_key: str
    name: str
    score: float
    meta: dict


# ============== Globals (DeepFM) ==============
_deepfm_state: dict = {}


def _load_deepfm():
    if _deepfm_state:
        return _deepfm_state
    device = torch.device(C.DEVICE if torch.cuda.is_available() else "cpu")
    ckpt_path = C.CHECKPOINT_DIR / "deepfm.pt"
    if not ckpt_path.exists():
        return {}
    ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
    model = DeepFM(field_dims=ckpt["field_dims"]).to(device)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    with open(C.PROCESSED_DIR / "encoders" / "encoders.pkl", "rb") as f:
        encs = pickle.load(f)

    _deepfm_state.update({
        "model": model,
        "device": device,
        "field_dims": ckpt["field_dims"],
        "encoders": encs,
    })

    # Vocab-drift guard (DeepFM) — mirrors the SASRec check in PersonalizedRAG. field_dims[1] is
    # the item embedding size; if encoders were rebuilt with more items but DeepFM wasn't retrained,
    # item_idx can exceed it → out-of-range embedding lookup (CUDA device-side assert → poisons the
    # context → every later request 500s). /recommend/ctr clamps such idx to UNK, but warn loudly.
    try:
        n_vocab = len(encs["item"].classes_)
        n_item = ckpt["field_dims"][1]
        if n_vocab > n_item:
            log.warning("VOCAB DRIFT (DeepFM): encoders item=%d > field_dims item=%d — retrain DeepFM; "
                        "/recommend/ctr clamps idx>=%d to UNK (CTR degraded).", n_vocab, n_item, n_item)
        else:
            log.info("Vocab check OK (DeepFM): item=%d, field_dims item=%d", n_vocab, n_item)
    except Exception as e:
        log.warning("DeepFM vocab drift check skipped: %s", e)

    return _deepfm_state


# ============== Startup ==============
@app.on_event("startup")
async def startup():
    log.info("Warming up models...")
    get_rag()  # load SASRec, DeepFM, RAG
    _load_deepfm()
    # Warmup hybrid image: load CLIP + image FAISS + text retriever
    try:
        from .hybrid_image import get_hybrid_image
        from .image_index import get_image_index
        from ..models.image_encoder import get_clip
        get_clip()
        get_image_index()
        hybrid = get_hybrid_image()
        hybrid._get_text_retriever()
        log.info("Hybrid image warm")
    except Exception as e:
        log.warning("Hybrid image warmup fail: %s", e)
    log.info("ML API ready")


# ============== Endpoints ==============
@app.get("/health")
async def health():
    return {"status": "ok", "cuda": torch.cuda.is_available()}


@app.post("/recommend/next")
async def recommend_next(req: RecommendNextRequest):
    """SASRec next-item prediction."""
    rag = get_rag()
    items = rag.predict_next(req.user_id, top_k=req.top_k, item_type=req.item_type)
    return {"user_id": req.user_id, "results": items}


@app.post("/recommend/ctr")
async def recommend_ctr(req: RecommendCTRRequest):
    """DeepFM CTR rerank — input candidates, return scores.

    Vocab-drift safe + never-500: every feature index is clamped to its trained field
    dimension (out-of-range → UNK), so an encoder/DeepFM mismatch degrades to a neutral
    score instead of an out-of-range embedding lookup — which on CUDA raises a device-side
    assert that poisons the context and 500s every subsequent request. Mirrors SASRec's
    `_safe_idx`. Any other failure returns empty scores so the BE circuit breaker isn't tripped.
    """
    state = _load_deepfm()
    if not state:
        raise HTTPException(503, "DeepFM not loaded")

    empty = {"user_id": req.user_id, "scores": []}
    try:
        encs = state["encoders"]
        device = state["device"]
        model = state["model"]
        fd = state["field_dims"]

        def _safe(v: int, j: int) -> int:
            return v if 0 <= v < fd[j] else 1  # out-of-range field idx → UNK(1)

        user_le = encs["user"]
        item_le = encs["item"]
        user_classes = set(user_le.classes_)
        item_classes = set(item_le.classes_)
        uid = int(user_le.transform([req.user_id])[0]) if req.user_id in user_classes else 1

        rows = []
        for iid, itype in zip(req.candidate_ids, req.item_types):
            key = f"P_{iid}" if itype == "PITCH" else f"T_{iid}"
            item_idx = int(item_le.transform([key])[0]) if key in item_classes else 1
            ctx = req.context
            row = [
                uid,
                item_idx,
                _enc_safe(encs["weather"], ctx.get("weather", "UNKNOWN")),
                _enc_safe(encs["os"], ctx.get("os", "UNKNOWN")),
                _enc_safe(encs["hour_bucket"], ctx.get("hour_bucket", "UNKNOWN")),
                _enc_safe(encs["price_bucket"], ctx.get("price_bucket", "UNKNOWN")),
                _enc_safe(encs["user_gender_snap"], ctx.get("user_gender", "UNKNOWN")),
                _enc_safe(encs["item_category_snap"], ctx.get("item_category", "UNKNOWN")),
                _enc_safe(encs["itemType"], itype),
            ]
            rows.append([_safe(v, j) for j, v in enumerate(row)])

        x = torch.tensor(rows, dtype=torch.long, device=device)
        with torch.no_grad():
            logits = model(x)
            probs = torch.sigmoid(logits).cpu().numpy().tolist()

        return {
            "user_id": req.user_id,
            "scores": [
                {"item_id": iid, "item_type": itype, "ctr_score": float(s)}
                for iid, itype, s in zip(req.candidate_ids, req.item_types, probs)
            ],
        }
    except Exception as e:
        log.exception("recommend_ctr failed (user=%s, n=%d): %s",
                      req.user_id, len(req.candidate_ids), e)
        return empty


@app.post("/retrieve/image")
async def retrieve_image(req: ImageRetrieveRequest):
    """Hybrid image retrieval — CLIP + caption text + tag Jaccard, RRF fuse, MMR diversify."""
    import base64
    from ..models.image_encoder import load_image_from_bytes
    from .hybrid_image import get_hybrid_image
    from .image_index import get_image_index

    raw = req.image_base64
    if "," in raw:
        raw = raw.split(",", 1)[1]
    try:
        img_bytes = base64.b64decode(raw)
    except Exception:
        raise HTTPException(400, "invalid base64")

    # LRU cache lookup — same image+params = skip full pipeline
    cache_key = _img_cache_key(img_bytes, req.caption or "", req.gemini_tags,
                                req.category_ids, req.top_k, req.retrieve_k)
    cached = _img_cache_get(cache_key)
    if cached is not None:
        log.info("Image retrieve CACHE HIT key=%s", cache_key[:12])
        return {**cached, "cache_hit": True}

    img = load_image_from_bytes(img_bytes)
    if img is None:
        raise HTTPException(400, "cannot decode image")

    index, _ = get_image_index()
    if index is None:
        raise HTTPException(503, "image index not built")

    hybrid = get_hybrid_image()
    hits = hybrid.retrieve(
        image=img,
        caption=req.caption or "",
        gemini_tags=req.gemini_tags,
        category_ids=req.category_ids,
        top_k=req.top_k,
        retrieve_k=req.retrieve_k,
        user_id=req.user_id,
    )
    latency_ms = hits[0].get("latency_ms") if hits else None
    response = {
        "results": hits,
        "latency_ms": latency_ms,
        "rrf_threshold": C.IMAGE_RRF_RETURN_THRESHOLD,
    }
    _img_cache_put(cache_key, response)
    return response


@app.post("/retrieve")
async def retrieve(req: RetrieveRequest):
    """Personalized RAG retrieve — query + user → top K items + scores."""
    try:
        rag = get_rag()
        items = rag.retrieve(
            query=req.query,
            user_id=req.user_id,
            top_k=req.top_k,
            item_type=req.item_type,
        )
    except Exception as e:
        # Never 500 the recommend path: BE has a circuit breaker that disables ML for 60s
        # after 5 fails. Log full trace, return empty → BE falls back to local vector search.
        log.exception("retrieve failed (query=%r user=%s): %s", req.query, req.user_id, e)
        items = []
    return {
        "query": req.query,
        "user_id": req.user_id,
        "results": items,
    }


# ============== Helpers ==============
def _enc_safe(le, val):
    classes = set(le.classes_)
    val = str(val) if val is not None else "UNKNOWN"
    if val not in classes:
        val = "<UNK>" if "<UNK>" in classes else "UNKNOWN" if "UNKNOWN" in classes else next(iter(classes))
    return int(le.transform([val])[0])
