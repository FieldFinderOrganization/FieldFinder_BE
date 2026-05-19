"""Train DeepFM — context-aware CTR rerank."""
from __future__ import annotations

import logging
import time

import numpy as np
import torch
import torch.nn as nn
from torch.optim import Adam

from .. import config as C
from ..datasets.deepfm_dataset import get_dataloaders
from ..eval.metrics import deepfm_metrics
from ..models.deepfm import DeepFM

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

CHECKPOINT = C.CHECKPOINT_DIR / "deepfm.pt"


def evaluate(model, loader, device):
    model.eval()
    ys, ps = [], []
    with torch.no_grad():
        for batch in loader:
            x = batch["x"].to(device)
            y = batch["y"].to(device)
            logits = model(x)
            probs = torch.sigmoid(logits)
            ys.append(y.cpu().numpy())
            ps.append(probs.cpu().numpy())
    return deepfm_metrics(np.concatenate(ys), np.concatenate(ps))


def main():
    device = torch.device(C.DEVICE if torch.cuda.is_available() else "cpu")
    log.info("Device: %s", device)

    train_loader, val_loader, test_loader, field_dims = get_dataloaders()
    log.info("field_dims=%s  train_batches=%d", field_dims, len(train_loader))

    model = DeepFM(field_dims=field_dims).to(device)
    opt = Adam(model.parameters(), lr=C.DEEPFM_LR, weight_decay=1e-5)
    crit = nn.BCEWithLogitsLoss()

    best_auc = 0.0
    patience = 15
    bad_epochs = 0

    for epoch in range(1, C.DEEPFM_EPOCHS + 1):
        model.train()
        t0 = time.time()
        total_loss = 0.0
        n_batch = 0
        for batch in train_loader:
            x = batch["x"].to(device)
            y = batch["y"].to(device)
            opt.zero_grad()
            logits = model(x)
            loss = crit(logits, y)
            loss.backward()
            opt.step()
            total_loss += loss.item()
            n_batch += 1

        avg_loss = total_loss / max(1, n_batch)
        val_metrics = evaluate(model, val_loader, device)
        log.info(
            "Epoch %3d  loss=%.4f  val_AUC=%.4f  val_LL=%.4f  (%.1fs)",
            epoch, avg_loss, val_metrics["auc"], val_metrics["logloss"], time.time() - t0,
        )

        if val_metrics["auc"] > best_auc:
            best_auc = val_metrics["auc"]
            bad_epochs = 0
            torch.save({
                "model_state": model.state_dict(),
                "field_dims": field_dims,
                "epoch": epoch,
                "metrics": val_metrics,
            }, CHECKPOINT)
            log.info("  → Saved checkpoint (AUC = %.4f)", best_auc)
        else:
            bad_epochs += 1
            if bad_epochs >= patience:
                log.info("Early stop at epoch %d", epoch)
                break

    # Test
    log.info("Loading best checkpoint...")
    ckpt = torch.load(CHECKPOINT, map_location=device, weights_only=False)
    model.load_state_dict(ckpt["model_state"])
    test_metrics = evaluate(model, test_loader, device)
    log.info("=== TEST ===")
    for k, v in test_metrics.items():
        log.info("  %s = %s", k, v)


if __name__ == "__main__":
    main()
