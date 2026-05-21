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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
public class MemberService {

    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final AesEncryptUtil aesEncryptUtil;
    private final JwtProvider jwtProvider;
    private final StringRedisTemplate stringRedisTemplate;

    @Transactional
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

    @Transactional
    public LoginResponse login(LoginRequest request) {
        Member member = memberRepository.findByEmail(request.email())
                .orElseThrow(() -> new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED));

        if (member.isDeleted()) {
            throw new BusinessException("탈퇴한 회원입니다.", HttpStatus.UNAUTHORIZED);
        }

        if (!passwordEncoder.matches(request.password(), member.getPassword())) {
            throw new BusinessException("이메일 또는 비밀번호가 올바르지 않습니다.", HttpStatus.UNAUTHORIZED);
        }

        String accessToken = jwtProvider.createAccessToken(member.getMemberIdx(), member.getRole().name());
        String refreshToken = jwtProvider.createRefreshToken(member.getMemberIdx());

        stringRedisTemplate.opsForValue().set(
                "refresh:" + member.getMemberIdx(),
                refreshToken,
                7,
                TimeUnit.DAYS
        );

        return new LoginResponse(accessToken, refreshToken);
    }

    public void logout(String accessToken) {
        Long memberIdx = jwtProvider.getMemberIdx(accessToken);

        stringRedisTemplate.delete("refresh:" + memberIdx);

        Date expiration = jwtProvider.getExpiration(accessToken);
        long remainingMs = expiration.getTime() - System.currentTimeMillis();
        stringRedisTemplate.opsForValue().set(
                "blackList:" + accessToken,
                "logout",
                remainingMs,
                TimeUnit.MILLISECONDS
        );
    }
}
