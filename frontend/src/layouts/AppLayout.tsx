import { useTranslation } from 'react-i18next';
import { Link, Outlet } from 'react-router';

import { useRuntime } from '@/app/runtimeContext';
import { DemoDataBanner } from '@/shared/ui/DemoDataBanner';

/**
 * Mise en page principale, mobile‑first. La navigation entre modules sera ajoutée avec
 * les premières pages métier.
 */
export function AppLayout() {
  const { t } = useTranslation();
  const { mocksActive } = useRuntime();

  return (
    <div className="flex min-h-screen flex-col">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:absolute focus:left-2 focus:top-2 focus:z-50 focus:rounded focus:bg-surface focus:px-3 focus:py-2"
      >
        {t('app.skipToContent')}
      </a>

      {mocksActive && <DemoDataBanner />}

      <header className="bg-brand text-brand-contrast">
        <div className="mx-auto flex max-w-5xl items-center px-4 py-3">
          <Link to="/" className="inline-flex min-h-touch items-center text-lg font-bold">
            {t('app.name')}
          </Link>
        </div>
      </header>

      <main id="main" className="mx-auto w-full max-w-5xl flex-1 px-4 py-6 sm:py-8">
        <Outlet />
      </main>

      <footer className="px-4 py-4 text-center text-xs text-ink-muted">{t('app.tagline')}</footer>
    </div>
  );
}
