"""Train SASRec — sequential next-item prediction."""
from __future__ import annotations

import logging
import time

import numpy as np
import torch
from torch.optim import Adam

from .. import config as C
from ..datasets.sasrec_dataset import get_dataloaders
from ..eval.metrics import compute_ranks, hr_at_k, ndcg_at_k
from ..models.sasrec import SASRec, bce_loss

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

CHECKPOINT = C.CHECKPOINT_DIR / "sasrec.pt"


def evaluate(model, loader, device):
    model.eval()
    all_ranks = []
    with torch.no_grad():
        for batch in loader:
            inp = batch["input"].to(device)
            cand = batch["candidates"].to(device)
            scores = model.predict(inp, cand)
            ranks = compute_ranks(scores)
            all_ranks.append(ranks)
    ranks = np.concatenate(all_ranks)
    return {
        "HR@10": hr_at_k(ranks, 10),
        "NDCG@10": ndcg_at_k(ranks, 10),
        "HR@5": hr_at_k(ranks, 5),
        "NDCG@5": ndcg_at_k(ranks, 5),
    }


def main():
    device = torch.device(C.DEVICE if torch.cuda.is_available() else "cpu")
    log.info("Device: %s", device)

    train_loader, val_loader, test_loader, num_items = get_dataloaders()
    log.info("num_items=%d  train_batches=%d", num_items, len(train_loader))

    model = SASRec(num_items=num_items).to(device)
    opt = Adam(model.parameters(), lr=C.SASREC_LR, betas=(0.9, 0.98))

    best_ndcg = 0.0
    patience = 10
    bad_epochs = 0

    for epoch in range(1, C.SASREC_EPOCHS + 1):
        model.train()
        t0 = time.time()
        total_loss = 0.0
        n_batch = 0
        for batch in train_loader:
            inp = batch["input"].to(device)
            pos = batch["pos"].to(device)
            neg = batch["neg"].to(device)
            opt.zero_grad()
            pos_logits, neg_logits = model(inp, pos, neg)
            loss = bce_loss(pos_logits, neg_logits, pos)
            loss.backward()
            opt.step()
            total_loss += loss.item()
            n_batch += 1

        avg_loss = total_loss / max(1, n_batch)

        # Eval val
        if epoch % 5 == 0 or epoch == 1:
            metrics = evaluate(model, val_loader, device)
            log.info(
                "Epoch %3d  loss=%.4f  HR@10=%.4f  NDCG@10=%.4f  (%.1fs)",
                epoch, avg_loss, metrics["HR@10"], metrics["NDCG@10"], time.time() - t0,
            )
            if metrics["NDCG@10"] > best_ndcg:
                best_ndcg = metrics["NDCG@10"]
                bad_epochs = 0
                torch.save({
                    "model_state": model.state_dict(),
                    "num_items": num_items,
                    "epoch": epoch,
                    "metrics": metrics,
                }, CHECKPOINT)
                log.info("  → Saved checkpoint (NDCG@10 = %.4f)", best_ndcg)
            else:
                bad_epochs += 1
                if bad_epochs >= patience:
                    log.info("Early stop at epoch %d", epoch)
                    break
        else:
            log.info("Epoch %3d  loss=%.4f  (%.1fs)", epoch, avg_loss, time.time() - t0)

    # Test
    log.info("Loading best checkpoint...")
    ckpt = torch.load(CHECKPOINT, map_location=device, weights_only=False)
    model.load_state_dict(ckpt["model_state"])
    test_metrics = evaluate(model, test_loader, device)
    log.info("=== TEST ===")
    for k, v in test_metrics.items():
        log.info("  %s = %.4f", k, v)


if __name__ == "__main__":
    main()
