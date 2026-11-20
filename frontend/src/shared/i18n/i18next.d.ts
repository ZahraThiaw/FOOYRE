import 'i18next';

import type apiErrorsFr from './locales/fr/apiErrors.json';
import type authFr from './locales/fr/auth.json';
import type catalogFr from './locales/fr/catalog.json';
import type commonFr from './locales/fr/common.json';
import type assistantFr from './locales/fr/assistant.json';
import type customersFr from './locales/fr/customers.json';
import type salesFr from './locales/fr/sales.json';
import type settingsFr from './locales/fr/settings.json';
import type sessionFr from './locales/fr/session.json';
import type stockFr from './locales/fr/stock.json';

/** Clés de traduction typées : une clé inexistante est une erreur de compilation. */
declare module 'i18next' {
  interface CustomTypeOptions {
    defaultNS: 'common';
    resources: {
      common: typeof commonFr;
      apiErrors: typeof apiErrorsFr;
      session: typeof sessionFr;
      auth: typeof authFr;
      catalog: typeof catalogFr;
      stock: typeof stockFr;
      customers: typeof customersFr;
      sales: typeof salesFr;
      assistant: typeof assistantFr;
      settings: typeof settingsFr;
    };
    returnNull: false;
  }
}
