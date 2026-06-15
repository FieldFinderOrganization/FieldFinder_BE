"""Unit-test cho lõi xếp hạng image-search (không cần model/FAISS).

Chạy:  python -m unittest discover -s tests   (từ thư mục ml/)

Phủ 3 hàm thuần quyết định thứ tự kết quả tìm theo ảnh:
  - jaccard:      độ trùng tag Gemini ∩ tag sản phẩm
  - _strip_colors: loại token màu khỏi Jaccard (chống rò "giày đen → hàng khác màu lọt")
  - rrf_fuse:     hợp nhất 3 bảng xếp hạng (CLIP / caption / tag) bằng Reciprocal Rank Fusion
"""
import unittest

from src.serve.hybrid_image import jaccard, rrf_fuse, _strip_colors


class JaccardTest(unittest.TestCase):
    def test_overlap(self):
        self.assertAlmostEqual(jaccard({"a", "b"}, {"b", "c"}), 1 / 3)

    def test_identical(self):
        self.assertEqual(jaccard({"a", "b"}, {"a", "b"}), 1.0)

    def test_disjoint(self):
        self.assertEqual(jaccard({"a"}, {"b"}), 0.0)

    def test_empty_returns_zero(self):
        self.assertEqual(jaccard(set(), {"a"}), 0.0)
        self.assertEqual(jaccard({"a"}, set()), 0.0)


class StripColorsTest(unittest.TestCase):
    def test_removes_color_tokens_vi_and_en(self):
        out = _strip_colors(["đen", "black", "giày", "sân", "navy"])
        self.assertEqual(out, ["giày", "sân"])

    def test_case_insensitive(self):
        self.assertEqual(_strip_colors(["BLACK", "Đen", "Nike"]), ["Nike"])

    def test_keeps_non_colors(self):
        self.assertEqual(_strip_colors(["áo", "thể thao"]), ["áo", "thể thao"])

    def test_color_leak_scenario(self):
        # Ảnh giày trắng phối đen: token màu phụ "đen" bị loại → Jaccard không kéo hàng đen vào.
        gemini = set(_strip_colors(["giày", "trắng", "đen", "thể thao"]))
        product_white = set(_strip_colors(["giày", "trắng", "thể thao"]))
        product_black = set(_strip_colors(["dép", "đen"]))
        self.assertGreater(jaccard(gemini, product_white), jaccard(gemini, product_black))


class RrfFuseTest(unittest.TestCase):
    def test_consensus_ranks_higher(self):
        # 'x' đứng đầu cả 2 bảng → điểm cao nhất.
        scores = rrf_fuse([["x", "y"], ["x", "z"]])
        self.assertGreater(scores["x"], scores["y"])
        self.assertGreater(scores["x"], scores["z"])

    def test_rank_position_matters(self):
        scores = rrf_fuse([["a", "b", "c"]])
        self.assertGreater(scores["a"], scores["b"])
        self.assertGreater(scores["b"], scores["c"])

    def test_k_smoothing(self):
        # k lớn → chênh lệch giữa các hạng nhỏ lại.
        s_small_k = rrf_fuse([["a", "b"]], k=1)
        s_big_k = rrf_fuse([["a", "b"]], k=1000)
        gap_small = s_small_k["a"] - s_small_k["b"]
        gap_big = s_big_k["a"] - s_big_k["b"]
        self.assertGreater(gap_small, gap_big)

    def test_empty_input(self):
        self.assertEqual(rrf_fuse([]), {})


if __name__ == "__main__":
    unittest.main()
