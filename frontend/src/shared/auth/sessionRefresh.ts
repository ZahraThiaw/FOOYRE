/**
 * Rafraîchissement de session (ADR‑04).
 *
 * Contrat avec le backend :
 * - `POST /api/v1/auth/refresh` ; le refresh token est un cookie `fooyre_refresh`
 *   HttpOnly + Secure (production) + SameSite=Strict + Path=/api/v1/auth, posé et lu par
 *   le backend uniquement : le frontend ne le voit jamais.
 * - La protection CSRF repose côté backend sur SameSite=Strict, le Path restreint et le
 *   contrôle de l'en‑tête Origin. L'en‑tête `X-Requested-With` envoyé par le client n'est
 *   qu'un complément : il ne constitue PAS à lui seul une protection suffisante.
 *
 * Le gestionnaire est branché au démarrage (app/renderApp.tsx → features/auth/api.ts
 * `refreshSession`, qui coordonne les onglets par l'API Web Locks quand elle existe).
 * Pendant une déconnexion, le renouvellement est SUSPENDU : une requête qui échoue à ce moment ne
 * doit pas rouvrir la session avec le cookie encore présent.
 */
export type RefreshHandler = () => Promise<string | null>;

let refreshHandler: RefreshHandler | null = null;
let inFlight: Promise<string | null> | null = null;
let suspensions = 0;

export function configureSessionRefresh(handler: RefreshHandler | null): void {
  refreshHandler = handler;
}

/**
 * Suspend le renouvellement (déconnexion en cours). Renvoie la fonction qui lève la suspension ;
 * les suspensions s'empilent (chaque appel doit être levé une fois).
 */
export function suspendSessionRefresh(): () => void {
  suspensions += 1;
  let released = false;
  return () => {
    if (!released) {
      released = true;
      suspensions -= 1;
    }
  };
}

/**
 * Rafraîchit le jeton d'accès. Les appels simultanés partagent une seule requête
 * (« single flight »). Renvoie le nouveau jeton, ou `null` si la session n'a pas pu
 * être rétablie (ou si le renouvellement est suspendu). Ne modifie pas le jeton en mémoire :
 * c'est l'appelant (httpClient) qui l'enregistre, après avoir vérifié qu'aucun jeton plus récent
 * n'a été obtenu entre‑temps. Les 401 reçus APRÈS un renouvellement terminé ne doivent pas
 * rappeler cette fonction : httpClient compare le jeton utilisé à l'envoi au jeton courant et
 * rejoue directement.
 */
export function refreshAccessToken(): Promise<string | null> {
  if (!refreshHandler || suspensions > 0) {
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
