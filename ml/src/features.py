"""Build user / item / context feature tables for DeepFM + RAG."""
from __future__ import annotations

import logging
from datetime import datetime
from pathlib import Path

import numpy as np
import pandas as pd

from . import config as C

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


# ============== Buckets ==============
def age_bucket(age) -> str:
    if age is None or pd.isna(age):
        return "UNKNOWN"
    age = float(age)
    if age < 18:
        return "TEEN"
    if age < 25:
        return "YOUNG"
    if age < 35:
        return "ADULT"
    if age < 50:
        return "MIDAGE"
    return "SENIOR"


def price_bucket(price) -> str:
    if price is None or pd.isna(price):
        return "UNKNOWN"
    p = float(price)
    if p <= 100_000:
        return "VERY_LOW"
    if p <= 250_000:
        return "LOW"
    if p <= 500_000:
        return "MID"
    if p <= 1_000_000:
        return "HIGH"
    return "VIP"


def _years_between(dob, now: datetime) -> float | None:
    if dob is None or pd.isna(dob):
        return None
    if isinstance(dob, str):
        try:
            dob = pd.to_datetime(dob)
        except Exception:
            return None
    return (now - dob).days / 365.25


# ============== User features ==============
def build_user_features(users: pd.DataFrame, logs: pd.DataFrame) -> pd.DataFrame:
    """Aggregate hành vi user + demographic → table feature."""
    now = datetime.utcnow()

    if not users.empty:
        users = users.copy()
        users["age"] = users["date_of_birth"].apply(lambda d: _years_between(d, now))
        users["age_bucket"] = users["age"].apply(age_bucket)
        users["gender"] = users["gender"].fillna("UNKNOWN")
        users["province"] = users["province"].fillna("UNKNOWN")
        users["occupation"] = users["occupation"].fillna("UNKNOWN")
    else:
        users = pd.DataFrame(columns=["user_id", "age", "age_bucket", "gender", "province", "occupation"])

    # Aggregate behavior từ log
    pos_logs = logs[logs["eventType"].isin(C.POSITIVE_EVENTS)].copy()
    pos_logs["userId"] = pos_logs["userId"].fillna("GUEST").astype(str)

    agg = pos_logs.groupby("userId").agg(
        total_events=("eventType", "count"),
        n_views=("eventType", lambda s: (s.isin({"VIEW_PITCH", "VIEW_PRODUCT"})).sum()),
        n_carts=("eventType", lambda s: (s == "ADD_TO_CART").sum()),
        n_orders=("eventType", lambda s: (s == "CREATE_ORDER").sum()),
        n_bookings=("eventType", lambda s: (s == "CREATE_BOOKING").sum()),
    ).reset_index().rename(columns={"userId": "user_id"})

    # Top category
    top_cat = (
        pos_logs.groupby(["userId", "item_category_snap"]).size()
        .reset_index(name="cnt")
        .sort_values(["userId", "cnt"], ascending=[True, False])
        .drop_duplicates("userId")
        .rename(columns={"userId": "user_id", "item_category_snap": "fav_category"})
        [["user_id", "fav_category"]]
    )

    # Avg price spent
    avg_price = (
        pos_logs.groupby("userId")["item_price_snapshot"].mean()
        .reset_index().rename(columns={"userId": "user_id", "item_price_snapshot": "avg_price"})
    )
    avg_price["price_pref_bucket"] = avg_price["avg_price"].apply(price_bucket)

    # Merge
    out = users.merge(agg, on="user_id", how="outer")
    out = out.merge(top_cat, on="user_id", how="left")
    out = out.merge(avg_price, on="user_id", how="left")
    out = out.fillna({
        "age_bucket": "UNKNOWN",
        "gender": "UNKNOWN",
        "province": "UNKNOWN",
        "occupation": "UNKNOWN",
        "fav_category": "UNKNOWN",
        "price_pref_bucket": "UNKNOWN",
        "total_events": 0,
        "n_views": 0,
        "n_carts": 0,
        "n_orders": 0,
        "n_bookings": 0,
    })

    log.info("User features: %d rows, %d cols", len(out), out.shape[1])
    return out


