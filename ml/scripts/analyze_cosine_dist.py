"""Analyze cosine distribution per case_type to inform threshold tuning."""
from __future__ import annotations
import json
import sys
from pathlib import Path
from collections import defaultdict

ROOT = Path(__file__).resolve().parent.parent
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from PIL import Image
from src import config as C
from src.serve.hybrid_image import get_hybrid_image
from src.serve.image_index import get_image_index

EVAL_DIR = C.DATA_DIR / "eval"
TEST_FILE = EVAL_DIR / "image_test.json"
IMG_DIR = EVAL_DIR / "images"


def percentile(xs, p):
    if not xs:
        return 0.0
    s = sorted(xs)
    k = (len(s) - 1) * p
    f = int(k)
    c = min(f + 1, len(s) - 1)
    if f == c:
        return s[f]
    return s[f] + (s[c] - s[f]) * (k - f)


def main():
    with open(TEST_FILE, "r", encoding="utf-8") as f:
        entries = json.load(f)["entries"]

    hybrid = get_hybrid_image()
    get_image_index()

    by_case = defaultdict(list)

    for ent in entries:
        img_path = IMG_DIR / ent["image_path"]
        try:
            img = Image.open(img_path).convert("RGB")
        except Exception:
            continue
        results = hybrid.retrieve(image=img, caption="", gemini_tags=[],
                                  category_ids=None, top_k=10, retrieve_k=30)
        if not results:
            continue
        top_clip = results[0].get("clip_score", 0.0)
        by_case[ent["case_type"]].append(top_clip)

    print(f"\n{'case_type':<18} {'N':>3}  {'min':>6} {'p25':>6} {'p50':>6} {'p75':>6} {'max':>6}")
    print("-" * 60)
    for ct in ["in_catalog", "similar", "crop_screenshot", "category_only", "unrelated"]:
        xs = by_case.get(ct, [])
        if not xs:
            continue
        print(f"{ct:<18} {len(xs):>3}  "
              f"{min(xs):.3f} {percentile(xs, 0.25):.3f} "
              f"{percentile(xs, 0.5):.3f} {percentile(xs, 0.75):.3f} {max(xs):.3f}")
    print()

    # Suggest thresholds
    in_cat = by_case.get("in_catalog", [])
    similar = by_case.get("similar", [])
    crop = by_case.get("crop_screenshot", [])
    cat_only = by_case.get("category_only", [])
    unrel = by_case.get("unrelated", [])

    print("Suggested thresholds:")
    if in_cat and similar:
        # exact threshold: separates in_catalog from similar
        exact = (percentile(in_cat, 0.1) + percentile(similar, 0.9)) / 2
        print(f"  IMAGE_EXACT_THRESHOLD ≈ {exact:.2f}  (in_cat p10={percentile(in_cat, 0.1):.3f}, similar p90={percentile(similar, 0.9):.3f})")
    if (similar or crop) and unrel:
        valid = similar + crop + cat_only
        low = (percentile(valid, 0.1) + percentile(unrel, 0.9)) / 2
        print(f"  low_conf cutoff ≈      {low:.2f}  (valid p10={percentile(valid, 0.1):.3f}, unrel p90={percentile(unrel, 0.9):.3f})")


if __name__ == "__main__":
    main()
