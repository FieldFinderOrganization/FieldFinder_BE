import os
import json
import time
import urllib.request
import urllib.error
import sys
from datetime import datetime, timedelta
from pathlib import Path

# Đảm bảo stdout ghi UTF-8 trên Windows
try:
    if sys.stdout.encoding != 'utf-8':
        sys.stdout.reconfigure(encoding='utf-8')
except Exception:
    pass

# --- 1. ĐỌC GOOGLE_API_KEY TỪ FILE .env Ở THƯ MỤC CHA ---
def load_api_key():
    # Thư mục gốc dự án chứa file .env
    root_dir = Path(__file__).resolve().parent.parent.parent
    env_path = root_dir / ".env"
    
    if not env_path.exists():
        print(f"Error: File .env not found at {env_path}")
        sys.exit(1)
        
    api_key = None
    with open(env_path, "r", encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if line.startswith("GOOGLE_API_KEY="):
                api_key = line.split("=", 1)[1].strip()
                # Loại bỏ dấu nháy đơn/kép nếu có
                if api_key.startswith(('"', "'")) and api_key.endswith(('"', "'")):
                    api_key = api_key[1:-1]
                break
                
    if not api_key:
        print("Error: GOOGLE_API_KEY not found in .env")
        sys.exit(1)
        
    return api_key

# --- 2. ĐỊNH NGHĨA SYSTEM INSTRUCTION TỪ BACKEND JAVA ---
SYSTEM_INSTRUCTION_RAW = """
Bạn là trợ lý AI thông minh cho hệ thống FieldFinder (Đặt sân & Shop thể thao).
Nhiệm vụ: Phân tích câu hỏi người dùng và trả về JSON cấu trúc để Backend xử lý.

CẤU TRÚC JSON TRẢ VỀ:
{
  "bookingDate": "yyyy-MM-dd" (hoặc null),
  "slotList": [1, 2...] (hoặc []),
  "pitchType": "FIVE_A_SIDE" | "SEVEN_A_SIDE" | "ELEVEN_A_SIDE" | "ALL",
  "message": "thông điệp mặc định" (hoặc null),
  "environment": "INDOOR" | "OUTDOOR" | null,
  "location": "tên khu vực/quận/đường nếu user nêu" (hoặc null),
  "nearMe": true | false,
  "data": {
    "action": "get_weather" | "check_stock" | "check_sales" | "check_size" | "prepare_order" | "list_on_sale" | "count_on_sale" | "max_discount_product" | "max_discount_brand" | "max_discount_category" | "best_selling_product" | "search_by_price_range" | "cheapest_product" | "most_expensive_product" | "product_detail" | "recommend_by_activity" | "list_pitches" | "recommend_pitch" | "count_pitches_by_type" | "check_pitch_availability" | "book_pitch" | "list_my_bookings" | "cheapest_pitch" | "most_expensive_pitch" | null,
    "productName": "...",
    "brand": "...",
    "city": "...",
    "size": "...",
    "quantity": 1,
    "categoryKeyword": "...",
    "productType": "SHOES" | "TOP" | "BOTTOM" | "SANDAL" | "DRESS" | "BAG" | "HAT" | "OTHER" | null,
    "color": "đen" | "trắng" | "xám" | "đỏ" | "cam" | "vàng" | "hồng" | "tím" | "nâu" | "xanh lá" | "xanh dương" | null,
    "minPrice": 0,
    "maxPrice": 0,
    "activity": "...",
    "suggestedCategories": [],
    "tags": [],
    "reasons": {}
  }
}

❗️ QUY TẮC XỬ LÝ SÂN:
- `pitchType`: Loại sân (5, 7, 11 người).
- `environment`: "INDOOR" (trong nhà/có mái che), "OUTDOOR" (ngoài trời).
- Giờ (từ 6h sáng đến 24h) được ánh xạ vào slot (1-18) như sau:
  Slot 1: 6h-7h, Slot 2: 7h-8h, Slot 3: 8h-9h, Slot 4: 9h-10h, Slot 5: 10h-11h ... đến Slot 18: 23h-24h.
  CHÚ Ý ĐẶC BIỆT: "từ 7h đến 11h" => `slotList`: [2, 3, 4, 5] (vì 7h-8h là slot 2, 8h-9h là slot 3, 9h-10h là slot 4, 10h-11h là slot 5). "từ 16h đến 18h" => `slotList`: [11, 12].
  LUÔN KIỂM TRA SỐ LƯỢNG SLOT: Số phần tử trong `slotList` PHẢI BẰNG (Giờ kết thúc - Giờ bắt đầu). Ví dụ: từ 11h đến 14h là 14 - 11 = 3 tiếng => CHỈ trả về đúng 3 slot [6, 7, 8]. TUYỆT ĐỐI KHÔNG trả về dư slot!
- THỜI GIAN HỆ THỐNG: Hôm nay: {{today}}, Ngày mai: {{plus1}}, Năm: {{year}}.
- Câu hỏi liệt kê / xem danh sách sân (vd: "có những sân nào", "cho xem danh sách sân", "sân 7 người ngoài trời có không") -> action: "list_pitches", kèm `pitchType` và `environment` nếu có.
- Câu đặt sân (vd: "đặt sân 5 ngày 20/4 slot 13", "book sân 7 chiều mai") -> action: "book_pitch", kèm đầy đủ `pitchType`, `bookingDate`, `slotList`.
- VỊ TRÍ / KHU VỰC: nếu user nêu khu vực, quận, phường hoặc tên đường cụ thể (vd "sân 5 ở Gò Vấp") -> điền `location` = đúng tên khu vực đó.
- GẦN TÔI: nếu user nói "gần tôi", "gần đây" -> `nearMe`: true.
- GỢI Ý SÂN: nếu user nhờ gợi ý sân CHUNG, không nêu loại/giá/khu vực cụ thể (vd "gợi ý sân giúp mình") -> action: "recommend_pitch", `pitchType`: "ALL".
"""

# --- 3. KHỞI TẠO TẬP KỊCH BẢN ĐO LƯỜNG (20 CÂU TEST CASES ĐẶT SÂN) ---
# Tự động tính toán ngày động theo lịch thực tế hôm nay để đối chiếu
today = datetime.now()
today_str = today.strftime("%Y-%m-%d")
tomorrow_str = (today + timedelta(days=1)).strftime("%Y-%m-%d")
next_day_str = (today + timedelta(days=2)).strftime("%Y-%m-%d")

TEST_CASES = [
    # DỄ
    {
        "input": f"đặt sân 5 ngày {today.strftime('%d/%m')} lúc 17h đến 19h",
        "expected": {
            "action": "book_pitch",
            "pitchType": "FIVE_A_SIDE",
            "bookingDate": today_str,
            "slotList": [12, 13], # 17h-18h = slot 12, 18h-19h = slot 13
            "location": None,
            "nearMe": False
        }
    },
    {
        "input": "book sân 7 chiều mai từ 16h đến 18h",
        "expected": {
            "action": "book_pitch",
            "pitchType": "SEVEN_A_SIDE",
            "bookingDate": tomorrow_str,
            "slotList": [11, 12], # 16h-17h = slot 11, 17h-18h = slot 12
            "location": None,
            "nearMe": False
        }
    },
    {
        "input": "tìm sân 5 còn trống ngày mai lúc 19h",
        "expected": {
            "action": "check_pitch_availability",
            "pitchType": "FIVE_A_SIDE",
            "bookingDate": tomorrow_str,
            "slotList": [14], # 19h-20h = slot 14
            "location": None,
            "nearMe": False
        }
    },
    {
        "input": "có bao nhiêu sân 5 người",
        "expected": {
            "action": "count_pitches_by_type",
            "pitchType": "FIVE_A_SIDE",
            "bookingDate": None,
            "slotList": [],
            "location": None,
            "nearMe": False
        }
    },
    {
        "input": "cho mình xem danh sách sân 11 người ngoài trời",
        "expected": {
            "action": "list_pitches",
            "pitchType": "ELEVEN_A_SIDE",
            "environment": "OUTDOOR",
            "bookingDate": None,
            "slotList": [],
            "location": None,
            "nearMe": False
        }
    },
    # TRUNG BÌNH
    {
        "input": "đặt sân 5 ở Gò Vấp tối nay",
        "expected": {
            "action": "book_pitch",
            "pitchType": "FIVE_A_SIDE",
            "bookingDate": today_str,
            "location": "Gò Vấp",
            "nearMe": False
        }
    },
    {
        "input": "tìm sân 7 gần tôi",
        "expected": {
            "action": "recommend_pitch",
            "pitchType": "SEVEN_A_SIDE",
            "nearMe": True,
            "location": None
        }
    },
    {
        "input": "sân 11 nào rẻ nhất",
        "expected": {
            "action": "cheapest_pitch",
            "pitchType": "ELEVEN_A_SIDE",
            "nearMe": False,
            "location": None
        }
    },
    {
        "input": "book sân 5 từ 7h đến 10h sáng ngày mai",
        "expected": {
            "action": "book_pitch",
            "pitchType": "FIVE_A_SIDE",
            "bookingDate": tomorrow_str,
            "slotList": [2, 3, 4] # 7-8h = slot 2, 8-9h = slot 3, 9-10h = slot 4
        }
    },
    {
        "input": "gợi ý sân giúp mình với",
        "expected": {
            "action": "recommend_pitch",
            "pitchType": "ALL",
            "nearMe": False
        }
    },
    {
        "input": "sân 7 trong nhà còn trống tối nay 20h không",
        "expected": {
            "action": "check_pitch_availability",
            "pitchType": "SEVEN_A_SIDE",
            "environment": "INDOOR",
            "bookingDate": today_str,
            "slotList": [15] # 20h-21h = slot 15
        }
    },
    {
        "input": "đặt sân 5 từ 11h đến 14h chiều mai",
        "expected": {
            "action": "book_pitch",
            "pitchType": "FIVE_A_SIDE",
            "bookingDate": tomorrow_str,
            "slotList": [6, 7, 8] # 11-12h = slot 6, 12-13h = slot 7, 13-14h = slot 8 (đúng 3 slot)
        }
    },
    # KHÓ
    {
        "input": "tìm sân 5 gần đây có mái che ở gò vấp tối nay đi đá lúc 7h tối",
        "expected": {
            "action": "recommend_pitch", # "gần đây" + "gợi ý" -> recommend_pitch
            "pitchType": "FIVE_A_SIDE",
            "environment": "INDOOR",
            "location": "gò vấp",
            "bookingDate": today_str,
            "slotList": [14] # 7h tối = 19h = slot 14
        }
    },
    {
        "input": "đặt sân 7 quận 1 ngày kia lúc 18h đến 21h",
        "expected": {
            "action": "book_pitch",
            "pitchType": "SEVEN_A_SIDE",
            "bookingDate": next_day_str,
            "location": "quận 1",
            "slotList": [13, 14, 15] # 18-19h = slot 13, 19-20h = slot 14, 20-21h = slot 15
        }
    },
    {
        "input": "đơn đặt sân của tôi đâu rồi",
        "expected": {
            "action": "list_my_bookings"
        }
    }
]

# --- 4. GỌI API GEMINI QUA HTTP POST (URLLIB) ---
def call_gemini(api_key, user_input, system_prompt):
    url = f"https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key={api_key}"
    
    headers = {
        "Content-Type": "application/json"
    }
    
    # Body request theo đúng cấu trúc của Gemini API v1beta
    body = {
        "system_instruction": {
            "parts": {
                "text": system_prompt
            }
        },
        "contents": [
            {
                "role": "user",
                "parts": {
                    "text": user_input
                }
            }
        ],
        "generationConfig": {
            "temperature": 0.1,
            "response_mime_type": "application/json"
        }
    }
    
    req_data = json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=req_data, headers=headers, method="POST")
    
    try:
        with urllib.request.urlopen(req, timeout=30) as response:
            res_body = response.read().decode("utf-8")
            res_json = json.loads(res_body)
            # Trích xuất đoạn text JSON trả về
            text_out = res_json["candidates"][0]["content"]["parts"][0]["text"]
            return json.loads(text_out.strip())
    except urllib.error.HTTPError as e:
        print(f"API HTTP Error: {e.code} - {e.read().decode('utf-8')}")
        return None
    except Exception as e:
        print(f"Connection Error: {e}")
        return None

