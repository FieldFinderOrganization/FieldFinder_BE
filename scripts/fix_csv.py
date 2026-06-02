"""Chuẩn hoá file products CSV trước khi import.

Sửa các lỗi format hay gặp khi copy-paste data:
  - description chứa dấu phẩy nhưng không bọc ngoặc kép (hoặc bọc " nhưng có dấu cách trước)
  - sizes dùng ',' thay ';'
  - dòng trống, dấu cách thừa quanh field
Ghi đè lại file với CSV bọc ngoặc đúng (csv.writer).

Cách dùng:
    python fix_csv.py products_to_add.csv
    python fix_csv.py products_to_add.csv --out clean.csv   # ghi ra file khác
"""
from __future__ import annotations

import argparse
import csv
import re
import sys

# name,brand,category,price,sex KHÔNG chứa phẩy. desc có thể chứa phẩy/quote.
# url = https... ; sizes = field cuối.
ROW = re.compile(
    r'^(?P<name>[^,]*),(?P<brand>[^,]*),(?P<category>[^,]*),(?P<price>[^,]*),'
    r'(?P<sex>[^,]*),(?P<desc>.*),\s*"?(?P<url>https?://[^",\s]+)"?\s*,\s*'
    r'(?P<sizes>[0-9A-Za-z:;,.\-/ ]+?)\s*$'
)
COLS = ["name", "brand", "category", "price", "sex", "description", "image_url", "sizes"]


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("csv", help="file CSV cần sửa")
    ap.add_argument("--out", default=None, help="file output (mặc định ghi đè)")
    args = ap.parse_args()

    lines = [l.rstrip("\n") for l in open(args.csv, encoding="utf-8-sig")]
    body = [l for l in lines[1:] if l.strip()]  # bỏ header + dòng trống

    out, bad = [], []
    for l in body:
        m = ROW.match(l)
        if not m:
            bad.append(l[:80])
            continue
        g = {k: v.strip() for k, v in m.groupdict().items()}
        g["desc"] = g["desc"].strip().strip('"').strip()           # bỏ quote + space quanh desc
        g["sizes"] = re.sub(r"\s*,\s*", ";", g["sizes"]).replace(" ", "")  # ',' -> ';', bỏ space
        out.append(g)

    print(f"parsed {len(out)} | bad {len(bad)}")
    for b in bad:
        print("  BAD:", b)
    if bad:
        print("Có dòng không parse được — kiểm tra thủ công, CHƯA ghi file.")
        sys.exit(1)

    dest = args.out or args.csv
    with open(dest, "w", encoding="utf-8", newline="") as f:
        w = csv.writer(f)
        w.writerow(COLS)
        for g in out:
            w.writerow([g["name"], g["brand"], g["category"], g["price"],
                        g["sex"], g["desc"], g["url"], g["sizes"]])
    print(f"wrote {len(out)} rows -> {dest}")


if __name__ == "__main__":
    main()
