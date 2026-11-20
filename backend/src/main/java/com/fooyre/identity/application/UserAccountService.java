package com.fooyre.identity.application;

import com.fooyre.identity.domain.UserAccount;
import com.fooyre.identity.domain.UserAccountRepository;
import com.fooyre.shared.audit.AuditAction;
import com.fooyre.shared.audit.AuditEvent;
import com.fooyre.shared.audit.AuditOutcome;
import com.fooyre.shared.audit.AuditService;
import com.fooyre.shared.audit.AuditTarget;
import com.fooyre.shared.security.PasswordConfiguration;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.UUID;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Création de comptes. MVP-1 : utilisé par l'initialisation du compte de démonstration (profil
 * dev) ; l'inscription publique arrivera avec son propre lot. Le mot de passe est haché (bcrypt)
 * AVANT toute persistance et n'est jamais conservé ni journalisé en clair.
 */
@Service
public class UserAccountService {

    /** Longueur minimale d'un mot de passe (politique provisoire, docs/architecture.md §5.5). */
    public static final int MIN_PASSWORD_LENGTH = 12;

    private final UserAccountRepository users;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final Clock clock;

    UserAccountService(UserAccountRepository users, PasswordEncoder passwordEncoder, AuditService audit, Clock clock) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Optional<UUID> findIdByEmail(String email) {
        return users.findByEmail(UserAccount.normalizeEmail(email)).map(UserAccount::getId);
    }

    /**
     * Noms complets des comptes demandés (ex. auteurs des mouvements de stock d'une entreprise, MVP-3).
     * Aucune autre donnée du compte n'est renvoyée ; un identifiant inconnu est simplement absent.
     */
    @Transactional(readOnly = true)
    public Map<UUID, String> fullNamesOf(Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Map.of();
        }
        return users.findAllById(Set.copyOf(userIds)).stream()
                .collect(Collectors.toMap(UserAccount::getId, UserAccount::getFullName));
    }

    /**
     * Crée un compte actif.
     *
     * @throws IllegalArgumentException si le mot de passe ne respecte pas la politique ou si
     *                                  l'adresse est déjà utilisée (message sans valeur sensible)
     */
    @Transactional
    public UUID createAccount(String email, String fullName, String rawPassword) {
        validatePassword(rawPassword);
        String normalized = UserAccount.normalizeEmail(email);
        if (normalized.isEmpty() || normalized.length() > UserAccount.MAX_EMAIL_LENGTH) {
            throw new IllegalArgumentException("adresse e-mail invalide");
        }
        if (fullName == null || fullName.isBlank() || fullName.trim().length() > UserAccount.MAX_FULL_NAME_LENGTH) {
            throw new IllegalArgumentException("nom invalide");
        }
        if (users.findByEmail(normalized).isPresent()) {
            throw new IllegalArgumentException("adresse e-mail déjà utilisée");
        }
        UserAccount account = new UserAccount(normalized, passwordEncoder.encode(rawPassword), fullName, clock.instant());
        UUID id = users.save(account).getId();
        // Acteur inconnu (initialisation système en MVP-1) ; aucune donnée personnelle dans l'événement.
        audit.record(AuditEvent.of(AuditAction.ACCOUNT_CREATED, AuditOutcome.SUCCESS).target(AuditTarget.USER, id));
        return id;
    }

    static void validatePassword(String rawPassword) {
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalArgumentException("mot de passe trop court (minimum " + MIN_PASSWORD_LENGTH + " caractères)");
        }
        if (rawPassword.getBytes(StandardCharsets.UTF_8).length > PasswordConfiguration.MAX_PASSWORD_BYTES) {
            throw new IllegalArgumentException("mot de passe trop long (maximum "
                    + PasswordConfiguration.MAX_PASSWORD_BYTES + " octets)");
        }
    }
}
