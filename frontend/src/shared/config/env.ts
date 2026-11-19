/**
 * Configuration PUBLIQUE du frontend (variables VITE_*). Aucune valeur secrète :
 * tout ce qui est lu ici est visible dans le code livré aux navigateurs.
 */
const DEFAULT_API_BASE_URL = '/api/v1';

function readApiBaseUrl(): string {
  const value = import.meta.env.VITE_API_BASE_URL?.trim();
  if (!value) {
    return DEFAULT_API_BASE_URL;
  }
  return value.endsWith('/') ? value.slice(0, -1) : value;
}

export const env = {
  apiBaseUrl: readApiBaseUrl(),
} as const;
