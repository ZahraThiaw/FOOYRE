# Déploiement de FOOYRE sur Render — guide de préparation

> Statut : **préparé, non déployé** (2026-11-20). Aucun service Render n'a été créé, rien n'a été
> publié. Ce document décrit ce qu'il faut faire, dans l'ordre, et ce qui reste à vérifier.

## 1. Architecture retenue

```
Navigateur ──HTTPS──> fooyre-web  (Render Static Site, CDN mondial, gratuit)
                         │  /*      -> index.html (application monopage)
                         │  /api/*  -> réécriture vers fooyre-api (même origine pour le navigateur)
                         ▼
                      fooyre-api  (Render Web Service, Docker, Java 21, Spring Boot 4.1.2, Francfort)
                         │  réseau interne Render (nom d'hôte interne, port 5432)
                         ▼
                      fooyre-db   (Render Postgres 18, Francfort, base dédiée et vierge)
```

**Pourquoi la « même origine » :** la sécurité de FOOYRE (ADR‑04) repose sur un cookie de
rafraîchissement `HttpOnly; Secure; SameSite=Strict; Path=/api/v1/auth`, un contrôle
`Origin` + `X-Requested-With: fooyre` sur `/auth/*`, et **aucune** autorisation cross-origin (pas
de CORS). Or `*.onrender.com` figure dans la Public Suffix List : `fooyre-web.onrender.com` et
`fooyre-api.onrender.com` sont deux **sites** différents, et un cookie `SameSite=Strict` ne serait
jamais envoyé de l'un à l'autre. La règle de réécriture du Static Site fait apparaître l'API sous
l'origine du frontend : rien n'est désactivé, rien n'est assoupli.

## 2. Fichiers préparés

| Fichier | Rôle |
|---|---|
| `render.yaml` | Blueprint : 2 services + 1 base, aucun secret (valeurs générées, saisies ou fournies par la base) |
| `backend/Dockerfile` | Image de production en 2 étapes (JDK 21 → JRE 21), utilisateur non-root, mémoire bornée |
| `backend/.dockerignore` | Exclut `target/`, IDE et tout fichier `.env*`/clé du contexte Docker |
| `backend/src/main/resources/application-prod.yml` | Port `PORT` de Render, pool réduit, Flyway contrôlé, arrêt propre |
| `backend/src/main/resources/application-demo.yml` | Profil `demo` (avec `prod`) : comptes de démonstration, sans mot de passe par défaut |
| `infra/render/01-app-role.sql` | Création du rôle applicatif `fooyre_app` (non propriétaire, soumis à la RLS) |
| `frontend/scripts/verify-build.mjs` | Refuse désormais toute URL locale (`localhost:port`, `127.0.0.1/…`) dans `dist/` |

## 3. Services et commandes

| Service | Type | Racine | Build | Démarrage | Publication / santé |
|---|---|---|---|---|---|
| `fooyre-web` | Static Site | `frontend` | `npm ci && npm run build` | — | `dist` |
| `fooyre-api` | Web Service Docker | contexte `./backend` | `backend/Dockerfile` | `java -jar app.jar` (image) | `/actuator/health/readiness` |
| `fooyre-db` | Postgres 18 | — | — | — | — |

- **Build frontend** : `npm run build` = `tsc -b` + `vite build` + contrôle de `dist/` (aucune
  donnée fictive, aucun secret, aucune URL locale). `VITE_API_BASE_URL=/api/v1` (relatif).
- **Build backend** : le Dockerfile compile avec `./mvnw -DskipTests package`. Les tests
  (Testcontainers) ne peuvent pas tourner dans le build Docker : **lancer `./mvnw verify` avant
  chaque déploiement**.
- **Migrations** : Flyway s'exécute au démarrage de l'API, avec le rôle **propriétaire**
  (`clean` interdit, validation des scripts déjà appliqués, pas de baseline implicite). Une seule
  instance : pas de migrations concurrentes (Flyway pose de toute façon un verrou).
- **Déploiements** : `autoDeployTrigger: off` : aucun déploiement implicite à chaque commit.

## 4. Variables d'environnement

### Saisies manuellement (`sync: false`) — jamais dans Git

| Variable | Service | Valeur attendue |
|---|---|---|
| `APP_PUBLIC_BASE_URL` | api | URL HTTPS exacte du Static Site, sans `/` final (ex. `https://fooyre-web.onrender.com`) |
| `POSTGRES_HOST` | api | Nom d'hôte **interne** de `fooyre-db` (page *Info* de la base) |
| `POSTGRES_APP_PASSWORD` | api | Mot de passe fort du rôle `fooyre_app` (le même que pour le script SQL) |
| `FOOYRE_DEMO_OWNER_EMAIL` | api | E-mail de connexion du compte « FOOYRE Commerce Démo » |
| `FOOYRE_DEMO_OWNER_PASSWORD` | api | Mot de passe (≥ 12 caractères) choisi par vous |
| `FOOYRE_DEMO2_OWNER_EMAIL` | api | E-mail de connexion d'Aminata Sarr (Nafi Chic) |
| `FOOYRE_DEMO2_OWNER_PASSWORD` | api | Mot de passe (≥ 12 caractères) choisi par vous |

