package com.example.driving.member.dto;

public record LoginResponse(
        String accessToken,
        String refreshToken
) {}
