"""Clean logs, flatten context, encode IDs, time-based train/val/test split."""
from __future__ import annotations

import logging
import pickle
import re
from pathlib import Path
from typing import Tuple

import numpy as np
import pandas as pd
from sklearn.preprocessing import LabelEncoder

from . import config as C

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

UUID_RE = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
    re.IGNORECASE,
)
ENCODER_DIR = C.PROCESSED_DIR / "encoders"
ENCODER_DIR.mkdir(parents=True, exist_ok=True)


# ============== Load ==============
def _load_raw() -> Tuple[pd.DataFrame, pd.DataFrame, pd.DataFrame, pd.DataFrame]:
    logs = pd.read_parquet(C.RAW_DIR / "interaction_logs.parquet")
    users = pd.read_parquet(C.RAW_DIR / "users.parquet") if (C.RAW_DIR / "users.parquet").exists() else pd.DataFrame()
    pitches = pd.read_parquet(C.RAW_DIR / "pitches.parquet") if (C.RAW_DIR / "pitches.parquet").exists() else pd.DataFrame()
    products = pd.read_parquet(C.RAW_DIR / "products.parquet") if (C.RAW_DIR / "products.parquet").exists() else pd.DataFrame()
    return logs, users, pitches, products


# ============== Clean ==============
def _is_valid_uuid(s) -> bool:
    if not isinstance(s, str):
        return False
    if "efbfbd" in s.lower():
        return False
    return bool(UUID_RE.match(s))


def _is_valid_item_id(s) -> bool:
    """Pitch dùng UUID, Product dùng int. Cả 2 OK miễn không corrupt + non-empty."""
    if s is None:
        return False
    s = str(s).strip()
    if not s or s.lower() == "nan":
        return False
    return "efbfbd" not in s.lower()


def clean_logs(df: pd.DataFrame) -> pd.DataFrame:
    n0 = len(df)
    # timestamp parse
    df["timestamp"] = pd.to_datetime(df["timestamp"], errors="coerce", utc=True)
    df = df.dropna(subset=["timestamp"])

    # eventType, sessionId, itemType phải có
    df = df.dropna(subset=["eventType", "sessionId"])

    # Drop log itemId không hợp lệ với event yêu cầu item
    # IMPRESSION_LIST có itemId=null nhưng items nằm trong eventMetadata.shownItemIds
    needs_item = df["eventType"].isin(C.POSITIVE_EVENTS)
    bad_item = needs_item & ~df["itemId"].apply(_is_valid_item_id)
    df = df[~bad_item]

    # itemType phải nằm trong tập đã biết (cho event positive)
    pos_mask = df["eventType"].isin(C.POSITIVE_EVENTS)
    df = df[(~pos_mask) | df["itemType"].isin(C.ITEM_TYPES)]

    log.info("Clean logs: %d → %d (drop %d)", n0, len(df), n0 - len(df))
    return df.reset_index(drop=True)


def clean_entity(df: pd.DataFrame, id_col: str) -> pd.DataFrame:
    if df.empty:
        return df
    df = df.dropna(subset=[id_col])
    df = df[df[id_col].astype(str).apply(_is_valid_item_id)]
    return df.reset_index(drop=True)


# ============== Flatten ==============
def flatten_context(df: pd.DataFrame) -> pd.DataFrame:
    """Expand context map → column phẳng."""
    if "context" not in df.columns:
        return df
    ctx = df["context"].apply(lambda x: x if isinstance(x, dict) else {})

    df["weather"] = ctx.apply(lambda c: c.get("weather", "UNKNOWN"))
    df["device_model"] = ctx.apply(lambda c: c.get("device_model", "UNKNOWN"))
    df["os"] = ctx.apply(lambda c: c.get("os", "UNKNOWN"))
    df["user_age_at_event"] = ctx.apply(lambda c: c.get("user_age_at_event"))
    df["user_gender_snap"] = ctx.apply(lambda c: c.get("user_gender", "UNKNOWN"))
    df["item_price_snapshot"] = ctx.apply(lambda c: c.get("item_price_snapshot"))
    df["item_category_snap"] = ctx.apply(lambda c: c.get("item_category", "UNKNOWN"))
    df["lat"] = ctx.apply(lambda c: (c.get("location") or {}).get("lat") if isinstance(c.get("location"), dict) else None)
    df["lng"] = ctx.apply(lambda c: (c.get("location") or {}).get("lng") if isinstance(c.get("location"), dict) else None)
    return df


def add_time_features(df: pd.DataFrame) -> pd.DataFrame:
    ts = df["timestamp"].dt.tz_convert("Asia/Ho_Chi_Minh") if df["timestamp"].dt.tz is not None else df["timestamp"]
    df["hour"] = ts.dt.hour
    df["dow"] = ts.dt.dayofweek + 1  # 1 = Monday
    df["is_weekend"] = df["dow"] >= 6
    df["hour_bucket"] = pd.cut(
        df["hour"],
        bins=[-1, 5, 11, 17, 21, 24],
        labels=["NIGHT", "MORNING", "AFTERNOON", "EVENING", "LATE"],
    ).astype(str)
    df["price_bucket"] = pd.cut(
        df["item_price_snapshot"].fillna(-1).astype(float),
        bins=[-2, 0, 100_000, 250_000, 500_000, 1_000_000, 1e12],
        labels=["UNKNOWN", "VERY_LOW", "LOW", "MID", "HIGH", "VIP"],
    ).astype(str)
    return df


