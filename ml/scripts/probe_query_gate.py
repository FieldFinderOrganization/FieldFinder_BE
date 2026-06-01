"""Probe text-query cosine distribution to pick RAG_QUERY_GATE threshold.

Loads encoder + FAISS item index, runs sample queries, prints top-K cosine
(query<->item) with item names so we can see where 'relevant' drops off.
"""
from __future__ import annotations
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import numpy as np

from src import config as C
from src.models.rag_retriever import get_encoder, load_index

QUERIES = [
    "giày thể thao",
    "giày bóng rổ",
    "áo bóng đá",
    "quần short tập gym",
    "balo đi học",
    "đồ thể thao",       # mơ hồ
    "xyz qwerty random",  # rác — sanity check
]
TOPK = 15


def main():
    encoder = get_encoder()
    index, meta = load_index()
    print(f"index: {index.ntotal} items\n")

    all_scores = []
    for q in QUERIES:
        qv = encoder.encode([q], normalize_embeddings=True, convert_to_numpy=True).astype(np.float32)
        D, I = index.search(qv, TOPK)
        scores = D[0]
        all_scores.extend(scores.tolist())
        print(f"=== '{q}' ===")
        for rank, (s, idx) in enumerate(zip(scores, I[0])):
            m = meta[idx]
            name = m.get("name", "?")
            cat = m.get("category", "?")
            print(f"  {rank:>2}  {s:.3f}  {name[:40]:<40} [{cat}]")
        print()

    arr = np.array(all_scores)
    print("--- overall top-K cosine distribution (all queries) ---")
    for p in (5, 10, 25, 50, 75, 90, 95):
        print(f"  p{p:<2} = {np.percentile(arr, p):.3f}")
    print(f"  min={arr.min():.3f}  max={arr.max():.3f}")


if __name__ == "__main__":
    main()