# ============== Item features ==============
def build_pitch_features(pitches: pd.DataFrame) -> pd.DataFrame:
    if pitches.empty:
        return pd.DataFrame()
    p = pitches.copy()
    p["item_id"] = p["pitch_id"].astype(str)
    p["item_type"] = "PITCH"
    p["item_key"] = "P_" + p["item_id"]
    p["price_bucket"] = p["price"].apply(price_bucket)
    p["category"] = p["pitch_type"].fillna("UNKNOWN")
    p["env"] = p["environment"].fillna("UNKNOWN")
    p["brand"] = "NA"
    p["sex"] = "NA"
    p["tags"] = ""  # pitches have no product tags — keep column for concat
    p["dominant_color"] = ""  # pitches have no color — keep column for concat
    return p[["item_id", "item_type", "item_key", "price", "price_bucket", "category", "env", "brand", "sex", "tags", "dominant_color", "name", "description"]]


def build_product_features(products: pd.DataFrame) -> pd.DataFrame:
    if products.empty:
        return pd.DataFrame()
    p = products.copy()
    p["item_id"] = p["product_id"].astype(str)
    p["item_type"] = "PRODUCT"
    p["item_key"] = "T_" + p["item_id"]
    p["price_bucket"] = p["price"].apply(price_bucket)
    p["category"] = p["category_id"].fillna("UNKNOWN").astype(str)
    p["env"] = "NA"
    p["brand"] = p.get("brand", pd.Series(dtype=str)).fillna("UNKNOWN")
    p["sex"] = p.get("sex", pd.Series(dtype=str)).fillna("UNKNOWN")
    # tags: rich VN keywords (giày, sneaker, giày thể thao…) — key relevance signal
    p["tags"] = p.get("tags", pd.Series(dtype=str)).fillna("")
    # dominant_color: màu chủ đạo CHUẨN (sạch hơn tags) — tín hiệu màu cho retrieve text
    p["dominant_color"] = p.get("dominant_color", pd.Series(dtype=str)).fillna("")
    return p[["item_id", "item_type", "item_key", "price", "price_bucket", "category", "env", "brand", "sex", "tags", "dominant_color", "name", "description"]]


def build_item_features(pitches: pd.DataFrame, products: pd.DataFrame) -> pd.DataFrame:
    parts = []
    pf = build_pitch_features(pitches)
    if not pf.empty:
        parts.append(pf)
    tf = build_product_features(products)
    if not tf.empty:
        parts.append(tf)
    if not parts:
        return pd.DataFrame()
    out = pd.concat(parts, ignore_index=True)
    log.info("Item features: %d rows (pitch=%d, product=%d)", len(out), len(pf), len(tf))
    return out


# ============== Run ==============
def run() -> None:
    logs = pd.read_parquet(C.PROCESSED_DIR / "logs_train.parquet")
    val = pd.read_parquet(C.PROCESSED_DIR / "logs_val.parquet")
    test = pd.read_parquet(C.PROCESSED_DIR / "logs_test.parquet")
    logs_all = pd.concat([logs, val, test], ignore_index=True)

    users = pd.read_parquet(C.PROCESSED_DIR / "users.parquet") if (C.PROCESSED_DIR / "users.parquet").exists() else pd.DataFrame()
    pitches = pd.read_parquet(C.PROCESSED_DIR / "pitches.parquet") if (C.PROCESSED_DIR / "pitches.parquet").exists() else pd.DataFrame()
    products = pd.read_parquet(C.PROCESSED_DIR / "products.parquet") if (C.PROCESSED_DIR / "products.parquet").exists() else pd.DataFrame()

    # Build features chỉ từ TRAIN (tránh leak), nhưng item table OK lấy full vì nó static
    user_feat = build_user_features(users, logs)  # logs = train only
    item_feat = build_item_features(pitches, products)

    user_feat.to_parquet(C.PROCESSED_DIR / "user_features.parquet", index=False)
    item_feat.to_parquet(C.PROCESSED_DIR / "item_features.parquet", index=False)
    log.info("Saved user_features + item_features")


if __name__ == "__main__":
    run()
