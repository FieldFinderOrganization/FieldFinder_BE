package com.example.FieldFinder.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;

@Service
public class PayOSService {

    private final WebClient webClient;

    public PayOSService(@Value("${payos.endpoint}") String baseUrl) {
        this.webClient = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Value("${payos.clientId}")
    private String clientId;

    @Value("${payos.apiKey}")
    private String apiKey;

    @Value("${payos.checksumKey}")
    private String checksumKey;

    @Value("${payos.cancelUrl}")
    private String defaultCancelUrl;

    @Value("${payos.returnUrl}")
    private String defaultReturnUrl;

    public record PaymentResult(String checkoutUrl, String paymentLinkId, String qrCode) {
    }

    /** Trạng thái thật của 1 payment link đọc trực tiếp từ PayOS (không tin webhook). */
    public record PaymentInfo(String status, long amount, long amountPaid) {
        public boolean isPaid() {
            return "PAID".equalsIgnoreCase(status);
        }
    }

    /**
     * Đọc trạng thái payment link từ PayOS theo {@code paymentLinkId} (server-to-server).
     * Dùng để XÁC NHẬN nạp ví trước khi cộng tiền — chống webhook giả mạo. Trả null nếu lỗi.
     */
    public PaymentInfo getPaymentInfo(String paymentLinkId) {
        try {
            Map<?, ?> res = webClient.get()
                    .uri("/v2/payment-requests/{id}", paymentLinkId)
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .block();
            if (res == null || !"00".equals(res.get("code"))) return null;
            Map<?, ?> data = (Map<?, ?>) res.get("data");
            if (data == null) return null;
            String status = String.valueOf(data.get("status"));
            long amount = toLong(data.get("amount"));
            long amountPaid = toLong(data.get("amountPaid"));
            return new PaymentInfo(status, amount, amountPaid);
        } catch (Exception e) {
            System.err.println("PayOS getPaymentInfo lỗi cho " + paymentLinkId + ": " + e.getMessage());
            return null;
        }
    }

    private static long toLong(Object o) {
        if (o instanceof Number n) return n.longValue();
        try {
            return o == null ? 0L : Long.parseLong(String.valueOf(o));
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    public PaymentResult createPayment(BigDecimal amount, int orderCode, String description, String returnUrl,
                                       String cancelUrl) {
        int amountInt = amount.intValue();

        String finalReturnUrl = (returnUrl != null && !returnUrl.isEmpty()) ? returnUrl : defaultReturnUrl;
        String finalCancelUrl = (cancelUrl != null && !cancelUrl.isEmpty()) ? cancelUrl : defaultCancelUrl;

        Map<String, Object> params = new TreeMap<>();
        params.put("amount", amountInt);
        params.put("cancelUrl", finalCancelUrl);
        params.put("description", description);
        params.put("orderCode", orderCode);
        params.put("returnUrl", finalReturnUrl);

        StringBuilder rawData = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (!rawData.isEmpty()) {
                rawData.append("&");
            }
            rawData.append(entry.getKey()).append("=").append(entry.getValue());
        }

        String signature;
        try {
            signature = generateSignature(rawData.toString(), checksumKey);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate signature", e);
        }

        Map<String, Object> body = new HashMap<>(params);
        body.put("signature", signature);

        System.out.println("PayOS request payload: " + body);
        System.out.println("Raw signature data: " + rawData);

        try {
            return webClient.post()
                    .uri("/v2/payment-requests")
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .doOnNext(res -> System.out.println("PayOS response: " + res))
                    .<PaymentResult>handle((res, sink) -> {
                        if (!"00".equals(res.get("code"))) {
                            sink.error(new IllegalStateException("PayOS error: " + res.get("desc")));
                            return;
                        }
                        Map<?, ?> data = (Map<?, ?>) res.get("data");
                        if (data == null) {
                            sink.error(new IllegalStateException("Missing data in PayOS response"));
                            return;
                        }

                        String checkoutUrl = (String) data.get("checkoutUrl");
                        String transactionId = (String) data.get("paymentLinkId");
                        String qrCode = (String) data.get("qrCode");

                        if (checkoutUrl == null || transactionId == null) {
                            sink.error(new IllegalStateException("Missing checkoutUrl or paymentLinkId in PayOS response"));
                            return;
                        }

                        sink.next(new PaymentResult(checkoutUrl, transactionId, qrCode));
                    })
                    .block();
        } catch (WebClientResponseException e) {
            System.err.println("PayOS API error: " + e.getResponseBodyAsString());
            throw new RuntimeException("PayOS API request failed", e);
        } catch (Exception e) {
            System.err.println("Unexpected error during PayOS payment creation: " + e.getMessage());
            throw new RuntimeException("Payment creation failed", e);
        }
    }

    private String generateSignature(String data, String key) throws Exception {
        Mac sha256_HMAC = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKey = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        sha256_HMAC.init(secretKey);
        byte[] hash = sha256_HMAC.doFinal(data.getBytes(StandardCharsets.UTF_8));

        StringBuilder hexString = new StringBuilder();
        for (byte b : hash) {
            hexString.append(String.format("%02x", b));
        }
        return hexString.toString();
    }
}