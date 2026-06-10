"""Build CLIP image FAISS index từ products table (MySQL).

Run từ ml/:
    python -m src.train.build_image_index
"""
from __future__ import annotations

import torch  # noqa: F401  — phải import TRƯỚC faiss (tránh xung đột DLL OpenMP/MKL trên Windows)

import logging
import pickle

import faiss
import numpy as np
import pandas as pd
from sqlalchemy import create_engine, text
from tqdm import tqdm

from .. import config as C
from ..models.image_encoder import encode_images, load_image_from_url

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

INDEX_PATH = C.CHECKPOINT_DIR / "items_image.faiss"
META_PATH = C.CHECKPOINT_DIR / "items_image_meta.pkl"


def fetch_products() -> pd.DataFrame:
    engine = create_engine(C.MYSQL_URL, connect_args=C.MYSQL_CONNECT_ARGS)
    sql = text("""
        SELECT product_id, name, brand, sex, image_url, category_id, tags, dominant_color
        FROM products
        WHERE image_url IS NOT NULL AND image_url <> ''
    """)
    with engine.connect() as conn:
        df = pd.read_sql(sql, conn)
    log.info("Fetched %d products with imageUrl", len(df))
    return df


def parse_tags(raw) -> list[str]:
    if raw is None:
        return []
    if isinstance(raw, list):
        return [str(t).strip().lower() for t in raw if t]
    s = str(raw).strip()
    if not s:
        return []
    # StringSetConverter joins by comma
    return [t.strip().lower() for t in s.split(",") if t.strip()]


def main():
    df = fetch_products()
    if df.empty:
        log.error("No products with imageUrl. Abort.")
        return

    log.info("Downloading images...")
    images = []
    keep_rows = []
    for _, row in tqdm(df.iterrows(), total=len(df)):
        img = load_image_from_url(row["image_url"])
        if img is None:
            continue
        images.append(img)
        keep_rows.append(row)

    log.info("Loaded %d/%d images. Encoding with CLIP...", len(images), len(df))
    if not images:
        log.error("No images downloaded. Abort.")
        return

    vecs = encode_images(images, batch_size=32)
    dim = vecs.shape[1]

    index = faiss.IndexFlatIP(dim)
    index.add(vecs)

    meta = []
    for row in keep_rows:
        pid = int(row["product_id"])
        cat_id = int(row["category_id"]) if row.get("category_id") is not None else None
        meta.append({
            "item_id": str(pid),
            "item_type": "PRODUCT",
            "item_key": f"T_{pid}",
            "name": str(row.get("name", "")),
            "brand": str(row.get("brand", "")) if row.get("brand") is not None else "",
            "sex": str(row.get("sex", "")) if row.get("sex") is not None else "",
            "image_url": str(row["image_url"]),
            "category_id": cat_id,
            "tags": parse_tags(row.get("tags")),
            "dominant_color": (str(row.get("dominant_color")).strip().lower()
                               if row.get("dominant_color") is not None else ""),
        })

    faiss.write_index(index, str(INDEX_PATH))
    with open(META_PATH, "wb") as f:
        pickle.dump(meta, f)

    log.info("Image FAISS index saved → %s (%d vectors, dim=%d)", INDEX_PATH, index.ntotal, dim)


if __name__ == "__main__":
    main()
