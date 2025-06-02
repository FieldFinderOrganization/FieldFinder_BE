package com.example.FieldFinder.service.impl;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.Map;

@Service
public class VietQRService {

    private final WebClient webClient = WebClient.create("https://api.vietqr.io");

    public String generateQrImage(String accountNo, String accountName, String bankBin, BigDecimal amount, String info) {
        Map<String, Object> requestBody = Map.of(
                "accountNo", accountNo,
                "accountName", accountName,
                "acqId", bankBin,
                "amount", amount.intValue(),
                "addInfo", info,
                "format", "text",
                "template", "compact"
        );

        return webClient.post()
                .uri("/v2/generate")
                .bodyValue(requestBody)
                .retrieve()
                .bodyToMono(Map.class)
                .map(response -> (String) ((Map<?, ?>) response.get("data")).get("qrDataURL"))
                .onErrorReturn(null)
                .block();
    }
}
