# FOOYRE

> **La lumière intelligente de votre entreprise.**
> Gérez. Analysez. Anticipez. Développez.

FOOYRE est une plateforme SaaS de gestion intelligente destinée principalement aux femmes entrepreneures et aux petites entreprises au Sénégal : catalogue et stocks, ventes et paiements (y compris partiels et à crédit), dépenses et trésorerie, tableau de bord, rapports, catalogue public partageable et assistant d'analyse basé sur Claude (Anthropic).

## État du projet

**Phase 0 — conception et architecture, consolidée le 2026-11-18.** **Phase 1** : frontend (socle validé, lots F1 à R8), socle backend (lots B1 et B1.1 : Spring Boot 4.1.2 — voir [backend/README.md](backend/README.md)) et **environnement local (MVP-0)** : PostgreSQL 18.7 (Docker Compose), backend et frontend démarrables localement. **MVP-1 à MVP-4A validés ; MVP de démonstration (en attente de validation)** : ventes (brouillon, calcul serveur, confirmation atomique avec sortie de stock, numérotation, coût moyen pondéré figé), tableau de bord réel, paramètres (TVA, remises des employés, consentement IA), assistante IA écrite et vocale (désactivée tant que `AI_ENABLED` et `ANTHROPIC_API_KEY` ne sont pas configurés ; paiement Wave non connecté) ; clients (création, recherche par nom, téléphone ou e-mail, fiche, modification, archivage) ; stock et mouvements de stock (MVP-3 : initialisation, entrées, sorties, ajustements, historique immuable, alertes, paramètre stock négatif) ; catalogue des produits et des catégories (MVP-2) ; authentification réelle, limitation des tentatives de connexion, journal d'audit, contexte d'entreprise avec RLS PostgreSQL prouvée (connexion, session renouvelée par cookie `HttpOnly`, déconnexion), profil `/me`, compte et entreprise de démonstration en profil `dev`, accueil provisoire. **Les autres fonctionnalités métier (approvisionnements et coût moyen pondéré, ventes, finances…) n'existent pas encore.**

Décisions validées : D1 à D20 ; points encore ouverts : O2 (validation comptable des règles de TVA, provisoires) et O7 à O13 — voir le [cahier des charges §8](docs/cahier-des-charges.md) et la [feuille de route](docs/development-roadmap.md).

Principes notables : annulations et corrections imputées à la période où elles interviennent (rapports passés inchangés) ; avoirs clients ; mode hors ligne **partiel** où seul le serveur valide les opérations ; interface en français, architecture prête pour les langues nationales (wolof, pulaar, sérère…) ; hébergement portable avec sauvegardes restaurables.

## Documentation

| Document | Contenu |
|---|---|
| [Cahier des charges](docs/cahier-des-charges.md) | Besoin, périmètre, **définitions financières normatives** (CMP, TVA HT/TTC), rôles et permissions, protection des données, décisions validées et points ouverts |
| [Architecture](docs/architecture.md) | Décisions structurantes, modules, sécurité multi‑tenant, IA, tests, secrets |
| [Schéma de données](docs/database-schema.md) | Entités, relations, conventions PostgreSQL, invariants |
| [Spécification API](docs/api-specification.md) | Conventions REST, erreurs, inventaire des routes |
| [Feuille de route](docs/development-roadmap.md) | Phases de développement et critères de sortie |
| [CLAUDE.md](CLAUDE.md) | Règles de développement à respecter à chaque phase |

## Stack technique

- **Frontend** : React 19, TypeScript 6, Vite 8, Tailwind CSS 3.4, React Router, TanStack Query, Axios, Lucide React, i18next (français)
- **Backend** : Java 21, Spring Boot 4.1.2 (versions gérées par le BOM — `docs/architecture.md` ADR‑12), Spring Security, Spring Data JPA, Spring Validation, Maven 3.10.1 (Maven Wrapper), Flyway 12.4.0, Spring Actuator ; tests : JUnit 6, Testcontainers (PostgreSQL 18.7), ArchUnit
- **Base de données** : PostgreSQL 18.7
- **IA** : API Anthropic, appelée uniquement depuis le backend ; **désactivée par défaut et reportée après le MVP initial**
- **Infrastructure** : Docker, Docker Compose (PostgreSQL local), Git ; hébergement cloud portable — fournisseur et région non choisis (O12)

## Charte graphique

| Couleur | Code | Usage |
|---|---|---|
| Vert profond | `#112F2B` | Couleur principale : navigation, boutons principaux, éléments structurants, texte |
| Or champagne | `#D5C69A` | Accents, détails distinctifs, éléments secondaires |
| Blanc cassé | `#F8F8F4` | Arrière-plans et surfaces |

