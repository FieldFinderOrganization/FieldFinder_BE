"""Eval cả 3 model trên test split, dump metrics → JSON."""
from __future__ import annotations

import json
import logging
import random

import numpy as np
import torch

from .. import config as C
from ..datasets.deepfm_dataset import get_dataloaders as deepfm_loaders
from ..datasets.sasrec_dataset import get_dataloaders as sasrec_loaders
from ..models.deepfm import DeepFM
from ..models.sasrec import SASRec
from .metrics import compute_ranks, deepfm_metrics, hr_at_k, ndcg_at_k

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


def eval_sasrec(device) -> dict:
    ckpt_path = C.CHECKPOINT_DIR / "sasrec.pt"
    if not ckpt_path.exists():
        return {"error": "sasrec checkpoint missing"}
    ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
    _, _, test_loader, num_items = sasrec_loaders()
    model = SASRec(num_items=num_items).to(device)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    all_ranks = []
    with torch.no_grad():
        for batch in test_loader:
            inp = batch["input"].to(device)
            cand = batch["candidates"].to(device)
            scores = model.predict(inp, cand)
            all_ranks.append(compute_ranks(scores))
    ranks = np.concatenate(all_ranks)
    return {
        "HR@5": hr_at_k(ranks, 5),
        "HR@10": hr_at_k(ranks, 10),
        "NDCG@5": ndcg_at_k(ranks, 5),
        "NDCG@10": ndcg_at_k(ranks, 10),
        "n_users": int(len(ranks)),
    }


def eval_sasrec_full(device) -> dict:
    """Full-ranking: xếp target giữa TOÀN BỘ item (loại item đã xem) — TẤT ĐỊNH, không bốc negative.
    Khó hơn sampled-100 nên số thấp hơn, nhưng không phụ thuộc seed ⇒ là con số CHUẨN để báo cáo."""
    from ..datasets.sasrec_dataset import (
        PAD, build_user_sequences, load_encoders, split_seq_leave_last_out,
    )
    ckpt_path = C.CHECKPOINT_DIR / "sasrec.pt"
    if not ckpt_path.exists():
        return {"error": "sasrec checkpoint missing"}
    ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
    encs = load_encoders()
    num_items = len(encs["item"].classes_)
    model = SASRec(num_items=num_items).to(device)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    seqs = build_user_sequences()
    item_emb = model.item_emb.weight  # (num_items, D)
    ranks = []
    with torch.no_grad():
        for full in seqs.values():
            train_seq, val_t, test_t = split_seq_leave_last_out(full)
            seq = (train_seq + [val_t])[-C.SASREC_MAX_LEN:]
            if not seq:
                continue
            pad_n = C.SASREC_MAX_LEN - len(seq)
            input_ids = torch.tensor([[PAD] * pad_n + seq], dtype=torch.long, device=device)
            last = model.encode(input_ids)[:, -1, :]      # (1, D)
            scores = (last @ item_emb.t()).squeeze(0)      # (num_items,)
            scores[PAD] = float("-inf")
            for it in set(full):                           # loại item đã xem, GIỮ target
                if it != test_t:
                    scores[it] = float("-inf")
            rank = int((scores > scores[test_t]).sum().item())  # 0-indexed
            ranks.append(rank)
    ranks = np.array(ranks)
    return {
        "HR@5": hr_at_k(ranks, 5),
        "HR@10": hr_at_k(ranks, 10),
        "NDCG@5": ndcg_at_k(ranks, 5),
        "NDCG@10": ndcg_at_k(ranks, 10),
        "n_users": int(len(ranks)),
        "num_items": int(num_items),
        "protocol": "full-ranking (vs all items, exclude seen)",
    }


def eval_deepfm(device) -> dict:
    ckpt_path = C.CHECKPOINT_DIR / "deepfm.pt"
    if not ckpt_path.exists():
        return {"error": "deepfm checkpoint missing"}
    ckpt = torch.load(ckpt_path, map_location=device, weights_only=False)
    _, _, test_loader, field_dims = deepfm_loaders()
    model = DeepFM(field_dims=field_dims).to(device)
    model.load_state_dict(ckpt["model_state"])
    model.eval()

    ys, ps = [], []
    with torch.no_grad():
        for batch in test_loader:
            x = batch["x"].to(device)
            y = batch["y"].to(device)
            probs = torch.sigmoid(model(x))
            ys.append(y.cpu().numpy())
            ps.append(probs.cpu().numpy())
    return deepfm_metrics(np.concatenate(ys), np.concatenate(ps))


def main():
    # Seed cố định ⇒ negative sampling lúc eval tái lập được (số HR/NDCG không đổi giữa các lần chạy).
    random.seed(C.RANDOM_SEED)
    np.random.seed(C.RANDOM_SEED)
    torch.manual_seed(C.RANDOM_SEED)

    device = torch.device(C.DEVICE if torch.cuda.is_available() else "cpu")
    log.info("Device: %s", device)

    results = {
        "sasrec": eval_sasrec(device),
        "deepfm": eval_deepfm(device),
    }

    out = C.CHECKPOINT_DIR / "metrics.json"
    with open(out, "w", encoding="utf-8") as f:
        json.dump(results, f, indent=2)
    log.info("Saved %s", out)
    print(json.dumps(results, indent=2))


if __name__ == "__main__":
    main()