# ============== Encode ==============
def fit_label_encoders(df: pd.DataFrame, cols: list[str]) -> dict[str, LabelEncoder]:
    encs = {}
    for col in cols:
        le = LabelEncoder()
        vals = df[col].fillna("UNKNOWN").astype(str).tolist() + ["<PAD>", "<UNK>"]
        le.fit(vals)
        encs[col] = le
    return encs


def transform_with_encoders(df: pd.DataFrame, encs: dict[str, LabelEncoder]) -> pd.DataFrame:
    for col, le in encs.items():
        vals = df[col].fillna("UNKNOWN").astype(str)
        known = set(le.classes_)
        vals = vals.apply(lambda v: v if v in known else "<UNK>")
        df[col + "_idx"] = le.transform(vals)
    return df


def save_encoders(encs: dict[str, LabelEncoder], path: Path) -> None:
    with open(path, "wb") as f:
        pickle.dump(encs, f)
    log.info("Saved encoders → %s", path)


# ============== Split ==============
def time_split(df: pd.DataFrame) -> dict[str, pd.DataFrame]:
    df = df.sort_values("timestamp").reset_index(drop=True)
    n = len(df)
    n_train = int(n * C.TRAIN_RATIO)
    n_val = int(n * (C.TRAIN_RATIO + C.VAL_RATIO))
    splits = {
        "train": df.iloc[:n_train],
        "val": df.iloc[n_train:n_val],
        "test": df.iloc[n_val:],
    }
    for k, v in splits.items():
        log.info("Split %s: %d rows (%s → %s)", k, len(v), v["timestamp"].min(), v["timestamp"].max())
    return splits


# ============== Pipeline ==============
def run() -> None:
    logs, users, pitches, products = _load_raw()

    logs = clean_logs(logs)
    logs = flatten_context(logs)
    logs = add_time_features(logs)

    pitches = clean_entity(pitches, "pitch_id")
    products = clean_entity(products, "product_id")
    users = clean_entity(users, "user_id") if not users.empty else users

    # Build unified item vocab (PITCH + PRODUCT)
    pitch_ids = pitches["pitch_id"].tolist() if not pitches.empty else []
    product_ids = products["product_id"].tolist() if not products.empty else []
    all_items = ["<PAD>", "<UNK>"] + [f"P_{x}" for x in pitch_ids] + [f"T_{x}" for x in product_ids]

    item_le = LabelEncoder()
    item_le.fit(all_items)

    def _prefix(row):
        if row["itemType"] == "PITCH":
            return f"P_{row['itemId']}"
        if row["itemType"] == "PRODUCT":
            return f"T_{row['itemId']}"
        return "<UNK>"

    logs["item_key"] = logs.apply(_prefix, axis=1)
    logs["item_idx"] = item_le.transform(
        logs["item_key"].apply(lambda v: v if v in set(item_le.classes_) else "<UNK>")
    )

    # User encoder
    user_le = LabelEncoder()
    user_pool = ["<PAD>", "<UNK>"] + (users["user_id"].dropna().astype(str).tolist() if not users.empty else [])
    # bổ sung user xuất hiện trong logs (guest_xxx, user uuid)
    user_pool += logs["userId"].fillna("GUEST").astype(str).unique().tolist()
    user_le.fit(list(set(user_pool)))
    logs["user_idx"] = user_le.transform(
        logs["userId"].fillna("GUEST").astype(str).apply(
            lambda v: v if v in set(user_le.classes_) else "<UNK>"
        )
    )

    # Categorical encoders cho context
    cat_cols = ["weather", "os", "hour_bucket", "price_bucket", "user_gender_snap", "item_category_snap", "eventType", "itemType"]
    encs = fit_label_encoders(logs, cat_cols)
    logs = transform_with_encoders(logs, encs)

    # Save
    encs["item"] = item_le
    encs["user"] = user_le
    save_encoders(encs, ENCODER_DIR / "encoders.pkl")

    splits = time_split(logs)
    for k, v in splits.items():
        out = C.PROCESSED_DIR / f"logs_{k}.parquet"
        v.to_parquet(out, index=False)
        log.info("Saved %s", out)

    # Save entity tables (encoded once)
    if not users.empty:
        users.to_parquet(C.PROCESSED_DIR / "users.parquet", index=False)
    if not pitches.empty:
        pitches.to_parquet(C.PROCESSED_DIR / "pitches.parquet", index=False)
    if not products.empty:
        products.to_parquet(C.PROCESSED_DIR / "products.parquet", index=False)

    log.info("Preprocess done. Vocab: items=%d users=%d", len(item_le.classes_), len(user_le.classes_))


if __name__ == "__main__":
    run()
