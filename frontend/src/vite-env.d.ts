/// <reference types="vite/client" />

/** Variables d'environnement PUBLIQUES du frontend (voir frontend/.env.example). */
interface ImportMetaEnv {
  readonly VITE_API_BASE_URL?: string;
  readonly VITE_ENABLE_MOCKS?: string;
}

interface ImportMeta {
  readonly env: ImportMetaEnv;
}
