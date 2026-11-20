package com.fooyre.identity.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Compte utilisateur (global, non rattaché à une entreprise). Le mot de passe n'est connu que
 * sous forme hachée (bcrypt) ; ce haché ne sort jamais du module identity.
 */
@Entity
@Table(name = "app_user")
public class UserAccount {

    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MAX_FULL_NAME_LENGTH = 150;
    public static final String DEFAULT_LOCALE = "fr";

    @Id
    private UUID id;

    @Column(nullable = false, length = MAX_EMAIL_LENGTH)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(name = "full_name", nullable = false, length = MAX_FULL_NAME_LENGTH)
    private String fullName;

    @Column(nullable = false, length = 10)
    private String locale;

    @Column(name = "platform_admin", nullable = false)
    private boolean platformAdmin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserAccountStatus status;

    @Column(name = "security_version", nullable = false)
    private int securityVersion;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private int version;

    protected UserAccount() {
        // JPA
    }

    public UserAccount(String email, String passwordHash, String fullName, Instant now) {
        this.id = UUID.randomUUID();
        this.email = normalizeEmail(email);
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.fullName = Objects.requireNonNull(fullName, "fullName").trim();
        this.locale = DEFAULT_LOCALE;
        this.platformAdmin = false;
        this.status = UserAccountStatus.ACTIVE;
        this.securityVersion = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Forme canonique d'une adresse e-mail (unicité insensible à la casse). */
    public static String normalizeEmail(String email) {
        return Objects.requireNonNull(email, "email").trim().toLowerCase(Locale.ROOT);
    }

    public void recordLogin(Instant now) {
        this.lastLoginAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public String getFullName() {
        return fullName;
    }

    public String getLocale() {
        return locale;
    }

    public boolean isPlatformAdmin() {
        return platformAdmin;
    }

    public UserAccountStatus getStatus() {
        return status;
    }

    public boolean isActive() {
        return status == UserAccountStatus.ACTIVE;
    }

    public int getSecurityVersion() {
        return securityVersion;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }
}
