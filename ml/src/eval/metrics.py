"""Metric helpers cho SASRec (HR/NDCG) + DeepFM (AUC/LogLoss)."""
from __future__ import annotations

import numpy as np
import torch
from sklearn.metrics import log_loss, roc_auc_score


# ============== SASRec ==============
def hr_at_k(ranks: np.ndarray, k: int) -> float:
    """ranks: (N,) — rank của target trong candidate list (0-indexed). Trả HR@k."""
    return float((ranks < k).mean())


def ndcg_at_k(ranks: np.ndarray, k: int) -> float:
    """NDCG@k với chỉ 1 ground-truth per row."""
    mask = ranks < k
    dcg = np.where(mask, 1.0 / np.log2(ranks + 2), 0.0)
    return float(dcg.mean())


def compute_ranks(scores: torch.Tensor) -> np.ndarray:
    """scores: (B, K) — index 0 = positive. Trả rank (0-indexed) của positive trong K."""
    # Argsort descending
    sorted_idx = torch.argsort(scores, dim=1, descending=True)
    # Rank của index 0
    ranks = (sorted_idx == 0).int().argmax(dim=1)
    return ranks.cpu().numpy()


# ============== DeepFM ==============
def deepfm_metrics(y_true: np.ndarray, y_score: np.ndarray) -> dict:
    """y_score: probability sau sigmoid."""
    y_score = np.clip(y_score, 1e-7, 1 - 1e-7)
    return {
        "auc": float(roc_auc_score(y_true, y_score)),
        "logloss": float(log_loss(y_true, y_score)),
        "n": int(len(y_true)),
        "pos_ratio": float(y_true.mean()),
    }


# ============== RAG ==============
def precision_at_k(retrieved_ids: list, relevant_ids: set, k: int = 5) -> float:
    if not retrieved_ids:
        return 0.0
    top = retrieved_ids[:k]
    return sum(1 for i in top if i in relevant_ids) / len(top)
