# FOOYRE — backend

Monolithe modulaire Spring Boot (`com.fooyre`). Lot **B1** : socle technique. **MVP-1** :
authentification (JWT d'accès + refresh token en cookie HttpOnly, ADR-04), profil `GET /api/v1/me`,
entreprise active `GET /api/v1/business`, migration `V1__identity_and_business.sql`, compte de
démonstration en profil `dev`. Aucune fonctionnalité métier (produits, ventes…) pour l'instant.

## Prérequis

| Outil | Version | Remarque |
|---|---|---|
| JDK | 21 (Temurin 21.0.13 utilisé) | contrôlé par `maven-enforcer-plugin` |
| Maven | 3.10.1 | **rien à installer** : le Maven Wrapper (`mvnw`) le télécharge et vérifie son SHA‑256 |
| Docker | Docker Desktop actif | requis par les tests (PostgreSQL 18.7 via Testcontainers) |

Versions des dépendances : gérées par le BOM Spring Boot 4.1.2 (seule exception : ArchUnit 1.5.1).
Détail et sources : `docs/architecture.md`, ADR‑12.

## Commandes

```bash
cd backend
./mvnw verify          # compilation + tous les tests (Testcontainers) + JAR
./mvnw test            # tests seuls
./mvnw -Dtest=ArchitectureTest test   # une classe de test
```

Sous Windows (`cmd.exe`) : `mvnw.cmd verify` (ou `.\mvnw.cmd verify` si l'exécution depuis le
dossier courant est désactivée par `NoDefaultCurrentDirectoryInExePath`).

## Profils et configuration

| Profil | Base de données | Usage |
|---|---|---|
| `test` | PostgreSQL 18.7 fourni par Testcontainers (`@ServiceConnection`) | tests automatisés |
| `dev` | PostgreSQL 18.7 local (`docker compose up -d` à la racine), variables `POSTGRES_*` lues dans le `.env` racine | développement (MVP-0) |
| `prod` | variables d'environnement injectées par l'hébergement | production |

Profil `dev` : le fichier `.env` de la racine (copie de `.env.example`, jamais versionné) est importé
comme fichier de propriétés (`spring.config.import` dans `application-dev.yml`), à condition de lancer
le backend depuis `backend/` ; une variable d'environnement du système reste prioritaire. Les autres
profils ne lisent aucun fichier `.env`. Aucune valeur secrète n'est versionnée.
Démarrage local complet (PostgreSQL, backend, frontend) : voir le `README.md` racine.

Sondes : `liveness` = processus vivant (indépendante de PostgreSQL) ; `readiness` = `readinessState` + base
de données (`503 DOWN` si PostgreSQL est indisponible, retour automatique à `UP`). Délai d'obtention
d'une connexion : 5 s (`spring.datasource.hikari.connection-timeout`).

Paramètres validés au démarrage (`fooyre.*`, `FooyreProperties`) : `APP_PUBLIC_BASE_URL`,
`APP_DEFAULT_TIMEZONE` (défaut `Africa/Dakar`), `APP_DEFAULT_CURRENCY` (défaut `XOF`).
En `prod`, l'application **refuse de démarrer** si un mot de passe PostgreSQL est vide ou contient
une valeur d'exemple (`change-me…`), ou si l'URL publique n'est pas en HTTPS.

Démarrage local sans PostgreSQL installé (Docker requis, aucune donnée réelle) — **commande proposée, NON VÉRIFIÉE** :
elle n'a jamais été exécutée (aucun démarrage manuel de l'application en B1/B1.1) et ne constitue pas une
procédure validée. Seuls les tests automatisés (`./mvnw verify`) ont été exécutés.

```bash
# NON VÉRIFIÉE — ne pas considérer comme une procédure validée
./mvnw spring-boot:test-run -Dspring-boot.run.profiles=test
```

## Socle transverse (`com.fooyre.shared`)

| Package | Contenu B1 |
|---|---|
| `shared.config` | `FooyreProperties` (validées), `MessagesConfiguration` (`i18n/messages_fr.properties`), garde de production |
| `shared.error` | Problem Details (RFC 9457) : `code` stable, `traceId`, `errors[{field, code, message}]` ; jamais de message technique ni de trace de pile |
| `shared.security` | Refus par défaut, sans état ; seules `/actuator/health/liveness` et `/readiness` sont publiques ; 401 `SESSION_REVOKED` / 403 `FORBIDDEN` en Problem Details |
| `shared.web` | `BigDecimal` en chaînes JSON (notation simple, échelle conservée, **aucun arrondi**) ; `traceId` généré par requête (en‑tête `X-Trace-Id`, MDC nettoyé) |
| `shared.tenant`, `shared.money`, `shared.audit`, `shared.idempotency` | `package-info.java` uniquement (lots suivants) |

Modules métier (`identity`, `business`, `gestion`, `commerce`, `finance`, `dashboard`, `reporting`,
`intelligence`, `storefront`, `platform`) : `package-info.java` uniquement. Leurs frontières sont
déjà contrôlées par ArchUnit (`ArchitectureTest`), dont le bon fonctionnement est lui‑même vérifié
sur des classes fautives (`ArchitectureRulesSelfTest`).

## Actuator

Seul `health` est exposé (`liveness`, `readiness`), sans détails ni composants. Tous les autres
endpoints sont désactivés (`management.endpoints.access.default=none`), ainsi que la page de
découverte `/actuator`.
