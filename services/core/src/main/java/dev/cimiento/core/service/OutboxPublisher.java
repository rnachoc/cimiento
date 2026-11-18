package dev.cimiento.core.service;

import dev.cimiento.core.domain.OutboxEvent;
import dev.cimiento.core.repository.OutboxRepository;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox events to Kafka, oldest first, keyed by project so events of one project
 * keep their order. A row is marked published only after the broker acknowledged it, so a
 * crash in between re-sends it: delivery is at least once and consumers must be idempotent
 * (every event carries the audit sequence number).
 */
@Component
@ConditionalOnProperty(name = "cimiento.outbox.enabled", havingValue = "true")
public class OutboxPublisher {

    public static final String TOPIC = "cimiento.audit";

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    public OutboxPublisher(OutboxRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    @Scheduled(fixedDelayString = "${cimiento.outbox.interval-ms:500}")
    @Transactional
    public void publish() throws Exception {
        for (OutboxEvent e : outbox.lockUnpublished(100)) {
            kafka.send(TOPIC, e.getEventKey(), e.getPayload()).get(10, TimeUnit.SECONDS);
            e.markPublished();
        }
    }

    @Configuration
    @EnableScheduling
    @ConditionalOnProperty(name = "cimiento.outbox.enabled", havingValue = "true")
    static class Topics {

        @Bean
        NewTopic auditTopic(@Value("${cimiento.kafka.partitions:3}") int partitions) {
            return TopicBuilder.name(TOPIC).partitions(partitions).replicas(1).build();
        }

        @Bean
        NewTopic intakeTopic(@Value("${cimiento.kafka.partitions:3}") int partitions) {
            return TopicBuilder.name("cimiento.intake").partitions(partitions).replicas(1).build();
        }

        @Bean
        NewTopic intakeDlq() {
            return TopicBuilder.name("cimiento.intake.dlq").partitions(1).replicas(1).build();
        }

        @Bean
        NewTopic auditDlq() {
            return TopicBuilder.name("cimiento.audit.dlq").partitions(1).replicas(1).build();
        }
    }
}
