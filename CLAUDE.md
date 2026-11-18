# CLAUDE.md — Règles de développement FOOYRE

Ces règles s'appliquent à **toutes** les phases. En cas de conflit entre une demande ponctuelle et ces règles, le signaler avant d'agir.

Documents de référence : `docs/cahier-des-charges.md` (définitions financières normatives §3, CMP §3.1, TVA §3.2, rôles §4, protection des données §5.1, décisions et points ouverts §8), `docs/architecture.md`, `docs/database-schema.md`, `docs/api-specification.md`, `docs/development-roadmap.md`.

Les décisions **D1 à D20** (`cahier-des-charges.md` §8.1) sont validées : ne pas les remettre en question sans demande explicite. Les anciens points O1, O3, O4, O5, O6 et hypothèses H8 à H11 sont tranchés (D11 à D19). Les points **O2 et O7 à O13** (§8.3) ne sont **pas** tranchés : ne pas les trancher à la place de la porteuse du projet, ne pas présenter leur traitement provisoire comme une décision, les rappeler avant la phase concernée.

## 1. Méthode de travail
- Travailler **phase par phase** selon `docs/development-roadmap.md`. S'arrêter à la fin de chaque phase et attendre la validation.
- Ne pas développer de fonctionnalité hors de la phase en cours ; ne pas créer de code spéculatif.
- Expliquer toute décision structurante (nouvelle dépendance, changement de schéma, écart à l'architecture) et mettre à jour `docs/` en conséquence.
- Signaler les hypothèses non validées au lieu de les trancher silencieusement.
- Ne jamais prétendre avoir exécuté, testé ou vérifié quelque chose qui ne l'a pas été. Indiquer précisément ce qui a été lancé et le résultat.
- Aucune action de déploiement, de publication ou d'appel à un service externe payant sans accord explicite.
- Ne pas faire de commit ni de push sans demande explicite.
- **Versions et identifiants externes** (Spring Boot, dépendances, identifiant de modèle Claude, tarifs, SDK) : ne jamais les présenter comme confirmés sans vérification sur une source officielle au moment de l'implémentation ; consigner la date et la source de la vérification dans `docs/architecture.md`.

## 2. Architecture
- Monolithe modulaire. Package racine `com.fooyre`, modules : `shared`, `identity`, `business`, `gestion`, `commerce`, `finance`, `dashboard`, `reporting`, `intelligence`, `storefront`, `platform`.
- Couches par module : `api` (contrôleurs + DTO) → `application` (services, transactions) → `domain` (entités, règles, repositories) ; `infrastructure` pour les adaptateurs techniques.
- Un module n'utilise jamais les repositories ou entités internes d'un autre module : uniquement son service applicatif public.
- Les entités JPA ne sortent jamais des contrôleurs : DTO (`record`) obligatoires en entrée et en sortie.
- Pas de logique métier dans les contrôleurs.
- Pas de microservices, pas de message broker, pas de cache distribué sans décision validée.

## 3. Multi‑tenant (règle de sécurité n° 1)
- Le `businessId` vient **uniquement** de `TenantContext` (jeton validé + appartenance `ACTIVE` vérifiée à chaque requête). Jamais d'un paramètre d'URL, d'un corps de requête ou d'un en‑tête client sur les routes privées. Deux usages encadrés qui ne choisissent jamais le tenant : `POST /auth/switch-business` (demande vérifiée contre les appartenances) et `X-Expected-Business-Id` des rejeux hors ligne (seulement comparé au jeton ; écart ⇒ `409 BUSINESS_CONTEXT_MISMATCH`).
- Tout identifiant référencé dans un corps (`customerId`, `productId`, `treasuryAccountId`…) est résolu dans l'entreprise active.
- Toute entité métier hérite de `TenantScopedEntity` ; `business_id` est renseigné automatiquement à l'insertion.
- Toute lecture par identifiant filtre par `businessId` (`findByIdAndBusinessId`). Interdit : `findById` seul sur une entité métier.
- Ressource d'un autre tenant ⇒ **404**.
- Toute nouvelle table métier : `business_id NOT NULL`, `UNIQUE (business_id, id)`, FK composites, politique RLS.
- Toute nouvelle route privée : ajout obligatoire à la suite de tests d'isolation / anti‑IDOR.
- Le `PLATFORM_ADMIN` n'a aucune permission métier ; tout accès aux données d'une entreprise passe par un octroi d'accès support (D12) : justification, **approbation explicite du propriétaire** (seule voie), durée limitée, périmètre défini, lecture seule par défaut (écriture uniquement si le propriétaire l'autorise explicitement), chaque requête journalisée, révocable à tout moment. Ne jamais créer de contournement (super‑utilisateur, désactivation de la RLS, route cachée, approbation implicite).