Définies une seule fois dans `frontend/src/shared/theme/tokens.css` (utilisées via Tailwind : `bg-brand`, `text-ink`, `border-accent`, `bg-surface`…). Ne pas ajouter d'autre couleur de marque.

**Logo** : le fichier officiel n'est pas encore dans le dépôt ; le nom « FOOYRE » est affiché en texte dans l'en‑tête (`frontend/src/layouts/AppLayout.tsx`). Pour l'intégrer : déposer le fichier (SVG de préférence) dans `frontend/src/assets/brand/`, l'importer dans `AppLayout.tsx` à la place du texte, dans un `<img>` avec un texte alternatif traduit (`src/shared/i18n/locales/fr/`). Aucun logo de remplacement ne doit être créé.

---

## Environnement de développement local (MVP-0)

### Architecture locale

| Composant | Exécution | Adresse |
|---|---|---|
| PostgreSQL 18.7 | conteneur Docker (`docker-compose.yml`), volume persistant `fooyre-pgdata` | `localhost:5432` (lié à `127.0.0.1` uniquement) |
| Backend Spring Boot | machine hôte, `mvnw`, profil `dev` | `http://localhost:8080` |
| Frontend React (Vite) | machine hôte, `npm run dev` | `http://localhost:5173` (le proxy Vite envoie `/api` vers le backend) |

### Prérequis

| Outil | Version testée | Vérification |
|---|---|---|
| Docker Desktop (démarré) | 29.9.3 (Compose v5.7.0) | `docker version` |
| JDK | 21 (Temurin 21.0.13) | `java -version` |
| Node.js / npm | 24.22.2 / 11.19.1 (exigé : Node ≥ 24.15 < 25) | `node --version` |
| Maven | **rien à installer** : le Maven Wrapper télécharge Maven 3.10.1 | — |
| Git | 2.56 | `git --version` |

### Installation (une seule fois)

Depuis la **racine du dépôt** :

```powershell
Copy-Item .env.example .env        # PowerShell   (Git Bash : cp .env.example .env)
cd frontend
npm ci
cd ..
```

Le fichier `.env` (jamais versionné) contient des valeurs **fictives** suffisantes pour la machine locale. Il est lu par Docker Compose **et** par le profil Spring `dev`. Variables principales :

| Variable | Rôle |
|---|---|
| `POSTGRES_DB`, `POSTGRES_PORT`, `POSTGRES_HOST` | Base `fooyre`, port 5432, hôte `localhost` |
| `POSTGRES_MIGRATION_USER` / `_PASSWORD` | Rôle **propriétaire** (migrations Flyway) |
| `POSTGRES_APP_USER` / `_PASSWORD` | Rôle **applicatif** (sans droit de modifier le schéma) |
| `POSTGRES_SUPERUSER_PASSWORD` | Superutilisateur de l'image Docker : administration uniquement |
| `APP_PUBLIC_BASE_URL`, `APP_DEFAULT_TIMEZONE`, `APP_DEFAULT_CURRENCY` | `http://localhost:5173`, `Africa/Dakar`, `XOF` |
| `JWT_SECRET` | Clé HMAC du jeton d'accès : **Base64 d'au moins 32 octets aléatoires**, à générer localement (`node -e "console.log(require('crypto').randomBytes(48).toString('base64'))"`) ; la valeur d'exemple est refusée au démarrage |
| `AUTH_COOKIE_SECURE` | `false` en local (HTTP) uniquement ; refusé en production |
| `LOGIN_THROTTLE_*` | Limitation des connexions (échecs tolérés par compte + IP, par compte, par IP ; fenêtre et durée de blocage) — valeurs par défaut adaptées au local, voir `.env.example` |
| `FOOYRE_DEMO_OWNER_PASSWORD` | Mot de passe du compte de démonstration `demo@fooyre.local` (profil `dev` uniquement, au moins 12 caractères). Vide : aucun compte de démonstration n'est créé |

⚠️ Les rôles PostgreSQL sont créés **au premier démarrage seulement** (volume vide). Modifier ensuite les mots de passe dans `.env` ne les change pas dans la base (voir « Dépannage »).

### Démarrage

Trois terminaux, dans cet ordre.

**1. PostgreSQL** — racine du dépôt :

```powershell
docker compose up -d
docker compose ps                  # attendre l'état « healthy »
```

**2. Backend** — dossier `backend/` :

```powershell
.\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=dev"     # PowerShell / cmd
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev           # Git Bash
```

Prêt quand le journal affiche `Started FooyreApplication`. Au premier démarrage, Flyway applique `V1__identity_and_business.sql` ; ensuite « Current version of schema "public": 1 ». En profil `dev`, le journal indique `Compte de démonstration : compte et entreprise créés` (puis `déjà présent, inchangé` aux démarrages suivants). Le backend de développement n'écoute que sur `127.0.0.1:8080` (`SERVER_ADDRESS`).

