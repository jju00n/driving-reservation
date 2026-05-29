# Testcontainers 학습 노트

> 멘토 피드백으로 도입한 Testcontainers의 원리, 일반 테스트와의 차이점, 장단점을 정리한 학습 노트.

## 1. 한 줄 요약

**Testcontainers는 테스트가 실행될 때 Docker 컨테이너로 실제 DB(혹은 Redis, Kafka 등)를 띄워, 운영 환경과 동일한 외부 시스템으로 테스트하게 해주는 라이브러리.**

JVM이 Docker daemon에 명령을 보내 컨테이너를 띄우고, 동적으로 할당된 포트 정보를 Spring에 주입한다.

---

## 2. 통합 테스트에서 DB를 다루는 3가지 방식

### A. 임베디드 DB (H2, HSQLDB)
- JVM 안에 인메모리 DB를 띄움
- **장점**: 매우 빠름, 외부 의존 없음
- **단점**: SQL 문법/타입/제약이 운영 DB(MySQL)와 미묘하게 다름 → "테스트는 통과했는데 운영에서 실패"

### B. 공용 외부 DB (docker-compose의 MySQL 등)
- 개발자가 미리 띄워둔 DB에 모든 테스트가 접속
- **장점**: 실제 DB와 동일
- **단점**:
  - 모든 테스트가 같은 DB 공유 → 테스트 순서/데이터 의존
  - 한 테스트가 남긴 데이터가 다른 테스트에 영향
  - 매 테스트마다 `delete from ...` 청소가 필요
  - CI 환경에 DB를 별도로 준비/관리해야 함
  - 병렬 실행 거의 불가

### C. Testcontainers
- 테스트 JVM이 직접 컨테이너를 띄우고, 종료 시 정리
- **장점**: 진짜 MySQL이고 + 깨끗한 환경 + 재현 가능
- **단점**: 컨테이너 부팅 시간(보통 5~10초), Docker daemon 필수

---

## 3. Testcontainers 동작 원리

### 흐름

1. **Docker daemon 탐색** — `DOCKER_HOST` 환경 변수, `/var/run/docker.sock` 등을 순차 시도
2. **이미지 pull** — `mysql:8.0` 이 로컬에 없으면 Docker Hub에서 다운로드
3. **컨테이너 시작** — `docker run mysql:8.0` 과 동일. **호스트 포트는 랜덤** 할당 (병렬 충돌 방지)
4. **Wait strategy** — MySQL이 "ready" 신호 보낼 때까지 블로킹
5. **Ryuk 컨테이너** — testcontainers/ryuk 라는 가비지 컬렉터 컨테이너가 같이 뜸. JVM이 비정상 종료해도 부모 컨테이너를 자동 정리

### Spring 통합 — `@ServiceConnection`

컨테이너 포트가 매번 다르기 때문에 `application.yml`에 URL을 하드코딩할 수 없다. `@ServiceConnection`이 해결:

1. Spring 컨텍스트 초기화 시점에 static 필드를 스캔
2. `MySQLContainer.getJdbcUrl()`, `getUsername()`, `getPassword()` 결과를 읽어
3. `JdbcConnectionDetails` 빈으로 등록 → HikariCP가 이걸 보고 connection pool 구성

→ `application-test.yml`에 datasource URL을 적지 않아도 동작.

---

## 4. 비교 표

| 항목 | H2 임베디드 | 공용 MySQL | Testcontainers |
|------|-------------|-------------|----------------|
| 실제 DB와 동일 | ✗ (호환성 문제) | ✓ | ✓ |
| 테스트 격리 | ✓ (인메모리) | ✗ (공용) | ✓ (컨테이너 격리) |
| 외부 인프라 필요 | ✗ | ✓ (DB 운영) | △ (Docker daemon만) |
| 첫 실행 속도 | 빠름 (<1초) | 빠름 | 느림 (5~10초 부팅) |
| 이후 테스트 속도 | 매우 빠름 | 빠름 | 컨테이너 재사용 시 빠름 |
| CI 환경 구성 | 쉬움 | 어려움 | 쉬움 (Docker만 있으면) |
| 병렬 실행 | 가능 | 불가 | 가능 (포트 랜덤) |
| 운영 SQL 검증 | ✗ | ✓ | ✓ |

---

## 5. 장점과 단점

### 장점

