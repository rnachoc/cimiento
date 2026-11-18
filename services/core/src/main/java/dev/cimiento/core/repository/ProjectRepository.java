package dev.cimiento.core.repository;

import dev.cimiento.core.domain.Project;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByCode(String code);
}