**Compte de démonstration (profil `dev`)** : e-mail `demo@fooyre.local`, mot de passe = valeur de `FOOYRE_DEMO_OWNER_PASSWORD` du `.env` local, entreprise « FOOYRE Commerce Démo », rôle propriétaire. Le mot de passe d'un compte existant n'est jamais écrasé : le modifier dans `.env` après la création n'a aucun effet.

**3. Frontend** — dossier `frontend/` :

```powershell
npm run dev
```

Ouvrir `http://localhost:5173`.

### Vérifier que tout communique

| Vérification | Commande ou action | Résultat attendu |
|---|---|---|
| PostgreSQL | `docker compose ps` | `fooyre-postgres … (healthy)` |
| Backend vivant | `curl http://localhost:8080/actuator/health/liveness` | `{"status":"UP"}` |
| Backend ↔ PostgreSQL | `curl http://localhost:8080/actuator/health/readiness` | `{"status":"UP"}` (passe à **503** `DOWN` si PostgreSQL est arrêté, puis revient seul) |
| Frontend | navigateur sur `http://localhost:5173` | page de connexion FOOYRE ; après connexion avec le compte de démonstration : « Bienvenue, Administrateur Démo », entreprise et rôle |
| Frontend ↔ backend | `curl -i http://localhost:5173/api/v1/me` | **401** `application/problem+json`, `code: SESSION_REVOKED`, en‑tête `WWW-Authenticate: Bearer` (requête sans jeton) |
| Migrations | `docker exec fooyre-postgres psql -U postgres -d fooyre -c "\dt"` | `app_user`, `business`, `business_membership`, `refresh_token`, `flyway_schema_history` |

Sous PowerShell, utiliser `curl.exe` (et non l'alias `curl`).

### Tests

```powershell
# Backend — dossier backend/ (Docker doit être démarré : tests sur PostgreSQL 18.7 via Testcontainers)
.\mvnw.cmd verify            # ou ./mvnw verify

# Frontend — dossier frontend/
npm run check                # typecheck + lint + tests + build
npm run format:check
```

Les tests backend utilisent leurs **propres** conteneurs PostgreSQL temporaires : ils n'utilisent pas et ne modifient pas la base locale.

### Arrêt

- Backend et frontend : `Ctrl+C` dans leur terminal.
- PostgreSQL (racine) : `docker compose stop` (ou `docker compose down` : supprime le conteneur, **conserve les données**).
- ⚠️ `docker compose down -v` **supprime définitivement la base locale** (volume `fooyre-pgdata`).

### Vérifier la persistance

```powershell
docker compose down
docker compose up -d
docker exec fooyre-postgres psql -U postgres -d fooyre -c "\dt"     # les tables sont toujours là
```

### Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `POSTGRES_SUPERUSER_PASSWORD manquant` au `docker compose up` | Pas de fichier `.env` à la racine | `Copy-Item .env.example .env` |
| `port is already allocated` (5432) | Un autre PostgreSQL utilise le port | L'arrêter, ou changer `POSTGRES_PORT` dans `.env` |
| `Cannot connect to the Docker daemon` | Docker Desktop non démarré | Démarrer Docker Desktop |
| Backend : `Could not resolve placeholder 'POSTGRES_APP_USER'` | `.env` absent, ou backend lancé hors de `backend/` | Créer `.env` à la racine ; lancer depuis `backend/` |
| Backend : `password authentication failed` | Mots de passe de `.env` modifiés après le premier démarrage | Revenir aux valeurs initiales, ou `ALTER ROLE … PASSWORD …` (superutilisateur), ou — **en perdant les données locales** — `docker compose down -v` puis `up -d` |
| `mvnw.cmd` n'est pas reconnu (PowerShell) | PowerShell n'exécute pas le dossier courant | Utiliser `.\mvnw.cmd` |
| Readiness répond 503 | PostgreSQL arrêté ou non prêt | `docker compose ps`, `docker compose start postgres` |
| La page indique « session expirée ou non connecté·e » | Normal : l'authentification n'est pas encore développée | — |
| Le frontend affiche « Votre profil n'a pas pu être chargé » et « Une erreur est survenue sur le serveur » | Backend non démarré : le proxy Vite répond 502 | Démarrer le backend (étape 2) |

## Arborescence

```
backend/            API Spring Boot (monolithe modulaire)
frontend/           Application React (SPA)
infra/postgres/     Initialisation PostgreSQL (rôles propriétaire / applicatif)
docker-compose.yml  PostgreSQL de développement local
docs/               Documentation de conception
```

Ne jamais commiter le fichier `.env`.
