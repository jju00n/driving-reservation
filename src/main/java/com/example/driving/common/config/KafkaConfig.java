package com.example.driving.common.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * 웹훅 처리 토픽 + 재시도/DLT 정책.
 *
 * <p>기존 구조(동기 처리)의 구멍은 "처리에 실패한 웹훅을 다시 볼 방법이 없다"는 것이었다.
 * 여기서 실패는 세 단계로 나뉜다.
 * <ol>
 *   <li>일시 실패(DB 순간 단절 등) → 지수 백오프 재시도로 회복</li>
 *   <li>재시도 소진 → DLT 토픽으로 격리. 원본 파티션이 뒤 메시지 때문에 막히지 않게 함</li>
 *   <li>DLT 적재분 → {@code WebhookDltListener} 가 FAILED 마킹 + 에러 로그(사람이 확인)</li>
 * </ol>
 */
@Configuration
public class KafkaConfig {

    /** DLT 토픽 접미사. Spring Kafka 관례(원본토픽 + ".DLT")를 그대로 따른다. */
    public static final String DLT_SUFFIX = ".DLT";

    @Value("${webhook.kafka.topic}")
    private String topic;

    @Value("${webhook.kafka.partitions:3}")
    private int partitions;

    @Value("${webhook.kafka.retry.attempts:3}")
    private int retryAttempts;

    @Value("${webhook.kafka.retry.initial-backoff-ms:1000}")
    private long initialBackoffMs;

    @Value("${webhook.kafka.retry.multiplier:2.0}")
    private double backoffMultiplier;

    @Bean
    public NewTopic webhookTopic() {
        return TopicBuilder.name(topic)
                .partitions(partitions)
                .replicas(1)            // 단일 브로커(로컬). 운영이면 최소 3 + min.insync.replicas=2
                .build();
    }

    /** 원본과 파티션 수를 맞춘다 — 아래 recoverer 가 실패 레코드를 같은 파티션 번호로 보내기 때문. */
    @Bean
    public NewTopic webhookDltTopic() {
        return TopicBuilder.name(topic + DLT_SUFFIX)
                .partitions(partitions)
                .replicas(1)
                .build();
    }

    /**
     * 리스너에서 예외가 나면 커밋하지 않고 재시도하다가, 소진되면 DLT 로 보낸다.
     *
     * <p>무한 재시도로 두지 않는 이유: 컨슈머는 파티션을 순서대로 읽으므로 한 건이 계속 실패하면
     * 그 뒤 메시지가 전부 밀린다(head-of-line blocking). 못 고칠 건은 빼내고 나머지를 흘려보내는 게 낫다.
     */
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                kafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + DLT_SUFFIX, record.partition()));

        // Spring 7 부터 ExponentialBackOffWithMaxRetries 가 ExponentialBackOff 로 통합(setMaxAttempts).
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(initialBackoffMs);
        backOff.setMultiplier(backoffMultiplier);
        backOff.setMaxAttempts(retryAttempts);

        return new DefaultErrorHandler(recoverer, backOff);
    }
}
