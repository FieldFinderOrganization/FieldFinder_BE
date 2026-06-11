"""Pull data từ Mongo (logs) + MySQL (users/pitches/products) → parquet."""
from __future__ import annotations

import json
import logging
from pathlib import Path
from typing import Optional

import pandas as pd
from pymongo import MongoClient
from sqlalchemy import create_engine, text
from tqdm import tqdm

from . import config as C

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)


# ============== MongoDB ==============
def _mongo_client() -> MongoClient:
    return MongoClient(C.MONGO_URI, serverSelectionTimeoutMS=5000)


def fetch_interaction_logs(
    limit: Optional[int] = None,
    save: bool = True,
) -> pd.DataFrame:
    """Pull all logs từ user_interaction_logs collection."""
    log.info("Connecting Mongo: %s / %s", C.MONGO_DB, C.MONGO_COLLECTION_LOGS)
    client = _mongo_client()
    coll = client[C.MONGO_DB][C.MONGO_COLLECTION_LOGS]

    cursor = coll.find({}, batch_size=1000)
    if limit:
        cursor = cursor.limit(limit)

    total = coll.estimated_document_count() if not limit else limit
    docs = []
    for doc in tqdm(cursor, total=total, desc="logs"):
        doc.pop("_class", None)
        docs.append(doc)
    client.close()

    df = pd.DataFrame(docs)
    log.info("Fetched %d logs", len(df))

    if save:
        out = C.RAW_DIR / "interaction_logs.parquet"
        # Map / dict columns serialize OK với pyarrow
        df.to_parquet(out, index=False)
        log.info("Saved %s", out)

        # Backup JSON for debugging
        out_json = C.RAW_DIR / "interaction_logs.json"
        df.head(100).to_json(out_json, orient="records", force_ascii=False, indent=2)

    return df


def fetch_logs_from_json(path: str | Path) -> pd.DataFrame:
    """Fallback: load script-generated JSON (ai_interaction_logs.json)."""
    path = Path(path)
    log.info("Loading JSON: %s", path)
    with open(path, encoding="utf-8") as f:
        docs = json.load(f)
    # Normalize $date wrapper từ Mongo extended JSON
    for d in docs:
        ts = d.get("timestamp")
        if isinstance(ts, dict) and "$date" in ts:
            d["timestamp"] = ts["$date"]
    df = pd.DataFrame(docs)
    out = C.RAW_DIR / "interaction_logs.parquet"
    df.to_parquet(out, index=False)
    log.info("Converted %d docs → %s", len(df), out)
    return df


# ============== MySQL ==============
def _engine():
    return create_engine(C.MYSQL_URL, pool_pre_ping=True)


def _query(sql: str) -> pd.DataFrame:
    with _engine().connect() as conn:
        return pd.read_sql(text(sql), conn)


def fetch_users(save: bool = True) -> pd.DataFrame:
    sql = """
        SELECT
            UserId AS user_id,
            Name AS name,
            Email AS email,
            Phone AS phone,
            Role AS role,
            Status AS status,
            DateOfBirth AS date_of_birth,
            Gender AS gender,
            Address AS address,
            Latitude AS latitude,
            Longitude AS longitude,
            Province AS province,
            District AS district,
            Occupation AS occupation,
            PreferredPitchType AS preferred_pitch_type,
            PreferredPlayTime AS preferred_play_time,
            CreatedAt AS created_at
        FROM Users
    """
    df = _query(sql)
    log.info("Fetched %d users", len(df))
    if save:
        df.to_parquet(C.RAW_DIR / "users.parquet", index=False)
    return df


def fetch_pitches(save: bool = True) -> pd.DataFrame:
    sql = """
        SELECT
            p.PitchId AS pitch_id,
            p.Name AS name,
            p.Description AS description,
            p.Price AS price,
            p.Type AS pitch_type,
            p.Environment AS environment,
            p.ProviderAddressId AS provider_address_id,
            p.ImageUrl AS image_url
        FROM Pitches p
    """
    df = _query(sql)
    log.info("Fetched %d pitches", len(df))
    if save:
        df.to_parquet(C.RAW_DIR / "pitches.parquet", index=False)
    return df


def fetch_products(save: bool = True) -> pd.DataFrame:
    sql = """
        SELECT
            p.ProductId AS product_id,
            p.Name AS name,
            p.Description AS description,
            p.Price AS price,
            p.Brand AS brand,
            p.CategoryId AS category_id,
            p.Tags AS tags,
            p.DominantColor AS dominant_color,
            p.Colors AS colors,
            p.ImageUrl AS image_url
        FROM Products p
    """
    df = _query(sql)
    log.info("Fetched %d products", len(df))
    if save:
        df.to_parquet(C.RAW_DIR / "products.parquet", index=False)
    return df


# ============== CSV fallback ==============
def _load_csv_mixed_sep(path: Path) -> pd.DataFrame:
    """Header dùng `;`, data dùng `,`."""
    with open(path, encoding="utf-8") as f:
        header_line = f.readline().rstrip("\n").rstrip("\r")
    cols = [c.strip() for c in header_line.split(";")]
    return pd.read_csv(
        path, skiprows=1, header=None, names=cols,
        sep=",", engine="python", on_bad_lines="skip", quotechar='"',
    )


def fetch_from_csv(csv_dir: str | Path) -> dict[str, pd.DataFrame]:
    """Load users/pitches/products từ CSV (header `;` data `,`)."""
    csv_dir = Path(csv_dir)
    rename_map = {
        "pitches": {"type": "pitch_type"},  # tránh đụng word reserved
        "products": {},
        "users": {},
    }
    out = {}
    for name in ("users", "pitches", "products"):
        f = csv_dir / f"{name}.csv"
        if f.exists():
            df = _load_csv_mixed_sep(f)
            for old, new in rename_map.get(name, {}).items():
                if old in df.columns:
                    df = df.rename(columns={old: new})
            df.to_parquet(C.RAW_DIR / f"{name}.parquet", index=False)
            out[name] = df
            log.info("CSV loaded %s: %d rows, cols=%s", name, len(df), list(df.columns))
    return out


# ============== Entry ==============
def load_all(use_mongo: bool = True, use_mysql: bool = True, csv_dir: Optional[str] = None):
    if use_mongo:
        fetch_interaction_logs()
    if use_mysql:
        fetch_users()
        fetch_pitches()
        fetch_products()
    if csv_dir:
        fetch_from_csv(csv_dir)


if __name__ == "__main__":
    import argparse

    ap = argparse.ArgumentParser()
    ap.add_argument("--mongo", action="store_true", help="Pull logs từ MongoDB")
    ap.add_argument("--mysql", action="store_true", help="Pull users/pitches/products từ MySQL")
    ap.add_argument("--json", type=str, help="Load logs từ JSON file (fallback)")
    ap.add_argument("--csv-dir", type=str, help="Load entities từ CSV dir (fallback)")
    args = ap.parse_args()

    if args.json:
        fetch_logs_from_json(args.json)
    if args.mongo:
        fetch_interaction_logs()
    if args.mysql:
        fetch_users()
        fetch_pitches()
        fetch_products()
    if args.csv_dir:
        fetch_from_csv(args.csv_dir)