### Fournies par Render
`POSTGRES_DB`, `POSTGRES_MIGRATION_USER`, `POSTGRES_MIGRATION_PASSWORD` (`fromDatabase`) ;
`JWT_SECRET` (`generateValue` : 256 bits aléatoires en Base64, ≥ 32 octets exigés).

### Fixées dans `render.yaml` (non secrètes)
`SPRING_PROFILES_ACTIVE=prod,demo`, `POSTGRES_PORT=5432`, `POSTGRES_APP_USER=fooyre_app`,
`DB_POOL_MAX_SIZE=5`, `AUTH_COOKIE_SECURE=true`, `AI_ENABLED=false`, `AI_PRODUCTION_APPROVED=false`,
`AI_PROVIDER=disabled`, `APP_DEFAULT_TIMEZONE=Africa/Dakar`, `APP_DEFAULT_CURRENCY=XOF`, noms des
entreprises de démonstration ; côté frontend `NODE_VERSION=24`, `VITE_API_BASE_URL=/api/v1`,
`VITE_ENABLE_MOCKS=false`.

**Volontairement absentes :** `ANTHROPIC_API_KEY` (IA désactivée en production, clé locale jamais
réutilisée) ; aucune variable Wave (paiements non connectés, aucune simulation).

`ProductionConfigurationGuard` refuse de démarrer si un secret est vide ou contient une valeur
d'exemple, si `APP_PUBLIC_BASE_URL` n'est pas en HTTPS ou si `AUTH_COOKIE_SECURE` ≠ `true`.

## 5. Étapes manuelles (dans l'ordre)

1. **Pré-requis local** : `cd backend && ./mvnw verify` ; `cd frontend && npm run check` ; pousser
   le code sur le dépôt GitHub (à faire par vous).
2. **Render → New → Blueprint** : sélectionner le dépôt ; Render lit `render.yaml` et demande les
   variables `sync: false`. Vérifier les offres affichées (coûts §7) avant de valider.
3. **Noter les URL réelles** : si `fooyre-web` ou `fooyre-api` sont déjà pris, Render ajoute un
   suffixe. Mettre à jour :
   - la destination de la règle `/api/*` de `fooyre-web` (URL publique de `fooyre-api`) ;
   - `APP_PUBLIC_BASE_URL` de `fooyre-api` (URL publique de `fooyre-web`).
