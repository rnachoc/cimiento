package dev.cimiento.core.repository;

import dev.cimiento.core.domain.RateEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RateRepository extends JpaRepository<RateEntity, String> {
}
