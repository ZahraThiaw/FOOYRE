import { AlertCircle } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { buttonClassName, panelClassName } from '@/shared/ui/styles';

/**
 * Erreur inattendue pendant le rendu d'une route. Aucun détail technique (pile d'appels,
 * message brut) n'est affiché à l'utilisateur.
 */
export function RouteErrorPage() {
  const { t } = useTranslation();
  return (
    <main className="min-h-screen bg-surface px-4 py-16">
      <div className={`${panelClassName} mx-auto max-w-xl px-6 py-12 text-center`}>
        <span aria-hidden="true" className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-danger-surface text-danger ring-1 ring-danger/30">
          <AlertCircle className="h-7 w-7" strokeWidth={1.75} />
        </span>
        <h1 className="mt-5 text-3xl text-ink sm:text-4xl">{t('errors.unexpected.title')}</h1>
        <p className="mt-2 text-ink-muted">{t('errors.unexpected.description')}</p>
        <Link to="/" className={`${buttonClassName('primary')} mt-6`}>
          {t('errors.notFound.backHome')}
        </Link>
      </div>
    </main>
  );
}