## 4. Argent et calculs financiers
- `BigDecimal` exclusivement pour les montants, coûts, quantités et taux. **Jamais `float` ni `double`** (y compris dans les tests et le frontend pour des calculs).
- Construire les `BigDecimal` depuis des `String` ou `BigDecimal.valueOf(long)`, jamais `new BigDecimal(double)`. Comparer avec `compareTo`, pas `equals`.
- Arrondi : `RoundingMode.HALF_UP` à l'échelle de la devise (XOF : 0 décimale), échelles définies dans `shared.money`.
- TVA : désactivée par défaut, activable par entreprise, saisie HT ou TTC selon le paramètre de l'entreprise, taux par défaut 18 % configurable (D4). Calcul **uniquement** via `TaxCalculator`, par ligne, selon `cahier-des-charges.md` §3.2 ; totaux = somme des lignes arrondies ; tests obligatoires pour chaque cas. ⚠️ L'algorithme par ligne, la ventilation des remises et les arrondis sont **provisoires, non validés fiscalement (O2)** : ne jamais les présenter comme des règles fiscales définitives (code, interface, documentation) ; toute correction issue de la validation comptable se fait dans `TaxCalculator` uniquement.
- Coût moyen pondéré (CMP) : recalculé aux entrées valorisées selon `cahier-des-charges.md` §3.1, sous le même verrou que le stock ; figé sur chaque ligne de vente ; jamais modifié rétroactivement.
- Stock négatif interdit sauf si l'entreprise l'autorise explicitement ; contrôle backend dans la transaction, sous verrou.
- PostgreSQL : `NUMERIC(19,2)` montants, `NUMERIC(19,4)` coûts unitaires, `NUMERIC(19,3)` quantités.
- JSON : montants et quantités transmis en chaînes.
- Les indicateurs (ventes brutes, annulations, ventes nettes ajustées, encaissements, dépenses, CMV, marge brute, résultat net, trésorerie, créances, avoirs en cours) sont calculés **uniquement** par `FinancialMetricsService` du module `finance`, selon les définitions du cahier des charges §3.
- **Périodes (D13)** : toute annulation ou correction est imputée à **sa propre date** (annulation, contre‑passation, remboursement, annulation de dépense) ; ne jamais modifier rétroactivement un chiffre d'une période passée. Les saisies antidatées sont journalisées ; leurs règles restent ouvertes (O9).
- **Annulation et avoirs (D11)** : annulation totale uniquement ; restitution du montant payé par remboursement (trésorerie) et/ou avoir (suivi séparé, jamais de mouvement de trésorerie) ; réintégration du stock seulement pour les lignes déclarées revendables. Modalités des avoirs (O7) et traitement des marchandises non revendables (O8) : provisoires.
- **Remises (D14)** : sans plafond pour OWNER/MANAGER ; pour EMPLOYEE, limite configurée par OWNER/MANAGER (forme provisoire : O11), contrôlée **côté serveur** à l'enregistrement et à la confirmation, remise et tentative refusée journalisées.
- Ne jamais présenter la marge brute comme un bénéfice. Le résultat net est libellé « Résultat net (estimation de gestion) ».
- Le frontend ne recalcule pas les totaux qui font foi : il affiche ceux du serveur.

## 5. Historique et intégrité
- Ventes, paiements, mouvements de stock, mouvements de trésorerie, audit : **pas de suppression ni de modification** après validation. Corriger par écriture inverse (annulation, contre‑passation, remboursement) avec motif.
- Prix, remises, mode HT/TTC, taux de TVA et coûts figés sur les lignes de vente à la confirmation.
- Opérations de stock sous verrou (`SELECT … FOR UPDATE`, ordre déterministe par `product_id`) ; confirmation, paiement, remboursement et annulation d'une vente sous verrou sur la vente, avec contrôle d'état (pas de double traitement).
- Idempotence (`Idempotency-Key`) sur : création, confirmation et annulation de vente, paiement (y compris par avoir), remboursement, création de dépense, demande de commande publique (et création de client depuis le mode hors ligne). **Unicité durable** : clé conservée sur la ressource avec `UNIQUE (business_id, idempotency_key)` (`sale`, `payment`, `customer`, `expense`), pour qu'aucun rejeu tardif ne crée de doublon.
- Migrations Flyway : jamais modifier une migration déjà fusionnée ; en créer une nouvelle.

