package com.example.driving.member.service;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.common.security.JwtProvider;
import com.example.driving.common.util.AesEncryptUtil;
import com.example.driving.member.domain.Member;
import com.example.driving.member.dto.LoginRequest;
import com.example.driving.member.dto.LoginResponse;
import com.example.driving.member.dto.SignupRequest;
import com.example.driving.member.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AesEncryptUtil aesEncryptUtil;
    private final JwtProvider jwtProvider;

    public void signup(SignupRequest request) {
        if (memberRepository.existsByEmail(request.email())) {
            throw new BusinessException("이미 사용 중인 이메일입니다.");
        }

        Member member = Member.create(
                request.email(),
                passwordEncoder.encode(request.password()),
                aesEncryptUtil.encrypt(request.name()),
                aesEncryptUtil.encrypt(request.phone())
        );

        memberRepository.save(member);
    }

    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED));

        if (member.isDeleted()) {
            throw new BusinessException("탈퇴한 회원입니다.", HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }

        return new LoginResponse(
                jwtProvider.createAccessToken(member.getMemberIdx(), member.getRole().name()),
                jwtProvider.createRefreshToken(member.getMemberIdx())
        );
    }
}
