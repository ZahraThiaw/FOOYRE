import 'i18next';

import type apiErrorsFr from './locales/fr/apiErrors.json';
import type commonFr from './locales/fr/common.json';
import type sessionFr from './locales/fr/session.json';

/** Clés de traduction typées : une clé inexistante est une erreur de compilation. */
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: {
      common: typeof commonFr;
      apiErrors: typeof apiErrorsFr;
      session: typeof sessionFr;
    };
    returnNull: false;
  }
}
