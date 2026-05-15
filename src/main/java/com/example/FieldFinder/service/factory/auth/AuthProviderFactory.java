package com.example.FieldFinder.service.factory.auth;

import com.example.FieldFinder.entity.UserProvider.ProviderName;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Component
public class AuthProviderFactory {

    private final Map<ProviderName, SocialAuthProvider> providers;

    public AuthProviderFactory(List<SocialAuthProvider> providerList) {
        this.providers = new EnumMap<>(ProviderName.class);
        for (SocialAuthProvider p : providerList) {
            this.providers.put(p.getProvider(), p);
        }
    }

    public SocialAuthProvider get(ProviderName name) {
        SocialAuthProvider provider = providers.get(name);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Provider không được hỗ trợ: " + name);
        }
        return provider;
    }
}