## 6. Sécurité
- Autorisation explicite sur chaque endpoint (`@PreAuthorize` avec les permissions nommées de `docs/architecture.md` §5.3). Rien n'est accessible par défaut. Toute modification de la matrice des permissions met à jour la documentation et le test qui la fige.
- Le masquage d'un bouton dans le frontend n'est **jamais** une mesure de sécurité ; les champs sensibles (coûts, marges, données financières) sont retirés des réponses côté backend pour `EMPLOYEE`, y compris dans les ventes de l'entreprise qu'il peut consulter (D14) ; l'employé ne consulte ni les soldes dus des clients ni les avoirs (`CUSTOMER_BALANCE_READ` : OWNER, MANAGER).
- Paramètres financiers et sensibles (TVA, mode HT/TTC, taux, stock négatif, devise) : modification par OWNER seul ; lecture par OWNER et MANAGER (D20). Informations générales, y compris la langue par défaut de l'entreprise : OWNER et MANAGER. La gestion des employés et des membres (`MEMBER_MANAGE`) est réservée à OWNER pour le moment ; toute délégation au gestionnaire exige une décision ultérieure.
- Sessions : jeton d'accès court en mémoire côté frontend ; refresh token opaque en cookie `HttpOnly`, `SameSite=Strict`, `Secure` en production, stocké haché, avec rotation et détection de réutilisation. **Aucun jeton sensible dans `localStorage` ni `sessionStorage`.** Les routes métier n'acceptent jamais d'authentification par cookie ; les routes `/auth/*` à cookie vérifient `Origin` et l'en‑tête `X-Requested-With`. Révoquer les sessions lors des événements listés dans `docs/architecture.md` ADR‑04.
- Validation `jakarta.validation` de toutes les entrées, tailles maximales définies.
- Aucun secret dans le code, les logs, les messages d'erreur, les tests ou les fichiers commités. Configuration sensible par variables d'environnement uniquement.
- Aucune variable `VITE_*` ne contient de secret. La clé Anthropic n'existe que côté backend.
- Pas de données personnelles (téléphones, noms de clients) dans les logs.
- Pas de trace de pile dans les réponses HTTP.
- Requêtes SQL paramétrées uniquement ; pas de concaténation de chaînes.
- Exports CSV protégés contre l'injection de formules.

