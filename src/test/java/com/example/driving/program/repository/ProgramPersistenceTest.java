package com.example.driving.program.repository;

import com.example.driving.program.domain.Program;
import com.example.driving.program.domain.Vehicle;
import com.example.driving.program.enums.ProgramStatus;
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
 * Program 저장 round-trip 테스트. 두 관심사를 분리한다:
 *
 * <ul>
 *   <li><b>랜덤 검증</b>(Fixture Monkey) — status/duration/amount 를 set 하지 않고 FM 이 타입 기반으로
 *       매번 다르게 생성하게 두고, 저장→조회 후 전부 보존되는지 본다. "어떤 유효 값이든 깨지지 않는다"를 검증.</li>
 *   <li><b>name 문자셋</b>(@ValueSource) — 이모지/다국어/특수문자/경계길이 등 특정 입력을 검증한다.
 *       값이 고정이라 Fixture Monkey 가 기여할 게 없으므로 순수 빌더를 쓴다(멘토 피드백: 값을 다 set 하면 FM 은 군더더기).</li>
 * </ul>
 */
@DisplayName("Program 저장 round-trip")
class ProgramPersistenceTest extends AbstractRepositoryTest {

    // 엔티티는 Lombok @Builder 를 쓰므로 builder 기반 introspector 로 생성한다.
    // defaultNotNull(true): NOT NULL 컬럼(duration/amount/status)을 set 없이도 FM 이 비-null 랜덤값으로 채운다.
    private static final FixtureMonkey fixtureMonkey = FixtureMonkey.builder()
            .objectIntrospector(BuilderArbitraryIntrospector.INSTANCE)
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

    // name 문자셋 보존만 검증하는 헬퍼. 나머지 값은 유효하기만 하면 되므로 순수 빌더로 충분하다.
    // (값을 전부 넘길 거면 Fixture Monkey 는 '빌더 + 군더더기' — 멘토 피드백. FM 은 아래 랜덤 테스트에만 남긴다)
    private Program newProgramWithName(String name, Long vehicleIdx) {
        LocalDateTime now = LocalDateTime.now();
        return Program.builder()
                .vehicleIdx(vehicleIdx)
                .name(name)
                .duration(60)
                .amount(150_000L)
                .status(ProgramStatus.ACTIVE)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Test
    @DisplayName("임의의 유효한 Program 이 저장 후 비즈니스 필드가 그대로 보존된다 (랜덤 생성 검증)")
    void program_roundTrip_randomBusinessFields() {
        Long vehicleIdx = savedVehicleIdx();
        LocalDateTime now = LocalDateTime.now();

        // 핵심: duration/amount/status 를 set 하지 않는다 → Fixture Monkey 가 타입 기반으로 매번 랜덤 생성.
        // 저장→조회 후 "전부" 보존되는지 검증한다(기존 테스트는 랜덤으로 만들기만 하고 name 만 단언 → 랜덤이 검증에 안 쓰였음).
        // 구조상 꼭 필요한 것만 고정한다:
        //   id(null→INSERT), vehicleIdx(FK 실재), name(VARCHAR(100) 길이를 FM 이 모름 → 문자셋은 전용 테스트),
        //   createdAt/updatedAt(FM 의 LocalDateTime 랜덤이 MySQL DATETIME 범위/정밀도와 어긋날 수 있어 고정).
        Program program = fixtureMonkey.giveMeBuilder(Program.class)
                .set("programIdx", null)
                .set("vehicleIdx", vehicleIdx)
                .set("name", "M 드라이빙 체험")
                .set("createdAt", now)
                .set("updatedAt", now)
                .sample();

        Program saved = programRepository.save(program);
        Program found = programRepository.findById(saved.getProgramIdx()).orElseThrow();

        assertThat(found)
                .usingRecursiveComparison()
                .ignoringFields("createdAt", "updatedAt") // MySQL DATETIME 나노초 절삭으로 정밀도 차이 → 비교 제외
                .isEqualTo(saved);
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
