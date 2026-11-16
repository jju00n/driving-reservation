# syntax=docker/dockerfile:1

# ── 1단계: 레이어 추출 ─────────────────────────────────────────────
# 애플리케이션 빌드(gradle 컴파일 + bootJar)는 CI(./gradlew build)에서 이미 끝났다.
# 이미지 빌드에선 재컴파일하지 않고, 미리 만들어진 layered jar 를 레이어별로 "추출"만 한다.
# → 컴파일이 CI 와 Docker 에서 중복되던 문제 제거. 추출만 하므로 JDK 불필요(JRE 로 충분).
FROM eclipse-temurin:25-jre AS extractor
WORKDIR /workspace

# CI/로컬에서 ./gradlew build 로 생성된 jar 를 빌드 컨텍스트에서 받는다(build/libs/*.jar).
# plain jar 는 build.gradle.kts 에서 비활성화 → build/libs 엔 실행 가능한 jar 하나뿐.
ARG JAR_FILE=build/libs/*.jar
COPY ${JAR_FILE} app.jar
RUN java -Djarmode=tools -jar app.jar extract --layers --destination extracted

# ── 2단계: 실행 (JRE) ─────────────────────────────────────────────
# 추출한 레이어를 "안 변하는 것 → 자주 변하는 것" 순서로 복사
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

# root 대신 비특권 사용자로 실행 (보안)
RUN useradd -r -u 1001 appuser
USER appuser

# 순서 중요: 앞 레이어일수록 잘 안 변함 → 코드만 고치면 마지막 레이어만 다시 빌드됨.
# Spring Boot 4 의 tools 추출 구조: dependencies=lib/ , application=app.jar(loader 내장).
# app.jar 는 같은 디렉토리의 lib/ 를 상대 참조하므로 둘을 같은 WORKDIR(/app)에 둔다.
COPY --from=extractor /workspace/extracted/dependencies/ ./
COPY --from=extractor /workspace/extracted/spring-boot-loader/ ./
COPY --from=extractor /workspace/extracted/snapshot-dependencies/ ./
COPY --from=extractor /workspace/extracted/application/ ./

EXPOSE 8080
# 얇은 app.jar 실행(loader 는 app.jar 에 내장) — 예전 JarLauncher 클래스 직접 지정 방식 아님.
ENTRYPOINT ["java", "-jar", "app.jar"]