## 7. Mode hors ligne partiel (D17, ADR‑14 ; modalités ouvertes : O10)
- Le client **prépare et met en attente**, il ne **valide jamais** : pas de numéro de reçu définitif, pas de déduction de stock faisant foi, aucun effet sur les indicateurs avant la réponse du serveur. Aucun état local ne signifie « confirmé ».
- Rejeu ordonné sur les **routes normales** (jamais de route de synchronisation qui contourne les contrôles), avec la clé d'idempotence générée **à la saisie** et `X-Expected-Business-Id`.
- Le serveur réapplique tous les contrôles avec l'état courant (droits, prix, remise, stock sous verrou, dates). Conflit ⇒ résolution explicite par un utilisateur autorisé ; jamais de fusion automatique d'opérations financières.
- Données locales minimales (jamais de coûts, marges, indicateurs, paramètres sensibles, journaux ni données d'IA), cloisonnées par utilisateur et entreprise, **aucun jeton stocké**, expiration, effacement à la déconnexion.
- Ne pas étendre la liste des opérations hors ligne sans arbitrage O10.

## 8. Langues et hébergement
- **Internationalisation (D16)** : aucun texte d'interface ou message utilisateur codé en dur ; catalogues frontend (`locales/fr`) et `messages_fr.properties` backend ; formats via `Intl` et la locale active. Français seul au lancement ; ne pas annoncer de prise en charge du wolof, du pulaar, du sérère ni de la voix sans évaluation.
- **Portabilité (D19)** : composants standard (Docker, PostgreSQL sans extension propriétaire), fichiers via l'interface `FileStorage` (clés d'objet, jamais d'URL de fournisseur), aucune dépendance à un service propre à un hébergeur sans décision ; fournisseur et région non choisis (O12).

## 9. Intelligence artificielle
- Appels à Claude **uniquement** depuis le backend, via le port `AiClient` (implémentations `AnthropicAiClient` avec le SDK Java officiel, `FakeAiClient` pour les tests). Aucun module métier ne dépend du fournisseur ou du modèle.
- Modèle par défaut : **Claude Opus 5.5** (`ANTHROPIC_MODEL`, identifiant `claude-opus-5-5` relevé le 2026-11-18 — à revérifier au démarrage de la phase 9). Modèle, clé, délai d'attente, nombre de tentatives, `max_tokens`, effort, tarifs d'estimation, quotas : configurables par variables d'environnement, jamais codés en dur.
- Claude reçoit un instantané **pré‑calculé, agrégé et pseudonymisé**, limité au strict nécessaire ; aucun nom ni téléphone de client pour les analyses ordinaires ; aucun accès direct à la base ; il ne calcule pas les chiffres de référence. Les données restent dans l'infrastructure FOOYRE (D18).
- IA **désactivée par défaut** pour chaque entreprise ; activée uniquement par consentement explicite du propriétaire, **révocable à tout moment** avec effet immédiat (D15).
- `AI_ENABLED` désactive globalement l'IA ; en `prod`, l'IA reste désactivée — et aucune donnée n'est transmise à Anthropic — tant que `AI_PRODUCTION_APPROVED` n'est pas positionné après les validations juridiques et réglementaires (CDP, transferts internationaux — `cahier-des-charges.md` §5.1), **non obtenues à ce jour**. Ne jamais activer l'IA en production de sa propre initiative.
- Invite système versionnée dans `src/main/resources/prompts/` : ne jamais inventer, signaler les données insuffisantes, distinguer marge brute et résultat net, répondre en français.
- Sortie structurée validée par schéma ; les chiffres cités doivent exister dans l'instantané.
- Chaque appel enregistre jetons, coût estimé, latence, statut (`ai_usage`). Quotas et plafonds de coût **de l'entreprise** vérifiés **avant** l'appel.
- Les tests n'appellent jamais l'API réelle.

## 10. Tests
- Tout code métier est accompagné de tests. Calculs financiers et stock : cas limites systématiques, y compris annulations dans une autre période (D13), avoirs et réintégration partielle (D11), limite de remise (D14).
- Mode hors ligne : rejeux multiples sans doublon, incohérence de tenant refusée, aucune opération locale présentée comme validée (`docs/architecture.md` §10).
- Intégration avec **Testcontainers PostgreSQL** (pas de H2).
- ArchUnit pour les règles d'architecture.
- Frontend : Vitest + React Testing Library + MSW ; Playwright pour les parcours critiques.
- Lancer les tests concernés avant d'annoncer qu'une tâche est terminée et rapporter le résultat réel.

## 11. Conventions de code
- Backend : Java 21 (records, `sealed`, pattern matching quand utile), Spring Boot 4.1.x (cible 4.1.2, à confirmer en phase 1 — Jackson 3 `tools.jackson`, Hibernate 7, Spring Security 7), injection par constructeur, pas de Lombok sauf décision contraire, noms de code en anglais, messages utilisateur en français.
- Abonnements : formules, fonctionnalités, quotas et limites sont des **données** (pas de constantes dans le code) ; aucun tarif figé ; aucun paiement d'abonnement.
- API : conventions de `docs/api-specification.md` (préfixe `/api/v1`, Problem Details, pagination, codes d'erreur stables).
- Frontend : TypeScript strict, organisation par `features/`, appels réseau via TanStack Query + Axios dans `features/*/api.ts`, styles Tailwind, icônes Lucide, formatage via `Intl` (`fr-SN`, XOF), mobile‑first.
- Pas de dépendance nouvelle sans justification (utilité, maintenance, licence).
- Commits en français ou en anglais, format conventionnel (`feat:`, `fix:`, `docs:`, `test:`, `chore:`).

## 12. Commandes (à compléter en phase 1)
```
# Backend
cd backend && ./mvnw verify
# Frontend
cd frontend && npm ci && npm run test && npm run build
# Environnement local
docker compose up -d
```
