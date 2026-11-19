/**
 * Jeton d'accès conservé EN MÉMOIRE uniquement (docs/architecture.md, ADR‑04).
 *
 * - Jamais dans localStorage, sessionStorage, IndexedDB ni un cookie lisible par JavaScript
 *   (règle ESLint `no-restricted-globals` / `no-restricted-properties`).
 * - Perdu au rechargement de la page : la session est alors rétablie par le rafraîchissement
 *   (cookie HttpOnly posé par le backend, voir sessionRefresh.ts).
 */
type Listener = (token: string | null) => void;

let accessToken: string | null = null;
const listeners = new Set<Listener>();

export function getAccessToken(): string | null {
  return accessToken;
}

export function setAccessToken(token: string): void {
  accessToken = token;
  listeners.forEach((listener) => listener(accessToken));
}

export function clearAccessToken(): void {
  accessToken = null;
  listeners.forEach((listener) => listener(accessToken));
}

export function subscribeToAccessToken(listener: Listener): () => void {
  listeners.add(listener);
  return () => {
    listeners.delete(listener);
  };
}
