# FOOYRE — Architecture technique

Version : 0.3 (Phase 0 consolidée — 2026-11-18) · Statut : **décisions D1 à D20 intégrées** (voir `cahier-des-charges.md` §8) ; les vérifications techniques et les points encore ouverts (O2, O7 à O13) sont signalés explicitement.

Ce document décrit l'architecture retenue, les décisions structurantes (ADR condensés) et leurs justifications. Les règles opérationnelles qui en découlent sont résumées dans [`CLAUDE.md`](../CLAUDE.md).

---

## 1. Analyse des exigences — ce qui structure l'architecture

| Exigence | Conséquence architecturale |
|---|---|
| Multi‑entreprises, isolation stricte | Le tenant est une **donnée de contexte serveur**, jamais un paramètre client ; filtrage systématique et défense en profondeur (§5). |
| Données financières exactes | `BigDecimal` / `NUMERIC`, définitions normatives centralisées dans un seul service de calcul, tests exhaustifs. |
| Historique immuable (ventes, paiements, stocks) | Modèle **append‑only** pour les journaux ; annulations par écritures inverses ; pas de `DELETE` métier. |
| Paiements partiels, ventes à crédit, annulations | Machine à états explicite sur `Sale` et `Payment`, montants dérivés (payé, reste dû) recalculés côté serveur. |
| IA fiable, sans invention | Le backend calcule un **instantané analytique** ; Claude ne fait que l'interpréter, sortie structurée validée. |
| Petite équipe, produit jeune | **Monolithe modulaire** : un seul déploiement, frontières de modules claires, extraction possible plus tard. |
| Réseau mobile instable | Clés d'idempotence sur les écritures critiques, réponses légères, pagination ; **mode hors ligne partiel** où le serveur reste seul à valider (ADR‑14). |
| Rapports historiques stables (D13) | Annulations et corrections datées du jour où elles interviennent ; indicateurs calculés depuis des journaux immuables. |
| Langues nationales à venir (D16) | Textes externalisés et locale par utilisateur dès la phase 1 (ADR‑13). |
| Hébergement réversible (D19) | Technologies standard, stockage de fichiers derrière une interface, sauvegardes restaurables (ADR‑15). |
| SaaS évolutif | Formules et quotas modélisés dès le départ, sans paiement. |

---

## 2. Vue d'ensemble

```
┌──────────────────────────┐        HTTPS         ┌───────────────────────────────────────────┐
│  Navigateur (mobile/PC)  │ ───────────────────▶ │  Reverse proxy (Nginx / Caddy) — TLS        │
│  React SPA (Vite build)  │ ◀─────────────────── │  sert le frontend statique + /api → backend │
└──────────────────────────┘                      └───────────────┬───────────────────────────┘
                                                                  │
                                                  ┌───────────────▼───────────────────────────┐
                                                  │  Backend Spring Boot (monolithe modulaire) │
                                                  │  identity · business · gestion · commerce  │
                                                  │  finance · dashboard · reporting           │
                                                  │  intelligence · storefront · platform      │
                                                  └───────┬───────────────────────┬───────────┘
                                                          │ JDBC (Flyway)         │ HTTPS (SDK Java officiel)
                                                  ┌───────▼────────┐     ┌────────▼─────────┐
                                                  │  PostgreSQL    │     │  API Anthropic   │
                                                  └────────────────┘     └──────────────────┘
```

- Le frontend et l'API sont servis **sous le même domaine** (`/` et `/api`) : cookies `SameSite=Strict`, pas de CORS en production.
- Le backend est **sans état applicatif** (hors base) : il peut être répliqué plus tard. Les limites de débit en mémoire du MVP devront alors migrer vers un stockage partagé (§9).

---

## 3. Décisions structurantes (ADR condensés)

### ADR‑01 — Monolithe modulaire
**Décision** : une application Spring Boot unique, découpée en modules métier par package, avec règles de dépendance vérifiées par des tests ArchUnit.
**Pourquoi** : déploiement, transactions et cohérence financière simples (une vente, son stock et ses paiements dans une même transaction ACID). Les microservices ajouteraient de la complexité distribuée (transactions, réseau, observabilité) sans bénéfice à cette échelle.
**Conséquence** : un module n'accède jamais au repository ni aux entités internes d'un autre module ; il passe par son API publique (service applicatif / interface `*Api`).

### ADR‑02 — Multi‑tenant : base partagée, schéma partagé, colonne `business_id`
**Alternatives** : une base par client (isolation forte, coût et migrations lourds), un schéma par client (Flyway × N, pool de connexions complexe).
**Décision** : toutes les tables métier portent `business_id NOT NULL`. Isolation assurée par 4 couches (§5), dont **Row‑Level Security PostgreSQL** comme filet de sécurité.
**Pourquoi** : simple à opérer pour des milliers de petites entreprises, migrations uniques, coût d'hébergement minimal.

