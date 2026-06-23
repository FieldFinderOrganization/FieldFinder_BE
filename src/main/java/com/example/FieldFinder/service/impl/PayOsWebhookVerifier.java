package com.example.FieldFinder.service.impl;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.TreeMap;

/**
 * Xác minh chữ ký webhook PayOS (HMAC-SHA256 trên {@code data} đã sort key, so với
 * {@code signature}). Chống webhook giả mạo — quan trọng vì nạp ví dẫn tới tiền thật.
 *
 * <p>3 chế độ qua {@code payos.webhook.signature-mode}:
 * <ul>
 *   <li>{@code off} — bỏ qua hoàn toàn (giữ hành vi cũ).</li>
 *   <li>{@code log} — tính + log khớp/lệch nhưng VẪN xử lý tiếp (mặc định: quan sát an toàn,
 *       không phá luồng BANK trong lúc kiểm chứng scheme với webhook thật).</li>
 *   <li>{@code enforce} — CHẶN khi chữ ký sai.</li>
 * </ul>
 * Quy trình: deploy {@code log} → xem log xác nhận luôn "match" với call thật của PayOS →
 * đổi sang {@code enforce}.</p>
 */
@Component
@Slf4j
public class PayOsWebhookVerifier {

    @Value("${payos.checksumKey}")
    private String checksumKey;

    @Value("${payos.webhook.signature-mode:log}")
    private String mode;

    /** true = cho phép xử lý webhook tiếp; false = chặn (chữ ký sai + mode=enforce). */
    public boolean allow(Map<String, Object> payload) {
        if (payload == null || "off".equalsIgnoreCase(mode)) return true;

        boolean valid = isValid(payload);
        if (valid) return true;

        if ("enforce".equalsIgnoreCase(mode)) {
            log.warn("⛔ PayOS webhook chữ ký SAI → từ chối (mode=enforce). payload keys={}",
                    payload.keySet());
            return false;
        }
        // mode=log: chỉ cảnh báo, vẫn xử lý (quan sát trước khi enforce)
        log.warn("⚠️ PayOS webhook chữ ký LỆCH (mode=log, vẫn xử lý). "
                + "Kiểm chứng trước khi bật enforce.");
        return true;
    }

    /** Tính HMAC-SHA256 trên data đã sort key rồi so hằng-thời-gian với signature. */
    public boolean isValid(Map<String, Object> payload) {
        Object sig = payload.get("signature");
        Object dataObj = payload.get("data");
        if (!(sig instanceof String signature) || !(dataObj instanceof Map<?, ?> data)) {
            return false;
        }
        try {
            String raw = buildSortedQuery(data);
            String computed = hmacSha256Hex(raw, checksumKey);
            return MessageDigest.isEqual(
                    computed.getBytes(StandardCharsets.UTF_8),
                    signature.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            log.warn("PayOS verify lỗi: {}", e.getMessage());
            return false;
        }
    }

    /** key1=val1&key2=val2... (sort key asc). null/"null"/"undefined" → "". Đúng SDK PayOS. */
    private static String buildSortedQuery(Map<?, ?> data) {
        TreeMap<String, Object> sorted = new TreeMap<>();
        for (Map.Entry<?, ?> e : data.entrySet()) {
            sorted.put(String.valueOf(e.getKey()), e.getValue());
        }
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Object> e : sorted.entrySet()) {
            if (sb.length() > 0) sb.append("&");
            Object v = e.getValue();
            String val = (v == null) ? "" : String.valueOf(v);
            if ("null".equals(val) || "undefined".equals(val)) val = "";
            sb.append(e.getKey()).append("=").append(val);
        }
        return sb.toString();
    }

    private static String hmacSha256Hex(String data, String key) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : hash) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
