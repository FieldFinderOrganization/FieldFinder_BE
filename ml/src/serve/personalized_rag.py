"""Personalized RAG orchestrator — hybrid retrieve + SASRec rerank.

Flow:
  query (text) + userId
    → encode query (bi-encoder)
    → FAISS top RETRIEVE_K
    → re-score: query_sim + user_sim + (optional) DeepFM CTR + SASRec next-item prob
    → top K items
    → trả JSON cho BE inject vào LLM prompt
"""
from __future__ import annotations

import logging
import pickle
import time
from pathlib import Path
from typing import Optional

import numpy as np
import pandas as pd
import torch
from pymongo import MongoClient

from .. import config as C
from ..models.deepfm import DeepFM
from ..models.rag_retriever import HybridRetriever
from ..models.sasrec import SASRec

log = logging.getLogger(__name__)


class PersonalizedRAG:
    """Singleton — load 1 lần khi serve start."""

    def __init__(self, device: torch.device | None = None):
        self.device = device or torch.device(C.DEVICE if torch.cuda.is_available() else "cpu")
        log.info("PersonalizedRAG init on %s", self.device)

        # 1. Hybrid retriever (encoder + FAISS + user vectors)
        self.retriever = HybridRetriever()

        # 2. SASRec — for sequence-based rerank
        self.sasrec = self._load_sasrec()

        # 3. DeepFM — for CTR rerank
        self.deepfm, self.field_dims = self._load_deepfm()

        # 4. Encoders + entity tables
        with open(C.PROCESSED_DIR / "encoders" / "encoders.pkl", "rb") as f:
            self.encoders = pickle.load(f)

        # User → seq history cache. Key: user_id (str). Value: (item_idx_list, expires_at_unix)
        self._user_seq_cache: dict[str, tuple[list[int], float]] = {}
        self._seq_cache_ttl = 60.0  # seconds — short TTL so new purchases reflect quickly

        # Lazy Mongo client for live sequence reads
        self._mongo_client: Optional[MongoClient] = None

        # Item key → meta lookup
        self.item_meta_by_key = {m["item_key"]: m for m in self.retriever.meta}

    def _load_sasrec(self) -> Optional[SASRec]:
        path = C.CHECKPOINT_DIR / "sasrec.pt"
        if not path.exists():
            log.warning("SASRec checkpoint missing — rerank disabled")
            return None
        ckpt = torch.load(path, map_location=self.device, weights_only=False)
        model = SASRec(num_items=ckpt["num_items"]).to(self.device)
        model.load_state_dict(ckpt["model_state"])
        model.eval()
        log.info("SASRec loaded (epoch %d)", ckpt["epoch"])
        return model

    def _load_deepfm(self) -> tuple[Optional[DeepFM], list[int] | None]:
        path = C.CHECKPOINT_DIR / "deepfm.pt"
        if not path.exists():
            log.warning("DeepFM checkpoint missing — CTR rerank disabled")
            return None, None
        ckpt = torch.load(path, map_location=self.device, weights_only=False)
        model = DeepFM(field_dims=ckpt["field_dims"]).to(self.device)
        model.load_state_dict(ckpt["model_state"])
        model.eval()
        log.info("DeepFM loaded (epoch %d)", ckpt["epoch"])
        return model, ckpt["field_dims"]

    # ============== User sequence ==============
    def _get_mongo_coll(self):
        if self._mongo_client is None:
            self._mongo_client = MongoClient(C.MONGO_URI, serverSelectionTimeoutMS=5000)
        return self._mongo_client[C.MONGO_DB][C.MONGO_COLLECTION_LOGS]

    def _build_user_seq(self, user_id: str) -> list[int]:
        """Build item_idx sequence từ MongoDB live logs. Cache 60s.

        Returns sequence of item_idx for SASRec input. New users / unknown items
        gracefully handled — UNK items become idx=1, missing → empty list.
        """
        if not user_id:
            return []

        # Check cache (TTL)
        now = time.time()
        cached = self._user_seq_cache.get(user_id)
        if cached and cached[1] > now:
            return cached[0]

        # Query MongoDB live
        try:
            coll = self._get_mongo_coll()
            cursor = coll.find(
                {
                    "userId": user_id,
                    "eventType": {"$in": list(C.POSITIVE_EVENTS)},
                },
                {"itemId": 1, "itemType": 1, "timestamp": 1, "_id": 0},
            ).sort("timestamp", 1).limit(200)
            docs = list(cursor)
        except Exception as e:
            log.warning("Mongo query fail for user %s: %s — fallback parquet", user_id, e)
            return self._build_user_seq_parquet(user_id)

        if not docs:
            self._user_seq_cache[user_id] = ([], now + self._seq_cache_ttl)
            return []

        # Map item_id+itemType → item_key → item_idx
        item_le = self.encoders["item"]
        known = set(item_le.classes_)
        seq = []
        for d in docs:
            iid = d.get("itemId")
            itype = d.get("itemType")
            if iid is None or itype is None:
                continue
            key = f"P_{iid}" if itype == "PITCH" else f"T_{iid}"
            if key in known:
                seq.append(int(item_le.transform([key])[0]))
            else:
                seq.append(1)  # UNK

        self._user_seq_cache[user_id] = (seq, now + self._seq_cache_ttl)
        log.info("user %s live seq: %d events from Mongo", user_id, len(seq))
        return seq

    def _build_user_seq_parquet(self, user_id: str) -> list[int]:
        """Fallback: read from training parquet (only if user was in training set)."""
        try:
            user_le = self.encoders["user"]
            uid = user_le.transform([user_id])[0] if user_id in set(user_le.classes_) else None
        except Exception:
            return []
        if uid is None:
            return []

        if not hasattr(self, "_train_logs"):
            try:
                self._train_logs = pd.read_parquet(C.PROCESSED_DIR / "logs_train.parquet")
            except Exception:
                return []

        df = self._train_logs[self._train_logs["user_idx"] == uid]
        df = df[df["eventType"].isin(C.POSITIVE_EVENTS)].sort_values("timestamp")
        return df["item_idx"].tolist()

    # ============== SASRec rerank ==============
    def _sasrec_scores(self, user_id: str, item_keys: list[str]) -> np.ndarray:
        """Trả score SASRec cho candidates. 0 nếu user/item không trong vocab."""
        if self.sasrec is None or not user_id:
            return np.zeros(len(item_keys), dtype=np.float32)

        seq = self._build_user_seq(user_id)
        if not seq:
            return np.zeros(len(item_keys), dtype=np.float32)

        item_le = self.encoders["item"]
        known = set(item_le.classes_)
        cand_idx = []
        for k in item_keys:
            if k in known:
                cand_idx.append(int(item_le.transform([k])[0]))
            else:
                cand_idx.append(1)  # UNK

        max_len = self.sasrec.max_len
        seq = seq[-max_len:]
        pad_n = max_len - len(seq)
        input_ids = torch.tensor([[0] * pad_n + seq], dtype=torch.long, device=self.device)
        cand = torch.tensor([cand_idx], dtype=torch.long, device=self.device)

        with torch.no_grad():
            scores = self.sasrec.predict(input_ids, cand)
        return scores.cpu().numpy()[0]

    # ============== Predict next-K (no query) ==============
    def predict_next(self, user_id: str, top_k: int = 10, item_type: str | None = None) -> list[dict]:
        if self.sasrec is None:
            return []
        seq = self._build_user_seq(user_id)
        if not seq:
            return []

        # Score all items in catalog
        item_le = self.encoders["item"]
        all_items = list(item_le.classes_)
        # Skip PAD/UNK
        all_keys = [k for k in all_items if k not in ("<PAD>", "<UNK>")]

        # Filter by item_type
        if item_type == "PITCH":
            all_keys = [k for k in all_keys if k.startswith("P_")]
        elif item_type == "PRODUCT":
            all_keys = [k for k in all_keys if k.startswith("T_")]

        if not all_keys:
            return []

        scores = self._sasrec_scores(user_id, all_keys)
        order = np.argsort(-scores)[:top_k]
        results = []
        for i in order:
            key = all_keys[i]
            meta = self.item_meta_by_key.get(key, {"item_key": key})
            results.append({
                **meta,
                "score": float(scores[i]),
            })
        return results

    # ============== Hybrid retrieve + rerank ==============
    def retrieve(
        self,
        query: str,
        user_id: str | None = None,
        top_k: int = C.RAG_TOP_K,
        retrieve_k: int = C.RAG_RETRIEVE_K,
        item_type: str | None = None,
    ) -> list[dict]:
        # Stage 1: hybrid query+user retrieval (type filter applied inside, pre-truncate)
        cands = self.retriever.retrieve(
            query, user_id=user_id, top_k=retrieve_k, retrieve_k=retrieve_k, item_type=item_type
        )

        # Safety net: drop any off-type leftover (e.g. fallback path)
        if item_type:
            cands = [c for c in cands if c.get("item_type") == item_type]
        if not cands:
            return []

        # Stage 2: SASRec rerank
        item_keys = [c["item_key"] for c in cands]
        sasrec_scores = self._sasrec_scores(user_id or "", item_keys)
        # Normalize SASRec scores to [0, 1]
        if sasrec_scores.max() > sasrec_scores.min():
            s_norm = (sasrec_scores - sasrec_scores.min()) / (sasrec_scores.max() - sasrec_scores.min() + 1e-9)
        else:
            s_norm = np.zeros_like(sasrec_scores)

        # Combined score
        for i, c in enumerate(cands):
            c["sasrec_score"] = float(s_norm[i])
            c["final_score"] = c["score"] * (1 - C.RAG_W_DEEPFM) + s_norm[i] * C.RAG_W_DEEPFM

        cands.sort(key=lambda x: -x["final_score"])
        return cands[:top_k]


    # ============== Image personalization ==============
    def personalize_image_results(
        self,
        candidates: list[dict],
        user_id: str | None,
        w_rrf: float = 0.6,
        w_sasrec: float = 0.4,
    ) -> list[dict]:
        """Re-rank image search candidates using SASRec next-item scores for the user.

        Blend: final = w_rrf * existing_score + w_sasrec * sasrec_norm

        Args:
            candidates: list of dicts with at least item_id (str) and score (float).
                        Item type assumed PRODUCT (image search returns products).
            user_id: user UUID string. If None or no history → pass through.
            w_rrf: weight for existing hybrid score (CLIP-blended RRF)
            w_sasrec: weight for SASRec rerank
        """
        if not user_id or not candidates or self.sasrec is None:
            return candidates

        # Build item_keys: image candidates have item_id (raw product_id), prefix with T_
        item_keys = [f"T_{c['item_id']}" for c in candidates]

        sasrec_scores = self._sasrec_scores(user_id, item_keys)
        if sasrec_scores.max() == sasrec_scores.min():
            # User unknown / no history → keep original order
            log.info("personalize_image: no SASRec signal for user %s — pass through", user_id)
            return candidates

        # Normalize both signals to [0, 1] for fair blend
        s_min, s_max = float(sasrec_scores.min()), float(sasrec_scores.max())
        s_norm = (sasrec_scores - s_min) / (s_max - s_min + 1e-9)

        existing = np.array([c.get("score", 0.0) for c in candidates], dtype=np.float32)
        if existing.max() > existing.min():
            e_norm = (existing - existing.min()) / (existing.max() - existing.min() + 1e-9)
        else:
            e_norm = existing

        out = []
        for i, c in enumerate(candidates):
            m = dict(c)
            m["sasrec_score"] = float(sasrec_scores[i])
            m["final_score"] = float(w_rrf * e_norm[i] + w_sasrec * s_norm[i])
            out.append(m)
        out.sort(key=lambda x: -x["final_score"])
        log.info("personalize_image: reranked %d items for user %s", len(out), user_id)
        return out


_rag_instance: PersonalizedRAG | None = None


def get_rag() -> PersonalizedRAG:
    global _rag_instance
    if _rag_instance is None:
        _rag_instance = PersonalizedRAG()
    return _rag_instance
