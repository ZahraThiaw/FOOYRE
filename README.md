# FOOYRE

> **La lumière intelligente de votre entreprise.**
> Gérez. Analysez. Anticipez. Développez.

FOOYRE est une plateforme SaaS de gestion intelligente destinée principalement aux femmes entrepreneures et aux petites entreprises au Sénégal : catalogue et stocks, ventes et paiements (y compris partiels et à crédit), dépenses et trésorerie, tableau de bord, rapports, catalogue public partageable et assistant d'analyse basé sur Claude (Anthropic).

## État du projet

**Phase 0 — conception et architecture, consolidée le 2026-11-18.** Aucun code applicatif n'existe encore. Décisions validées : D1 à D20 ; points encore ouverts : O2 (validation comptable des règles de TVA, provisoires) et O7 à O13 — voir le [cahier des charges §8](docs/cahier-des-charges.md) et la [feuille de route](docs/development-roadmap.md).

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

- **Frontend** : React, TypeScript, Vite, Tailwind CSS, React Router, TanStack Query, Axios, Recharts, Lucide React ; internationalisation (bibliothèque à choisir en phase 1) ; PWA pour le mode hors ligne partiel (phase proposée, calendrier à confirmer — O10)
- **Backend** : Java 21, Spring Boot (cible 4.1.2 — existence, stabilité et compatibilité Java 21 vérifiées ; compatibilité des dépendances à confirmer en phase 1), Spring Security, Spring Data JPA, Spring Validation, Maven, Flyway, Spring Actuator
- **Base de données** : PostgreSQL
- **IA** : API Anthropic, modèle par défaut Claude Opus 5.5 (`claude-opus-5-5`), appelée uniquement depuis le backend via le SDK Java officiel ; désactivée par défaut, activée par entreprise sur consentement du propriétaire (révocable), non activable en production avant les validations juridiques et réglementaires (CDP, transferts internationaux)
- **Infrastructure** : Docker, Docker Compose, Git ; hébergement cloud portable (technologies standard, stockage de fichiers compatible S3, sauvegardes restaurables) — fournisseur et région non choisis (O12)

## Démarrage (disponible à partir de la phase 1)

```bash
cp .env.example .env      # puis renseigner des valeurs locales
docker compose up -d
```

Ne jamais commiter le fichier `.env`.

## Arborescence prévue

```
backend/    API Spring Boot (monolithe modulaire)
frontend/   Application React (SPA)
infra/      Reverse proxy, initialisation PostgreSQL
docs/       Documentation de conception
```
