"""Personalized RAG retriever — sentence-transformer + FAISS hybrid scoring."""
from __future__ import annotations

import logging
import pickle
from pathlib import Path
from typing import List, Tuple

import faiss
import numpy as np
import pandas as pd
import torch
from sentence_transformers import SentenceTransformer

from .. import config as C

log = logging.getLogger(__name__)

INDEX_PATH = C.CHECKPOINT_DIR / "items.faiss"
META_PATH = C.CHECKPOINT_DIR / "items_meta.pkl"
USER_VEC_PATH = C.CHECKPOINT_DIR / "user_vectors.npy"
USER_IDS_PATH = C.CHECKPOINT_DIR / "user_ids.pkl"


# ============== Encoder ==============
def get_encoder(model_name: str = C.RAG_EMBED_MODEL) -> SentenceTransformer:
    log.info("Loading encoder: %s", model_name)
    device = "cuda" if torch.cuda.is_available() else "cpu"
    return SentenceTransformer(model_name, device=device)


def build_item_text(row: pd.Series) -> str:
    parts = []
    parts.append(str(row.get("name", "")))
    cat = row.get("category", "")
    if cat and cat != "UNKNOWN":
        parts.append(f"loại: {cat}")
    env = row.get("env", "")
    if env and env not in ("UNKNOWN", "NA"):
        parts.append(f"môi trường: {env}")
    brand = row.get("brand", "")
    if brand and brand not in ("UNKNOWN", "NA"):
        parts.append(f"thương hiệu: {brand}")
    desc = row.get("description", "")
    if isinstance(desc, str) and desc:
        parts.append(desc)
    return ". ".join(p for p in parts if p)


def build_user_text(row: pd.Series) -> str:
    parts = []
    if row.get("gender") and row["gender"] != "UNKNOWN":
        parts.append(f"giới tính {row['gender']}")
    if row.get("age_bucket") and row["age_bucket"] != "UNKNOWN":
        parts.append(f"độ tuổi {row['age_bucket']}")
    if row.get("province") and row["province"] != "UNKNOWN":
        parts.append(f"khu vực {row['province']}")
    if row.get("occupation") and row["occupation"] != "UNKNOWN":
        parts.append(f"nghề nghiệp {row['occupation']}")
    if row.get("fav_category") and row["fav_category"] != "UNKNOWN":
        parts.append(f"thường quan tâm {row['fav_category']}")
    if row.get("price_pref_bucket") and row["price_pref_bucket"] != "UNKNOWN":
        parts.append(f"mức giá {row['price_pref_bucket']}")
    return ", ".join(parts) if parts else "người dùng phổ thông"


# ============== Index ==============
def build_item_index(items: pd.DataFrame, encoder: SentenceTransformer) -> Tuple[faiss.Index, list]:
    texts = [build_item_text(r) for _, r in items.iterrows()]
    log.info("Encoding %d items...", len(texts))
    vecs = encoder.encode(texts, batch_size=64, show_progress_bar=True, convert_to_numpy=True, normalize_embeddings=True)

    dim = vecs.shape[1]
    index = faiss.IndexFlatIP(dim)  # cosine vì đã normalize
    index.add(vecs.astype(np.float32))

    meta = items[["item_id", "item_type", "item_key", "name", "category", "env", "brand", "price"]].to_dict("records")
    log.info("FAISS index built: %d vectors, dim=%d", index.ntotal, dim)
    return index, meta


def save_index(index: faiss.Index, meta: list) -> None:
    faiss.write_index(index, str(INDEX_PATH))
    with open(META_PATH, "wb") as f:
        pickle.dump(meta, f)
    log.info("Saved index → %s", INDEX_PATH)


def load_index() -> Tuple[faiss.Index, list]:
    index = faiss.read_index(str(INDEX_PATH))
    with open(META_PATH, "rb") as f:
        meta = pickle.load(f)
    return index, meta


def build_user_vectors(user_feat: pd.DataFrame, encoder: SentenceTransformer) -> None:
    texts = [build_user_text(r) for _, r in user_feat.iterrows()]
    vecs = encoder.encode(texts, batch_size=64, show_progress_bar=True, convert_to_numpy=True, normalize_embeddings=True)
    np.save(USER_VEC_PATH, vecs.astype(np.float32))
    with open(USER_IDS_PATH, "wb") as f:
        pickle.dump(user_feat["user_id"].astype(str).tolist(), f)
    log.info("Saved user vectors: %s (%d users)", USER_VEC_PATH, len(vecs))


def load_user_vectors() -> Tuple[np.ndarray, list]:
    vecs = np.load(USER_VEC_PATH)
    with open(USER_IDS_PATH, "rb") as f:
        ids = pickle.load(f)
    return vecs, ids


# ============== Retriever ==============
class HybridRetriever:
    def __init__(self):
        self.encoder = get_encoder()
        self.index, self.meta = load_index()
        self.user_vecs, self.user_ids = load_user_vectors()
        self.user_id_to_row = {uid: i for i, uid in enumerate(self.user_ids)}

    def _user_vec(self, user_id: str) -> np.ndarray | None:
        i = self.user_id_to_row.get(str(user_id))
        if i is None:
            return None
        return self.user_vecs[i]

    def retrieve(
        self,
        query: str,
        user_id: str | None = None,
        top_k: int = C.RAG_TOP_K,
        retrieve_k: int = C.RAG_RETRIEVE_K,
    ) -> List[dict]:
        # Encode query
        qv = self.encoder.encode([query], normalize_embeddings=True, convert_to_numpy=True).astype(np.float32)

        # Stage 1: query-based retrieval
        D_q, I = self.index.search(qv, retrieve_k)
        cand_idx = I[0]
        cand_meta = [self.meta[i] for i in cand_idx]

        # Stage 2: hybrid score (query sim + user sim)
        scores = C.RAG_W_QUERY * D_q[0]
        if user_id is not None:
            uv = self._user_vec(user_id)
            if uv is not None:
                # Lấy item vector từ index — reconstruct
                item_vecs = np.stack([self.index.reconstruct(int(i)) for i in cand_idx])
                user_sim = item_vecs @ uv
                scores = scores + C.RAG_W_USER * user_sim

        order = np.argsort(-scores)[:top_k]
        results = []
        for o in order:
            m = dict(cand_meta[o])
            m["score"] = float(scores[o])
            results.append(m)
        return results