- **운영과 동일한 DB**: MySQL 고유 문법(`ON DUPLICATE KEY UPDATE`, `JSON_*`)이나 타임존, 컬레이션 같은 디테일까지 테스트됨
- **테스트 간 격리**: 매번 컨테이너를 새로 띄울 수 있어서 다른 테스트가 남긴 데이터에 영향받지 않음
- **재현 가능**: 내 PC에서도, 동료 PC에서도, CI에서도 동일한 MySQL 8.0 컨테이너. "내 컴퓨터에선 됐는데" 사라짐
- **다양한 인프라 지원**: MySQL뿐 아니라 Redis, Kafka, Elasticsearch, Localstack(AWS 흉내) 등 거의 모든 미들웨어
- **Ryuk 자동 정리**: JVM이 죽어도 컨테이너가 좀비로 남지 않음

### 단점

- **첫 부팅 비용**: 매 테스트마다 컨테이너 띄우면 매우 느려짐 → 공유 패턴 필수 (아래 6번)
- **Docker daemon 의존**: 로컬/CI에 Docker가 떠 있어야 함
- **CPU/메모리 사용**: 다수 컨테이너가 동시에 뜨면 자원 부담
- **이미지 다운로드**: 첫 실행 시 이미지 풀 시간 (수십 MB ~ 수백 MB)

---

## 6. 이 프로젝트에서 적용한 패턴

### static initializer 공유 컨테이너

`src/test/java/com/example/driving/support/AbstractIntegrationTest.java`:

```java
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379);

    static {
        MYSQL.start();
        REDIS.start();
    }
}
```

**왜 `@Container` 대신 `static { start(); }`?**

- `@Container` + `@Testcontainers`를 쓰면 **테스트 클래스 단위로 start/stop** → 클래스가 N개면 N번 컨테이너 부팅 (매우 느림)
- static initializer로 띄우면 **JVM lifetime 동안 한 번만** 시작 → 모든 테스트 클래스가 같은 컨테이너 재사용
- JVM 종료 시 Ryuk이 알아서 컨테이너 정리

### 테스트 격리

컨테이너가 공유되므로 데이터 격리는 별도로 챙겨야 한다:

- **Repository 테스트(`@DataJdbcTest`)**: Spring이 자동으로 `@Transactional`을 걸어 각 테스트 끝나면 롤백
- **Controller 통합 테스트(`@SpringBootTest`)**: 트랜잭션 자동 롤백이 안 되므로, `@BeforeEach`에서 명시적으로 `deleteAll()` + `flushDb()` (Redis)

### `application-test.yml`

```yaml
spring:
  sql:
    init:
      mode: always
      schema-locations: classpath:sql/schema.sql
```

- 컨테이너 안의 MySQL은 깨끗한 상태이므로 스키마를 매번 초기화
- `data.sql`은 로드 안 함 → 테스트가 자기 데이터를 명시적으로 준비

---

## 7. 언제 쓰면 좋은가 / 언제 부적합한가

### 적합

- 외부 시스템(DB, Redis, Kafka, S3 등)과의 통합 흐름 검증
- 운영 환경의 SQL/쿼리 동작 보장
- 멀티 인스턴스/동시성 테스트 (Redis 분산락 등 — 이 프로젝트 2주차 작업)
- CI 환경에서 매번 깨끗한 인프라 필요할 때

### 부적합

- **단위 테스트**: Mockito로 의존성 mock하는 게 훨씬 빠르고 명확. 외부 시스템 없이 검증 가능한 로직은 단위 테스트로
- **순수 도메인 로직 검증**: 외부 시스템과 무관하면 굳이 컨테이너 띄울 이유 없음
- **개발 머신에 Docker가 없는 환경**: Docker daemon 없으면 동작 자체 불가

---

## 8. 한 줄 결론

> **단위 테스트는 Mockito로 빠르게, 통합 테스트는 Testcontainers로 신뢰도 있게**
> — 두 층을 분리해서 가져가는 게 일반적인 권장 패턴이고, 이 프로젝트도 그 구조로 가는 중.

---

**참고:**
- Testcontainers 공식: https://java.testcontainers.org/
- Spring Boot 통합(`@ServiceConnection`): Spring Boot 3.1+ 부터 지원
- 이 프로젝트는 Spring Boot 4.0.4 + Testcontainers 2.0.4 조합 사용
