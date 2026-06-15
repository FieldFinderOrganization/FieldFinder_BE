package com.example.FieldFinder.ai.enrich;

import java.util.ArrayList;
import java.util.List;

/** Kết quả enrich 1 ảnh sản phẩm: tags (gồm màu phụ) + màu chủ đạo canonical. */
public class ProductEnrichment {
    public List<String> tags = new ArrayList<>();
    /** Màu chủ đạo đã chuẩn hóa canonical (vd "đen"); null nếu AI không xác định. */
    public String dominantColor;
    /** Tập màu chính canonical (≤3, dominant đầu) cho sp đa màu; rỗng nếu không xác định. */
    public List<String> colors = new ArrayList<>();
}
