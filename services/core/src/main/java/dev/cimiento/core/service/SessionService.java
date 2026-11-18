package dev.cimiento.core.service;

import dev.cimiento.core.domain.Person;
import dev.cimiento.core.domain.PersonSession;
import dev.cimiento.core.repository.PersonRepository;
import dev.cimiento.core.repository.PersonSessionRepository;
import dev.cimiento.core.security.Actor;
import dev.cimiento.core.security.Role;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Console sign-in for people. A person proves who they are with a personal token; the core
 * answers with a random session id that travels only in an HttpOnly cookie. Tokens and session
 * ids are stored as SHA-256 hashes and compared in constant time.
 */
@Service
public class SessionService {

    public static final Duration TTL = Duration.ofHours(8);
    private static final String UNKNOWN_HASH = "0".repeat(64);

    public record Started(String sessionId, Person person, Instant expiresAt) {
    }

    private final PersonRepository people;
    private final PersonSessionRepository sessions;
    private final AuditService audit;
    private final Clock clock = Clock.systemUTC();
    private final SecureRandom random = new SecureRandom();

    public SessionService(PersonRepository people, PersonSessionRepository sessions, AuditService audit) {
        this.people = people;
        this.sessions = sessions;
        this.audit = audit;
    }

    @Transactional
    public Started start(String username, String token) {
        Optional<Person> found = username == null ? Optional.empty() : people.findByUsername(username);
        String expected = found.filter(Person::isActive).map(Person::getTokenHash).orElse(UNKNOWN_HASH);
        String given = CanonicalJson.sha256(token == null ? "" : token);
        // compare even for unknown users so the answer takes the same time
        boolean ok = MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                given.getBytes(StandardCharsets.US_ASCII)) && found.isPresent() && found.get().isActive();
        if (!ok) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "unknown user or wrong token");
        }
        Person person = found.get();
        byte[] raw = new byte[32];
        random.nextBytes(raw);
        String id = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Instant expires = clock.instant().plus(TTL);
        sessions.deleteExpired(clock.instant());
        sessions.save(new PersonSession(CanonicalJson.sha256(id), person.getId(), expires));
        audit.append(actor(person), "SESSION_STARTED", "person", person.getId(), Map.of("role", person.getRole().name()),
                "console");
        return new Started(id, person, expires);
    }

    @Transactional(readOnly = true)
    public Optional<Person> resolve(String sessionId) {
        if (sessionId == null || sessionId.isBlank() || sessionId.length() > 100) {
            return Optional.empty();
        }
        return sessions.findById(CanonicalJson.sha256(sessionId))
                .filter(s -> s.getExpiresAt().isAfter(clock.instant()))
                .flatMap(s -> people.findById(s.getPersonId()))
                .filter(Person::isActive);
    }

    @Transactional
    public void end(String sessionId) {
        if (sessionId == null || sessionId.length() > 100) {
            return;
        }
        String hash = CanonicalJson.sha256(sessionId);
        sessions.findById(hash).ifPresent(s -> {
            sessions.delete(s);
            people.findById(s.getPersonId()).ifPresent(p -> audit.append(actor(p), "SESSION_ENDED", "person",
                    p.getId(), Map.of(), "console"));
        });
    }

    @Transactional(readOnly = true)
    public String displayName(String username) {
        return people.findByUsername(username).map(Person::getDisplayName).orElse(username);
    }

    public static Actor actor(Person p) {
        return new Actor(Role.REVIEWER, p.getUsername(), p.getRole());
    }
}
