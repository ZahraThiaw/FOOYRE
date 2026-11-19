/**
 * Architecture du rafraîchissement de session (ADR‑04) — PRÉPARÉE, NON BRANCHÉE.
 *
 * Contrat prévu avec le backend (phase 2) :
 * - `POST /api/v1/auth/refresh` ; le refresh token est un cookie `fooyre_refresh`
 *   HttpOnly + Secure (production) + SameSite=Strict + Path=/api/v1/auth, posé et lu par
 *   le backend uniquement : le frontend ne le voit jamais.
 * - La protection CSRF repose côté backend sur SameSite=Strict, le Path restreint et le
 *   contrôle de l'en‑tête Origin. L'en‑tête `X-Requested-With` envoyé par le client n'est
 *   qu'un complément : il ne constitue PAS à lui seul une protection suffisante.
 *
 * Aucun gestionnaire n'est configuré tant que le backend n'existe pas : un 401 conduit
 * alors simplement à l'effacement du jeton en mémoire. Aucune connexion réelle n'est simulée.
 *
 * Reste à faire en phase 2 : coordination entre onglets (Web Locks / BroadcastChannel)
 * pour qu'un seul onglet rafraîchisse à la fois.
 */
export type RefreshHandler = () => Promise<string | null>;

let refreshHandler: RefreshHandler | null = null;
let inFlight: Promise<string | null> | null = null;

export function configureSessionRefresh(handler: RefreshHandler | null): void {
  refreshHandler = handler;
}

/**
 * Rafraîchit le jeton d'accès. Les appels simultanés partagent une seule requête
 * (« single flight »). Renvoie le nouveau jeton, ou `null` si la session n'a pas pu
 * être rétablie. Ne modifie pas le jeton en mémoire : c'est l'appelant (httpClient) qui
 * l'enregistre, après avoir vérifié qu'aucun jeton plus récent n'a été obtenu entre‑temps.
 * Les 401 reçus APRÈS un renouvellement terminé ne doivent pas rappeler cette fonction :
 * httpClient compare le jeton utilisé à l'envoi au jeton courant et rejoue directement.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshHandler) {
    return Promise.resolve(null);
  }
  if (!inFlight) {
    inFlight = refreshHandler()
      .catch(() => null)
      .finally(() => {
        inFlight = null;
      });
  }
  return inFlight;
}
