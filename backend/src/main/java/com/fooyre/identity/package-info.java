/**
 * Module Identité : comptes, sessions (refresh tokens), connexion, profil /me (MVP-1).
 * Couches : api -> application -> domain ; infrastructure pour les adaptateurs. Aucun accès aux
 * repositories ou entités d'un autre module (règles ArchUnit) : les entreprises et appartenances
 * sont lues via le service applicatif public du module business.
 */
package com.fooyre.identity;
