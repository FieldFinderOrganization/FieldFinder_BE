"""DeepFM dataset — positive (click/view/buy) + negative (impression no-click)."""
from __future__ import annotations

import logging
import pickle
import random
from pathlib import Path
from typing import Tuple

import numpy as np
import pandas as pd
import torch
from sklearn.preprocessing import LabelEncoder
from torch.utils.data import Dataset

from .. import config as C

log = logging.getLogger(__name__)

CAT_FIELDS = [
    "user_idx",
    "item_idx",
    "weather_idx",
    "os_idx",
    "hour_bucket_idx",
    "price_bucket_idx",
    "user_gender_snap_idx",
    "item_category_snap_idx",
    "itemType_idx",
]


def load_encoders():
    with open(C.PROCESSED_DIR / "encoders" / "encoders.pkl", "rb") as f:
        return pickle.load(f)


# ============== Build positive / negative ==============
def _explode_impressions(df: pd.DataFrame, item_le: LabelEncoder) -> pd.DataFrame:
    """IMPRESSION_LIST event có metadata.shownItemIds[] → explode thành 1 row per shown item."""
    imp = df[df["eventType"] == C.IMPRESSION_EVENT].copy()
    if imp.empty:
        return pd.DataFrame()

    import numpy as np
    rows = []
    for _, r in imp.iterrows():
        meta = r.get("eventMetadata")
        if meta is None or (isinstance(meta, float) and np.isnan(meta)):
            meta = {}
        shown = meta.get("shownItemIds") if isinstance(meta, dict) else None
        if shown is None or (hasattr(shown, "__len__") and len(shown) == 0):
            continue
        shown = list(shown)
        positions = meta.get("positions")
        if positions is None or (hasattr(positions, "__len__") and len(positions) == 0):
            positions = list(range(len(shown)))
        else:
            positions = list(positions)
        item_type = meta.get("itemType", r.get("itemType", "PRODUCT"))
        for pos, iid in zip(positions, shown):
            key = f"P_{iid}" if item_type == "PITCH" else f"T_{iid}"
            rows.append({
                **r.to_dict(),
                "itemId": iid,
                "itemType": item_type,
                "item_key": key,
                "position": pos,
            })

    out = pd.DataFrame(rows)
    classes = set(item_le.classes_)
    out["item_idx"] = item_le.transform(out["item_key"].apply(lambda v: v if v in classes else "<UNK>"))
    return out


def build_deepfm_frame(split: str, encs: dict) -> pd.DataFrame:
    """Trả DataFrame có column CAT_FIELDS + label."""
    assert split in ("train", "val", "test")
    df = pd.read_parquet(C.PROCESSED_DIR / f"logs_{split}.parquet")
    item_le = encs["item"]

    # Positive
    pos = df[df["eventType"].isin(C.POSITIVE_EVENTS)].copy()
    pos["label"] = 1.0

    # Negative từ impression
    imp = _explode_impressions(df, item_le)
    if not imp.empty:
        # Drop impression nếu cùng (user, item, session) đã click → click thắng
        click_keys = set(zip(pos["user_idx"], pos["item_idx"], pos["sessionId"]))
        mask = imp.apply(lambda r: (r["user_idx"], r["item_idx"], r["sessionId"]) not in click_keys, axis=1)
        neg = imp[mask].copy()
        neg["label"] = 0.0
    else:
        neg = pd.DataFrame()

    # Sample neg theo NEG_RATIO
    if not neg.empty and len(neg) > C.DEEPFM_NEG_RATIO * len(pos):
        neg = neg.sample(n=C.DEEPFM_NEG_RATIO * len(pos), random_state=C.RANDOM_SEED)

    parts = [pos]
    if not neg.empty:
        parts.append(neg)
    out = pd.concat(parts, ignore_index=True)

    # Đảm bảo các col idx tồn tại — nếu thiếu, fill UNK idx (1)
    for col in CAT_FIELDS:
        if col not in out.columns:
            out[col] = 1
        out[col] = out[col].fillna(1).astype(np.int64)

    out = out[CAT_FIELDS + ["label"]].sample(frac=1, random_state=C.RANDOM_SEED).reset_index(drop=True)
    log.info("DeepFM %s: %d rows (pos=%d, neg=%d)", split, len(out), len(pos), len(neg) if not neg.empty else 0)
    return out


def field_dims(encs: dict) -> list[int]:
    """Trả list số class per field theo CAT_FIELDS."""
    map_ = {
        "user_idx": len(encs["user"].classes_),
        "item_idx": len(encs["item"].classes_),
        "weather_idx": len(encs["weather"].classes_),
        "os_idx": len(encs["os"].classes_),
        "hour_bucket_idx": len(encs["hour_bucket"].classes_),
        "price_bucket_idx": len(encs["price_bucket"].classes_),
        "user_gender_snap_idx": len(encs["user_gender_snap"].classes_),
        "item_category_snap_idx": len(encs["item_category_snap"].classes_),
        "itemType_idx": len(encs["itemType"].classes_),
    }
    return [map_[f] for f in CAT_FIELDS]


# ============== Torch Dataset ==============
class DeepFMDataset(Dataset):
    def __init__(self, frame: pd.DataFrame):
        self.X = frame[CAT_FIELDS].values.astype(np.int64)
        self.y = frame["label"].values.astype(np.float32)

    def __len__(self) -> int:
        return len(self.y)

    def __getitem__(self, idx: int):
        return {
            "x": torch.from_numpy(self.X[idx]),
            "y": torch.tensor(self.y[idx]),
        }


def get_dataloaders():
    from torch.utils.data import DataLoader

    encs = load_encoders()
    train_frame = build_deepfm_frame("train", encs)
    val_frame = build_deepfm_frame("val", encs)
    test_frame = build_deepfm_frame("test", encs)

    train_loader = DataLoader(DeepFMDataset(train_frame), batch_size=C.DEEPFM_BATCH, shuffle=True, drop_last=True)
    val_loader = DataLoader(DeepFMDataset(val_frame), batch_size=C.DEEPFM_BATCH, shuffle=False)
    test_loader = DataLoader(DeepFMDataset(test_frame), batch_size=C.DEEPFM_BATCH, shuffle=False)

    return train_loader, val_loader, test_loader, field_dims(encs)
