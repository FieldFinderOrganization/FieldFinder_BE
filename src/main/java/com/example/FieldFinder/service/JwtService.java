package com.example.FieldFinder.service;

import com.example.FieldFinder.dto.res.AuthTokenResponseDTO;
import com.example.FieldFinder.dto.res.UserResponseDTO;
import com.example.FieldFinder.entity.User;
import io.jsonwebtoken.Claims;

public interface JwtService {
    String generateAccessToken(User user);

    String generateRefreshToken(User user);

    AuthTokenResponseDTO generateTokenPair(User user);

    Claims verifyAccessToken(String token);

    String refreshAccessToken(String rawRefreshToken);

    void revokeRefreshToken(String rawRefreshToken);
}
