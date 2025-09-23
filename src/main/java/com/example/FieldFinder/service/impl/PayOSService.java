package com.example.FieldFinder.service.impl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

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
    private String cancelUrl;

    @Value("${payos.returnUrl}")
    private String returnUrl;

    public record PaymentResult(String checkoutUrl, String paymentLinkId) {}

    public PaymentResult createPayment(BigDecimal amount, int orderCode, String description) {
        int amountInt = amount.intValue();

        Map<String, Object> params = new TreeMap<>();
        params.put("amount", amountInt);
        params.put("cancelUrl", cancelUrl);
        params.put("description", description);
        params.put("orderCode", orderCode);
        params.put("returnUrl", returnUrl);

        StringBuilder rawData = new StringBuilder();
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            if (rawData.length() > 0) {
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
                    .map(res -> {
                        if (!"00".equals(res.get("code"))) {
                            throw new IllegalStateException("PayOS error: " + res.get("desc"));
                        }
                        Map<?, ?> data = (Map<?, ?>) res.get("data");
                        if (data == null) {
                            throw new IllegalStateException("Missing data in PayOS response");
                        }

                        String checkoutUrl = (String) data.get("checkoutUrl");
                        String transactionId = (String) data.get("paymentLinkId");

                        if (checkoutUrl == null || transactionId == null) {
                            throw new IllegalStateException("Missing checkoutUrl or paymentLinkId in PayOS response");
                        }

                        return new PaymentResult(checkoutUrl, transactionId);
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
