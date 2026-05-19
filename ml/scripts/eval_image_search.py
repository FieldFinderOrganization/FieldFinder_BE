"""Eval suite cho image search pipeline.

Đo Recall@1, Recall@5, MRR, category accuracy, latency p50/p95 trên test set.
Call HybridImageRetriever trực tiếp (skip BE). pHash đo riêng qua MySQL.

Run từ ml/:
    python -m scripts.eval_image_search

Output:
    ml/data/eval/results.json
    ml/data/eval/results.md
"""
from __future__ import annotations

import json
import logging
import sys
import time
from pathlib import Path
from typing import Optional

# Allow running both as script and module
ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from PIL import Image  # noqa: E402

from src import config as C  # noqa: E402
from src.serve.hybrid_image import get_hybrid_image  # noqa: E402
from src.serve.image_index import get_image_index  # noqa: E402

logging.basicConfig(level=logging.INFO, format="%(asctime)s [%(levelname)s] %(message)s")
log = logging.getLogger(__name__)

EVAL_DIR = C.DATA_DIR / "eval"
TEST_FILE = EVAL_DIR / "image_test.json"
IMG_DIR = EVAL_DIR / "images"
RESULTS_JSON = EVAL_DIR / "results.json"
RESULTS_MD = EVAL_DIR / "results.md"


def load_test_set() -> list[dict]:
    with open(TEST_FILE, "r", encoding="utf-8") as f:
        data = json.load(f)
    return data.get("entries", [])


def load_image(path: Path) -> Optional[Image.Image]:
    try:
        img = Image.open(path).convert("RGB")
        return img
    except Exception as e:
        log.warning("load fail %s: %s", path, e)
        return None


def percentile(values: list[float], p: float) -> float:
    if not values:
        return 0.0
    s = sorted(values)
    k = (len(s) - 1) * p
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def confidence_band(top_clip: float) -> str:
    if top_clip >= 0.90:
        return "exact"
    if top_clip < 0.70:
        return "low_conf"
    return "similar"


def expected_band(case_type: str) -> str:
    return {
        "in_catalog": "exact",
        "similar": "similar",       # cosine 0.75-0.85 expected
        "crop_screenshot": "similar",
        "category_only": "similar",
        "unrelated": "low_conf",
    }.get(case_type, "similar")


