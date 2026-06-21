# KẾT QUẢ ĐO LƯỜNG HỆ GỢI Ý SÂN BÃI THỰC TẾ (PITCH RECOMMENDATION)

Thử nghiệm được thực hiện trên **34 sân bóng thực tế** lấy từ Database của hệ thống FieldFinder.
Giao thức đánh giá: *Leave-One-Out Evaluation* trên 100 người dùng.

## 1. Bảng số liệu đánh giá độ chính xác (Accuracy Metrics)

| Chỉ số đánh giá (Metric) | Không AI (Bộ lọc tĩnh) | Có AI (Composite Ranker) | Tỷ lệ cải thiện |
| :--- | :---: | :---: | :---: |
| **Hit Rate @ 1 (HR@1)** | 68.0% | 85.0% | +17.0% |
| **Hit Rate @ 5 (HR@5)** | 100.0% | 100.0% | +0.0% |
| **Hit Rate @ 10 (HR@10)** | 100.0% | 100.0% | +0.0% |
| **Mean Reciprocal Rank (MRR)** | 0.830 | 0.923 | +0.093 |

## 2. Thời gian phản hồi (Latency)

*   **Thời gian xử lý trung bình của Không AI:** 0.017 ms
*   **Thời gian xử lý trung bình của Có AI:** 0.069 ms

---
*Kết quả được tạo tự động bởi scripts/eval_pitch_recommendation.py dựa trên dữ liệu thật của hệ thống.*
