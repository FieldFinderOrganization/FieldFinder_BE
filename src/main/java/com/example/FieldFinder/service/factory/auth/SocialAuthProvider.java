package com.example.FieldFinder.service.factory.auth;

import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.entity.UserProvider.ProviderName;

public interface SocialAuthProvider {
    ProviderName getProvider();

    AuthTokenResponseDTO login(String token);
}
