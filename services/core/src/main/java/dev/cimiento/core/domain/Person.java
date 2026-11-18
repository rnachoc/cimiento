package dev.cimiento.core.domain;

import dev.cimiento.core.rules.PersonRole;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;

/** A person who decides in the console. Only the SHA-256 of the personal token is stored. */
@Entity
public class Person {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(name = "display_name", nullable = false)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PersonRole role;

    @Column(name = "token_hash", nullable = false)
    private String tokenHash;

    @Column(nullable = false)
    private boolean active = true;

    protected Person() {
    }

    public Person(String username, String displayName, PersonRole role, String tokenHash) {
        this.username = username;
        this.displayName = displayName;
        this.role = role;
        this.tokenHash = tokenHash;
    }

    public void update(String displayName, PersonRole role, String tokenHash) {
        this.displayName = displayName;
        this.role = role;
        this.tokenHash = tokenHash;
        this.active = true;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public PersonRole getRole() {
        return role;
    }

    public String getTokenHash() {
        return tokenHash;
    }

    public boolean isActive() {
        return active;
    }
}
