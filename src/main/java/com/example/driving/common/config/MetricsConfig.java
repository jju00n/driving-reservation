package com.example.driving.common.config;

import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.micrometer.tagged.TaggedCircuitBreakerMetrics;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 커스텀 메트릭 바인딩 설정.
 *
 * <p>resilience4j 서킷브레이커 지표({@code resilience4j_circuitbreaker_*})를 Micrometer/Prometheus에 노출한다.
 * Spring Boot 4.0 + resilience4j-spring-boot3 2.3.0 조합에서는 서킷 메트릭이 자동 바인딩되지 않아
 * {@link TaggedCircuitBreakerMetrics} 바인더를 명시적으로 등록한다.</p>
 *
 * <p>바인더는 등록 시점의 서킷뿐 아니라 이후 생성되는 서킷도 {@code onEntryAdded} 리스너로 자동 포함한다
 * (우리 서킷은 {@code @CircuitBreaker(name="tossPayments")} 최초 호출 시 지연 생성됨).</p>
 */
@Configuration
public class MetricsConfig {

    @Bean
    public MeterBinder circuitBreakerMetrics(CircuitBreakerRegistry circuitBreakerRegistry) {
        return TaggedCircuitBreakerMetrics.ofCircuitBreakerRegistry(circuitBreakerRegistry);
    }
}
