package com.example.FieldFinder.service.banklookup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * Tra cứu tên chủ TK qua VietQR.io (CASSO / NAPAS).
 * {@code POST https://api.vietqr.io/v2/lookup}, headers x-client-id + x-api-key,
 * body {@code {"bin": <int>, "accountNumber": "<str>"}}.
 * Thành công: {@code {"code":"00","data":{"accountName":"..."}}}.
 */
@Service
public class VietQrBankLookupService implements BankLookupService {

    private final WebClient webClient;
    private final String url;
    private final String clientId;
    private final String apiKey;

    public VietQrBankLookupService(WebClient webClient,
                                   @Value("${bank.lookup.vietqr.url:https://api.vietqr.io/v2/lookup}") String url,
                                   @Value("${bank.lookup.vietqr.client-id:}") String clientId,
                                   @Value("${bank.lookup.vietqr.api-key:}") String apiKey) {
        this.webClient = webClient;
        this.url = url;
        this.clientId = clientId;
        this.apiKey = apiKey;
    }

    @Override
    public BankLookupResult lookup(String bankBin, String accountNumber) {
        if (bankBin == null || accountNumber == null) {
            return BankLookupResult.invalid("Thiếu mã ngân hàng hoặc số tài khoản!");
        }
        int bin;
        try {
            bin = Integer.parseInt(bankBin.trim());
        } catch (NumberFormatException e) {
            return BankLookupResult.invalid("Mã ngân hàng (BIN) không hợp lệ!");
        }
        // Chưa cấu hình key ⇒ không tra cứu được, để soft-save quyết định
        if (clientId.isBlank() || apiKey.isBlank()) {
            return BankLookupResult.unavailable("Chưa cấu hình VietQR client-id/api-key");
        }

        Map<String, Object> body = Map.of("bin", bin, "accountNumber", accountNumber.trim());

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = webClient.post()
                    .uri(url)
                    .header("x-client-id", clientId)
                    .header("x-api-key", apiKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(6))
                    .block();

            if (res == null) {
                return BankLookupResult.unavailable("VietQR không phản hồi");
            }
            String code = String.valueOf(res.get("code"));
            if ("00".equals(code)) {
                Object data = res.get("data");
                String name = (data instanceof Map<?, ?> m) ? str(m.get("accountName")) : null;
                if (name != null && !name.isBlank()) {
                    return BankLookupResult.ok(name.trim());
                }
                // code 00 mà thiếu tên — coi như lỗi tạm thời, không kết luận TK ảo
                return BankLookupResult.unavailable("VietQR thiếu tên chủ tài khoản");
            }
            // code != 00 ⇒ ngân hàng kết luận TK/BIN không hợp lệ
            String desc = str(res.get("desc"));
            return BankLookupResult.invalid(desc != null ? desc : "Số tài khoản không hợp lệ");

        } catch (WebClientResponseException e) {
            // 429 (rate limit) / 4xx / 5xx ⇒ lỗi tạm thời, không chặn user
            return BankLookupResult.unavailable("VietQR HTTP " + e.getRawStatusCode());
        } catch (Exception e) {
            // timeout / mạng
            return BankLookupResult.unavailable("Lỗi gọi VietQR: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