4. **Rôle applicatif** (avant ou après le premier démarrage de l'API) :
   - ajouter temporairement votre IP publique (`x.x.x.x/32`) dans *Access Control* de `fooyre-db` ;
   - exécuter, avec l'*External Database URL* (utilisateur par défaut) :
     `psql "<External Database URL>" -v ON_ERROR_STOP=1 -v app=fooyre_app -v app_password='<mot de passe>' -f infra/render/01-app-role.sql`
   - vérifier la dernière ligne : `rolsuper = f`, `rolbypassrls = f`, `is_database_owner = f` ;
   - retirer votre IP de la liste.
5. **Saisir** `POSTGRES_HOST` (hôte interne) et `POSTGRES_APP_PASSWORD`, puis **déployer
   manuellement** `fooyre-api`. Au premier démarrage : migrations V1→V7, puis création des deux
   comptes de démonstration (journal : « compte et entreprise créés », sans e-mail ni mot de passe).
6. **Déployer** `fooyre-web`.
7. **Recette** (§8).

## 6. Compatibilité PostgreSQL

- **Version** : Render propose PostgreSQL 13 à 18, **18 par défaut** (vérifié le 2026-11-20 sur
  render.com, docs *Create and Connect to Render Postgres* et changelog *PostgreSQL 18 is now
  available*). La version mineure exacte est gérée par Render (FOOYRE est développé et testé en
  18.7) : à relever après création (`SELECT version();`).
- **Extensions** : **aucune** (décision D19 : pas de `citext`, `pgcrypto`… ; UUID générés par
  l'application). Rien à activer.
- **Flyway** : migrations SQL standard (tables, contraintes, index, politiques RLS,
  `current_setting`) ; pilote `flyway-database-postgresql` géré par le BOM Spring Boot, testé sur
  PostgreSQL 18.7 (Testcontainers).
- **RLS** : activée (`ENABLE`, sans `FORCE`) : elle s'applique au rôle applicatif et **pas** au
  propriétaire. D'où l'obligation du rôle `fooyre_app` (§5, étape 4) : l'API ne doit **jamais** se
  connecter avec l'utilisateur par défaut de Render.
- **Pool** : Hikari plafonné à 5 connexions (`DB_POOL_MAX_SIZE`), rôle applicatif limité à 20
  connexions, plus 1 connexion Flyway au démarrage.

## 7. Coûts et limites (relevés le 2026-11-20 sur render.com, à confirmer sur la page de tarifs)

| Élément | Gratuit | Payant recommandé pour une démonstration durable |
|---|---|---|
| Static Site `fooyre-web` | Gratuit (CDN) | — |
| Web Service `fooyre-api` | `free` : 512 Mo, **mise en veille après 15 min** sans trafic (premier accès lent : démarrage de Spring Boot), 750 h/mois par espace de travail, aucun disque persistant | `0.5c-512mb` (ex-« Starter ») : **7 $/mois**, toujours actif |
| Postgres `fooyre-db` | `free` : 256 Mo, 1 Go, **expire 30 jours après création** (puis 14 jours de grâce, ensuite **supprimée avec ses données**), une seule par espace de travail, sans sauvegarde | `0.1c-256mb` (ex-« Basic-256mb ») : **≈ 6 $/mois** + stockage **0,30 $/Go/mois** (15 Go par défaut ≈ 4,50 $ ; prévoir `diskSizeGB: 1` ≈ 0,30 $) |

Estimation durable : **≈ 13 à 18 $/mois** (hors bande passante au-delà des quotas). Le Blueprint
est réglé sur `free` : aucune dépense n'est engagée tant que vous ne changez pas les offres.

## 8. Recette après déploiement (bloquante)

1. `https://<fooyre-web>/` puis rechargement de `/stock` : pas de 404 (réécriture SPA).
2. `https://<fooyre-web>/api/v1/me` sans jeton → **401** au format Problem Details (la réécriture
   `/api/*` atteint bien l'API).
3. **Connexion** avec le compte de démonstration : succès, puis **rechargement de la page** : la
   session doit être conservée (le cookie `fooyre_refresh` a bien traversé la réécriture, et
   l'en-tête `Origin` est bien transmis). En cas d'échec `403 CSRF_CHECK_FAILED` ou de session
   perdue : voir §9, point 1.
4. Déconnexion, puis accès à `/` → retour à la connexion.
5. Isolation : se connecter avec le second compte → aucune donnée du premier.
6. Assistante : statut « désactivée » ; aucune requête vers Anthropic.
7. En-têtes du Static Site présents (`X-Frame-Options`, `Strict-Transport-Security`…).

## 9. Points bloquants ou à vérifier

1. **Relais de la réécriture `/api/*`** : la documentation Render indique qu'une destination de
   réécriture peut être une URL publique externe, mais ne précise pas le traitement des cookies,
   de l'en-tête `Origin`, des méthodes `POST`/`PUT` ni de la taille des requêtes. **À valider à la
   recette (§8, point 3).** Repli possible, chacun à décider : domaine personnalisé (ex.
   `app.` et `api.` d'un même domaine : même site) **avec** l'ajout d'une configuration CORS
   restreinte (modification de code), ou service unique (Spring Boot servant aussi le frontend).
2. **Adresse IP des clients derrière les proxys Render** : l'API lit l'adresse TCP
   (`getRemoteAddr`), jamais `X-Forwarded-For` (choix de sécurité). Derrière Render, elle verra
   l'adresse du proxy : la limite « 50 échecs par IP en 15 min » devient **commune à tous les
   utilisateurs** (risque de blocage collectif de la connexion pendant 15 min) et l'empreinte
   d'IP de l'audit perd son sens. Les limites par compte restent efficaces. Une prise en compte
   sûre de `X-Forwarded-For` (proxy de confiance) est une modification de code à arbitrer.
3. **Création du rôle `fooyre_app`** : Render indique qu'on peut créer des rôles par SQL avec
   l'utilisateur par défaut (non superutilisateur). Si `CREATE ROLE` était refusé, **ne pas**
   connecter l'API avec l'utilisateur par défaut (la RLS ne s'appliquerait pas) : il faudrait
   alors décider d'une migration `FORCE ROW LEVEL SECURITY`.
4. **Propriétés `fromDatabase`** : `host` et `port` ne sont pas documentées pour les bases ; d'où
   la saisie manuelle de `POSTGRES_HOST`.
5. **Base gratuite** : expiration à 30 jours, pas de sauvegarde : inadaptée au-delà d'une courte
   démonstration.
6. **Images Docker** : figer `eclipse-temurin:21-*-noble` par empreinte `sha256` (audit).
7. **Content-Security-Policy** : non posée pour l'instant (styles en ligne de React à évaluer) :
   à définir lors de l'audit sécurité.

## 10. Sources (consultées le 2026-11-20)

- [Create and Connect to Render Postgres](https://render.com/docs/postgresql-creating-connecting)
- [PostgreSQL 18 is now available for Render Postgres](https://render.com/changelog/postgresql-18-is-now-available-for-render-postgres-databases)
- [Database Credentials for Render Postgres](https://render.com/docs/postgresql-credentials)
- [Blueprint YAML Reference](https://render.com/docs/blueprint-spec)
- [Redirects and Rewrites](https://render.com/docs/redirects-rewrites)
- [Deploy for Free](https://render.com/docs/free)
- [Render Compute Plans](https://render.com/docs/compute-plans)
