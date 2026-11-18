package dev.cimiento.core.web;

import dev.cimiento.core.domain.Person;
import dev.cimiento.core.rules.Authority;
import dev.cimiento.core.security.Actor;
import dev.cimiento.core.security.ApiKeyFilter;
import dev.cimiento.core.service.ApiException;
import dev.cimiento.core.service.SessionService;
import dev.cimiento.core.web.ApiDtos.PersonView;
import dev.cimiento.core.web.ApiDtos.SessionRequest;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Console sign-in. The session id travels only in an HttpOnly, SameSite=Strict cookie. */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private final SessionService sessions;

    public SessionController(SessionService sessions) {
        this.sessions = sessions;
    }

    @PostMapping
    public ResponseEntity<PersonView> start(@Valid @RequestBody SessionRequest body, HttpServletRequest request) {
        SessionService.Started s = sessions.start(body.username(), body.token());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookie(s.sessionId(), SessionService.TTL, request))
                .body(view(s.person()));
    }

    @GetMapping("/me")
    public PersonView me(@RequestAttribute(ApiKeyFilter.ACTOR_ATTRIBUTE) Actor actor) {
        if (actor.person() == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "no person signed in");
        }
        return new PersonView(actor.person(), sessions.displayName(actor.person()), actor.personRole(),
                Authority.scope(actor.personRole()));
    }

    @DeleteMapping
    public ResponseEntity<Void> end(HttpServletRequest request) {
        sessions.end(ApiKeyFilter.sessionId(request));
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, request)).build();
    }

    private static PersonView view(Person p) {
        return new PersonView(p.getUsername(), p.getDisplayName(), p.getRole(), Authority.scope(p.getRole()));
    }

    private static String cookie(String value, Duration maxAge, HttpServletRequest request) {
        boolean https = request.isSecure() || "https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"));
        return ResponseCookie.from(ApiKeyFilter.SESSION_COOKIE, value).httpOnly(true).secure(https)
                .sameSite("Strict").path("/api").maxAge(maxAge).build().toString();
    }
}
