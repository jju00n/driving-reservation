package com.example.driving.member.repository;

import com.example.driving.member.domain.Member;
import com.example.driving.support.AbstractRepositoryTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class MemberRepositoryTest extends AbstractRepositoryTest {

    @Autowired
    private MemberRepository memberRepository;

    @Test
    @DisplayName("이메일로 회원을 조회한다")
    void findByEmail() {
        memberRepository.save(Member.create("test@example.com", "encoded", "name", "01000000000"));

        Optional<Member> found = memberRepository.findByEmail("test@example.com");

        assertThat(found).isPresent();
        assertThat(found.get().getEmail()).isEqualTo("test@example.com");
    }

    @Test
    @DisplayName("존재하지 않는 이메일은 빈 결과를 반환한다")
    void findByEmail_notFound() {
        Optional<Member> found = memberRepository.findByEmail("none@example.com");

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("이메일 존재 여부를 확인한다")
    void existsByEmail() {
        memberRepository.save(Member.create("exists@example.com", "encoded", "name", "01000000000"));

        assertThat(memberRepository.existsByEmail("exists@example.com")).isTrue();
        assertThat(memberRepository.existsByEmail("none@example.com")).isFalse();
    }
}
