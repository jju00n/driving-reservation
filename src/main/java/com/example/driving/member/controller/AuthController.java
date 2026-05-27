package com.example.driving.member.controller;

import com.example.driving.common.response.ApiResponse;
import com.example.driving.member.dto.LoginRequest;
import com.example.driving.member.dto.LoginResponse;
import com.example.driving.member.dto.SignupRequest;
import com.example.driving.member.service.MemberService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Auth", description = "인증 API")
@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final MemberService memberService;

    @Operation(summary = "회원가입")
    @PostMapping("/signup")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Void> signup(@Valid @RequestBody SignupRequest request) {
        memberService.signup(request);
        return ApiResponse.ok();
    }

    @Operation(summary = "로그인")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(memberService.login(request));
    }

    @Operation(summary = "로그아웃")
    @PostMapping("/logout")
    public  ApiResponse<Void> logout(HttpServletRequest request) {
        String token = request.getHeader("Authorization").substring(7);
        memberService.logout(token);
        return ApiResponse.ok();
    }
}