# --- 5. HÀM SO KHỚP VÀ TÍNH TOÁN METRIC ---
def run_evaluation():
    api_key = load_api_key()
    
    # Thay thế biến động ngày vào System Instruction
    system_prompt = SYSTEM_INSTRUCTION_RAW \
        .replace("{{today}}", today_str) \
        .replace("{{plus1}}", tomorrow_str) \
        .replace("{{year}}", str(today.year))
        
    print(f"Bắt đầu chạy đánh giá Chatbot Gemini trên {len(TEST_CASES)} kịch bản đặt sân...")
    
    metrics = {
        "action": 0,
        "pitchType": 0,
        "bookingDate": 0,
        "slotList": 0,
        "location": 0,
        "strict_match": 0
    }
    
    details = []
    
    for idx, case in enumerate(TEST_CASES, 1):
        user_input = case["input"]
        expected = case["expected"]
        
        print(f"[{idx}/{len(TEST_CASES)}] Query: \"{user_input}\"")
        time.sleep(1.0) # Tránh bị rate limit API miễn phí
        
        response = call_gemini(api_key, user_input, system_prompt)
        
        if response is None:
            print("  -> Lỗi kết nối API.")
            continue
            
        # Trích xuất các trường từ response để so sánh
        resp_action = response.get("data", {}).get("action") if isinstance(response.get("data"), dict) else None
        resp_pitch_type = response.get("pitchType")
        resp_date = response.get("bookingDate")
        resp_slots = response.get("slotList", [])
        resp_loc = response.get("location")
        resp_near_me = response.get("nearMe", False)
        
        # So khớp từng trường
        match_action = resp_action == expected.get("action")
        match_pitch = resp_pitch_type == expected.get("pitchType")
        
        # Nếu expected không yêu cầu cụ thể các trường sau, mặc định là khớp nếu bằng None hoặc khớp trực tiếp
        match_date = True
        if "bookingDate" in expected:
            match_date = resp_date == expected.get("bookingDate")
            
        match_slots = True
        if "slotList" in expected:
            match_slots = sorted(resp_slots) == sorted(expected.get("slotList", []))
            
        match_loc = True
        if "location" in expected:
            # So khớp không phân biệt hoa thường và khoảng trắng
            exp_l = expected.get("location")
            if exp_l is None:
                match_loc = resp_loc is None
            else:
                match_loc = resp_loc is not None and exp_l.lower() in resp_loc.lower()
                
        strict_match = match_action and match_pitch and match_date and match_slots and match_loc
        
        # Lưu cộng dồn kết quả đúng
        if match_action: metrics["action"] += 1
        if match_pitch: metrics["pitchType"] += 1
        if match_date: metrics["bookingDate"] += 1
        if match_slots: metrics["slotList"] += 1
        if match_loc: metrics["location"] += 1
        if strict_match: metrics["strict_match"] += 1
        
        details.append({
            "query": user_input,
            "expected_action": expected.get("action"),
            "got_action": resp_action,
            "expected_slots": expected.get("slotList"),
            "got_slots": resp_slots,
            "strict_match": "✅" if strict_match else "❌"
        })
        
        print(f"  -> Kết quả: {'Khớp hoàn toàn ✅' if strict_match else 'Lệch ❌'} (Action: {resp_action}, Slots: {resp_slots}, Date: {resp_date})")

    # --- TÍNH TOÁN TỶ LỆ PHẦN TRĂM ---
    total = len(TEST_CASES)
    acc_action = (metrics["action"] / total) * 100
    acc_pitch = (metrics["pitchType"] / total) * 100
    acc_date = (metrics["bookingDate"] / total) * 100
    acc_slots = (metrics["slotList"] / total) * 100
    acc_loc = (metrics["location"] / total) * 100
    acc_strict = (metrics["strict_match"] / total) * 100
    
    report_md = f"""# BÁO CÁO ĐÁNH GIÁ TRÍCH XUẤT THAM SỐ CHATBOT GEMINI

Bộ test đánh giá tự động dựa trên **{total} kịch bản đặt sân thực tế** từ dễ đến khó.
Mô hình sử dụng: **gemini-2.5-flash** (giao tiếp dạng JSON qua API).

## 1. Tỷ lệ trích xuất chính xác theo từng tham số (Slot-level Accuracy)

| Tham số cần trích xuất | Số câu đúng | Tỷ lệ thành công (%) |
| :--- | :---: | :---: |
| **Intent Action (`action`)** | {metrics["action"]}/{total} | {acc_action:.1f}% |
| **Loại sân (`pitchType`)** | {metrics["pitchType"]}/{total} | {acc_pitch:.1f}% |
| **Ngày đặt (`bookingDate`)** | {metrics["bookingDate"]}/{total} | {acc_date:.1f}% |
| **Khung giờ chơi (`slotList`)** | {metrics["slotList"]}/{total} | {acc_slots:.1f}% |
| **Vị trí/Khu vực (`location`)** | {metrics["location"]}/{total} | {acc_loc:.1f}% |
| **Khớp hoàn hảo (Strict Match - 5/5)** | **{metrics["strict_match"]}/{total}** | **{acc_strict:.1f}%** |

## 2. Chi tiết kết quả kiểm thử từng câu lệnh

| STT | Câu lệnh người dùng | Action mong muốn | Action thực tế | Slot mong muốn | Slot thực tế | Kết quả |
| :---: | :--- | :--- | :--- | :---: | :---: | :---: |
"""
    for idx, d in enumerate(details, 1):
        report_md += f"| {idx} | \"{d['query']}\" | {d['expected_action']} | {d['got_action']} | {d['expected_slots']} | {d['got_slots']} | {d['strict_match']} |\n"
        
    print("\n" + "="*50 + "\n")
    try:
        print(report_md)
    except UnicodeEncodeError:
        print(report_md.encode('utf-8', errors='ignore').decode('utf-8', errors='ignore'))
        
    # Ghi file kết quả
    eval_dir = Path(__file__).resolve().parent.parent / "data" / "eval"
    eval_dir.mkdir(parents=True, exist_ok=True)
    
    with open(eval_dir / "results_gemini_slots.md", "w", encoding="utf-8") as f:
        f.write(report_md)
        
    results_json = {
        "total_test_cases": total,
        "accuracies": {
            "action": round(acc_action, 2),
            "pitchType": round(acc_pitch, 2),
            "bookingDate": round(acc_date, 2),
            "slotList": round(acc_slots, 2),
            "location": round(acc_loc, 2),
            "strict_match": round(acc_strict, 2)
        }
    }
    
    with open(eval_dir / "results_gemini_slots.json", "w", encoding="utf-8") as f:
        json.dump(results_json, f, indent=2, ensure_ascii=False)
        
    print(f"Báo cáo Markdown đã xuất ra: {eval_dir / 'results_gemini_slots.md'}")
    print(f"Số liệu JSON đã xuất ra: {eval_dir / 'results_gemini_slots.json'}")

if __name__ == "__main__":
    run_evaluation()
