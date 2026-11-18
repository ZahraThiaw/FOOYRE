# FOOYRE — Feuille de route de développement

Statut : **proposition**. Chaque phase se termine par une démonstration, des tests verts et une **validation explicite** avant la suivante. Les durées ne sont pas estimées ici (elles dépendent de l'équipe et des validations).

Définition de « terminé » commune à toutes les phases :
- code relu, conforme à `CLAUDE.md` ;
- tests unitaires et d'intégration verts (Testcontainers), suite d'isolation multi‑tenant étendue aux nouvelles routes ;
- migrations Flyway versionnées, jamais modifiées après fusion ;
- documentation (`docs/`) mise à jour ;
- aucun secret commité.

---

## Phase 0 — Conception et architecture ✅ consolidée le 2026-11-18
Livrables : cahier des charges, architecture, schéma de données, conventions API, feuille de route, `CLAUDE.md`, `README.md`, `.gitignore`, `.env.example`. Décisions **D1 à D20** intégrées (les anciens points O1, O3, O4, O5, O6 et hypothèses H8 à H11 sont tranchés : D11 à D19 ; D20 précise les paramètres sensibles). Points encore ouverts : O2 et O7 à O13 (`cahier-des-charges.md` §8.3).

Points ouverts à trancher **avant** la phase concernée (aucun n'est une décision ; les traitements provisoires documentés ne valent pas validation) :
| Point | À trancher avant |
|---|---|
| O13 (canal de réinitialisation du mot de passe et des invitations) | Phase 2 |
| O2 (validation comptable des règles provisoires de TVA et d'arrondi) | Phase 5 |
| O7 (modalités des avoirs), O8 (marchandises non revendables), O9 (saisie antidatée, clôture de période), O11 (forme de la limite de remise) | Phase 5 |
| O10 (modalités et calendrier du mode hors ligne) | Phase 12 proposée — **y compris l'ordonnancement de cette phase** |
| O12 (fournisseur et région d'hébergement) | Phase 13 proposée |
| Validations juridiques et réglementaires (`cahier-des-charges.md` §5.1 : loi n° 2008‑12, CDP, transferts internationaux) | Activation de l'IA en production (D15, D18) et mise en production |

## Phase 1 — Socle technique
- **Vérification préalable des versions** : confirmer Spring Boot 4.1.2 (ou le dernier correctif 4.1.x publié), relever les versions gérées de Flyway, JUnit et Testcontainers, vérifier la compatibilité d'ArchUnit, springdoc‑openapi et Bucket4j ; repli documenté sur 3.5.x en cas d'incompatibilité bloquante (`architecture.md` ADR‑12). Résultat consigné dans `architecture.md`.
- Squelette backend Maven (Java 21, Spring Boot 4.1.x, Security, JPA, Validation, `spring-boot-starter-flyway`, Actuator), structure des modules, `application.yml` par profils, validation des variables obligatoires au démarrage (refus de démarrer en `prod` si `AUTH_COOKIE_SECURE=false` ou secret d'exemple).
- Configuration Jackson 3 : sérialisation des montants `BigDecimal` en chaînes.
- **Internationalisation (D16, ADR‑13)** : `MessageSource` backend (`messages_fr.properties`), catalogues frontend `locales/fr`, choix justifié de la bibliothèque d'i18n, contrôle « aucun texte d'interface codé en dur » ; français seul.
- **Portabilité (D19, ADR‑15)** : uniquement des composants standard (conteneurs, PostgreSQL sans extension propriétaire) ; interface `FileStorage` (implémentation locale en développement).
- Squelette frontend (Vite, React, TypeScript, Tailwind, Router, TanStack Query, Axios), mise en page mobile‑first, client API.
- `docker-compose.yml` : PostgreSQL, backend, frontend/reverse proxy ; script d'init des rôles PostgreSQL (propriétaire / applicatif).
- Gestion d'erreurs Problem Details, identifiant de corrélation, logs structurés.
- Tests : ArchUnit (règles de modules, interdiction `float`/`double` monétaire), test de démarrage avec Testcontainers.
- CI (GitHub Actions ou équivalent) : build, tests, analyse de dépendances.
**Critère de sortie** : `docker compose up` démarre l'ensemble ; `/actuator/health` répond ; la page d'accueil s'affiche.

## Phase 2 — Identité, entreprises, rôles, isolation
- Inscription (compte + entreprise), connexion, JWT d'accès + refresh token en cookie `HttpOnly` avec rotation, fenêtre de tolérance et détection de réutilisation, déconnexion, « déconnecter tous les appareils », changement d'entreprise active sécurisé.
- Protection CSRF des routes à cookie (contrôle `Origin`, en‑tête personnalisé) ; révocation des sessions sur les événements de sécurité (ADR‑04).
- Frontend : jeton d'accès en mémoire, rafraîchissement unique coordonné entre onglets, vidage du cache au changement d'entreprise.
- Appartenances multi‑entreprises et rôles, invitations (canal : O13), **matrice de permissions** (test qui fige la matrice), paramètres sensibles (TVA, mode HT/TTC, stock négatif, devise) modifiables par le propriétaire seul et lisibles par le gestionnaire (D20), préférence de langue (`locale`).
- `TenantContext`, entité de base `TenantScopedEntity`, FK composites, **RLS PostgreSQL**.
- Journal d'audit, limitation de débit sur l'authentification.
- **Harnais de tests d'isolation / anti‑IDOR** réutilisable (deux entreprises, utilisateurs de chaque rôle, identifiants croisés dans les URL et les corps).
**Critère de sortie** : un utilisateur de A ne peut lire/modifier aucune donnée de B (tests automatisés) ; une session révoquée ou une appartenance retirée est refusée dès la requête suivante.

## Phase 3 — GESTION : catalogue et partenaires
- Catégories, produits (avec visibilité des coûts selon rôle), clients, fournisseurs.
- Recherche, pagination, archivage.
- Écrans frontend correspondants.

## Phase 4 — GESTION : stocks et approvisionnements
- Journal `stock_movement` (mouvements compensatoires uniquement pour les corrections), projection `product_stock` avec verrouillage.
- Stock initial, ajustements avec motif, approvisionnements (réception, annulation compensatoire), **recalcul du CMP** selon `cahier-des-charges.md` §3.1, alertes de stock bas.
- Option « stock négatif autorisé » par entreprise (désactivée par défaut) appliquée par le service de stock.
- Tests de concurrence (deux sorties simultanées sur le même produit) et tests du CMP (stock nul ou négatif, coût inconnu, annulation d'achat).

## Phase 5 — COMMERCE : ventes, paiements, annulations, avoirs, reçus
*Prérequis : arbitrages O2 (validation comptable de la TVA), O7, O8, O9, O11. Les décisions D11, D13 et D14 sont déjà validées.*
- Ventes (brouillon, confirmation atomique), photographie des prix, taux et coûts sur les lignes, numérotation séquentielle.
- **TVA HT/TTC** via `TaxCalculator` (règles du §3.2, tests dédiés) — règles **provisoires** tant qu'O2 n'est pas validé : ne pas les présenter comme définitives.
- **Remises** (D14) : sans plafond pour OWNER/MANAGER ; limite de l'employé configurable par OWNER/MANAGER (forme : O11), contrôlée par le serveur, journalisée.
- Refus des ventes dépassant le stock quand le stock négatif n'est pas autorisé.
- Paiements partiels, ventes à crédit, contre‑passation, remboursements, comptes de trésorerie minimum.
- **Annulation** (D11, D13) : choix revendable ligne par ligne, remboursement total ou partiel et/ou avoir, date d'annulation imputée à sa propre période.
- **Avoirs clients** : émission, journal, règlement par avoir sous verrou (modalités : O7).
- Consultation des ventes de l'entreprise par l'employé, sans coûts ni marges (D14).
- Reçus (écran + PDF), historique client, idempotence avec **unicité durable des clés** (préalable au mode hors ligne).
- Tests de concurrence (double confirmation, dernier article, paiements simultanés, double utilisation d'un avoir) et E2E Playwright sur le parcours de vente.

## Phase 6 — FINANCE
- Catégories de dépenses, dépenses payées / à payer, trésorerie complète (transferts, apports, retraits).
- `FinancialMetricsService` : implémentation des définitions normatives, avec **imputation des corrections dans la période où elles interviennent** (D13 : ventes brutes, annulations / ajustements, ventes nettes ajustées), batterie de tests par cas (vente annulée dans une autre période, remboursement, avoir, vente à crédit, produit sans coût, recalcul d'une période passée identique…).
- Endpoint `/finance/summary`.

## Phase 7 — TABLEAU DE BORD
- Indicateurs, séries temporelles, top produits/clients, alertes ; graphiques Recharts ; version restreinte pour EMPLOYEE.

## Phase 8 — RAPPORTS
- Exports CSV (protection contre l'injection de formules) et PDF (bibliothèque à choisir : OpenPDF ou équivalent sous licence compatible), libellés en français via les catalogues de messages.
- Rapports de ventes distinguant ventes brutes, annulations / ajustements et ventes nettes ajustées (D13) ; rapport des avoirs ; signalement des saisies antidatées (O9).

## Phase 9 — FOOYRE INTELLIGENCE
*Prérequis : revérification sur la documentation officielle d'Anthropic de l'identifiant `claude-opus-5-5`, de sa disponibilité, de ses tarifs et de la dernière version du SDK Java. (L'activation par entreprise est décidée : D15.)*
- Port `AiClient`, `FakeAiClient`, `AnthropicAiClient` (SDK Java officiel), modèle par défaut **Claude Opus 5.5** configurable par `ANTHROPIC_MODEL`.
- Construction de l'instantané analytique agrégé et pseudonymisé, invites versionnées, sortie structurée validée par le backend.
- Quotas et plafonds de coût **par entreprise** (formule + dérogations), suivi des jetons et coûts, plafond global, interrupteur `AI_ENABLED`, garde‑fou `AI_PRODUCTION_APPROVED`.
- **Consentement du propriétaire** (D15) : IA désactivée par défaut, activation par consentement explicite horodaté, **révocation à tout moment** avec effet immédiat, journalisation.
- Instantané limité au strict nécessaire, sans donnée personnelle de client (D18) ; réponses en français (D16).
- Interface « Assistant » : lancer une analyse, historique, mention d'avertissement.
- Tests exclusivement avec le faux client en CI ; essai manuel avec une vraie clé uniquement en local, sur données fictives.
- **L'IA n'est pas activée en production à l'issue de cette phase** : activation et transfert de données vers Anthropic seulement après les validations juridiques et réglementaires (`cahier-des-charges.md` §5.1 : CDP, transferts internationaux).

## Phase 10 — COMMERCE DIGITAL
- Paramétrage du catalogue public (slug, produits visibles), pages publiques optimisées mobile et partage WhatsApp.
- Demandes de commande (anti‑abus), conversion en vente.

## Phase 11 — Administration plateforme et formules
- Back‑office : entreprises, suspension, formules paramétrables et dérogations, consommation IA, journaux d'audit.
- **Accès support encadré** (D7, D12) : demande motivée avec périmètre et durée, **approbation explicite du propriétaire** (seule voie d'autorisation), réduction possible du périmètre et de la durée, lecture seule par défaut (écriture uniquement sur autorisation explicite du propriétaire), journalisation de chaque requête, consultation et **révocation à tout moment** par le propriétaire.
- Application des limites de formule (utilisateurs, produits, IA). Toujours **sans** paiement d'abonnement ; aucun tarif figé.

## Phase 12 — Mode hors ligne partiel (proposition)
> ⚠️ **Ordonnancement proposé, à confirmer au titre d'O10** (le calendrier — avant ou après le lancement — et les modalités du hors ligne ne sont pas arbitrés). Les fondations (idempotence durable, routes normales rejouables) sont posées dès la phase 5.

*Prérequis : arbitrage O10 (liste des opérations autorisées, durées de conservation et d'attente, justificatif provisoire, verrouillage local, politique de résolution des conflits).*
- PWA (*service worker*, mise en cache de l'application) ; instantané de consultation `GET /offline/snapshot` minimal et horodaté.
- File d'opérations en attente (IndexedDB), clé d'idempotence générée à la saisie, rejeu ordonné sur les routes normales, gestion des dépendances, contrôle `X-Expected-Business-Id`.
- Aucune validation définitive côté client ; conflits présentés pour résolution explicite ; aucune fusion automatique d'opérations financières.
- Cloisonnement des données locales par utilisateur et entreprise, aucun jeton stocké, expiration, effacement à la déconnexion (ADR‑14).
- Tests : rejeux multiples sans doublon (y compris après expiration de l'enregistrement d'idempotence), coupure pendant la requête, incohérence de tenant, droits retirés, prix ou stock modifiés, Playwright en réseau coupé.
**Critère de sortie** : aucune opération hors ligne n'est enregistrée deux fois ni dans une autre entreprise ; aucune n'apparaît comme validée avant la réponse du serveur.

## Phase 13 — Préparation à la production
*Prérequis : arbitrage O12 (fournisseur et région d'hébergement) ; validations juridiques et réglementaires (loi n° 2008‑12, CDP, transferts internationaux, conditions d'utilisation, durées de conservation) — aucune n'est obtenue à ce jour.*
- Durcissement : en‑têtes de sécurité, CSP, revue OWASP, tests de charge légers.
- **Hébergement portable (D19, ADR‑15)** : stockage de fichiers compatible S3, sauvegardes chiffrées, **restauration testée** sur un environnement vierge, procédure de réversibilité documentée (migration possible vers le Sénégal ou une autre juridiction appropriée).
- Observabilité (métriques, alertes), documentation d'exploitation.
- Procédure de déploiement — **le déploiement lui‑même nécessite une validation explicite**. L'IA reste désactivée en production tant que `AI_PRODUCTION_APPROVED` n'a pas été positionné après validations.

---

## Évolutions envisagées (post‑MVP, non engagées)
- Extension du mode hors ligne au‑delà du périmètre partiel (D17).
- Intégrations Wave / Orange Money (vérification de paiement).
- Paiement des abonnements.
- Assistant conversationnel avec outils de lecture contrôlés.
- Multi‑boutiques / multi‑entrepôts.
- Traductions en wolof, pulaar, sérère et autres langues nationales (architecture prévue dès la phase 1 ; formats régionaux et qualité de l'IA à évaluer — D16).
- Interface vocale : évaluation technique préalable, aucune prise en charge promise (D16).
- Envoi de reçus par WhatsApp / SMS.
- Retours partiels de marchandises (annulation partielle). Les avoirs sur annulation totale sont prévus en phase 5 (D11).
- TVA déductible sur achats.
