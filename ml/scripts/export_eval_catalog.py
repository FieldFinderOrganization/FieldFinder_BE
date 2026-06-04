"""Export product catalog attributes for the Text-Query A/B eval (one-time).

Pulls REAL data from MySQL so the offline reranker port scores items exactly like
production CompositeRanker would:
    - product_id, name, brand, sex (normalized MEN/WOMEN/UNISEX)
    - category_id -> category_name (leaf) via categories tree
    - ptype  : SHOES/SANDAL/TOP/BOTTOM/DRESS/BAG/HAT/OTHER  (mirror CategoryServiceImpl)
    - domain : FOOTWEAR/CLOTHING/ACCESSORY                  (report bucketing)
    - total_sold : SUM(order_items.quantity) over non-cancelled orders ("đã bán")

Output:
    ml/data/eval/catalog.parquet
    ml/data/eval/category_map.json   (id -> {name, parent_id, type})

Run from ml/:
    python -m scripts.export_eval_catalog
"""
from __future__ import annotations

import json
import logging
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

import pandas as pd
from sqlalchemy import create_engine, text

from src import config as C

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

EVAL_DIR = C.DATA_DIR / "eval"
EVAL_DIR.mkdir(parents=True, exist_ok=True)
CATALOG_PATH = EVAL_DIR / "catalog.parquet"
CATMAP_PATH = EVAL_DIR / "category_map.json"

# ---- leaf category name -> productType (mirror CATEGORY_KEYWORD_TO_TYPE in CategoryServiceImpl) ----
CATEGORY_KEYWORD_TO_TYPE = {
    "tops and t-shirts": "TOP",
    "hoodies and sweatshirts": "TOP",
    "jackets and gilets": "TOP",
    "pants and leggings": "BOTTOM",
    "shorts": "BOTTOM",
    "football shoes": "SHOES",
    "tennis shoes": "SHOES",
    "basketball shoes": "SHOES",
    "running shoes": "SHOES",
    "shoes": "SHOES",
    "lifestyle": "SHOES",
    "sandals and slides": "SANDAL",
    "bags and backpacks": "BAG",
    "hats and headwears": "HAT",
    "socks": "OTHER",
    "running accessories": "OTHER",
    "tennis accessories": "OTHER",
    "basketball accessories": "OTHER",
    "football accessories": "OTHER",
    "gloves": "OTHER",
}

# productType -> domain bucket (report only)
TYPE_DOMAIN = {
    "SHOES": "FOOTWEAR", "SANDAL": "FOOTWEAR",
    "TOP": "CLOTHING", "BOTTOM": "CLOTHING", "DRESS": "CLOTHING",
    "BAG": "ACCESSORY", "HAT": "ACCESSORY", "OTHER": "ACCESSORY",
}


def norm_sex(s: str | None) -> str:
    if not s:
        return "UNISEX"
    s = str(s).strip().upper()
    if s.startswith("M"):      # MEN / MALE / Men
        return "MEN"
    if s.startswith("W") or s.startswith("F"):  # WOMEN / Women / FEMALE
        return "WOMEN"
    return "UNISEX"


def main() -> None:
    eng = create_engine(C.MYSQL_URL, pool_pre_ping=True, connect_args=C.MYSQL_CONNECT_ARGS)
    with eng.connect() as conn:
        cats = pd.read_sql(text(
            "SELECT category_id, name, parent_id, category_type FROM categories"), conn)
        prods = pd.read_sql(text(
            "SELECT product_id, name, brand, category_id, sex, tags FROM products"), conn)
        # total_sold = đã bán (exclude cancelled/refunded)
        sold = pd.read_sql(text("""
            SELECT oi.product_id AS product_id, COALESCE(SUM(oi.quantity),0) AS total_sold
            FROM order_items oi JOIN orders o ON o.order_id = oi.order_id
            WHERE o.status NOT IN ('CANCELLED','REFUNDED','FAILED')
            GROUP BY oi.product_id
        """), conn)

    log.info("categories=%d products=%d sold_rows=%d", len(cats), len(prods), len(sold))

    id2name = dict(zip(cats["category_id"], cats["name"]))

    # category_map.json (for the eval to resolve activityCats etc.)
    catmap = {
        int(r.category_id): {
            "name": r.name,
            "parent_id": (int(r.parent_id) if pd.notna(r.parent_id) else None),
            "type": r.category_type,
        }
        for r in cats.itertuples(index=False)
    }
    CATMAP_PATH.write_text(json.dumps(catmap, ensure_ascii=False, indent=2), encoding="utf-8")

    prods["category_name"] = prods["category_id"].map(id2name).fillna("")
    prods["sex_norm"] = prods["sex"].map(norm_sex)
    prods["brand"] = prods["brand"].fillna("").astype(str).str.strip()

    # ptype: leaf category name -> type; ambiguous clothing leaves left to per-product name resolution
    def ptype(cat_name: str, prod_name: str) -> str:
        t = CATEGORY_KEYWORD_TO_TYPE.get((cat_name or "").lower().strip())
        if t:
            return t
        n = (prod_name or "").lower()
        # minimal name fallback (Java scoreProductType spirit) for ambiguous *Clothing leaves
        if any(k in n for k in ("short",)):
            return "BOTTOM"
        if any(k in n for k in ("pant", "legging", "jogger", "trouser")):
            return "BOTTOM"
        if any(k in n for k in ("skirt", "dress", "váy", "đầm")):
            return "DRESS"
        if any(k in n for k in ("tee", "shirt", "jersey", "hoodie", "jacket", "polo", "áo")):
            return "TOP"
        return "OTHER"

    prods["ptype"] = [ptype(c, n) for c, n in zip(prods["category_name"], prods["name"])]
    prods["domain"] = prods["ptype"].map(TYPE_DOMAIN).fillna("ACCESSORY")

    out = prods.merge(sold, on="product_id", how="left")
    out["total_sold"] = out["total_sold"].fillna(0).astype(int)
    out = out[["product_id", "name", "brand", "sex_norm", "category_id",
               "category_name", "ptype", "domain", "tags", "total_sold"]]
    out.to_parquet(CATALOG_PATH, index=False)

    log.info("Saved %s (%d products)", CATALOG_PATH, len(out))
    log.info("ptype dist: %s", out["ptype"].value_counts().to_dict())
    log.info("domain dist: %s", out["domain"].value_counts().to_dict())
    log.info("brand dist: %s", out["brand"].value_counts().to_dict())
    log.info("sold>0: %d products", int((out["total_sold"] > 0).sum()))


if __name__ == "__main__":
    main()
