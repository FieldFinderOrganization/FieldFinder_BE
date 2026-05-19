"""Sanity check: model forward + loss + simple preprocess pipeline.

Run từ ml root:
    python -m scripts.smoke_test

Hoặc:
    python scripts/smoke_test.py
"""
from __future__ import annotations

import sys
import traceback
from pathlib import Path

import numpy as np
import torch

# Cho phép chạy trực tiếp `python scripts/smoke_test.py`
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))


def section(title: str):
    print("\n" + "=" * 60)
    print(f"  {title}")
    print("=" * 60)


# ============== 1. Config ==============
def test_config():
    section("1. Config")
    from src import config as C
    print(f"ROOT       : {C.ROOT_DIR}")
    print(f"RAW_DIR    : {C.RAW_DIR}  exists={C.RAW_DIR.exists()}")
    print(f"PROCESSED  : {C.PROCESSED_DIR}  exists={C.PROCESSED_DIR.exists()}")
    print(f"CHECKPOINTS: {C.CHECKPOINT_DIR}  exists={C.CHECKPOINT_DIR.exists()}")
    print(f"DEVICE     : {C.DEVICE}")
    print(f"POSITIVE   : {sorted(C.POSITIVE_EVENTS)}")
    print("OK")


# ============== 2. SASRec ==============
def test_sasrec():
    section("2. SASRec forward + loss")
    from src.models.sasrec import SASRec, bce_loss

    num_items = 500
    B, L = 4, 50
    model = SASRec(num_items=num_items, max_len=L)
    print(f"Params: {sum(p.numel() for p in model.parameters()):,}")

    # Random batch (idx 0=PAD, ≥2 valid)
    inp = torch.randint(2, num_items, (B, L))
    inp[:, :10] = 0  # padding đầu
    pos = torch.randint(2, num_items, (B, L))
    pos[:, :10] = 0
    neg = torch.randint(2, num_items, (B, L))
    neg[:, :10] = 0

    pos_logits, neg_logits = model(inp, pos, neg)
    loss = bce_loss(pos_logits, neg_logits, pos)
    print(f"pos_logits: {pos_logits.shape}")
    print(f"neg_logits: {neg_logits.shape}")
    print(f"loss      : {loss.item():.4f}")

    # Predict path
    cand = torch.randint(2, num_items, (B, 101))
    scores = model.predict(inp, cand)
    print(f"predict scores: {scores.shape}")

    # Backward
    loss.backward()
    print("backward OK")
    print("OK")


# ============== 3. DeepFM ==============
def test_deepfm():
    section("3. DeepFM forward + loss")
    from src.models.deepfm import DeepFM

    field_dims = [1000, 5000, 10, 5, 5, 6, 4, 50, 4]
    model = DeepFM(field_dims=field_dims)
    print(f"Params: {sum(p.numel() for p in model.parameters()):,}")

    B, F = 16, len(field_dims)
    x = torch.stack([torch.randint(0, d, (B,)) for d in field_dims], dim=1)
    y = torch.randint(0, 2, (B,)).float()

    logits = model(x)
    loss = torch.nn.functional.binary_cross_entropy_with_logits(logits, y)
    print(f"x      : {x.shape}")
    print(f"logits : {logits.shape}")
    print(f"loss   : {loss.item():.4f}")
    loss.backward()
    print("backward OK")
    print("OK")


# ============== 4. Preprocess pipeline (optional - cần data) ==============
def test_preprocess_pipeline():
    section("4. Preprocess pipeline (optional)")
    from src import config as C

    log_path = C.RAW_DIR / "interaction_logs.parquet"
    if not log_path.exists():
        print(f"SKIP — chưa có {log_path}")
        print("Chạy `python -m src.data_loader --json <path> --csv-dir <dir>` trước.")
        return

    try:
        from src.preprocess import run as preprocess_run
        preprocess_run()
        print("Preprocess OK")
    except Exception:
        traceback.print_exc()
        print("Preprocess FAIL")


# ============== 5. SASRec dataset (optional) ==============
def test_sasrec_dataset():
    section("5. SASRec dataset (optional)")
    from src import config as C

    if not (C.PROCESSED_DIR / "logs_train.parquet").exists():
        print("SKIP — chưa preprocess.")
        return

    try:
        from src.datasets.sasrec_dataset import get_dataloaders
        train_loader, val_loader, test_loader, num_items = get_dataloaders()
        print(f"num_items: {num_items}")
        print(f"train batches: {len(train_loader)}")
        batch = next(iter(train_loader))
        print(f"input: {batch['input'].shape}, pos: {batch['pos'].shape}, neg: {batch['neg'].shape}")
        print("OK")
    except Exception:
        traceback.print_exc()
        print("Dataset FAIL — có thể seq/user quá ngắn (cần ≥20 event)")


# ============== 6. DeepFM dataset (optional) ==============
def test_deepfm_dataset():
    section("6. DeepFM dataset (optional)")
    from src import config as C

    if not (C.PROCESSED_DIR / "logs_train.parquet").exists():
        print("SKIP — chưa preprocess.")
        return

    try:
        from src.datasets.deepfm_dataset import get_dataloaders
        train_loader, val_loader, test_loader, dims = get_dataloaders()
        print(f"field_dims: {dims}")
        print(f"train batches: {len(train_loader)}")
        batch = next(iter(train_loader))
        print(f"x: {batch['x'].shape}, y: {batch['y'].shape}")
        print(f"label dist: pos={batch['y'].sum().item():.0f}/{len(batch['y'])}")
        print("OK")
    except Exception:
        traceback.print_exc()
        print("Dataset FAIL — có thể thiếu IMPRESSION_LIST nên không có negative")


# ============== Main ==============
def main():
    results = {}
    for name, fn in [
        ("config", test_config),
        ("sasrec_model", test_sasrec),
        ("deepfm_model", test_deepfm),
        ("preprocess", test_preprocess_pipeline),
        ("sasrec_dataset", test_sasrec_dataset),
        ("deepfm_dataset", test_deepfm_dataset),
    ]:
        try:
            fn()
            results[name] = "PASS"
        except Exception as e:
            traceback.print_exc()
            results[name] = f"FAIL ({e})"

    section("SUMMARY")
    for k, v in results.items():
        print(f"  {k:20s} → {v}")


if __name__ == "__main__":
    main()
