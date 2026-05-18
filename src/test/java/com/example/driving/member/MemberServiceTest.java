package com.example.driving.member;

import com.example.driving.common.exception.BusinessException;
import com.example.driving.common.security.JwtProvider;
import com.example.driving.common.util.AesEncryptUtil;
import com.example.driving.member.domain.Member;
import com.example.driving.member.dto.LoginRequest;
import com.example.driving.member.dto.LoginResponse;
import com.example.driving.member.dto.SignupRequest;
import com.example.driving.member.enums.Role;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.member.service.MemberService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MemberServiceTest {

    @InjectMocks
    private MemberService memberService;

    @Mock
    private MemberRepository memberRepository;
    @Mock
    private PasswordEncoder passwordEncoder;
    @Mock
    private AesEncryptUtil aesEncryptUtil;
    @Mock
    private JwtProvider jwtProvider;

    @Test
    @DisplayName("회원가입 성공")
    void signup_success() {
        SignupRequest request = new SignupRequest("test@example.com", "password123", "홍길동", "01012345678");

        given(memberRepository.existsByEmail(request.email())).willReturn(false);
        given(passwordEncoder.encode(anyString())).willReturn("encodedPw");
        given(aesEncryptUtil.encrypt(anyString())).willReturn("encrypted");

        memberService.signup(request);

        verify(memberRepository).save(any(Member.class));
    }

    @Test
    @DisplayName("회원가입 실패 - 중복 이메일")
    void signup_fail_duplicateEmail() {
        SignupRequest request = new SignupRequest("test@example.com", "password123", "홍길동", "01012345678");

        given(memberRepository.existsByEmail(request.email())).willReturn(true);

        assertThatThrownBy(() -> memberService.signup(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이미 사용 중인 이메일입니다.");
    }

    @Test
    @DisplayName("로그인 성공")
    void login_success() {
        LoginRequest request = new LoginRequest("test@example.com", "password123");
        Member member = Member.builder()
                .memberIdx(1L)
                .email("test@example.com")
                .password("encodedPw")
                .role(Role.CUSTOMER)
                .build();

        given(memberRepository.findByEmail(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPassword())).willReturn(true);
        given(jwtProvider.createAccessToken(any(), any())).willReturn("access-token");
        given(jwtProvider.createRefreshToken(any())).willReturn("refresh-token");

        LoginResponse response = memberService.login(request);

        assertThat(response.accessToken()).isEqualTo("access-token");
        assertThat(response.refreshToken()).isEqualTo("refresh-token");
    }

    @Test
    @DisplayName("로그인 실패 - 존재하지 않는 이메일")
    void login_fail_emailNotFound() {
        LoginRequest request = new LoginRequest("notfound@example.com", "password123");

        given(memberRepository.findByEmail(request.email())).willReturn(Optional.empty());

        assertThatThrownBy(() -> memberService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }

    @Test
    @DisplayName("로그인 실패 - 비밀번호 불일치")
    void login_fail_wrongPassword() {
        LoginRequest request = new LoginRequest("test@example.com", "wrongPassword");
        Member member = Member.builder()
                .memberIdx(1L)
                .email("test@example.com")
                .password("encodedPw")
                .role(Role.CUSTOMER)
                .build();

        given(memberRepository.findByEmail(request.email())).willReturn(Optional.of(member));
        given(passwordEncoder.matches(request.password(), member.getPassword())).willReturn(false);

        assertThatThrownBy(() -> memberService.login(request))
                .isInstanceOf(BusinessException.class)
                .hasMessage("이메일 또는 비밀번호가 올바르지 않습니다.");
    }
}