### ADR‑03 — Identifiants UUID générés par l'application
**Décision** : clés primaires `UUID` (pas d'entiers séquentiels exposés).
**Pourquoi** : pas d'énumération d'identifiants, fusion/migration facilitée. Les numéros lisibles (reçus `R-2026-000123`) sont des **numéros métier séquentiels par entreprise**, distincts de l'identifiant technique.

### ADR‑04 — Authentification : JWT d'accès court + refresh token en cookie HttpOnly — **validée (D2)**
**Décision** :
- **Jeton d'accès** JWT signé (HS256 au MVP, clé ≥ 256 bits fournie par variable d'environnement), durée **15 min**. Claims : `sub` (userId), `bid` (businessId actif), `mid` (membershipId), `role` (rôle dans cette entreprise), `ver` (version de sécurité de l'utilisateur), `sid` (identifiant de session = famille de refresh tokens), `jti`.
- **Refresh token** opaque (≥ 256 bits aléatoires), **stocké uniquement haché** (SHA‑256) en base, envoyé en cookie `fooyre_refresh` : `HttpOnly; SameSite=Strict; Path=/api/v1/auth; Secure` (attribut `Secure` **obligatoire en production** : l'application refuse de démarrer en profil `prod` si `AUTH_COOKIE_SECURE=false`). Durée configurable (30 jours proposés), **rotation à chaque utilisation**.
- **Détection de réutilisation** : la présentation d'un refresh token déjà remplacé révoque toute la famille (session) et est journalisée. Une courte fenêtre de tolérance (quelques secondes, configurable) évite les faux positifs quand deux onglets rafraîchissent simultanément : pendant cette fenêtre, l'ancien jeton est refusé (`401`) **sans** révoquer la famille.
- **Aucun jeton sensible dans `localStorage` ni `sessionStorage`.**
**Pourquoi** : aucun jeton longue durée lisible par JavaScript (limite l'impact d'une XSS), révocation possible, compatible avec une future application mobile.
**Alternative écartée** : sessions serveur (Spring Session JDBC), valable mais écartée pour préparer un client mobile.

**Protection CSRF** (adaptée à cette architecture) :
- Les routes métier n'acceptent que `Authorization: Bearer` ; ce jeton n'est jamais envoyé automatiquement par le navigateur ⇒ pas de risque CSRF sur ces routes. Le backend n'authentifie **jamais** une route métier à partir d'un cookie.
- Les seules routes utilisant le cookie sont `/api/v1/auth/refresh`, `/api/v1/auth/logout` et `/api/v1/auth/switch-business`. Elles sont protégées par : `SameSite=Strict` + `Path` restreint ; **vérification de l'en‑tête `Origin`** (doit correspondre à `APP_PUBLIC_BASE_URL`, sinon `403`) ; présence obligatoire d'un en‑tête personnalisé (`X-Requested-With: fooyre`) qu'un formulaire tiers ne peut pas envoyer sans pré‑requête CORS ; méthode `POST` uniquement.
- CORS : désactivé en production (même domaine) ; en développement, le serveur Vite relaie `/api` (proxy) pour rester en même origine.

**Révocation des sessions** :
| Événement | Effet |
|---|---|
| Déconnexion | Révocation de la famille de refresh tokens de la session courante, suppression du cookie |
| « Déconnecter tous les appareils » | Révocation de toutes les familles de l'utilisateur + incrément de `security_version` |
| Changement / réinitialisation du mot de passe | Révocation de toutes les sessions + incrément de `security_version` |
| Compte désactivé ou verrouillé | Idem |
| Réutilisation d'un refresh token détectée | Révocation de la famille concernée, audit |
| Appartenance révoquée ou rôle modifié | Révocation des sessions liées à cette appartenance ; les jetons d'accès en cours sont rejetés dès la requête suivante (contrôle ci‑dessous) |
| Entreprise suspendue | Accès refusé à toutes les sessions sur cette entreprise |

À **chaque requête**, le filtre de sécurité vérifie, en plus de la signature et de l'expiration : `ver` = `security_version` courant, appartenance `mid` toujours `ACTIVE` avec le même rôle, entreprise `ACTIVE`. Ce contrôle (une lecture indexée, éventuellement mise en cache quelques secondes) rend la révocation effective sans attendre l'expiration du jeton d'accès.

**Responsabilités frontend / backend** :
| Frontend | Backend |
|---|---|
| Garde le jeton d'accès **en mémoire uniquement** (variable du client API) | Émet, signe et valide les jetons ; seul juge des droits |
| Ne lit jamais le cookie de rafraîchissement (inaccessible car `HttpOnly`) | Pose, fait tourner et révoque le cookie ; stocke uniquement des hachés |
| Au chargement de la page ou sur `401`, appelle **une seule fois** `/auth/refresh` (requête unique partagée, coordination entre onglets via l'API Web Locks / `BroadcastChannel`) puis rejoue la requête | Applique la rotation, la fenêtre de tolérance et la détection de réutilisation |
| Envoie `X-Requested-With: fooyre` sur les routes `/auth/*` | Vérifie `Origin` et l'en‑tête personnalisé sur les routes à cookie |
| Après changement d'entreprise : remplace le jeton, **vide entièrement le cache TanStack Query**, recharge le contexte (`/me`) | Vérifie l'appartenance et le statut avant d'émettre le nouveau jeton |
| Masque les actions non autorisées (confort uniquement) | Contrôle chaque permission et filtre les champs sensibles |

### ADR‑05 — Rôle d'entreprise porté par l'appartenance (BusinessMembership) — **validée (D6)**
`PLATFORM_ADMIN` est un attribut global de l'utilisateur. `BUSINESS_OWNER`, `BUSINESS_MANAGER`, `EMPLOYEE` sont portés par `business_membership (user_id, business_id, role, status)`. Un utilisateur peut être membre de plusieurs entreprises, avec un rôle propre à chacune ; une seule entreprise est **active** par session.

**Changement d'entreprise active** (`POST /auth/switch-business`) : c'est la **seule** route où un `businessId` fourni par le client est accepté, et uniquement comme *demande* : le backend vérifie que l'utilisateur possède une appartenance `ACTIVE` sur une entreprise `ACTIVE`, puis émet un nouveau jeton d'accès et fait tourner le refresh token (rattaché à la nouvelle entreprise). En cas d'échec : `404` (ne révèle pas l'existence de l'entreprise). L'opération est journalisée.

### ADR‑06 — Argent : `BigDecimal` + `NUMERIC(19,2)` + devise portée par l'entreprise
- Montants : `NUMERIC(19,2)` ; quantités : `NUMERIC(19,3)` (vente au kg/litre) ; coûts unitaires : `NUMERIC(19,4)` (précision du coût moyen).
- Arrondi : `RoundingMode.HALF_UP` ; les montants sont **calculés et arrondis à l'échelle de la devise** (XOF : 0 décimale selon l'ISO 4217) puis stockés en `NUMERIC(19,2)`, ce qui laisse la possibilité d'autres devises plus tard. Règles détaillées de TVA HT/TTC et d'arrondi : `cahier-des-charges.md` §3.2 — **règles provisoires, non validées fiscalement (O2)** ; elles sont isolées dans `TaxCalculator` pour pouvoir être corrigées sans impact ailleurs.
- La devise est **stockée explicitement** sur l'entreprise (XOF initialement, une devise par entreprise) et recopiée sur chaque vente, paiement, dépense et approvisionnement.
- Un *value object* `Money` (montant + devise) et des utilitaires centralisés dans `shared.money` ; un service unique `TaxCalculator` pour la TVA ; interdiction de `float`/`double` (règle ArchUnit + revue).
- Côté JSON, les montants sont transmis en **chaînes** (`"15000.00"`) pour éviter toute perte de précision en JavaScript ; le frontend les formate sans arithmétique flottante (affichage uniquement ; les totaux font foi côté serveur).

### ADR‑07 — Journaux immuables et machines à états
- `stock_movement`, `payment`, `cash_movement`, `customer_credit_movement`, `sale_cancellation_line`, `audit_log` : **insertion seule**. Une correction = un mouvement inverse.
- `sale.status` : `DRAFT → CONFIRMED → CANCELLED` (un brouillon peut aussi être annulé, sans effet sur le stock ni la trésorerie). Une vente confirmée n'est plus modifiable dans ses lignes ; elle peut seulement être annulée (avec motif, date d'annulation, choix de réintégration par ligne et règlement du montant payé par remboursement et/ou avoir — D11). Le paiement (`payment_status`) et la livraison (`fulfillment_status`) sont des **dimensions séparées**, dérivées ou mises à jour indépendamment (l'ancien statut `COMPLETED`, ambigu, est supprimé).
- **Datation des corrections (D13)** : toute annulation, contre‑passation, remboursement ou annulation de dépense porte sa propre date métier ; les indicateurs l'imputent à cette date. Aucune écriture ne modifie la date ou le montant d'une écriture antérieure.
- Le stock courant `product_stock.quantity` est une **projection** mise à jour dans la même transaction que le mouvement, avec verrou pessimiste (`SELECT … FOR UPDATE`) sur la ligne de stock pour éviter les conditions de course. Un job de contrôle peut recalculer la projection depuis le journal.

### ADR‑08 — Valorisation au coût moyen pondéré (CMP) — **validée (D1)**
Le CMP est recalculé à chaque entrée valorisée ; il est **figé** dans `sale_line.unit_cost` à la confirmation, ce qui rend le CMV historique stable ; les corrections passent par des mouvements compensatoires et ne modifient jamais rétroactivement les coûts figés. Formules et cas particuliers (stock nul ou négatif, coût inconnu, annulation d'achat, TVA) : `cahier-des-charges.md` §3.1. Le recalcul du CMP se fait sous le même verrou que la mise à jour du stock.

### ADR‑09 — Idempotence des écritures critiques
Les opérations suivantes exigent un en‑tête `Idempotency-Key` (UUID généré par le client) : création de vente, confirmation de vente, annulation de vente, ajout de paiement (y compris le **règlement par avoir**), remboursement, création de dépense, demande de commande publique, **création de client depuis le mode hors ligne** (facultatif pour une création en ligne). Les clés sont stockées par `(business_id, key)` pendant 24 h avec l'empreinte de la requête et la réponse ; une même clé renvoie la même réponse, une clé réutilisée avec un contenu différent renvoie `409 IDEMPOTENCY_KEY_REUSED`. L'idempotence complète (et ne remplace pas) les contrôles d'état sous verrou décrits au §6.

**Unicité durable** (nécessaire au mode hors ligne, ADR‑14) : la clé est aussi conservée sur la ressource créée (`sale`, `payment`, `customer`, `expense`) avec une contrainte `UNIQUE (business_id, idempotency_key)`. Si l'enregistrement d'idempotence de 24 h a expiré (opération restée longtemps hors ligne), un rejeu ne peut donc toujours pas créer de doublon : le serveur renvoie la ressource existante si le contenu est identique, `409 IDEMPOTENCY_KEY_REUSED` sinon.

### ADR‑10 — IA derrière un port, données pré‑calculées
Voir §7.

### ADR‑11 — Base de tests réelle (Testcontainers), pas H2
Les comportements critiques (RLS, `NUMERIC`, verrous, contraintes) sont propres à PostgreSQL ; les tests d'intégration tournent contre un vrai PostgreSQL en conteneur.

### ADR‑12 — Versions des dépendances
Java 21 (imposé). Frontend : dernières versions stables de React, Vite, Tailwind CSS, TanStack Query au démarrage de la phase 1. Les versions exactes seront figées dans `pom.xml` / `package-lock.json`.

**Spring Boot : cible 4.1.2 — vérifiée partiellement le 2026-11-18, confirmation finale au démarrage de la phase 1.**

| Point | Résultat | Source consultée |
|---|---|---|
| Existence | ✅ `4.1.2` présente dans les métadonnées Maven Central (mises à jour le 2026-11-05) | `repo1.maven.org/.../spring-boot/maven-metadata.xml` |
| Stabilité | ✅ version GA (sans suffixe M/RC/SNAPSHOT), libellée « Latest » sur GitHub et « Stable » dans la documentation ; la branche 4.2 n'existe qu'en RC à cette date | GitHub releases `v4.1.2`, `docs.spring.io` |
| Java 21 | ✅ « requires at least Java 17 and is compatible with versions up to and including Java 27 » | `docs.spring.io/spring-boot/system-requirements.html` |
| Versions gérées lues dans les notes de version 4.1.2 | Spring Framework 7.0.10, Spring Security 7.1.2, Spring Data BOM 2026.0.2, Hibernate ORM 7.4.13.Final, Hibernate Validator 9.1.5.Final, pilote PostgreSQL 42.7.14, Jackson 3.1.7 (+ Jackson 2.21.7) | notes de version GitHub `v4.1.2` |
| Flyway, JUnit, Testcontainers | ⚠️ non confirmés textuellement (lecture partielle de la page des coordonnées : Flyway 12.4.0, JUnit Jupiter 6.0.3, Testcontainers 2.0.5 — **à vérifier**) | `docs.spring.io/.../dependency-versions/coordinates.html` |
| Correctif plus récent | ⚠️ la documentation mentionne des snapshots `4.1.4-SNAPSHOT` : un 4.1.3 a peut‑être été publié depuis ; vérifier le dernier correctif 4.1.x avant de figer | `docs.spring.io` |

Points de compatibilité à traiter en phase 1 (issus du guide de migration Spring Boot 4.0) :
- **Flyway** : la dépendance `spring-boot-starter-flyway` est désormais nécessaire (la seule dépendance Flyway ne suffit plus) ; vérifier si Flyway 12 exige un module PostgreSQL séparé.
- **Jackson 3** (`tools.jackson`) est la bibliothèque JSON par défaut : la sérialisation des `BigDecimal` en chaînes (ADR‑06) se configure via `JsonMapper` / `JsonMapperBuilderCustomizer`, plus `ObjectMapper`.
- **Spring Security 7** : relire son guide de migration ; les tests `@WithMockUser` nécessitent `spring-boot-starter-security-test`.
- Starters renommés (ex. `spring-boot-starter-webmvc`) ; Hibernate 7 (et non 6) pour l'option `@TenantId`.
- Compatibilité à vérifier avec Spring Boot 4.1 / JUnit 6 : ArchUnit, springdoc‑openapi, Bucket4j, bibliothèque PDF, Testcontainers 2.

**Alternative si une incompatibilité bloquante apparaît** : dernier correctif de la branche **3.5.x** (3.5.16 relevée dans les métadonnées Maven Central), compatible Java 21 et à l'écosystème plus mûr ; cette option n'a pas été vérifiée en détail et ne serait retenue qu'après validation.

### ADR‑13 — Internationalisation extensible — **validée (D16)**
**Décision** : français seul au lancement, mais aucun texte d'interface codé en dur dès la phase 1.
- **Frontend** : textes dans des catalogues par locale (`frontend/src/shared/i18n/locales/fr/*.json`) ; bibliothèque d'internationalisation à choisir et justifier en phase 1 (licence, maintenance) ; formats de dates, nombres et montants via `Intl` avec la locale active (`fr-SN` au lancement). Les messages d'erreur sont produits par le frontend à partir des `code` stables de l'API.
- **Backend** : textes destinés à l'utilisateur (reçus, PDF, emails, `title`/`detail` des erreurs) dans des fichiers `messages_<locale>.properties` (`MessageSource`), en français au lancement.
- **Préférence** : `app_user.locale` (défaut `fr`), `business.default_locale` (défaut `fr`) ; seules les locales déclarées comme prises en charge sont acceptées.
- **IA** : la langue de réponse est un paramètre de l'invite ; français uniquement tant que la qualité dans une autre langue n'a pas été évaluée.
- **Non vérifié** : la disponibilité de données de format régional (CLDR) pour le wolof, le pulaar ou le sérère dans les navigateurs cibles, et la qualité des réponses de Claude dans ces langues. À évaluer avant toute ouverture ; à défaut, repli sur les formats `fr-SN`.
- **Voix** : aucune architecture spécifique n'est engagée ; une évaluation technique (reconnaissance et synthèse vocales dans les langues nationales, coût, confidentialité) sera menée avant toute décision.

### ADR‑14 — Mode hors ligne partiel — **validée (D17)**, modalités ouvertes (O10)
**Principe** : le client peut **préparer et mettre en attente**, jamais **valider**. Le serveur reste l'unique source de vérité.

**Composants** :
- Application installable (PWA) avec *service worker* : mise en cache de l'application (fichiers statiques) ; les réponses de l'API ne sont **pas** mises en cache par le *service worker* de façon générique.
- **Instantané de consultation** (`GET /offline/snapshot`) : sous‑ensemble minimal, filtré par permissions, horodaté (`syncedAt`) — catalogue sans coûts, niveaux de stock indicatifs, clients actifs (nom, téléphone), ventes récentes. **Jamais** de coûts, marges, indicateurs financiers, paramètres sensibles, journaux d'audit ni données d'IA, quel que soit le rôle.
- **File d'opérations en attente** (IndexedDB) : chaque opération porte un identifiant local, sa clé d'idempotence (UUID générée **à la saisie** et conservée à chaque tentative), l'`userId`, le `businessId` et le `membershipId` de création, l'horodatage déclaré par l'appareil et l'état local : `PENDING_SYNC`, `SYNCING`, `ACCEPTED`, `REJECTED`, `CONFLICT`, `EXPIRED`. Aucun état local ne signifie « confirmé ».

**Règles de synchronisation** :
1. Les opérations sont rejouées **dans l'ordre de saisie** via les **routes normales** de l'API (pas de route de synchronisation qui contournerait les contrôles), avec leur `Idempotency-Key`. Une opération dépendante (vente référençant un client créé hors ligne) attend la réponse de l'opération parente et utilise l'identifiant serveur obtenu ; si la parente est rejetée, la dépendante passe en `CONFLICT`.
2. Le serveur applique **tous** les contrôles comme pour une saisie en ligne, avec l'état **courant** : authentification, appartenance et rôle actuels, entreprise active, permissions, identifiants résolus dans l'entreprise active, prix catalogue et droit de modifier un prix, limite de remise, stock sous verrou (D5), règles de date (O9).
3. **Contrôle de cohérence du tenant** : chaque requête rejouée envoie `X-Expected-Business-Id` (entreprise de création). Le serveur le **compare** au `bid` du jeton et refuse en cas d'écart (`409 BUSINESS_CONTEXT_MISMATCH`). Cet en‑tête ne sert **jamais** à choisir le tenant (§5.1) ; le client ne rejoue une opération que si l'entreprise active est celle de création.
4. **Doublons** : impossibles grâce à l'idempotence et à l'unicité durable des clés (ADR‑09) ; un rejeu après une coupure en cours de requête renvoie le résultat déjà enregistré.
5. **Conflits** : refus métier (prix modifié, stock insuffisant, client archivé, droit retiré…) → l'opération passe en `CONFLICT` avec le `code` d'erreur ; elle est présentée à un utilisateur autorisé qui la corrige et la renvoie (nouvelle clé) ou l'abandonne. Aucune résolution automatique de type « la dernière écriture gagne » pour une opération financière. Politique détaillée : O10.
6. **Résultat** : seule la réponse du serveur attribue le numéro de reçu, déduit le stock et compte la vente dans les indicateurs. L'horodatage de l'appareil est conservé à titre informatif (`client_created_at`) et n'est pas fiable.

**Stock** : le stock affiché hors ligne est celui de la dernière synchronisation, présenté comme indicatif ; aucune déduction locale ne fait foi.

**Protection des données locales** :
- Cloisonnement par `(userId, businessId)` : une base IndexedDB par couple ; changer d'entreprise rend inaccessible la base de l'autre entreprise dans l'interface, sans rien rejouer sur la nouvelle entreprise.
- **Aucun jeton** (accès ou rafraîchissement) n'est stocké localement (ADR‑04 inchangé) : au retour du réseau, la session est rétablie par `/auth/refresh` ; à défaut, l'utilisateur se reconnecte avant toute synchronisation.
- Expiration : l'instantané n'est plus affiché au‑delà d'une durée maximale ; les opérations en attente au‑delà d'une durée maximale ne sont plus envoyées automatiquement et exigent une revue (durées : O10).
- Effacement : à la déconnexion (après avertissement s'il reste des opérations en attente), sur réponse `401 SESSION_REVOKED` persistante ou `404` d'appartenance, et sur demande.
- Chiffrement local envisagé (WebCrypto) ; ses limites sont assumées et documentées : un appareil déverrouillé et une session de navigateur ouverte restent exposés. Le verrouillage local par code relève d'O10.

### ADR‑15 — Hébergement portable et sauvegardes — **validée (D19)**, fournisseur ouvert (O12)
- **Technologies standard** : conteneurs Docker, PostgreSQL standard (aucune extension ni fonction propre à un fournisseur sans décision), reverse proxy standard, configuration par variables d'environnement.
- **Fichiers** (images produits, justificatifs de dépenses) : interface `FileStorage` dans `shared`, implémentation compatible avec l'API S3 (offerte par de nombreux fournisseurs) ou système de fichiers en développement ; la base ne stocke que des **clés d'objet**, jamais d'URL propre à un fournisseur.
- **Sauvegardes** : sauvegardes logiques régulières (`pg_dump`) et, si l'offre retenue le permet, sauvegarde continue (archivage WAL / restauration à un instant donné) ; chiffrées ; une copie hors du fournisseur principal est recommandée pour la réversibilité ; **restauration testée** périodiquement, procédure documentée. Fréquence et rétention à fixer avec le choix d'hébergement (O12) et les validations juridiques.
- **Réversibilité** : procédure documentée de migration vers un autre hébergeur (export complet de la base et des fichiers, reconfiguration) ; permet une migration future vers une infrastructure au Sénégal ou dans une autre juridiction appropriée.
- **Localisation** : les données financières et commerciales restent dans cette infrastructure (D18) ; seule l'API Anthropic reçoit, après validation juridique, les instantanés d'analyse minimisés.

---

## 4. Modules

### 4.1 Backend (`com.fooyre`)

| Module | Responsabilité | Dépend de |
|---|---|---|
| `shared` | Transverse : configuration, sécurité (filtres, JWT), contexte tenant, erreurs (Problem Details), `Money`, audit, idempotence, pagination, horloge, messages localisés (`MessageSource`), stockage de fichiers (`FileStorage`) | — |
| `identity` | Utilisateurs, authentification, mots de passe, jetons de rafraîchissement, invitations | shared |
| `business` | Entreprises (tenants), appartenances et rôles, invitations, paramètres (devise, TVA et mode HT/TTC, stock négatif, numérotation, consentement IA, limite de remise des employés, locale par défaut), approbation des accès support | shared, identity |
| `gestion` | Catégories, produits, stocks et mouvements, approvisionnements, clients, fournisseurs | shared, business |
| `commerce` | Ventes, lignes, contrôle des remises, paiements, annulations (réintégration par ligne), remboursements, **avoirs clients**, reçus, instantané hors ligne | shared, business, gestion (API stock/produits) |
| `finance` | Dépenses, comptes de trésorerie, mouvements de trésorerie, **service d'indicateurs financiers** (seule source des calculs §3 du cahier des charges) | shared, business, commerce (lecture) |
| `dashboard` | Agrégation pour l'écran d'accueil, alertes | finance, gestion, commerce (lecture) |
| `reporting` | Génération PDF / CSV | finance, gestion, commerce (lecture) |
| `intelligence` | Construction de l'instantané analytique, appel Claude, quotas, suivi des coûts, historique des analyses | finance, dashboard, platform (quotas) |
| `storefront` | Catalogue public, demandes de commande | gestion (lecture), commerce (conversion) |
| `platform` | Administration plateforme, formules, quotas et dérogations, accès support encadré | identity, business |

**Structure interne d'un module** :
```
<module>/
  api/             contrôleurs REST + DTO (records) + mappers
  application/     services applicatifs (cas d'usage, transactions), interface publique du module
  domain/          entités JPA, value objects, règles métier, repositories (interfaces Spring Data)
  infrastructure/  adaptateurs techniques (client IA, PDF, etc.) si nécessaire
```
Règles : les contrôleurs n'appellent que `application` ; les entités ne sortent jamais d'un contrôleur (DTO obligatoires) ; la logique métier vit dans `domain`/`application`, pas dans les contrôleurs.

### 4.2 Frontend (`frontend/src`)

| Dossier | Contenu |
|---|---|
| `app/` | Point d'entrée, routeur, `QueryClientProvider`, garde d'authentification, mise en page |
| `features/auth` | Connexion, inscription, invitation, choix de l'entreprise active |
| `features/products`, `categories`, `inventory`, `customers`, `suppliers` | GESTION |
| `features/sales`, `payments`, `receipts` | COMMERCE |
| `features/expenses`, `treasury` | FINANCE |
| `features/dashboard` | Tableau de bord (Recharts) |
| `features/reports` | Rapports |
| `features/assistant` | FOOYRE INTELLIGENCE |
| `features/storefront` | Gestion du catalogue public + pages publiques |
| `features/settings`, `features/team` | Paramètres, utilisateurs |
| `features/platform-admin` | Back‑office plateforme |
| `shared/api` | Instance Axios, intercepteurs (jeton, rafraîchissement, erreurs Problem Details), clés de requêtes |
| `shared/i18n` | Configuration de l'internationalisation, catalogues `locales/fr/` (ADR‑13) |
| `shared/offline` | Service worker, instantané local, file d'opérations en attente, synchronisation (ADR‑14 ; phase dédiée) |
| `shared/ui` | Composants UI réutilisables (Tailwind, icônes Lucide) |
| `shared/lib` | Formatage monétaire/dates (`Intl`, `fr-SN`, XOF), utilitaires |
| `shared/hooks`, `shared/types` | Hooks et types partagés |

Chaque *feature* contient : `api.ts` (fonctions Axios + hooks TanStack Query), `components/`, `pages/`, `types.ts`. Les permissions affichées côté client sont **cosmétiques** : le backend reste seul juge.

---

## 5. Sécurité et isolation multi‑tenant

### 5.1 Principe
Le `businessId` courant provient **exclusivement** du jeton authentifié (claim `bid`), validé contre une appartenance active. Il n'est jamais lu depuis l'URL, le corps ou un en‑tête fourni par le client pour les routes privées. Deux usages encadrés d'un identifiant d'entreprise fourni par le client, qui **ne choisissent jamais** le tenant : la demande de `POST /auth/switch-business` (ADR‑05), vérifiée contre les appartenances, et l'en‑tête `X-Expected-Business-Id` des opérations synchronisées (ADR‑14), seulement **comparé** au `bid` du jeton pour refuser une opération créée dans une autre entreprise.

### 5.2 Défense en profondeur (4 couches)
1. **Contexte tenant** : un filtre Spring Security place `TenantContext` (businessId, membershipId, userId, rôle, permissions) dans le contexte de la requête après validation du JWT et vérification que l'appartenance est active et l'entreprise non suspendue (ADR‑04). Un `businessId` présent dans un corps, une URL ou un en‑tête n'est **jamais** considéré comme fiable.
2. **Couche d'accès aux données** : toutes les entités métier héritent d'un `TenantScopedEntity` ; le `business_id` est renseigné automatiquement à l'insertion depuis le contexte (jamais depuis un DTO). Toutes les lectures **et écritures** par identifiant passent par des méthodes `findByIdAndBusinessId(...)`, y compris pour les identifiants référencés dans un corps de requête (ex. `customerId`, `productId`, `treasuryAccountId` d'une vente). Option envisagée : discriminant `@TenantId` d'Hibernate (version 7 avec Spring Boot 4.1) pour automatiser le filtrage — à confirmer en phase 2 après prototype.
3. **Base de données** :
   - Clés étrangères composites `(business_id, id)` entre tables métier : impossible de lier une vente d'une entreprise A à un client de l'entreprise B, même en cas de bug applicatif.
   - **Row‑Level Security PostgreSQL** sur les tables métier, avec `SET LOCAL app.business_id = …` en début de transaction ; l'application se connecte avec un rôle **non propriétaire** des tables (RLS non contournable). Les migrations Flyway utilisent un rôle propriétaire distinct.
4. **Tests anti‑IDOR** : une suite d'isolation obligatoire vérifie pour chaque endpoint qu'un utilisateur de l'entreprise A reçoit `404` sur une ressource de B (lecture, modification, suppression, actions), qu'aucune liste ne mélange les tenants, qu'un identifiant de B glissé dans le corps d'une requête de A (ex. `customerId` d'une vente) est rejeté, et qu'un jeton d'une appartenance révoquée est refusé.

Une ressource d'un autre tenant renvoie **404** (et non 403) pour ne pas révéler son existence.

### 5.3 Autorisation — matrice des permissions techniques (D3)
- Contrôle sur **chaque** endpoint par permission nommée (`@PreAuthorize("hasAuthority('SALE_CANCEL')")`) ; le mapping rôle → permissions est centralisé (`shared.security.RolePermissions`) et couvert par un test qui fige cette matrice. Rien n'est accessible par défaut.
- Les DTO de lecture sont filtrés selon les droits (ex. `unitCost`, `averageCost`, marges **absents** pour `EMPLOYEE`, pas seulement masqués à l'écran).
- Le frontend n'utilise les permissions (renvoyées par `/me`) que pour l'affichage.

| Permission | OWNER | MANAGER | EMPLOYEE |
|---|:-:|:-:|:-:|
| `BUSINESS_READ` (profil + informations nécessaires à la saisie d'une vente : devise, TVA activée, mode HT/TTC) | ✅ | ✅ | ✅ |
| `BUSINESS_PROFILE_UPDATE` (nom, contacts, logo, langue par défaut) | ✅ | ✅ | ❌ |
| `BUSINESS_SETTINGS_READ` (paramètres financiers et sensibles — D20) | ✅ | ✅ | ❌ |
| `BUSINESS_SETTINGS_UPDATE` (TVA, HT/TTC, taux, stock négatif, devise — D20) | ✅ | ❌ | ❌ |
| `SALES_POLICY_MANAGE` (limite de remise des employés — D14) | ✅ | ✅ | ❌ |
| `MEMBER_MANAGE` (invitations, rôles, révocations ; réservé au propriétaire pour le moment, toute délégation au gestionnaire exigera une décision ultérieure) | ✅ | ❌ | ❌ |
| `SUPPORT_ACCESS_GRANT` (approuver, refuser, révoquer — D12), `AI_CONSENT_MANAGE` (D15) | ✅ | ❌ | ❌ |
| `STOREFRONT_MANAGE` | ✅ | ✅ | ❌ |
| `CATALOG_READ` (produits, catégories, sans coûts) | ✅ | ✅ | ✅ |
| `CATALOG_WRITE` | ✅ | ✅ | ❌ |
| `COST_READ` (coûts, CMP, marges, valeur du stock) | ✅ | ✅ | ❌ |
| `STOCK_READ` (quantités, alertes) | ✅ | ✅ | ✅ |
| `STOCK_ADJUST` | ✅ | ✅ | ❌ |
| `SUPPLIER_MANAGE`, `PURCHASE_MANAGE` | ✅ | ✅ | ❌ |
| `CUSTOMER_READ` (fiche client, historique des ventes sans coûts), `CUSTOMER_WRITE` | ✅ | ✅ | ✅ |
| `CUSTOMER_BALANCE_READ` (soldes dus des clients, avoirs clients et leurs mouvements, en consultation) | ✅ | ✅ | ❌ |
| `CUSTOMER_ARCHIVE` | ✅ | ✅ | ❌ |
| `SALE_CREATE` (créer, confirmer), `PAYMENT_CREATE` | ✅ | ✅ | ✅ |
| `SALE_READ_ALL` (ventes de l'entreprise ; champs sensibles filtrés pour E) | ✅ | ✅ | ✅ (D14) |
| `SALE_PRICE_OVERRIDE` (prix unitaire différent du catalogue) | ✅ | ✅ | ❌ |
| `SALE_DISCOUNT_UNLIMITED` (remise sans plafond) | ✅ | ✅ | ❌ |
| `SALE_DISCOUNT_LIMITED` (remise dans la limite `employee_max_discount_rate`, contrôlée par le serveur, journalisée — D14) | — | — | ✅ |
| `SALE_CANCEL` (avec remboursement et/ou avoir, choix de réintégration — D11), `PAYMENT_VOID`, `REFUND_CREATE` | ✅ | ✅ | ❌ |
| `CUSTOMER_CREDIT_USE` (régler une vente avec un avoir) | ✅ | ✅ | ❌ (provisoire, O7) |

Les avoirs sont **créés** uniquement lors d'une annulation (`SALE_CANCEL`), **utilisés** avec `CUSTOMER_CREDIT_USE` et **remboursés** selon les modalités encore ouvertes d'O7 (`REFUND_CREATE` le cas échéant), conformément aux routes de `api-specification.md` §2.4. L'employé ne consulte ni les avoirs ni les soldes dus des clients.
| `ORDER_REQUEST_MANAGE` | ✅ | ✅ | ✅ |
| `EXPENSE_MANAGE`, `TREASURY_MANAGE` | ✅ | ✅ | ❌ |
| `TREASURY_OWNER_MOVEMENT` (apports, retraits, ajustements) | ✅ | ❌ | ❌ |
| `FINANCE_READ`, `REPORT_EXPORT`, `DASHBOARD_FULL` | ✅ | ✅ | ❌ |
| `DASHBOARD_OWN_DAY` (récapitulatif de ses ventes du jour) | ✅ | ✅ | ✅ |
| `AI_USE` | ✅ | ✅ | ❌ |

`PLATFORM_ADMIN` dispose uniquement des permissions `PLATFORM_*` (`PLATFORM_BUSINESS_MANAGE`, `PLATFORM_PLAN_MANAGE`, `PLATFORM_AUDIT_READ`, `PLATFORM_AI_USAGE_READ`) et d'**aucune** permission métier.

### 5.4 Accès support encadré (D7, D12)
- Par défaut, aucun accès du `PLATFORM_ADMIN` aux données métier ; les routes `/platform/*` n'exposent que des métadonnées (nom, statut, formule, compteurs, consommation IA).
- Un accès exceptionnel suppose un **octroi d'accès support** (`support_access_grant`) :
  1. **demande** de l'administrateur : justification obligatoire, périmètre demandé (domaines : catalogue, stock, ventes, clients, finance…), durée demandée ;
  2. **approbation explicite du propriétaire** de l'entreprise (permission `SUPPORT_ACCESS_GRANT`), qui peut réduire le périmètre et la durée ; aucune autre voie d'autorisation (ni second administrateur, ni approbation implicite, ni délai valant accord) ;
  3. durée plafonnée par `SUPPORT_ACCESS_MAX_DURATION_HOURS` ; **lecture seule par défaut** — l'écriture n'est possible que si le propriétaire l'a explicitement accordée ;
  4. **révocable** à tout moment par le propriétaire ; expiration automatique.
- L'administrateur obtient alors un jeton d'accès distinct, de courte durée, portant `support_grant_id` ; le filtre de sécurité vérifie à chaque requête que l'octroi est actif, non expiré, et que la route appelée appartient au périmètre et au mode accordés (sinon `403`).
- **Chaque requête** effectuée sous accès support est inscrite au journal d'audit (action, ressource, horodatage), consultable par la propriétaire.
- Aucun accès silencieux, permanent ou contournant ce mécanisme (pas de « super‑utilisateur » applicatif, pas de désactivation de la RLS pour l'administration).

### 5.5 Autres mesures
- Mots de passe : `Argon2PasswordEncoder` (ou bcrypt coût ≥ 12), politique de longueur minimale, verrouillage progressif après échecs.
- Limitation de débit : connexion, inscription, endpoints publics du catalogue, IA (Bucket4j en mémoire au MVP).
- Validation systématique des entrées (`jakarta.validation`), tailles maximales de chaînes et de listes.
- En‑têtes de sécurité : CSP stricte, `X-Content-Type-Options`, `Referrer-Policy`, HSTS en production.
- CSRF : voir ADR‑04 (Bearer sur les routes métier ; `SameSite=Strict`, `Path` restreint, contrôle `Origin` et en‑tête personnalisé sur les routes à cookie).
- CSV : neutralisation de l'injection de formules (préfixe `'` pour les cellules commençant par `= + - @`).
- Erreurs : jamais de trace de pile ni de détail SQL dans les réponses ; identifiant de corrélation pour le support.
- Journal d'audit : connexion, changement d'entreprise active, révocation de session, détection de réutilisation de jeton, changement de rôle, modification des paramètres sensibles et de la limite de remise, remise appliquée par un employé et tentative refusée, annulation de vente, remboursement, émission / utilisation / contre‑passation d'avoir, ajustement de stock, demande / approbation / refus / usage / révocation d'accès support, consentement IA et sa révocation, opération hors ligne rejetée pour incohérence de tenant.
- Dépendances : analyse de vulnérabilités (Dependabot / OWASP Dependency‑Check / `npm audit`) en CI.

---

## 6. Données et transactions

- Une transaction par cas d'usage (`@Transactional` sur le service applicatif).
- Confirmation de vente, dans **une** transaction :
  1. verrou sur la vente (`SELECT … FOR UPDATE`) et contrôle de son statut (`DRAFT` requis : une seconde confirmation concurrente échoue avec `409`, pas de double déduction) ;
  2. verrouillage des lignes `product_stock` concernées, dans un **ordre déterministe par `product_id`** (évite les interblocages) ;
  3. contrôle de disponibilité : si l'entreprise n'autorise pas le stock négatif (D5, défaut), toute ligne dépassant le stock disponible fait échouer la vente entière (`409 INSUFFICIENT_STOCK`), sans effet partiel ;
  4. contrôle des prix et des remises : prix différent du catalogue refusé sans `SALE_PRICE_OVERRIDE` ; pour un employé, remise totale comparée à `business.employee_max_discount_rate` relue sous la transaction (refus `422 DISCOUNT_LIMIT_EXCEEDED`, tentative journalisée ; remise acceptée journalisée — D14). Ce contrôle est effectué **côté serveur à deux étapes** : à l'**enregistrement du brouillon** (`POST /sales`, `PUT /sales/{id}`) et de nouveau ici, à la **confirmation**, la limite pouvant avoir changé entre‑temps ;
  5. figer prix, remises, mode HT/TTC, taux de TVA et CMP sur les lignes ; calcul des totaux par `TaxCalculator` ;
  6. mouvements de stock `SALE_OUT` et mise à jour de la projection ;
  7. numéro de reçu ; paiement initial éventuel ; mouvement de trésorerie ; audit.
  Toute erreur annule l'ensemble (atomicité).
- Paiements, remboursements, contre‑passations et annulation : verrou sur la vente avant de recalculer `amount_paid` ; un paiement dépassant le reste dû est refusé (`422 PAYMENT_EXCEEDS_BALANCE`) ; deux paiements concurrents ne peuvent donc pas dépasser le total.
- Règlement par avoir : verrou sur la vente puis sur l'avoir (`customer_credit … FOR UPDATE`, ordre fixe vente → avoir) ; l'avoir doit appartenir au client de la vente et disposer d'un solde suffisant (`422 CREDIT_INSUFFICIENT`) ; création d'un `payment` de méthode `CUSTOMER_CREDIT` et d'un mouvement d'avoir `USE`, **sans** mouvement de trésorerie.
- Annulation d'une vente confirmée (D11, D13), dans une seule transaction :
  1. verrou sur la vente, contrôle du statut (`CONFIRMED` requis, sinon `409 SALE_ALREADY_CANCELLED`) ;
  2. pour chaque ligne suivie en stock, choix `restock` (revendable ou non) enregistré dans `sale_cancellation_line` ; verrou des stocks des lignes réintégrées (ordre par `product_id`), mouvements `SALE_CANCEL_IN` au coût figé et recalcul du CMP ; aucune écriture de stock pour les lignes non revendables ;
  3. règlement du montant payé : remboursements (`payment` de type `REFUND` + `cash_movement` sortant) et/ou conversion en avoir (`payment` de type `CREDIT_CONVERSION`, création du `customer_credit` et de son mouvement `ISSUE`, **aucun** mouvement de trésorerie) ; règle provisoire : remboursements + avoir = montant payé (O7), un avoir exige un client identifié ;
  4. `cancellation_date` = date du jour dans le fuseau de l'entreprise (base de l'imputation en période d'annulation) ; audit.
- Approvisionnement reçu : verrou sur les stocks concernés, mouvements `PURCHASE_IN`, recalcul du CMP ; annulation d'un approvisionnement reçu par mouvement compensatoire `PURCHASE_CANCEL_OUT` (soumis au contrôle de stock négatif).
- Numérotation séquentielle par entreprise via une table `business_sequence` verrouillée (`FOR UPDATE`) — sans trous en cas de succès ; les annulations conservent leur numéro.
- Verrouillage optimiste (`@Version`) sur les entités éditables (produit, client, dépense).
- Horodatage `TIMESTAMPTZ` en UTC ; dates métier (`sale_date`, `expense_date`) en `DATE` interprétées dans le fuseau de l'entreprise (`Africa/Dakar` par défaut). Une `Clock` injectable rend les tests déterministes.
- Les indicateurs sont calculés par requêtes SQL agrégées dans `finance`, selon les définitions du `cahier-des-charges.md` §3 : ventes brutes par `sale_date`, annulations par `cancellation_date`, encaissements par date des mouvements de trésorerie (D13). Une table de pré‑agrégats (par jour) pourra être ajoutée si les performances l'exigent ; elle ne serait alimentée qu'en ajout, jamais recalculée rétroactivement.
- Saisie antidatée (date de vente ou de dépense antérieure) : seule source possible de modification d'une période passée ; règles à arbitrer (O9). En attendant, la date saisie et la date réelle d'enregistrement sont toutes deux conservées et toute saisie antidatée est journalisée.

---

## 7. Intégration de l'IA (FOOYRE INTELLIGENCE)

### 7.1 Principe : « le backend calcule, Claude explique »
```
Utilisateur ──▶ POST /api/v1/intelligence/analyses {type, période}
   │
   ├─ 0. IA activée globalement (AI_ENABLED, + AI_PRODUCTION_APPROVED en prod) et pour l'entreprise (opt‑in) ?
   ├─ 1. Contrôle des droits + quota et plafond de coût de l'entreprise (refus 429 si dépassé)
   ├─ 2. AnalysisSnapshotBuilder : agrège les indicateurs via FinancialMetricsService,
   │     stocks, top/flop produits, tendances N vs N‑1, marqueurs de complétude
   │     (« 3 jours de données seulement », « coûts d'achat manquants pour 12 produits »)
   ├─ 3. Pseudonymisation : aucun nom/téléphone de client, identifiants remplacés par des alias
   ├─ 4. AiClient (port) ──▶ AnthropicAiClient (SDK Java officiel) | FakeAiClient (tests)
   ├─ 5. Validation de la réponse structurée (schéma JSON), vérification que les chiffres cités
   │     existent dans l'instantané ; sinon rejet / signalement
   └─ 6. Persistance : ai_analysis (entrée, sortie), ai_usage (jetons, coût estimé, durée, statut)
```

### 7.2 Décisions
- **Port/adaptateur** : interface `AiClient` dans `intelligence.application` ; implémentation `AnthropicAiClient` dans `intelligence.infrastructure` utilisant le SDK Java officiel (`com.anthropic:anthropic-java`) ; `FakeAiClient` déterministe pour les tests et le développement sans clé. Les modules métier ne connaissent que le service d'analyse : **changer de modèle ou de fournisseur ne modifie aucun module métier**.
- **Modèle par défaut : Claude Opus 5.5 (décision D9)**, configurable par `ANTHROPIC_MODEL`.
  Vérification effectuée le 2026-11-18 sur la documentation officielle (`platform.claude.com/docs/en/about-claude/models/overview`, page du modèle et page des tarifs) :
  | Élément | Valeur relevée |
  |---|---|
  | Identifiant API | `claude-opus-5-5` |
  | Statut | « Active (latest) », publié le 22 septembre 2026, retrait pas avant le 22 septembre 2027 |
  | Contexte / sortie maximale | 1 M jetons / 128 K jetons |
  | Tarifs (USD par million de jetons) | entrée 4 · sortie 20 · lecture de cache 0,20 · écriture de cache 5 min 5 |
  | SDK Java | `com.anthropic:anthropic-java` 3.10.0 selon le README du dépôt officiel (Java 8+) ; dernière version sur Maven Central **non vérifiée** |
  Ces informations évoluent : **les revérifier au démarrage de la phase 9** avant de coder l'identifiant, les tarifs (utilisés pour l'estimation des coûts) ou la version du SDK. Les tarifs ne sont jamais codés en dur : ils sont lus depuis la configuration.
- **Particularités du modèle à prendre en compte à l'implémentation** (relevées dans la documentation de référence, à confirmer en phase 9) : la réflexion (*thinking*) ne peut pas être désactivée sur Opus 5.5, la profondeur se règle par le paramètre `effort` (défaut `medium`, rendu configurable via `ANTHROPIC_EFFORT`) ; le choix forcé d'outil n'est pas supporté (d'où l'usage des sorties structurées) ; le motif d'arrêt `refusal` doit être traité ; `max_tokens` doit laisser de la marge à la réflexion (16 000 proposés).
- **Sortie structurée** : réponse contrainte par schéma JSON (`output_config.format`) : `summary`, `insights[]` (avec références aux indicateurs de l'instantané), `risks[]`, `recommendations[]`, `dataLimitations[]`.
- **Invite système** : rôle de conseiller, utiliser uniquement les données fournies, ne jamais inventer de chiffre, distinguer marge brute et résultat net, signaler les données insuffisantes, répondre en français simple. L'invite système stable est mise en cache (prompt caching) pour réduire les coûts.
- **Garde‑fous** : délai d'attente configurable (`ANTHROPIC_TIMEOUT_SECONDS`), nombre de nouvelles tentatives limité (`ANTHROPIC_MAX_RETRIES`, uniquement sur 429/5xx/réseau), `max_tokens` borné, gestion explicite des motifs d'arrêt (`refusal`, `max_tokens`) et des erreurs typées du SDK ; message utilisateur clair en cas d'indisponibilité, sans bloquer le reste de l'application.
- **Quotas et plafonds par entreprise** : quota mensuel d'analyses et plafond mensuel de coût définis par la formule, avec dérogation possible par entreprise ; vérifiés **avant** chaque appel. Table `ai_usage` (jetons d'entrée/sortie/cache, coût estimé en USD via une grille de prix configurable, latence), agrégée par entreprise et par mois. Plafond global mensuel de dépense (`AI_GLOBAL_MONTHLY_BUDGET_USD`) avec coupure automatique.
- **Interrupteur global** `AI_ENABLED=false` pour désactiver totalement l'IA ; en profil `prod`, l'IA reste désactivée tant que `AI_PRODUCTION_APPROVED` n'est pas à `true` (à positionner uniquement après les validations juridiques du `cahier-des-charges.md` §5.1).
- **Activation par entreprise (D15)** : désactivée par défaut ; activée uniquement par le consentement explicite du propriétaire (`AI_CONSENT_MANAGE`), horodaté, avec auteur et version des conditions acceptées ; **révocable à tout moment** — la révocation prend effet immédiatement (étape 0 ci‑dessus) et est journalisée. Le sort des analyses déjà conservées après révocation relève des durées de conservation à valider juridiquement (`cahier-des-charges.md` §5.1).
- **Confidentialité et localisation (D18)** : les données restent dans l'infrastructure FOOYRE ; aucun accès direct de l'IA à la base ; seules les données strictement nécessaires à l'analyse demandée sont transmises, agrégées et pseudonymisées ; aucun nom ni téléphone de client dans les analyses ordinaires (aucune analyse utilisant des données personnelles n'est prévue) ; l'instantané envoyé est conservé (`ai_analysis.input_snapshot`) pour pouvoir prouver ce qui a été transmis. **Aucun transfert vers Anthropic depuis la production** tant que les validations juridiques et réglementaires (CDP, transferts internationaux) ne sont pas obtenues : c'est le rôle de `AI_PRODUCTION_APPROVED`.
- **Langue** : réponses en français (D16) ; toute autre langue suppose une évaluation préalable de la qualité.
- **Pas de streaming au MVP** : analyses courtes, réponse complète ; streaming envisageable pour un mode conversationnel ultérieur.
- **Pas d'agent / d'outils au MVP** : un appel unique par analyse suffit. Un mode « questions libres » avec outils de lecture (requêtes pré‑définies côté backend) pourra être étudié ensuite.

---

## 8. Commerce digital (catalogue public)

- URL : `https://<domaine>/c/{slug}` ; API : `/api/v1/public/storefronts/{slug}`.
- Seuls les produits marqués `publicly_visible` et l'entreprise active sont exposés ; aucune donnée de coût, de stock exact (option : « disponible / épuisé ») ni d'identifiant interne sensible.
- Demandes de commande : validation stricte, limitation de débit par IP et par catalogue, champ *honeypot* anti‑robot, taille maximale ; CAPTCHA à ajouter si abus constatés.
- La demande ne réserve pas de stock ; l'entreprise la convertit manuellement en vente.

---

## 9. Observabilité et exploitation

- Logs JSON structurés avec `traceId`, `businessId` (jamais de données personnelles ni de secrets).
- Actuator : `health` (liveness/readiness) public, autres endpoints protégés ou désactivés en production.
- Métriques Micrometer : latence API, erreurs, appels IA (durée, jetons, coût).
- Limites du MVP à revoir à la montée en charge : limitation de débit en mémoire (→ Redis ou PostgreSQL), génération PDF synchrone (→ tâches asynchrones).

---

## 10. Stratégie de tests

| Niveau | Outils | Portée |
|---|---|---|
| Unitaires backend | JUnit (version gérée par Spring Boot), AssertJ, Mockito | Règles métier, calculs financiers (cas limites : CA nul, arrondis, annulations, paiements partiels, remboursements, avoirs), CMP (stock nul/négatif, coût inconnu, réintégration partielle), TVA HT/TTC et arrondis (règles provisoires O2), limite de remise |
| Périodes (D13) | Tests d'intégration sur données datées | Vente d'octobre annulée en novembre : rapport d'octobre inchangé, annulation en novembre ; contre‑passation et annulation de dépense dans une autre période ; recalcul d'une période passée identique |
| Intégration backend | Spring Boot Test, **Testcontainers PostgreSQL**, MockMvc | Endpoints, transactions, contraintes, RLS, migrations Flyway |
| Concurrence | Tests multi‑threads sur Testcontainers | Deux confirmations simultanées, deux ventes sur le dernier article, paiements concurrents, double utilisation d'un même avoir, rejeu idempotent |
| Hors ligne (ADR‑14) | Tests d'intégration backend + tests frontend (file d'attente simulée, MSW) + Playwright en mode réseau coupé | Rejeu multiple d'une même opération = un seul enregistrement (y compris après expiration de l'enregistrement d'idempotence) ; coupure pendant la requête ; `X-Expected-Business-Id` différent du jeton refusé ; appartenance révoquée avant synchronisation ; prix modifié et stock épuisé entre saisie et synchronisation ; aucune opération locale affichée comme confirmée ; effacement des données à la déconnexion ; cloisonnement entre deux entreprises et deux utilisateurs d'un même appareil |
| Internationalisation (ADR‑13) | Vitest + contrôle statique | Aucun texte d'interface codé en dur hors des catalogues ; clés manquantes détectées |
| Restauration (ADR‑15) | Procédure d'exploitation | Restauration d'une sauvegarde sur un environnement vierge, vérifiée périodiquement |
| Isolation multi‑tenant / IDOR | Suite dédiée, paramétrée sur tous les endpoints privés | Obligatoire pour toute nouvelle route |
| Sécurité | `spring-security-test` (+ `spring-boot-starter-security-test`) | Matrice rôles → permissions, jetons expirés/altérés, rotation, réutilisation, révocation, contrôle `Origin`, changement d'entreprise, accès support (sans approbation, expiré, révoqué, hors périmètre, écriture en lecture seule), filtrage des champs sensibles pour l'employé, gestionnaire en lecture seule sur les paramètres sensibles |
| Architecture | ArchUnit | Dépendances entre modules, pas de `double`/`float` monétaire, entités hors des contrôleurs |
| IA | `FakeAiClient`, tests de contrat sur la construction de l'instantané et la validation des réponses | **Aucun appel réel** à l'API Anthropic en CI |
| Frontend unitaire / composants | Vitest, React Testing Library, MSW | Formulaires, formatage monétaire, gestion d'erreurs |
| End‑to‑end | Playwright (à partir de la phase Commerce) | Parcours critiques : connexion, vente avec paiement partiel, annulation |

Objectif : couverture élevée sur `finance`, `commerce`, `gestion` (stock) et `shared.security` ; la couverture globale n'est pas un objectif en soi.

---

## 11. Variables d'environnement et secrets

- Toute configuration sensible passe par des **variables d'environnement** ; `application.yml` ne contient que des références `${VAR}` et des valeurs non sensibles par défaut.
- `.env` (local) est ignoré par Git ; `.env.example` documente les noms avec des valeurs fictives.
- Profils Spring : `dev`, `test`, `prod`. En `prod`, l'application **refuse de démarrer** si un secret obligatoire manque ou garde une valeur d'exemple (`JWT_SECRET` trop court, `AUTH_COOKIE_SECURE=false`, `ANTHROPIC_API_KEY` d'exemple alors que l'IA est activée, etc.).
- La clé `ANTHROPIC_API_KEY` n'existe que côté backend ; aucune variable `VITE_*` ne contient de secret (tout ce qui est préfixé `VITE_` est public dans le bundle).
- Production : secrets injectés par la plateforme d'hébergement (gestionnaire de secrets), rotation documentée.

---

## 12. Arborescence proposée

```
FOOYRE/
├── backend/
│   ├── pom.xml
│   ├── Dockerfile
│   └── src/
│       ├── main/
│       │   ├── java/com/fooyre/
│       │   │   ├── FooyreApplication.java
│       │   │   ├── shared/        (config, security, tenant, error, money, audit, idempotency, web)
│       │   │   ├── identity/      (api, application, domain)
│       │   │   ├── business/
│       │   │   ├── gestion/
│       │   │   ├── commerce/
│       │   │   ├── finance/
│       │   │   ├── dashboard/
│       │   │   ├── reporting/
│       │   │   ├── intelligence/  (api, application, domain, infrastructure)
│       │   │   ├── storefront/
│       │   │   └── platform/
│       │   └── resources/
│       │       ├── application.yml
│       │       ├── application-dev.yml
│       │       ├── application-prod.yml
│       │       ├── db/migration/          (V1__..., V2__... Flyway)
│       │       ├── i18n/                  (messages_fr.properties — ADR‑13)
│       │       └── prompts/               (invites système IA versionnées)
│       └── test/java/com/fooyre/
│           ├── architecture/              (ArchUnit)
│           ├── tenantisolation/
│           └── <module>/...
├── frontend/
│   ├── package.json
│   ├── vite.config.ts
│   ├── tsconfig.json
│   ├── index.html
│   ├── Dockerfile
│   └── src/
│       ├── app/
│       ├── features/
│       └── shared/                        (api, i18n/locales/fr, offline, ui, lib…)
├── infra/
│   ├── nginx/                             (configuration reverse proxy)
│   ├── postgres/                          (script d'init : rôles app/migration)
│   └── backup/                            (scripts et procédure de sauvegarde / restauration — ADR‑15)
├── docs/
├── docker-compose.yml
├── .env.example
├── .gitignore
├── CLAUDE.md
└── README.md
```
