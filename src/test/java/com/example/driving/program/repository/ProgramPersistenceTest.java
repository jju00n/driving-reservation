package com.example.driving.program.repository;

import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.support.AbstractRepositoryTest;
import com.navercorp.fixturemonkey.FixtureMonkey;
import com.navercorp.fixturemonkey.api.introspector.BuilderArbitraryIntrospector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Program 저장 round-trip 테스트.
 *
 * "내가 만든 정상 객체만 넣으면 깨질 일이 없다"(멘토 피드백 — 성공 위주 테스트)를 벗어나기 위해,
 * - 엔티티 골격(status/duration/amount)은 Fixture Monkey 로 매번 랜덤 생성하고
 * - name 은 이모지/다국어/특수문자/경계길이 등 실제로 깨질 수 있는 입력으로 파라미터화한다.
 *
 * 목적: 저장 후 조회했을 때 값이 그대로 보존되는가(특히 MySQL utf8mb4 에서 이모지/다국어).
 */
@DisplayName("Program 저장 round-trip - Fixture Monkey 다양한 입력")
class ProgramPersistenceTest extends AbstractRepositoryTest {

    // 엔티티는 Lombok @Builder 를 쓰므로 builder 기반 introspector 로 생성한다.
    private static final FixtureMonkey fixtureMonkey = FixtureMonkey.builder()
            .objectIntrospector(BuilderArbitraryIntrospector.INSTANCE)
            // NOT NULL 컬럼(duration/amount/status)에 FM 이 null 을 넣지 않도록
            .defaultNotNull(true)
            .build();

    @Autowired
    private ProgramRepository programRepository;
    @Autowired
    private VehicleRepository vehicleRepository;

    private Long savedVehicleIdx() {
        LocalDateTime now = LocalDateTime.now();
        return vehicleRepository.save(Vehicle.builder()
                        .name("BMW").model("M3").createdAt(now).updatedAt(now).build())
                .getVehicleIdx();
    }

    private Program newProgramWithName(String name, Long vehicleIdx) {
        LocalDateTime now = LocalDateTime.now();
        return fixtureMonkey.giveMeBuilder(Program.class)
                .set("programIdx", null)          // @Id null → INSERT
                .set("vehicleIdx", vehicleIdx)    // FK 충족
                .set("name", name)                // 검증 대상은 직접 지정
                .set("createdAt", now)            // DATETIME 범위 밖 랜덤값 방지
                .set("updatedAt", now)
                // status / duration / amount 는 Fixture Monkey 랜덤값 그대로 사용
                .sample();
    }

    @ParameterizedTest(name = "name=\"{0}\"")
    @ValueSource(strings = {
            "BMW M3 드라이빙 체험",
            "中文驾驶体验课程",          // 중국어
            "日本語のドライビング",       // 일본어
            "🚗💨 드라이빙 🏎️🔥",        // 이모지(BMP 밖)
            "MiXeD CaSe Program",      // 대소문자 혼합
            "a",                       // 최소 길이
            "프로그램'; DROP TABLE--",  // 특수문자/SQL 유사 문자열
    })
    @DisplayName("다양한 문자셋 name 이 저장 후 그대로 조회된다 (utf8mb4)")
    void name_roundTrip_variousCharsets(String name) {
        Long vehicleIdx = savedVehicleIdx();

        Program saved = programRepository.save(newProgramWithName(name, vehicleIdx));
        Program found = programRepository.findById(saved.getProgramIdx()).orElseThrow();

        assertThat(found.getName()).isEqualTo(name);
    }

    @Test
    @DisplayName("name VARCHAR(100) 경계 - 100자(다국어) 저장/조회 보존")
    void name_maxLength_boundary() {
        Long vehicleIdx = savedVehicleIdx();
        String name100 = "가".repeat(100);

        Program saved = programRepository.save(newProgramWithName(name100, vehicleIdx));
        Program found = programRepository.findById(saved.getProgramIdx()).orElseThrow();

        assertThat(found.getName()).hasSize(100).isEqualTo(name100);
    }
}
