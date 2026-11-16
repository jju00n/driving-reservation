package com.example.driving.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.utility.DockerImageName;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    @ServiceConnection
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0");

    @ServiceConnection(name = "redis")
    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .waitingFor(Wait.forListeningPort());

    /** 웹훅 수신/처리 분리용 브로커. KRaft 단일 노드라 Zookeeper 컨테이너가 없다. */
    @ServiceConnection
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.8.0");

    static {
        // 컨테이너 병렬 시작 (순차 start() 대비 기동 시간 단축).
        // Redis는 기본 wait strategy가 없어 포트 열리기 전 연결될 수 있어 waitingFor로 방어.
        Startables.deepStart(MYSQL, REDIS, KAFKA).join();
    }

    /**
     * Redisson(RedissonConfig)은 @ServiceConnection 이 아니라 spring.data.redis.host/port 를
     * @Value 로 직접 읽으므로, Testcontainers Redis 의 동적 host/port 를 주입해줘야 한다.
     * 이게 없으면 Redisson 이 application 기본값(localhost:6379)에 붙어 로컬 환경에 의존하게 된다.
     */
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
}
