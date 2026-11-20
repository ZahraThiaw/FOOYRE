# FOOYRE — Spécification de l'API REST

Version : 0.3 (Phase 0 consolidée — 2026-11-18) · Statut : **conventions + inventaire des routes, alignés sur les décisions D1 à D20** ; les éléments dépendant des points ouverts O7, O9, O10 et O11 sont marqués « provisoire ». Le contrat détaillé (OpenAPI) sera généré à partir du code à chaque phase (outil `springdoc-openapi` sous réserve de compatibilité avec Spring Boot 4.1, à vérifier en phase 1).

Les rôles indiqués ci‑dessous sont la traduction des **permissions** de `architecture.md` §5.3 ; c'est la permission qui est vérifiée côté backend, sur chaque route.

## 1. Conventions générales

| Sujet | Convention |
|---|---|
| Préfixe | `/api/v1` (versionnage dans l'URL ; une v2 n'est créée qu'en cas de rupture) |
| Format | JSON UTF‑8, propriétés en `camelCase` |
| Ressources | noms au pluriel, en anglais, `kebab-case` (`/expense-categories`) |
| Identifiants | UUID dans l'URL |
| Actions métier | sous‑ressource verbale en `POST` quand ce n'est pas un CRUD : `POST /sales/{id}/confirm`, `POST /sales/{id}/cancel` |
| Méthodes | `GET` lecture · `POST` création/action · `PUT` remplacement · `PATCH` modification partielle (évitée au MVP) · `DELETE` uniquement pour archivage de référentiels (jamais pour ventes, paiements, mouvements) |
| Tenant | **jamais** dans l'URL ni le corps pour les routes privées : déduit du jeton |
| Montants | chaînes décimales : `"15000.00"` ; devise en code ISO : `"XOF"` |
| Quantités | chaînes décimales : `"2.500"` |
| Dates | `LocalDate` : `"2026-11-18"` ; instants : ISO 8601 UTC `"2026-11-18T14:05:00Z"` |
| Énumérations | `UPPER_SNAKE_CASE` |
| Champs nuls | omis ou `null` (cohérence via Jackson : `NON_NULL` non utilisé pour les montants, toujours présents) |

### 1.1 Authentification et sessions (D2)
- `Authorization: Bearer <accessToken>` sur toutes les routes privées. Le jeton d'accès (15 min) est conservé **en mémoire** par le frontend, jamais dans `localStorage` / `sessionStorage`.
- Cookie `fooyre_refresh` (`HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`) utilisé **uniquement** par `/auth/refresh`, `/auth/logout` et `/auth/switch-business`. `Secure` est obligatoire en production.
- Ces trois routes exigent en plus : méthode `POST`, en‑tête `Origin` égal à l'origine de l'application, en‑tête `X-Requested-With: fooyre`. À défaut : `403 CSRF_CHECK_FAILED`.
- Les routes métier n'acceptent **jamais** d'authentification par cookie.
- Un jeton d'accès est refusé (`401 SESSION_REVOKED`) si la session a été révoquée, si `security_version` a changé, si l'appartenance n'est plus active ou si l'entreprise est suspendue.

### 1.2 Pagination, tri, filtres
Requête : `?page=0&size=20&sort=saleDate,desc&from=2026-11-01&to=2026-11-30&q=texte`
- `size` maximum 100 (défaut 20).
- Les champs de tri autorisés sont listés par endpoint (liste blanche).

Réponse :
```json
{
  "content": [ ... ],
  "page": { "number": 0, "size": 20, "totalElements": 134, "totalPages": 7 }
}
```

### 1.3 Erreurs — RFC 9457 (Problem Details)
`Content-Type: application/problem+json`
```json
{
  "type": "https://fooyre.app/problems/insufficient-stock",
  "title": "Stock insuffisant",
  "status": 409,
  "detail": "Le produit « Savon karité » n'a que 3 unités en stock.",
  "instance": "/api/v1/sales/0f8c.../confirm",
  "code": "INSUFFICIENT_STOCK",
  "traceId": "6b1f2c...",
  "errors": [ { "field": "lines[0].quantity", "code": "INVALID_VALUE", "message": "doit être supérieur à 0" } ]
}
```

| Statut | Usage |
|---|---|
| 400 | Requête mal formée / validation (`VALIDATION_ERROR`) |
| 401 | Non authentifié, jeton expiré, invalide ou révoqué (`SESSION_REVOKED`) ; connexion refusée — e-mail inconnu, mot de passe erroné ou compte inactif, indistinguables (`INVALID_CREDENTIALS`, MVP-1) |
| 403 | Authentifié mais permission insuffisante **dans sa propre entreprise** (`FORBIDDEN`), contrôle CSRF échoué (`CSRF_CHECK_FAILED`), IA non activée pour l'entreprise (`AI_NOT_ENABLED`), route hors du périmètre ou du mode d'un accès support (`SUPPORT_SCOPE_DENIED`) |
| 404 | Ressource inexistante **ou appartenant à un autre tenant** (`NOT_FOUND`) |
| 409 | Conflit métier (`INSUFFICIENT_STOCK`, `STOCK_ALREADY_INITIALIZED`, `SALE_ALREADY_CONFIRMED`, `SALE_ALREADY_CANCELLED`, `OPTIMISTIC_LOCK`, `IDEMPOTENCY_KEY_REUSED`, `CURRENCY_LOCKED`, `BUSINESS_CONTEXT_MISMATCH`) |
| 422 | Règle métier non respectée (`STOCK_NOT_INITIALIZED`, `PRODUCT_ARCHIVED`, `PAYMENT_EXCEEDS_BALANCE`, `INVALID_STATE_TRANSITION`, `DISCOUNT_LIMIT_EXCEEDED`, `CREDIT_INSUFFICIENT`, `CUSTOMER_REQUIRED_FOR_CREDIT`, `CANCELLATION_SETTLEMENT_MISMATCH`, `AI_INSUFFICIENT_DATA`) |
| 429 | Limite de débit ou quota dépassé (`RATE_LIMITED`, `AI_QUOTA_EXCEEDED`) + `Retry-After` |
| 502 | Sortie de l'IA invalide après validation de schéma (`AI_INVALID_OUTPUT`, §2.7) |
| 503 | Dépendance indisponible (`AI_UNAVAILABLE`) ; IA désactivée globalement ou non approuvée en production (`AI_DISABLED`, §2.7) |
| 500 | Erreur interne (`INTERNAL_ERROR`, message générique + `traceId`) |

Les codes `code` sont stables : le frontend les traduit dans la langue de l'utilisateur à partir de ses catalogues (français au lancement — D16). Les champs `title` et `detail` sont rédigés en français par le backend (`MessageSource`) et ne servent qu'en complément : **le frontend ne les affiche jamais** (ils peuvent être techniques).

**Erreurs de validation par champ** (`errors[]`, avec `VALIDATION_ERROR`) : chaque entrée porte `field` (chemin du champ), un `code` stable et un `message` facultatif (complément, jamais affiché tel quel). Codes initiaux : `REQUIRED`, `INVALID_FORMAT`, `TOO_LONG`, `TOO_SHORT`, `INVALID_VALUE`, `ALREADY_EXISTS` ; ajoutés : `ARCHIVED` (MVP-2), `LOCKED` (MVP-3 : valeur qui ne peut plus être modifiée, ex. unité d'un produit dont le stock est suivi). Un code inconnu du frontend est affiché comme `INVALID_VALUE`.

**Interprétation côté frontend** (`frontend/src/shared/api/errorMessages.ts`, unique point de traduction) : code connu → message du code ; sinon message du statut (400, 401, 403, 404, 409, 422, 429, 5xx) ; référence `traceId` affichée pour les 5xx ; `Retry-After` (secondes ou date HTTP) affiché pour 429, **sans nouvelle tentative automatique** (aucune relance n'est faite sur une 4xx). Codes produits par le frontend lui‑même, jamais par le backend : `NETWORK_ERROR` (aucune réponse), `TIMEOUT` (délai dépassé), `UNKNOWN_ERROR` (réponse sans code exploitable), et `501 MOCK_NOT_IMPLEMENTED` (mode démonstration local uniquement, route non simulée — jamais en production).

### 1.4 Idempotence
En‑tête `Idempotency-Key: <uuid>` **obligatoire** sur : opérations de stock (initialisation, entrée, sortie, ajustement — MVP-3), création de vente, confirmation de vente, annulation de vente, ajout de paiement (y compris règlement par avoir), remboursement, création de dépense, demande de commande publique ; **facultatif** sur la création de client (obligatoire lorsqu'elle provient du mode hors ligne). Rejeu identique → même réponse ; même clé avec un corps différent → `409 IDEMPOTENCY_KEY_REUSED`. Conservation de l'enregistrement d'idempotence : 24 h.

**Unicité durable** (ADR‑09) : la clé est aussi enregistrée sur la ressource créée (vente, paiement, client, dépense) avec une contrainte d'unicité par entreprise. Après expiration des 24 h — cas typique d'une opération restée hors ligne — un rejeu ne crée toujours pas de doublon : le serveur renvoie la ressource existante si le contenu est identique, `409 IDEMPOTENCY_KEY_REUSED` sinon.

### 1.5 Concurrence
- Les ressources éditables exposent `version` ; une mise à jour avec une version périmée renvoie `409 OPTIMISTIC_LOCK`.
- Confirmation, paiement, remboursement et annulation d'une vente sont sérialisés côté serveur (verrou sur la vente et les stocks) : une seconde confirmation renvoie `409 SALE_ALREADY_CONFIRMED`, un paiement dépassant le reste dû `422 PAYMENT_EXCEEDS_BALANCE`, un stock insuffisant `409 INSUFFICIENT_STOCK` (si le stock négatif n'est pas autorisé).

### 1.6 Visibilité selon le rôle
Les champs sensibles (`unitCost`, `averageCost`, `costAmount`, `lineCostAmount`, `grossMargin`, valeur du stock, indicateurs financiers) sont **absents** des réponses pour `EMPLOYEE` (sérialisation filtrée côté backend, pas un simple masquage à l'écran), y compris dans les ventes de l'entreprise qu'il peut consulter (D14) et dans l'instantané hors ligne. L'employé ne consulte pas non plus les **soldes dus des clients** ni les **avoirs** (`CUSTOMER_BALANCE_READ` : O,M) ; seul le reste dû de la vente qu'il encaisse lui est présenté. Les paramètres financiers et sensibles sont lisibles par OWNER et MANAGER, modifiables par OWNER seul (D20).

### 1.7 Identifiants d'entreprise
Un `businessId` présent dans une URL, un corps ou un en‑tête n'est jamais utilisé pour déterminer le tenant. Deux usages encadrés, qui **ne choisissent jamais** le tenant :
- `POST /auth/switch-business`, où il constitue une **demande** vérifiée contre les appartenances actives de l'utilisateur ;
- l'en‑tête `X-Expected-Business-Id` envoyé lors du rejeu d'une opération saisie hors ligne : il est seulement **comparé** au `bid` du jeton ; en cas d'écart, `409 BUSINESS_CONTEXT_MISMATCH` (et audit), sans aucun traitement.

Tout identifiant référencé dans un corps (`customerId`, `productId`, `treasuryAccountId`, `customerCreditId`…) est recherché **dans l'entreprise active** : s'il appartient à une autre entreprise → `404`.

### 1.8 TVA et prix
Les montants renvoyés pour une vente comprennent toujours `netAmount` (HT), `taxAmount` et `totalAmount` (TTC), ainsi que `vatEnabled` et `priceEntryMode` figés sur la vente. Les prix saisis par le client sont interprétés selon le mode HT/TTC de l'entreprise. Tous les calculs sont faits par le backend, par `TaxCalculator`, selon des règles de calcul par ligne et d'arrondi **provisoires, non validées fiscalement** (O2, `cahier-des-charges.md` §3.2) : l'interface et les documents produits ne doivent pas les présenter comme des règles fiscales définitives.

### 1.9 Périodes et corrections (D13)
Les indicateurs et rapports imputent chaque annulation ou correction à **sa propre date** (date d'annulation, de contre‑passation, de remboursement, d'annulation de dépense). Une période passée n'est jamais modifiée par une opération ultérieure ; les réponses distinguent `grossRevenue` (ventes brutes), `salesCancellations` (annulations / ajustements de la période) et `revenue` (ventes nettes ajustées). Seule une saisie antidatée peut toucher une période passée : elle est journalisée et ses règles restent ouvertes (O9).

### 1.10 Langue (D16)
Le français est la seule locale prise en charge au lancement. La préférence de l'utilisateur (`locale`) est renvoyée par `/me` et modifiable par `PUT /me/preferences` ; seules les locales déclarées prises en charge sont acceptées (`400 VALIDATION_ERROR` sinon).

## 2. Inventaire des routes

Légende rôles : **O** = BUSINESS_OWNER, **M** = BUSINESS_MANAGER, **E** = EMPLOYEE, **A** = PLATFORM_ADMIN, **P** = public.

### 2.1 Authentification et compte (`identity`)
| Méthode | Route | Rôles | Description |
|---|---|---|---|
| POST | `/auth/register` | P | Créer un compte + une première entreprise (devient O) |
| POST | `/auth/login` | P (contrôles `Origin` + `X-Requested-With`) | Connexion `{ "email", "password" }` → `{ "accessToken", "expiresIn" }` + cookie de rafraîchissement ; la liste des entreprises est lue ensuite par `GET /me` (MVP-1). Échec : `401 INVALID_CREDENTIALS` ; trop de tentatives (MVP-1.1) : `429 RATE_LIMITED` + `Retry-After`, même réponse que le compte existe ou non |
| POST | `/auth/refresh` | P (cookie + contrôles CSRF) | Rotation du refresh token, nouveau jeton d'accès |
| POST | `/auth/logout` | tous (cookie + contrôles CSRF) | Révoque la session courante, supprime le cookie |
| POST | `/auth/logout-all` | tous | Révoque toutes les sessions de l'utilisateur |
| POST | `/auth/switch-business` | tous (Bearer + cookie + contrôles CSRF) | `{ "businessId": "…" }` : vérifie l'appartenance active, recalcule les permissions, émet un nouveau jeton, fait tourner le refresh token ; `404 NOT_FOUND` si non membre. Réponse : voir ci‑dessous |
| POST | `/auth/password/forgot`, `/auth/password/reset` | P | Réinitialisation (canal d'envoi : point ouvert O13 — email, SMS ou les deux) ; révoque toutes les sessions |
| POST | `/me/password` | tous | Changement de mot de passe ; révoque les autres sessions |
| GET | `/me` | tous | Profil, `locale`, entreprise active, rôle, **permissions effectives**, liste des entreprises dont l'utilisateur est membre ; pour l'employé, sa limite de remise (D14). Contrat : voir ci‑dessous |
| PUT | `/me/preferences` | tous | Préférence de langue (`locale`), parmi les locales prises en charge (D16) |
| POST | `/invitations/{token}/accept` | P | Acceptation d'une invitation |

#### Contrat de `GET /me` (validé — lot 3, R8)
```jsonc
{
  "id": "uuid",
  "email": "…",
  "fullName": "…",
  "locale": "fr",
  "platformAdmin": false,                    // attribut GLOBAL de l'utilisateur (ADR‑05)
  "activeBusiness": { "id": "uuid", "name": "…", "currency": "XOF" },      // ou null
  "activeMembership": {                                                   // ou null
    "id": "uuid",                            // = claim mid
    "role": "BUSINESS_OWNER | BUSINESS_MANAGER | EMPLOYEE",
    "employeeMaxDiscountRate": "10.00"       // chaîne décimale, renseignée pour EMPLOYEE (D14, forme provisoire O11), sinon null
  },
  "permissions": ["BUSINESS_READ", "…"],     // permissions effectives dans le contexte actif (architecture §5.3)
  "businesses": [ { "id": "uuid", "name": "…", "role": "EMPLOYEE" } ]     // appartenances ACTIVES uniquement
}
```
Règles :
- `activeBusiness` et `activeMembership` sont **toujours cohérents** : tous deux renseignés ou tous deux `null` (le frontend traite toute incohérence comme une erreur).
- **Entreprise active à la connexion** : si l'utilisateur a plusieurs appartenances actives, le backend restaure la dernière entreprise sélectionnée si son appartenance est toujours active ; sinon `activeBusiness` et `activeMembership` valent `null` et le frontend demande de choisir une entreprise (`POST /auth/switch-business`).
- `PLATFORM_ADMIN` sans octroi d'accès support : `platformAdmin: true`, `activeBusiness: null`, `activeMembership: null`, permissions `PLATFORM_*` uniquement (aucune permission métier — D12).
- Les rôles et permissions renvoyés servent **uniquement à l'affichage** ; le backend vérifie chaque requête à partir du jeton (appartenance `ACTIVE`, rôle, permissions recalculées).
- Erreurs : `401` (session absente, expirée ou révoquée → le frontend affiche « session expirée »), `403` (compte non autorisé).

#### Réponse de `POST /auth/switch-business` (validé — lot 3, R8)
```json
{ "accessToken": "…", "expiresIn": 900 }
```
Le refresh token tourne via le cookie sécurisé (§1.1). Le `businessId` envoyé n'est qu'une demande : il ne choisit jamais le tenant sans vérification de l'appartenance active. Côté frontend (ADR‑04) : nouveau jeton en mémoire, cache TanStack Query entièrement réinitialisé, puis rechargement de `/me` ; en cas d'échec, ni le jeton ni le cache ne sont modifiés.

### 2.2 Entreprise et équipe (`business`)
| Méthode | Route | Rôles |
|---|---|---|
| GET | `/business` | O,M,E (profil ; pour E, uniquement les informations nécessaires à la saisie d'une vente : devise, TVA activée, mode HT/TTC). MVP-1 : `{ id, name, legalName, currency, timezone, defaultLocale }` de l'entreprise ACTIVE (jamais d'identifiant d'entreprise en entrée), permission `BUSINESS_READ` |
| PUT | `/business/profile` | O,M (nom, contacts, logo, langue par défaut) |
| GET | `/business/settings` | O,M (paramètres financiers et sensibles ; M en lecture seule — D20). **MVP-3 : `{ "allowNegativeStock": false }`** (seul paramètre implémenté ; `BUSINESS_SETTINGS_READ`) |
| PUT | `/business/settings` | O (TVA, mode HT/TTC, taux, stock négatif, devise — devise non modifiable après la première transaction — D20). **MVP-3 : `{ "allowNegativeStock": true|false }`** (`BUSINESS_SETTINGS_UPDATE`, OWNER seul ; journalisé `SETTINGS_CHANGED` uniquement si la valeur change ; désactivé par défaut) |
| GET | `/business/sales-policy` | O,M (limite de remise des employés) |
| PUT | `/business/sales-policy` | O,M (`{ "employeeMaxDiscountRate": "10.00" }` — forme provisoire, O11 ; modification journalisée — D14) |
| GET | `/business/members` | O |
| POST | `/business/invitations` | O (canal d'envoi : O13) |
| PUT | `/business/members/{membershipId}/role` | O (révoque les sessions du membre concerné) |
| POST | `/business/members/{membershipId}/revoke` | O |
| GET | `/business/ai-consent` | O (état, date, auteur, version des conditions) |
| PUT | `/business/ai-consent` | O (`{ "enabled": true, "termsVersion": "…" }` pour consentir, `{ "enabled": false }` pour **révoquer à tout moment** ; effet immédiat, journalisé — D15) |
| GET | `/business/support-access-grants` | O |
| POST | `/business/support-access-grants/{id}/approve` | O — **approbation explicite du propriétaire** (D12) : `{ "grantedScope": ["SALES", "CATALOG"], "durationHours": 2, "accessMode": "READ_ONLY" }` ; périmètre ⊆ périmètre demandé, durée ≤ durée demandée et ≤ plafond ; `READ_WRITE` uniquement sur choix explicite |
| POST | `/business/support-access-grants/{id}/reject`, `/revoke` | O (révocation possible à tout moment, effet immédiat) |
| GET | `/business/audit-logs?supportOnly=true` | O (consultation des actions réalisées sous accès support) |

### 2.3 GESTION
| Méthode | Route | Rôles |
|---|---|---|
| GET / POST | `/categories` | GET O,M,E · POST O,M |
| PUT / DELETE | `/categories/{id}` | O,M (DELETE = archivage) |
| GET / POST | `/products` | GET O,M,E · POST O,M |
| GET / PUT / DELETE | `/products/{id}` | GET O,M,E · PUT/DELETE O,M |
| GET | `/stock`, `/stock/summary`, `/stock/alerts`, `/stock/movements`, `/stock/{productId}`, `/stock/{productId}/movements` | O,M,E (`STOCK_READ` ; valeur estimée réservée à `COST_READ` : O,M) — contrat MVP-3 ci‑dessous |
| POST | `/stock/{productId}/initialize`, `/entries`, `/exits`, `/adjustments` | O,M (`STOCK_ADJUST`, `Idempotency-Key` obligatoire, motif obligatoire sauf initialisation) |
| PUT | `/stock/{productId}/minimum-quantity` | O,M (`STOCK_ADJUST`) |
| GET / POST | `/purchases` | O,M |
| GET | `/purchases/{id}` | O,M |
| POST | `/purchases/{id}/receive` | O,M (entrée en stock + recalcul du CMP) || POST | `/purchases/{id}/cancel` | O,M (si reçu : mouvement compensatoire de sortie, soumis au contrôle de stock négatif) |
| GET / POST | `/customers` | O,M,E (POST : `Idempotency-Key` facultatif, obligatoire depuis le mode hors ligne) |
| GET / PUT | `/customers/{id}` | GET O,M,E · PUT O,M,E |
| DELETE | `/customers/{id}` | O,M (archivage) |
| GET | `/customers/{id}/sales` | O,M,E (E : sans coûts ni marges) |
| GET | `/customers/{id}/balance` | O,M (`CUSTOMER_BALANCE_READ` : créance et avoirs disponibles présentés séparément) |
| GET | `/customers/{id}/credits` | O,M (`CUSTOMER_BALANCE_READ` : avoirs du client — numéro, montant, solde restant, origine) |
| GET / POST | `/suppliers` | O,M |
| GET / PUT / DELETE | `/suppliers/{id}` | O,M |

#### Catalogue — contrat implémenté (MVP-2, 2026-11-19)
- `GET /categories?status=ACTIVE|ARCHIVED|ALL&page&size&sort=name|createdAt,asc|desc` (défaut `ACTIVE`, tri `name,asc` sans tenir compte de la casse) → page de `{ id, name, description, status, activeProductCount, createdAt, updatedAt, version }`. `activeProductCount` est calculé en base.
- `GET /categories/{id}` ; `POST /categories` `{ name, description }` → `201` + `Location` ; `PUT /categories/{id}` `{ name, description, version }` ; `DELETE /categories/{id}` = archivage, `204`, idempotent.
- `GET /products?q&categoryId&status&page&size&sort=name|createdAt|salePrice,asc|desc` : `q` (≤ 100 caractères) cherche dans le nom, le SKU et le code-barres, sans tenir compte de la casse, caractères `%` / `_` pris littéralement ; `size` 1 à 100 (défaut 20) ; tri sur liste blanche (tout autre champ → `400`).
- Produit : `{ id, name, description, sku, barcode, unit, category: { id, name, status } | null, purchasePrice?, salePrice, currency, status, createdAt, updatedAt, version }`. `unit` ∈ `PIECE, UNIT, KILOGRAM, GRAM, LITRE, MILLILITRE, METRE, PACK, CARTON, LOT`. Montants en chaînes : `salePrice` ET `purchasePrice` au plus avec les décimales de la devise (ISO 4217 : XOF 0 → `"3500"`, EUR 2), refusés sinon (`400 INVALID_VALUE`), jamais arrondis — décision de la porteuse du projet du 2026-11-19. Un prix d'achat enregistré avant cette règle (ex. `1800.5` en XOF) est renvoyé tel quel. **`purchasePrice` est absent de la réponse sans `COST_READ`** (et ignoré à l'écriture). C'est un prix d'achat de RÉFÉRENCE saisi, distinct du coût moyen pondéré (D1, module de stock).
- `POST /products`, `PUT /products/{id}` (avec `version`), `DELETE /products/{id}` = archivage. Archivage par `DELETE` conformément à la convention « DELETE uniquement pour archivage de référentiels » (§1).
- Erreurs : `400 VALIDATION_ERROR` avec `errors[{field, code}]` — `REQUIRED`, `TOO_LONG`, `INVALID_FORMAT` (SKU, code-barres, nombre illisible, unité inconnue), `INVALID_VALUE` (prix négatif ou trop de décimales — jamais arrondi), `ALREADY_EXISTS` (nom de catégorie, SKU, code-barres parmi les éléments ACTIFS de l'entreprise), **`ARCHIVED`** (nouveau code de champ : catégorie archivée choisie pour un produit) ; `404 NOT_FOUND` (ressource ou catégorie référencée inexistante ou d'une autre entreprise) ; `409 OPTIMISTIC_LOCK` (version périmée) ; `422 INVALID_STATE_TRANSITION` (modifier un élément archivé) ; `403` sans `CATALOG_WRITE` ou sans entreprise active.
- **MVP-3** : `PUT /products/{id}` refuse le changement d'unité dès que le stock du produit est initialisé (`400 VALIDATION_ERROR`, `unit` = `LOCKED`) : les quantités existantes perdraient leur sens.

#### Ventes, tableau de bord, assistante — contrat implémenté (2026-11-19)
- `GET /sales?status=ALL|DRAFT|CONFIRMED|CANCELLED&customerId&page&size` (`SALE_READ_ALL`) ; `GET /sales/{id}` → vente (`quote` figé si confirmée, recalculé aux prix actuels si brouillon ; `unitCost`, `grossMargin`, `costsComplete` uniquement avec `COST_READ`).
- `POST /sales/quote` (`SALE_CREATE`) `{ customerId?, lines: [{ productId, quantity, discount? }], globalDiscount? }` → récapitulatif calculé par le serveur, sans enregistrement (`discountWithinLimit`).
- `POST /sales` (`Idempotency-Key` obligatoire) → brouillon `201` ; `PUT /sales/{id}` (`version`) ; `POST /sales/{id}/confirm` (`Idempotency-Key` obligatoire) → `200` (rejeu : `Idempotent-Replay: true`) ; `POST /sales/{id}/cancel` : abandon d'un BROUILLON uniquement.
- Erreurs : `404` (vente, produit ou client d'une autre entreprise), `409 INSUFFICIENT_STOCK`, `409 SALE_ALREADY_CONFIRMED`, `409 IDEMPOTENCY_KEY_REUSED`, `422 DISCOUNT_LIMIT_EXCEEDED` (journalisé), `422 PRODUCT_ARCHIVED`, `422 STOCK_NOT_INITIALIZED`, `422 INVALID_STATE_TRANSITION` (client archivé), `400` (`lines[i].quantity`, `lines[i].discount`, `globalDiscount`).
- `GET /dashboard` (`DASHBOARD_FULL` ou `DASHBOARD_OWN_DAY`) : `today`/`month` (montants, `null` sans `DASHBOARD_FULL`), compteurs, dernières ventes, `paymentsConnected: false`.
- `GET/PUT /business/settings` : ajout de `vatEnabled`, `vatRate`, `priceEntryMode` (`HT`|`TTC`) — modification OWNER seul ; `GET/PUT /business/sales-policy` `{ employeeMaxDiscountRate }` (`SALES_POLICY_MANAGE`) ; `GET/PUT /business/ai-consent` (`AI_CONSENT_MANAGE`).
- `GET /assistant/status`, `POST /assistant/messages` `{ question (≤ 1000), history? (≤ 12 tours) }` (`AI_USE` : OWNER, MANAGER) → `{ answer, insufficientData, proposal? }` ; erreurs `503 AI_DISABLED`, `503 AI_NOT_CONFIGURED` (nouveau), `403 AI_NOT_ENABLED`, `429 AI_QUOTA_EXCEEDED`, `503 AI_UNAVAILABLE`, `502 AI_INVALID_OUTPUT`.
- `POST /stock/{productId}/initialize` : la quantité initiale est STRICTEMENT positive (comportement existant ; la mention « ≥ 0 » ci-dessous est corrigée).

#### Clients — contrat implémenté (MVP-4A, 2026-11-19)
Permissions existantes (matrice D3, aucune nouvelle) : `CUSTOMER_READ` (O,M,E), `CUSTOMER_WRITE` (O,M,E — création et modification, utiles à la vente au comptoir), `CUSTOMER_ARCHIVE` (O,M). Le `businessId` vient uniquement du jeton ; un client d'une autre entreprise → `404`.
- `GET /customers?q&status&page&size&sort` : `status` ∈ `ACTIVE` (défaut), `ARCHIVED`, `ALL` ; `q` (≤ 100 caractères) cherche dans le nom et l'e-mail (casse ignorée, `%`/`_` littéraux) et, s'il contient au moins 3 chiffres, dans les CHIFFRES du téléphone (mise en forme ignorée ; préfixe `00` = `+`) ; `sort` ∈ `name`, `createdAt`, `updatedAt` (liste blanche ; défaut `name,asc`, casse ignorée) ; `size` 1 à 100.
- Client : `{ id, name, phone, email, address, notes, status, archivedAt, createdAt, updatedAt, version }`.
- `POST /customers` `{ name?, phone?, email?, address?, notes? }` → `201` + `Location`. **Au moins un nom ou un téléphone** (`name` = `REQUIRED` sinon). `Idempotency-Key` (UUID) facultatif : rejeu identique → `200` + `Idempotent-Replay: true` (client existant) ; même clé, autre contenu → `409 IDEMPOTENCY_KEY_REUSED` (unicité durable, `UNIQUE (business_id, idempotency_key)`).
- `GET /customers/{id}` ; `PUT /customers/{id}` `{ …, version }` (`version` obligatoire ; périmée → `409 OPTIMISTIC_LOCK`) ; `DELETE /customers/{id}` = archivage, `204`.
- Règles : nom ≤ 150 (espaces superflus supprimés) ; téléphone ≤ 30 caractères, chiffres, espaces, `( ) . -` et un seul `+`, de 6 à 15 chiffres, conservé tel que saisi (`INVALID_FORMAT` sinon) ; e-mail ≤ 254, format simple, enregistré en minuscules ; adresse ≤ 500 ; notes ≤ 2000. **Aucune unicité** sur le nom ni le téléphone (homonymes, numéros partagés).
- Erreurs : `400 VALIDATION_ERROR` (`REQUIRED`, `TOO_LONG`, `INVALID_FORMAT`) ; `404 NOT_FOUND` ; `409 OPTIMISTIC_LOCK` ; `409 ALREADY_ARCHIVED` (nouveau code : client déjà archivé) ; `422 INVALID_STATE_TRANSITION` (modifier un client archivé) ; `403` sans permission.
- Audit : `CUSTOMER_CREATED`, `CUSTOMER_UPDATED`, `CUSTOMER_ARCHIVED` (cible `CUSTOMER` + identifiant) — aucune donnée personnelle ; un rejeu idempotent n'est pas journalisé.
- Non implémentés (lots suivants) : historique des ventes du client, solde et avoirs (`CUSTOMER_BALANCE_READ`), désarchivage.

#### Stock — contrat implémenté (MVP-3, 2026-11-19)
Remplace les routes provisoires `/products/{id}/stock-movements` et `/stock-adjustments` de la conception initiale. Le `businessId` vient uniquement du jeton ; un produit d'une autre entreprise → `404`.
- **Lecture** (`STOCK_READ` : O,M,E) :
  - `GET /stock?q&categoryId&status&page&size&sort` : produits **actifs** avec leur stock (y compris non initialisés). `status` ∈ `ALL` (défaut), `TRACKED`, `ALERT` (faible ou rupture), `IN_STOCK`, `LOW_STOCK`, `OUT_OF_STOCK`, `NOT_INITIALIZED` ; `sort` ∈ `name`, `quantity`, `updatedAt` (`asc|desc`, liste blanche) ; `q` cherche nom et SKU (≤ 100 caractères, `%`/`_` littéraux).
  - `GET /stock/alerts` : produits `LOW_STOCK` ou `OUT_OF_STOCK` (paginé).
  - `GET /stock/summary` → `{ trackedProducts, notInitializedProducts, inStock, lowStock, outOfStock, estimatedValue? }`. `estimatedValue` = `{ amount, currency, productsWithoutReferencePrice }`, **présent uniquement avec `COST_READ`** : somme, sur les produits à quantité positive, de quantité × prix d'achat **de référence** du catalogue, arrondie ligne par ligne (HALF_UP, décimales de la devise). C'est une **estimation**, ni le coût réel ni une valeur comptable (le CMP n'existe pas encore).
  - `GET /stock/{productId}` → `{ productId, productName, sku, category, unit, productStatus, initialized, quantity, minimumQuantity, status, updatedAt }` (produit archivé consultable ; `quantity` et `minimumQuantity` `null` si non initialisé).
  - `GET /stock/movements?page&size` (toute l'entreprise) et `GET /stock/{productId}/movements` : du plus récent au plus ancien, `{ id, productId, productName, unit, type, quantity, quantityBefore, quantityAfter, reason, reference, unitCost?, author: { id, fullName }, createdAt }` — **`unitCost` absent sans `COST_READ`** (retiré par le serveur, y compris dans la réponse d'une opération) et pour un mouvement sans coût.
- **Écriture** (`STOCK_ADJUST` : O,M ; en‑tête `Idempotency-Key` UUID **obligatoire** ; réponse `201` `{ movement, stock }`, ou `200` + `Idempotent-Replay: true` pour un rejeu identique) :
  - `POST /stock/{productId}/initialize` `{ quantity, minimumQuantity?, note? }` — une seule fois par produit (`409 STOCK_ALREADY_INITIALIZED` ensuite) ; `quantity` > 0, mouvement `INITIAL`.
  - `POST /stock/{productId}/entries` `{ quantity, reason, reference?, unitCost? }` — mouvement `ENTRY`. **`unitCost`** (finalisation MVP-3, V5) : coût unitaire d'acquisition facultatif, chaîne > 0 avec au plus les décimales de la devise (XOF 0, EUR/USD 2), jamais arrondi (`400`, champ `unitCost` = `INVALID_VALUE`) ; réservé à `COST_READ` (`403` sinon) ; ne modifie jamais le prix d'achat de référence du produit ; immuable une fois enregistré ; aucun coût moyen pondéré n'est calculé.
  - `POST /stock/{productId}/exits` `{ quantity, reason, reference? }` — mouvement `EXIT` (`unitCost` refusé : `400`). Refus pour stock insuffisant **journalisé** (`STOCK_EXIT_REJECTED_INSUFFICIENT`, voir ci‑dessous).
  - `POST /stock/{productId}/adjustments` `{ direction: IN|OUT, quantity, reason }` — `ADJUSTMENT_IN` / `ADJUSTMENT_OUT` (`unitCost` refusé : `400`).
  - `PUT /stock/{productId}/minimum-quantity` `{ minimumQuantity }` (≥ 0) → stock ; journalisé si la valeur change (pas d'idempotence nécessaire : opération idempotente par nature).
- **Quantités** : chaînes décimales strictement positives (`initialize` : ≥ 0), décimales selon l'unité — `PIECE, UNIT, PACK, CARTON, LOT` : 0 ; `KILOGRAM, GRAM, LITRE, MILLILITRE, METRE` : 3 — au plus 15 chiffres entiers ; jamais arrondies (`400`, `INVALID_VALUE`). `reason` ≤ 255, `reference` ≤ 100, `note` ≤ 255.
- **Statut** : `OUT_OF_STOCK` si quantité ≤ 0 ; `LOW_STOCK` si quantité ≤ seuil minimal ; sinon `IN_STOCK` ; `NOT_INITIALIZED` sans stock.
- **Stock négatif** : seuls les mouvements qui **diminuent** le stock sont contrôlés ; si la nouvelle quantité serait < 0 et que `allowNegativeStock` est faux (relu dans la transaction, sous verrou) → `409 INSUFFICIENT_STOCK`. Une entrée n'est jamais refusée, même sur un stock négatif.
- **Concurrence et idempotence** : verrou `SELECT … FOR UPDATE` sur la ligne de stock (sur le produit pour l'initialisation) ; la clé est conservée **sur le mouvement** (`UNIQUE (business_id, idempotency_key)`, sans expiration) avec l'empreinte SHA‑256 de la requête (produit, type, quantité, seuil initial, motif ou note, référence) : même clé + même contenu → mouvement existant renvoyé ; même clé + contenu différent → `409 IDEMPOTENCY_KEY_REUSED`. Clé absente ou non UUID → `400`.
- **Erreurs** : `404` (produit inexistant ou d'une autre entreprise), `422 PRODUCT_ARCHIVED` (opération sur un produit archivé), `422 STOCK_NOT_INITIALIZED` (mouvement ou seuil avant initialisation), `409 INSUFFICIENT_STOCK`, `409 STOCK_ALREADY_INITIALIZED`, `409 IDEMPOTENCY_KEY_REUSED`, `403` sans permission.
- **Historique immuable** : aucune route de modification ni de suppression ; une erreur se corrige par un ajustement motivé.
- **Audit des sorties refusées** (finalisation MVP-3) : une sortie (`EXIT`) refusée pour stock insuffisant, par une utilisatrice authentifiée et autorisée, crée un événement `STOCK_EXIT_REJECTED_INSUFFICIENT` (résultat `DENIED`, entreprise, auteur, cible `PRODUCT` + identifiant, détail `REASON = INSUFFICIENT_STOCK`, date) — ni quantité, ni texte saisi, ni donnée de session ; aucun mouvement, quantité inchangée. Les refus antérieurs au contrôle (`401`, `403`, `404`, validation) ne sont pas journalisés ; un ajustement à la baisse refusé ne l'est pas non plus (hors périmètre demandé).
- **Empreinte d'idempotence** : inclut `unitCost` lorsqu'il est renseigné (même clé + autre coût → `409 IDEMPOTENCY_KEY_REUSED`) ; inchangée pour un mouvement sans coût (rejeux des mouvements antérieurs reconnus).

### 2.4 COMMERCE
| Méthode | Route | Rôles | Notes |
|---|---|---|---|
| GET | `/sales` | O,M,E | Ventes de l'entreprise ; E : toutes les ventes, sans coûts, marges ni données financières sensibles (D14) |
| POST | `/sales` | O,M,E | Crée en `DRAFT` ou directement `CONFIRMED` (`"confirm": true`) avec paiement initial optionnel ; `X-Expected-Business-Id` obligatoire lors d'un rejeu hors ligne |
| GET | `/sales/{id}` | O,M,E | E : sans coûts ni marges |
| PUT | `/sales/{id}` | O,M,E | Uniquement en `DRAFT` (mêmes contrôles de prix et de remise qu'à la confirmation) |
| POST | `/sales/{id}/confirm` | O,M,E | Transaction atomique : contrôle des prix et des remises, fige prix, remises, TVA et CMP, sort le stock (refus si stock insuffisant et stock négatif non autorisé), attribue le numéro |
| POST | `/sales/{id}/cancel` | O,M | Annulation totale (D11, D13), voir ci‑dessous ; brouillon : sans effet sur le stock, la trésorerie ni les indicateurs |
| POST | `/sales/{id}/payments` | O,M,E | Paiement partiel ou total ; règlement par avoir (`"method": "CUSTOMER_CREDIT"`, `"customerCreditId"`) réservé à `CUSTOMER_CREDIT_USE` : O,M — E refusé à titre provisoire (O7) |
| POST | `/sales/{id}/payments/{paymentId}/void` | O,M | Contre‑passation d'une erreur de saisie, datée du jour de la contre‑passation (D13) ; pour un règlement par avoir, le solde de l'avoir est rétabli |
| POST | `/sales/{id}/refunds` | O,M | Remboursement (sortie de trésorerie datée du jour) |
| GET | `/sales/{id}/receipt` | O,M,E | JSON du reçu ; `?format=pdf` pour le PDF |
| GET | `/payments` | O,M | Journal des encaissements |
| GET | `/customer-credits` | O,M | Liste des avoirs (filtre statut, client) — `CUSTOMER_BALANCE_READ` |
| GET | `/customer-credits/{id}` | O,M | Détail d'un avoir et de ses mouvements — `CUSTOMER_BALANCE_READ` ; l'employé ne consulte ni les avoirs ni les soldes clients |

**Remises (D14)** : OWNER et MANAGER appliquent des remises sans plafond. Un EMPLOYEE peut appliquer une remise uniquement dans la limite `employeeMaxDiscountRate` de l'entreprise (forme provisoire : pourcentage de la remise totale — lignes + remise globale — rapporté au montant brut de la vente, O11). Contrôle **côté serveur** à l'enregistrement du brouillon et à la confirmation ; dépassement → `422 DISCOUNT_LIMIT_EXCEEDED` ; remise acceptée et tentative refusée sont journalisées. Un `unitPrice` différent du prix catalogue exige `SALE_PRICE_OVERRIDE` (OWNER, MANAGER), sinon `403 FORBIDDEN`.

**Annulation d'une vente confirmée** (`POST /sales/{id}/cancel`, `Idempotency-Key`) :
```json
{
  "reason": "Erreur de commande",
  "lines": [
    { "saleLineId": "…", "restock": true },
    { "saleLineId": "…", "restock": false }
  ],
  "settlement": {
    "refunds": [ { "amount": "3000.00", "method": "CASH", "treasuryAccountId": "…" } ],
    "creditAmount": "2000.00"
  }
}
```
- `lines` : une entrée par ligne suivie en stock ; `restock: true` uniquement si le produit retourné est revendable (mouvement `SALE_CANCEL_IN` au coût figé) ; `false` : aucun mouvement de stock (traitement comptable : O8).
- `settlement` : restitution du montant payé par remboursement total ou partiel (sortie de trésorerie) et/ou avoir (aucun mouvement de trésorerie, suivi séparé). Règle **provisoire** (O7) : Σ remboursements + `creditAmount` = montant payé, sinon `422 CANCELLATION_SETTLEMENT_MISMATCH`. Un avoir exige un client identifié sur la vente, sinon `422 CUSTOMER_REQUIRED_FOR_CREDIT`.
- La date d'annulation est fixée par le serveur (date du jour, fuseau de l'entreprise) : l'annulation est imputée à cette période, la période de la vente reste inchangée (D13).
- Réponse : la vente annulée, ses remboursements et, le cas échéant, l'avoir créé (`number`, `amount`).

Exemple — création d'une vente confirmée avec paiement partiel :
```http
POST /api/v1/sales
Idempotency-Key: 5d0c6f0e-3b1a-4c7e-9f60-2a1d8e7b9c11

{
  "customerId": "a3f1...",
  "channel": "WHATSAPP",
  "saleDate": "2026-11-18",
  "lines": [
    { "productId": "7b2e...", "quantity": "2", "unitPrice": "7500.00", "discountAmount": "0.00" }
  ],
  "discountAmount": "500.00",
  "confirm": true,
  "initialPayment": { "amount": "5000.00", "method": "WAVE", "treasuryAccountId": "c9d4...", "externalReference": "WV-88123" }
}
```
Réponse `201` : la vente avec `number`, `totalAmount: "14500.00"`, `amountPaid: "5000.00"`, `balanceDue: "9500.00"`, `paymentStatus: "PARTIALLY_PAID"`.
Dans cet exemple, la TVA est désactivée (`netAmount = totalAmount`, `taxAmount = "0.00"`). La remise globale de 500 (3,3 % du brut) est acceptée sans condition pour OWNER et MANAGER ; pour un EMPLOYEE, seulement si elle respecte la limite configurée (voir « Remises » ci‑dessus).

### 2.5 FINANCE
| Méthode | Route | Rôles |
|---|---|---|
| GET / POST | `/expense-categories` | O,M |
| GET / POST | `/expenses` | O,M |
| GET / PUT | `/expenses/{id}` | O,M |
| POST | `/expenses/{id}/pay`, `/expenses/{id}/cancel` | O,M (annulation datée du jour, imputée à sa propre période — D13) |
| GET / POST | `/treasury-accounts` | O,M |
| GET | `/treasury-accounts/{id}/movements` | O,M |
| POST | `/treasury-transfers` | O,M |
| POST | `/cash-movements` | O (apport / retrait du propriétaire, ajustement) |
| GET | `/finance/summary?from=&to=` | O,M |

`GET /finance/summary` — toutes les valeurs sont calculées selon les définitions normatives du cahier des charges (§3), avec imputation des corrections dans la période où elles interviennent (D13) :
```json
{
  "period": { "from": "2026-11-01", "to": "2026-11-30" },
  "currency": "XOF",
  "grossRevenue": "1300000.00",
  "salesCancellations": "-50000.00",
  "revenue": "1250000.00",
  "collections": "980000.00",
  "expenses": "310000.00",
  "costOfGoodsSoldGross": "750000.00",
  "costOfGoodsSoldRestocked": "-30000.00",
  "costOfGoodsSold": "720000.00",
  "grossMargin": "530000.00",
  "grossMarginRate": "42.40",
  "netResult": "220000.00",
  "netResultLabel": "Résultat net (estimation de gestion)",
  "availableCash": "1430000.00",
  "receivables": "270000.00",
  "customerCreditsOutstanding": "15000.00",
  "dataQuality": { "productsWithoutCost": 3, "salesWithUnknownCost": 5, "backdatedEntries": 0 }
}
```
`revenue` = ventes nettes ajustées ; `collections` exclut les conversions en avoir et les règlements par avoir ; `costOfGoodsSoldRestocked` ne couvre que les lignes réintégrées (les marchandises non revendables relèvent d'O8) ; `customerCreditsOutstanding` est présenté séparément des créances.

### 2.6 Tableau de bord et rapports
| Méthode | Route | Rôles |
|---|---|---|
| GET | `/dashboard?from=&to=` | O,M (ventes brutes, annulations, ventes nettes ajustées — D13 ; avoirs en cours) |
| GET | `/dashboard/my-day` | O,M,E (nombre et montant de ses propres ventes du jour, sans coûts ni marges) |
| GET | `/dashboard/timeseries?metric=revenue&granularity=DAY&from=&to=` | O,M |
| GET | `/dashboard/top-products`, `/dashboard/top-customers` | O,M |
| GET | `/dashboard/alerts` | O,M |
| GET | `/reports/{reportType}?from=&to=&format=pdf\|csv` | O,M |

`reportType` : `sales` (ventes brutes, annulations / ajustements, ventes nettes ajustées), `stock`, `expenses`, `financial-summary`, `receivables`, `customer-credits`. Un rapport d'une période passée, régénéré plus tard, donne le même résultat, sauf saisie antidatée (O9) — signalée dans le rapport. Libellés des rapports en français (D16).

### 2.7 INTELLIGENCE
| Méthode | Route | Rôles |
|---|---|---|
| POST | `/intelligence/analyses` | O,M |
| GET | `/intelligence/analyses` | O,M |
| GET | `/intelligence/analyses/{id}` | O,M |
| GET | `/intelligence/usage` | O,M (quota restant du mois) |

Requête :
```json
{ "type": "PERFORMANCE_OVERVIEW", "from": "2026-11-01", "to": "2026-11-30" }
```
Réponse `201` :
```json
{
  "id": "…",
  "type": "PERFORMANCE_OVERVIEW",
  "status": "SUCCEEDED",
  "summary": "…",
  "insights": [ { "title": "…", "explanation": "…", "metricRefs": ["revenue", "grossMarginRate"] } ],
  "risks": [ { "severity": "HIGH", "title": "Rupture probable", "explanation": "…", "productRef": "P3" } ],
  "recommendations": [ { "priority": 1, "action": "…", "rationale": "…" } ],
  "dataLimitations": [ "Coût d'achat inconnu pour 3 produits : la marge est sous‑estimée." ],
  "generatedAt": "2026-11-18T14:05:00Z",
  "disclaimer": "Analyse générée par IA à partir de vos données. Vérifiez avant toute décision importante."
}
```
Préconditions (D15, D18) : IA activée globalement (`AI_ENABLED`, et `AI_PRODUCTION_APPROVED` en production, positionné uniquement après les validations juridiques et réglementaires — CDP, transferts internationaux — **non obtenues à ce jour**) et consentement explicite du propriétaire en vigueur (non révoqué). Seul un instantané minimal, agrégé et pseudonymisé est transmis ; réponses en français.

Erreurs spécifiques : `403 AI_NOT_ENABLED` (consentement de l'entreprise absent ou révoqué), `503 AI_DISABLED` (interrupteur global ou validation de production absente), `429 AI_QUOTA_EXCEEDED` (quota ou plafond de coût de l'entreprise, ou plafond global), `503 AI_UNAVAILABLE`, `422 AI_INSUFFICIENT_DATA` (si l'instantané est vide, sans appel au modèle), `502 AI_INVALID_OUTPUT` (réponse rejetée par la validation du backend).
La réponse ne contient jamais de nom ni de téléphone de client : les références sont des alias (`P3`, `C12`) que le frontend résout localement à partir des données de l'entreprise.

### 2.8 COMMERCE DIGITAL
Privé :
| Méthode | Route | Rôles |
|---|---|---|
| GET / PUT | `/storefront` | O,M |
| GET | `/order-requests` | O,M,E |
| POST | `/order-requests/{id}/convert` | O,M,E → crée une vente `DRAFT` |
| POST | `/order-requests/{id}/status` | O,M,E |

Public (sans authentification, limité en débit) :
| Méthode | Route |
|---|---|
| GET | `/public/storefronts/{slug}` |
| GET | `/public/storefronts/{slug}/products?page=&q=&categoryId=` |
| POST | `/public/storefronts/{slug}/order-requests` (Idempotency-Key, honeypot) |

### 2.9 Administration plateforme
| Méthode | Route | Rôles |
|---|---|---|
| GET | `/platform/businesses` | A |
| GET | `/platform/businesses/{id}` | A (métadonnées uniquement : nom, statut, formule, compteurs) |
| POST | `/platform/businesses/{id}/suspend`, `/reactivate` | A |
| PUT | `/platform/businesses/{id}/plan` | A |
| GET / POST / PUT | `/platform/plans` | A |
| PUT | `/platform/businesses/{id}/limits` | A (dérogations de quotas et plafonds) |
| GET | `/platform/ai-usage` | A (coûts agrégés par entreprise et par mois) |
| GET | `/platform/audit-logs` | A |
| POST | `/platform/businesses/{id}/support-access-requests` | A (`{ "reason", "requestedScope": ["SALES"], "durationHours", "accessMode": "READ_ONLY" }` — justification obligatoire) |
| POST | `/platform/support-access-grants/{id}/session` | A — émet un jeton de support de courte durée uniquement si l'octroi a été **approuvé par le propriétaire**, n'est ni expiré ni révoqué |

Aucune route `/platform/*` ne renvoie de données métier (ventes, clients, produits…). Sous jeton de support (D12), l'administrateur n'accède qu'aux routes de l'entreprise concernée relevant du **périmètre accordé**, en **lecture seule** sauf écriture explicitement accordée par le propriétaire, pendant la durée de l'octroi ; toute autre route → `403 SUPPORT_SCOPE_DENIED` ; chaque requête est journalisée et consultable par le propriétaire ; la révocation par le propriétaire prend effet à la requête suivante.

### 2.10 Exploitation
| Route | Accès |
|---|---|
| `/actuator/health/liveness`, `/actuator/health/readiness` | public |
| `/actuator/info`, `/actuator/metrics`, `/actuator/prometheus` | réseau interne uniquement |
| `/v3/api-docs`, `/swagger-ui` | `dev` uniquement |

### 2.11 Mode hors ligne partiel (D17 ; modalités ouvertes : O10)
| Méthode | Route | Rôles |
|---|---|---|
| GET | `/offline/snapshot` | O,M,E — instantané de consultation minimal, filtré selon les permissions, horodaté (`syncedAt`) : catalogue **sans coûts**, niveaux de stock indicatifs, clients actifs, ventes récentes ; jamais de coûts, marges, indicateurs financiers, paramètres sensibles, journaux ni données d'IA, quel que soit le rôle |

Il n'existe **pas** de route de synchronisation dédiée : les opérations saisies hors ligne sont rejouées, dans l'ordre de saisie, sur les **routes normales** (`POST /sales`, `POST /sales/{id}/payments`, `POST /customers`… selon la liste arrêtée en O10), avec :
- `Idempotency-Key` générée **au moment de la saisie** et réutilisée à chaque tentative (unicité durable, §1.4) ;
- `X-Expected-Business-Id` (entreprise de création), comparé au jeton (§1.7) ;
- `X-Client-Created-At` (horodatage de l'appareil, informatif, conservé dans `client_created_at`, jamais utilisé comme date de référence).

Le serveur applique **tous** les contrôles avec l'état courant (session, appartenance, rôle, permissions, prix, limite de remise, stock sous verrou, règles de date O9). Seule sa réponse valide l'opération (numéro, stock, indicateurs). Un refus métier (`INSUFFICIENT_STOCK`, `DISCOUNT_LIMIT_EXCEEDED`, `FORBIDDEN`, `404`…) place l'opération en conflit côté client, pour résolution explicite par un utilisateur autorisé ; aucune résolution automatique (politique détaillée : O10).

