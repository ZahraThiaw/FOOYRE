/**
 * Définition unique de « requête destinée à l'API FOOYRE » (ADR‑04).
 *
 * Une URL appartient à l'API si, après résolution et normalisation (WHATWG URL : `..`,
 * `%2e%2e`, barres obliques inverses…) :
 * - elle a exactement la même origine (schéma, hôte, port) que l'URL de base de l'API ;
 * - elle ne contient pas d'identifiants (`user:password@`) ;
 * - son chemin est le chemin de base de l'API (`/api/v1`) ou se trouve sous lui (`/api/v1/…`).
 *
 * Toute autre URL (autre domaine, même origine hors du chemin de l'API, URL invalide) est
 * considérée comme externe : le jeton d'accès ne doit jamais lui être transmis.
 */

/** Origine de la page courante, utilisée pour résoudre les URL relatives. */
export function currentOrigin(): string {
  return globalThis.location.origin;
}

function resolveBase(apiBaseUrl: string, origin: string): URL {
  const withoutTrailingSlash = apiBaseUrl.replace(/\/+$/, '');
  return new URL(`${withoutTrailingSlash}/`, origin);
}

/**
 * Chemin de la requête relatif à l'API (ex. `auth/refresh`), ou `null` si l'URL n'appartient
 * pas à l'API FOOYRE.
 */
export function apiRelativePath(
  target: string,
  apiBaseUrl: string,
  origin: string = currentOrigin(),
): string | null {
  let base: URL;
  let url: URL;
  try {
    base = resolveBase(apiBaseUrl, origin);
    url = new URL(target, origin);
  } catch {
    return null;
  }

  if (url.origin !== base.origin || url.username !== '' || url.password !== '') {
    return null;
  }

  const basePath = base.pathname; // se termine toujours par « / »
  if (url.pathname === basePath.slice(0, -1)) {
    return '';
  }
  return url.pathname.startsWith(basePath) ? url.pathname.slice(basePath.length) : null;
}

export function isApiUrl(
  target: string,
  apiBaseUrl: string,
  origin: string = currentOrigin(),
): boolean {
  return apiRelativePath(target, apiBaseUrl, origin) !== null;
}
