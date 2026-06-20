package com.example.FieldFinder.service.banklookup;

import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tra cứu tên chủ TK qua BankLookup.net (thay thế VietQR.io đã dừng 2024-08-20).
 * <pre>
 * POST https://api.banklookup.net
 * Headers: x-api-key, x-api-secret, Content-Type: application/json
 * Body:    {"bank": "MB", "account": "1234567890"}
 * Success: {"code":200,"success":true,"data":{"ownerName":"NGUYEN VAN A"}}
 * Errors:  402 (hết credit), 422 (TK không tồn tại), 429 (rate limit)
 * </pre>
 * <p>
 * BankLookup.net dùng <b>bank code</b> (vd "MB", "ACB") thay vì BIN (vd "970422").
 * Service tự fetch danh sách bank từ {@code /bank/list} lúc khởi động để build map BIN → code.
 */
@Service
public class BankLookupNetService implements BankLookupService {

    private static final Logger log = LoggerFactory.getLogger(BankLookupNetService.class);

    private final WebClient webClient;
    private final boolean enabled;
    private final String apiKey;
    private final String apiSecret;

    /** Map BIN (String, vd "970422") → bank code (vd "MB"). Loaded lúc init. */
    private final Map<String, String> binToCode = new ConcurrentHashMap<>();

    public BankLookupNetService(WebClient webClient,
                                @Value("${bank.lookup.enabled:false}") boolean enabled,
                                @Value("${bank.lookup.api-key:}") String apiKey,
                                @Value("${bank.lookup.api-secret:}") String apiSecret) {
        this.webClient = webClient;
        this.enabled = enabled;
        this.apiKey = apiKey;
        this.apiSecret = apiSecret;
    }

    /** Fetch danh sách ngân hàng từ BankLookup.net lúc khởi động để build map BIN → code. */
    @PostConstruct
    void loadBankList() {
        if (!enabled || apiKey.isBlank() || apiSecret.isBlank()) {
            log.info("BankLookupNet: disabled hoặc chưa cấu hình key — bỏ qua load danh sách ngân hàng.");
            return;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = webClient.get()
                    .uri("https://api.banklookup.net/bank/list")
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(10))
                    .block();

            if (res != null && Boolean.TRUE.equals(res.get("success"))) {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> banks = (List<Map<String, Object>>) res.get("data");
                if (banks != null) {
                    for (Map<String, Object> bank : banks) {
                        Object lookupSupported = bank.get("lookup_supported");
                        if (lookupSupported != null && !Integer.valueOf(1).equals(lookupSupported)) {
                            continue; // Bỏ qua bank không hỗ trợ lookup
                        }
                        String bin = String.valueOf(bank.get("bin"));
                        String code = (String) bank.get("code");
                        if (bin != null && code != null) {
                            binToCode.put(bin, code);
                        }
                    }
                    log.info("BankLookupNet: loaded {} ngân hàng (BIN → code).", binToCode.size());
                }
            }
        } catch (Exception e) {
            log.warn("BankLookupNet: không load được danh sách ngân hàng — lookup sẽ dùng BIN fallback. Lỗi: {}", e.getMessage());
        }
    }

    @Override
    public BankLookupResult lookup(String bankBin, String accountNumber) {
        // Tắt tra cứu ⇒ không gọi API, không treo
        if (!enabled) {
            return BankLookupResult.unavailable("Tạm chưa hỗ trợ tra cứu tự động — nhập tên thủ công.");
        }
        if (bankBin == null || accountNumber == null) {
            return BankLookupResult.invalid("Thiếu mã ngân hàng hoặc số tài khoản!");
        }
        String trimmedBin = bankBin.trim();
        String trimmedAccount = accountNumber.trim();
        if (trimmedBin.isEmpty() || trimmedAccount.isEmpty()) {
            return BankLookupResult.invalid("Mã ngân hàng hoặc số tài khoản trống!");
        }
        // Chưa cấu hình key ⇒ không tra cứu được
        if (apiKey.isBlank() || apiSecret.isBlank()) {
            return BankLookupResult.unavailable("Chưa cấu hình BankLookup API key/secret");
        }

        // BankLookup.net dùng bank code (vd "MB"), không phải BIN (vd "970422").
        // Map BIN → code; nếu không tìm thấy, thử gửi BIN trực tiếp (fallback).
        String bankCode = binToCode.getOrDefault(trimmedBin, trimmedBin);

        Map<String, String> body = Map.of("bank", bankCode, "account", trimmedAccount);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = webClient.post()
                    .uri("https://api.banklookup.net")
                    .header("x-api-key", apiKey)
                    .header("x-api-secret", apiSecret)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(Map.class)
                    .timeout(Duration.ofSeconds(8))
                    .block();

            if (res == null) {
                return BankLookupResult.unavailable("BankLookup không phản hồi");
            }

            boolean success = Boolean.TRUE.equals(res.get("success"));
            if (success) {
                Object data = res.get("data");
                String ownerName = (data instanceof Map<?, ?> m) ? str(m.get("ownerName")) : null;
                if (ownerName != null && !ownerName.isBlank()) {
                    return BankLookupResult.ok(ownerName.trim());
                }
                return BankLookupResult.unavailable("BankLookup thiếu tên chủ tài khoản");
            }

            // success=false: kiểm tra code/msg
            String msg = str(res.get("msg"));
            return BankLookupResult.unavailable(msg != null ? msg : "Không tra cứu được");

        } catch (WebClientResponseException e) {
            int status = e.getStatusCode().value();
            if (status == 422) {
                // 422 = Not Found — TK không tồn tại → kết luận TK ảo
                return BankLookupResult.invalid("Số tài khoản không tồn tại hoặc không hợp lệ!");
            }
            // 402 (hết credit), 429 (rate limit), 4xx/5xx khác → lỗi tạm thời
            return BankLookupResult.unavailable("BankLookup HTTP " + status);
        } catch (Exception e) {
            // timeout / mạng
            return BankLookupResult.unavailable("Lỗi gọi BankLookup: " + e.getMessage());
        }
    }

    private static String str(Object o) {
        return o == null ? null : o.toString();
    }
}
