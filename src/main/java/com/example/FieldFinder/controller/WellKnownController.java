package com.example.FieldFinder.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class WellKnownController {

    @Value("${passkey.android.fingerprint}")
    private String androidFingerprint;

    @GetMapping(value = "/.well-known/assetlinks.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public List<Map<String, Object>> getAssetLinks() {
        return List.of(
                Map.of(
                        "relation", List.of(
                                "delegate_permission/common.handle_all_urls",
                                "delegate_permission/common.get_login_creds"
                        ),
                        "target", Map.of(
                                "namespace", "android_app",
                                "package_name", "vn.mttt.sportshub",
                                "sha256_cert_fingerprints", List.of(androidFingerprint)
                        )
                )
        );
    }
}