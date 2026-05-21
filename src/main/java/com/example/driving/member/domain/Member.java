package com.example.driving.member.domain;

import com.example.driving.member.enums.Role;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Table("users")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@AllArgsConstructor
@Builder
public class Member {

    @Id
    private Long memberIdx;
    private String email;
    private String password;
    private String name;
    private String phone;
    private Role role;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public static Member create(String email, String encodedPassword, String encryptedName, String encryptedPhone) {
        LocalDateTime now = LocalDateTime.now();
        return Member.builder()
                .email(email)
                .password(encodedPassword)
                .name(encryptedName)
                .phone(encryptedPhone)
                .role(Role.CUSTOMER)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public boolean isDeleted() {
        return deletedAt != null;
    }
}
