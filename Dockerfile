# syntax=docker/dockerfile:1

# ── 1단계: 빌드 (JDK) ─────────────────────────────────────────────
# JDK가 포함된 이미지에서 gradle 빌드 → 실행 가능한 layered jar 생성
FROM eclipse-temurin:25-jdk AS builder
WORKDIR /workspace

# 1) gradle 래퍼/설정만 먼저 복사 → 의존성 해석 레이어를 소스와 분리해 캐시
COPY gradlew settings.gradle.kts build.gradle.kts ./
COPY gradle ./gradle
RUN chmod +x gradlew && ./gradlew dependencies --no-daemon || true

# 2) 소스 복사 후 빌드 (테스트는 CI에서 이미 돌리므로 이미지 빌드에선 skip)
COPY src ./src
RUN ./gradlew bootJar --no-daemon -x test

# 3) layered jar 를 레이어별로 추출
#    dependencies(거의 안 변함) / application(자주 변함) 을 분리해 재빌드 시 캐시 최대화
RUN java -Djarmode=tools -jar build/libs/*.jar extract --layers --destination extracted

# ── 2단계: 실행 (JRE) ─────────────────────────────────────────────
# JRE만 있는 가벼운 이미지에 추출한 레이어를 "안 변하는 것 → 자주 변하는 것" 순서로 복사
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# root 대신 비특권 사용자로 실행 (보안)
RUN useradd -r -u 1001 appuser
USER appuser

# 순서 중요: 앞 레이어일수록 잘 안 변함 → 코드만 고치면 마지막 레이어만 다시 빌드됨
COPY --from=builder /workspace/extracted/dependencies/ ./
COPY --from=builder /workspace/extracted/spring-boot-loader/ ./
COPY --from=builder /workspace/extracted/snapshot-dependencies/ ./
COPY --from=builder /workspace/extracted/application/ ./

EXPOSE 8080
ENTRYPOINT ["java", "org.springframework.boot.loader.launch.JarLauncher"]