def evaluate():
    if not TEST_FILE.exists():
        log.error("Test file not found: %s", TEST_FILE)
        return
    entries = load_test_set()
    if not entries:
        log.error("No test entries.")
        return

    log.info("Warming up models...")
    hybrid = get_hybrid_image()
    idx, _ = get_image_index()
    if idx is None:
        log.error("Image index not built. Run build_image_index first.")
        return

    # Per-case-type metric buckets
    buckets: dict[str, dict] = {}
    all_latency: list[float] = []
    band_correct = 0
    band_total = 0

    log.info("Evaluating %d entries...", len(entries))
    for ent in entries:
        case_type = ent.get("case_type", "unknown")
        img_path = IMG_DIR / ent["image_path"]
        img = load_image(img_path)
        if img is None:
            log.warning("skip %s (image missing)", ent["image_path"])
            continue

        t0 = time.time()
        # Note: caption/tags/category_ids = empty để đo pure CLIP+text+tag baseline
        # Nếu muốn đo end-to-end với Gemini parse → cần call BE. Ở đây đo ML side.
        results = hybrid.retrieve(
            image=img,
            caption="",
            gemini_tags=[],
            category_ids=None,
            top_k=10,
            retrieve_k=30,
        )
        latency_ms = (time.time() - t0) * 1000
        all_latency.append(latency_ms)

        top_ids = [r.get("item_id") for r in results]
        top_categories = [r.get("category_id") for r in results]
        top_clip = results[0].get("clip_score", 0.0) if results else 0.0

        # Init bucket
        b = buckets.setdefault(case_type, {
            "n": 0, "recall_1": 0, "recall_5": 0, "mrr_sum": 0.0,
            "cat_n": 0, "cat_correct_1": 0, "cat_correct_5": 0,
        })
        b["n"] += 1

        expected_pid = ent.get("expected_product_id")
        expected_cat = ent.get("expected_category")

        if expected_pid is not None:
            if top_ids and top_ids[0] == expected_pid:
                b["recall_1"] += 1
            if expected_pid in top_ids[:5]:
                b["recall_5"] += 1
                rank = top_ids[:5].index(expected_pid) + 1
                b["mrr_sum"] += 1.0 / rank

        # Category accuracy: map top product's category_id back to FOOTWEAR/CLOTHING/ACCESSORY
        # Use simple heuristic: cat_id ranges roughly:
        #   FOOTWEAR: 2,4,31,34,37,40,43,44 / CLOTHING: 10,12-16,32,35,38,41 / ACCESSORY: 21-25,33,36,39,42
        if expected_cat is not None and top_categories:
            super_map = {
                # FOOTWEAR
                2: "FOOTWEAR", 4: "FOOTWEAR", 31: "FOOTWEAR", 34: "FOOTWEAR",
                37: "FOOTWEAR", 40: "FOOTWEAR", 43: "FOOTWEAR", 44: "FOOTWEAR",
                # CLOTHING
                10: "CLOTHING", 12: "CLOTHING", 13: "CLOTHING", 14: "CLOTHING",
                15: "CLOTHING", 16: "CLOTHING", 32: "CLOTHING", 35: "CLOTHING",
                38: "CLOTHING", 41: "CLOTHING",
                # ACCESSORY
                21: "ACCESSORY", 22: "ACCESSORY", 23: "ACCESSORY", 24: "ACCESSORY",
                25: "ACCESSORY", 33: "ACCESSORY", 36: "ACCESSORY", 39: "ACCESSORY",
                42: "ACCESSORY",
            }
            b["cat_n"] += 1
            top1_super = super_map.get(top_categories[0])
            top5_supers = [super_map.get(c) for c in top_categories[:5]]
            if top1_super == expected_cat:
                b["cat_correct_1"] += 1
            if expected_cat in top5_supers:
                b["cat_correct_5"] += 1

        # Confidence band check
        actual_band = confidence_band(top_clip)
        exp_band = expected_band(case_type)
        if actual_band == exp_band:
            band_correct += 1
        band_total += 1

        log.info("[%s] %s → top=%s top_clip=%.3f band=%s/%s lat=%.1fms",
                 case_type, ent["image_path"],
                 top_ids[0] if top_ids else "-",
                 top_clip, actual_band, exp_band, latency_ms)

    # Aggregate
    summary = {
        "total_evaluated": sum(b["n"] for b in buckets.values()),
        "latency_p50_ms": round(percentile(all_latency, 0.5), 1),
        "latency_p95_ms": round(percentile(all_latency, 0.95), 1),
        "confidence_band_accuracy": round(band_correct / band_total, 3) if band_total else 0.0,
        "buckets": {},
    }
    for ct, b in buckets.items():
        n = b["n"]
        cn = b["cat_n"]
        summary["buckets"][ct] = {
            "n": n,
            "recall@1": round(b["recall_1"] / n, 3) if n else 0.0,
            "recall@5": round(b["recall_5"] / n, 3) if n else 0.0,
            "mrr": round(b["mrr_sum"] / n, 3) if n else 0.0,
            "cat_acc@1": round(b["cat_correct_1"] / cn, 3) if cn else 0.0,
            "cat_acc@5": round(b["cat_correct_5"] / cn, 3) if cn else 0.0,
        }

    # Write JSON
    with open(RESULTS_JSON, "w", encoding="utf-8") as f:
        json.dump(summary, f, indent=2, ensure_ascii=False)

    # Write Markdown
    md = ["# Image Search Eval Results\n"]
    md.append(f"- Total evaluated: **{summary['total_evaluated']}**")
    md.append(f"- Latency p50: **{summary['latency_p50_ms']}ms**")
    md.append(f"- Latency p95: **{summary['latency_p95_ms']}ms**")
    md.append(f"- Confidence band accuracy: **{summary['confidence_band_accuracy']}**\n")
    md.append("## Per-case-type metrics\n")
    md.append("| Case | N | Recall@1 | Recall@5 | MRR | CatAcc@1 | CatAcc@5 |")
    md.append("|------|---|----------|----------|-----|----------|----------|")
    for ct, m in summary["buckets"].items():
        md.append(f"| {ct} | {m['n']} | {m['recall@1']} | {m['recall@5']} | {m['mrr']} | {m['cat_acc@1']} | {m['cat_acc@5']} |")
    with open(RESULTS_MD, "w", encoding="utf-8") as f:
        f.write("\n".join(md) + "\n")

    log.info("Results → %s", RESULTS_JSON)
    log.info("Markdown → %s", RESULTS_MD)
    print("\n".join(md))


if __name__ == "__main__":
    evaluate()
