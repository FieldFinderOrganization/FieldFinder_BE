# BÁO CÁO ĐÁNH GIÁ TRÍCH XUẤT THAM SỐ CHATBOT GEMINI

Bộ test đánh giá tự động dựa trên **15 kịch bản đặt sân thực tế** từ dễ đến khó.
Mô hình sử dụng: **gemini-2.5-flash** (giao tiếp dạng JSON qua API).

## 1. Tỷ lệ trích xuất chính xác theo từng tham số (Slot-level Accuracy)

| Tham số cần trích xuất | Số câu đúng | Tỷ lệ thành công (%) |
| :--- | :---: | :---: |
| **Intent Action (`action`)** | 13/15 | 86.7% |
| **Loại sân (`pitchType`)** | 14/15 | 93.3% |
| **Ngày đặt (`bookingDate`)** | 15/15 | 100.0% |
| **Khung giờ chơi (`slotList`)** | 15/15 | 100.0% |
| **Vị trí/Khu vực (`location`)** | 15/15 | 100.0% |
| **Khớp hoàn hảo (Strict Match - 5/5)** | **12/15** | **80.0%** |

## 2. Chi tiết kết quả kiểm thử từng câu lệnh

| STT | Câu lệnh người dùng | Action mong muốn | Action thực tế | Slot mong muốn | Slot thực tế | Kết quả |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: |
| 1 | "đặt sân 5 ngày 21/06 lúc 17h đến 19h" | book_pitch | book_pitch | [12, 13] | [12, 13] | ✅ |
| 2 | "book sân 7 chiều mai từ 16h đến 18h" | book_pitch | book_pitch | [11, 12] | [11, 12] | ✅ |
| 3 | "tìm sân 5 còn trống ngày mai lúc 19h" | check_pitch_availability | check_pitch_availability | [14] | [14] | ✅ |
| 4 | "có bao nhiêu sân 5 người" | count_pitches_by_type | count_pitches_by_type | [] | [] | ✅ |
| 5 | "cho mình xem danh sách sân 11 người ngoài trời" | list_pitches | list_pitches | [] | [] | ✅ |
| 6 | "đặt sân 5 ở Gò Vấp tối nay" | book_pitch | book_pitch | None | [] | ✅ |
| 7 | "tìm sân 7 gần tôi" | recommend_pitch | list_pitches | None | [] | ❌ |
| 8 | "sân 11 nào rẻ nhất" | cheapest_pitch | cheapest_pitch | None | [] | ✅ |
| 9 | "book sân 5 từ 7h đến 10h sáng ngày mai" | book_pitch | book_pitch | [2, 3, 4] | [2, 3, 4] | ✅ |
| 10 | "gợi ý sân giúp mình với" | recommend_pitch | recommend_pitch | None | [] | ✅ |
| 11 | "sân 7 trong nhà còn trống tối nay 20h không" | check_pitch_availability | check_pitch_availability | [15] | [15] | ✅ |
| 12 | "đặt sân 5 từ 11h đến 14h chiều mai" | book_pitch | book_pitch | [6, 7, 8] | [6, 7, 8] | ✅ |
| 13 | "tìm sân 5 gần đây có mái che ở gò vấp tối nay đi đá lúc 7h tối" | recommend_pitch | list_pitches | [14] | [14] | ❌ |
| 14 | "đặt sân 7 quận 1 ngày kia lúc 18h đến 21h" | book_pitch | book_pitch | [13, 14, 15] | [13, 14, 15] | ✅ |
| 15 | "đơn đặt sân của tôi đâu rồi" | list_my_bookings | list_my_bookings | None | [] | ❌ |
