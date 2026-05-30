package com.example.driving.member.controller;

import com.example.driving.member.domain.Member;
import com.example.driving.member.dto.LoginRequest;
import com.example.driving.member.dto.SignupRequest;
import com.example.driving.member.repository.MemberRepository;
import com.example.driving.support.AbstractIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 컨테이너를 공유하므로 테스트 간 DB 데이터가 누적된다.
 * 별도 cleanup 없이도 격리되도록, 각 테스트는 고유한 이메일을 사용하고
 * 자신이 생성/조회한 데이터(memberIdx 단위 Redis 키 등)만 단언한다.
 */
class AuthControllerIntegrationTest extends AbstractIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MemberRepository memberRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    @DisplayName("회원가입 성공 - 201 응답 및 DB 저장")
    void signup_success() throws Exception {
        SignupRequest request = new SignupRequest("signup-success@example.com", "password123", "홍길동", "01012345678");

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true));

        assertThat(memberRepository.existsByEmail("signup-success@example.com")).isTrue();
    }

    @Test
    @DisplayName("회원가입 실패 - 잘못된 이메일은 400")
    void signup_fail_invalidEmail() throws Exception {
        SignupRequest request = new SignupRequest("not-an-email", "password123", "홍길동", "01012345678");

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("회원가입 실패 - 중복 이메일")
    void signup_fail_duplicateEmail() throws Exception {
        memberRepository.save(Member.create("signup-dup@example.com", passwordEncoder.encode("password123"), "이름", "01000000000"));
        SignupRequest request = new SignupRequest("signup-dup@example.com", "password123", "홍길동", "01012345678");

        mockMvc.perform(post("/auth/signup")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("이미 사용 중인 이메일입니다."));
    }

    @Test
    @DisplayName("로그인 성공 - access/refresh 토큰 발급 및 Redis에 refresh 저장")
    void login_success() throws Exception {
        Member member = memberRepository.save(Member.create(
                "login-success@example.com", passwordEncoder.encode("password123"), "이름", "01000000000"));
        LoginRequest request = new LoginRequest("login-success@example.com", "password123");

        MvcResult result = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andExpect(jsonPath("$.data.refreshToken").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String refreshToken = body.get("data").get("refreshToken").asText();
        String stored = stringRedisTemplate.opsForValue().get("refresh:" + member.getMemberIdx());
        assertThat(stored).isEqualTo(refreshToken);
    }

    @Test
    @DisplayName("로그인 실패 - 비밀번호 불일치는 401")
    void login_fail_wrongPassword() throws Exception {
        memberRepository.save(Member.create(
                "login-fail@example.com", passwordEncoder.encode("password123"), "이름", "01000000000"));
        LoginRequest request = new LoginRequest("login-fail@example.com", "wrong-password");

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    @DisplayName("로그아웃 성공 - 블랙리스트 등록 및 refresh 삭제")
    void logout_success() throws Exception {
        Member member = memberRepository.save(Member.create(
                "logout-success@example.com", passwordEncoder.encode("password123"), "이름", "01000000000"));

        LoginRequest loginRequest = new LoginRequest("logout-success@example.com", "password123");
        MvcResult loginResult = mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(loginRequest)))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode body = objectMapper.readTree(loginResult.getResponse().getContentAsString());
        String accessToken = body.get("data").get("accessToken").asText();

        mockMvc.perform(post("/auth/logout")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk());

        assertThat(stringRedisTemplate.hasKey("blackList:" + accessToken)).isTrue();
        assertThat(stringRedisTemplate.hasKey("refresh:" + member.getMemberIdx())).isFalse();
    }
}
