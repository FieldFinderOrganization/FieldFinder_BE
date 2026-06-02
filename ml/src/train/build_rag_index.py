"""Build FAISS index cho item descriptions + user profile vectors."""
from __future__ import annotations

import torch  # noqa: F401  — phải import TRƯỚC faiss (tránh xung đột DLL OpenMP/MKL trên Windows)

import logging
from datetime import datetime

import pandas as pd

from .. import config as C
from ..features import build_user_features, build_item_features
from ..models.rag_retriever import (
    build_item_index, build_user_vectors, get_encoder, save_index,
)

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def main():
    # Load processed data
    pitches = pd.read_parquet(C.PROCESSED_DIR / "pitches.parquet") if (C.PROCESSED_DIR / "pitches.parquet").exists() else pd.DataFrame()
    products = pd.read_parquet(C.PROCESSED_DIR / "products.parquet") if (C.PROCESSED_DIR / "products.parquet").exists() else pd.DataFrame()
    users = pd.read_parquet(C.PROCESSED_DIR / "users.parquet") if (C.PROCESSED_DIR / "users.parquet").exists() else pd.DataFrame()
    train_logs = pd.read_parquet(C.PROCESSED_DIR / "logs_train.parquet")

    # Cột chuẩn hóa: pitches.type ↔ pitch_type
    if not pitches.empty:
        if "type" in pitches.columns and "pitch_type" not in pitches.columns:
            pitches = pitches.rename(columns={"type": "pitch_type"})

    log.info("pitches=%d  products=%d  users=%d", len(pitches), len(products), len(users))

    # Build feature tables
    item_feat = build_item_features(pitches, products)
    user_feat = build_user_features(users, train_logs)

    log.info("item_feat=%d  user_feat=%d", len(item_feat), len(user_feat))

    # Encode + index
    encoder = get_encoder()
    index, meta = build_item_index(item_feat, encoder)
    save_index(index, meta)

    build_user_vectors(user_feat, encoder)

    log.info("RAG index built. Items=%d, Users=%d", index.ntotal, len(user_feat))


if __name__ == "__main__":
    main()
