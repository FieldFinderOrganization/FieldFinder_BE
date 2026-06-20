package com.example.FieldFinder.service.banklookup;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Map;

/**
 * [DEPRECATED] Tra cứu tên chủ TK qua VietQR.io — đã dừng từ 2024-08-20.
 * Thay thế bởi {@link BankLookupNetService} (BankLookup.net).
 * Giữ lại để tham khảo; không còn được Spring inject.
 */
@Deprecated
// @Service  — đã thay bằng BankLookupNetService
public class VietQrBankLookupService implements BankLookupService {

    private final WebClient webClient;
    private final boolean enabled;
    private final String url;
    private final String clientId;
    private final String apiKey;

    public VietQrBankLookupService(WebClient webClient,
                                   @Value("${bank.lookup.enabled:false}") boolean enabled,
                                   @Value("${bank.lookup.vietqr.url:https://api.vietqr.io/v2/lookup}") String url,
                                   @Value("${bank.lookup.vietqr.client-id:}") String clientId,
                                   @Value("${bank.lookup.vietqr.api-key:}") String apiKey) {
        this.webClient = webClient;
        this.enabled = enabled;
        this.url = url;
        this.clientId = clientId;
        this.apiKey = apiKey;
    }

    @Override
    public BankLookupResult lookup(String bankBin, String accountNumber) {
        // Tắt tra cứu (VietQR Free Plan dừng / Cas Pay Out chưa GA) ⇒ không gọi API, không treo
        if (!enabled) {
            return BankLookupResult.unavailable("Tạm chưa hỗ trợ tra cứu tự động — nhập tên thủ công.");
        }
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
            // code != 00: chỉ kết luận TK ẢO khi rõ ràng là lỗi tài khoản; còn lại
            // (hết hạn plan code"47", thiếu key, rate limit, provider busy...) ⇒ tạm thời.
            String desc = str(res.get("desc"));
            return isAccountInvalid(desc)
                    ? BankLookupResult.invalid(desc)
                    : BankLookupResult.unavailable(desc != null ? desc : "Không tra cứu được");

        } catch (WebClientResponseException e) {
            // 429 (rate limit) / 4xx / 5xx ⇒ lỗi tạm thời, không chặn user
            return BankLookupResult.unavailable("VietQR HTTP " + e.getRawStatusCode());
        } catch (Exception e) {
            // timeout / mạng
            return BankLookupResult.unavailable("Lỗi gọi VietQR: " + e.getMessage());
        }
    }

    /** Chỉ những desc rõ ràng "tài khoản/BIN không hợp lệ" mới coi là TK ảo (hard reject). */
    private static boolean isAccountInvalid(String desc) {
        if (desc == null) return false;
        String d = desc.toLowerCase();
        return d.contains("account number invalid")
                || d.contains("invalid account")
                || d.contains("invalid bin")
                || d.contains("bank not support")
                || d.contains("không tồn tại")
                || d.contains("không hợp lệ");
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
