package dev.cimiento.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.cimiento.core.domain.Person;
import dev.cimiento.core.repository.PersonRepository;
import dev.cimiento.core.rules.PersonRole;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Loads the people of the deployment from CIMIENTO_PEOPLE, a JSON array of
 * {username, displayName, role, tokenHash}. Only token hashes travel in the environment; the
 * tokens themselves stay with the people (in the demo, in the git-ignored .demo-keys).
 */
@Component
public class PersonSeeder implements ApplicationRunner {

    public record Entry(String username, String displayName, PersonRole role, String tokenHash) {
    }

    private final PersonRepository people;
    private final ObjectMapper mapper;
    private final String config;

    public PersonSeeder(PersonRepository people, ObjectMapper mapper,
                        @Value("${cimiento.people:}") String config) {
        this.people = people;
        this.mapper = mapper;
        this.config = config;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        if (config == null || config.isBlank()) {
            return;
        }
        for (Entry e : mapper.readValue(config, Entry[].class)) {
            if (e.tokenHash() == null || !e.tokenHash().matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("CIMIENTO_PEOPLE: tokenHash of " + e.username()
                        + " must be a SHA-256 in hex");
            }
            people.findByUsername(e.username()).ifPresentOrElse(
                    p -> p.update(e.displayName(), e.role(), e.tokenHash()),
                    () -> people.save(new Person(e.username(), e.displayName(), e.role(), e.tokenHash())));
        }
    }
}
