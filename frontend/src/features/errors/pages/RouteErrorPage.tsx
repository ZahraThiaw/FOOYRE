import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

/**
 * Erreur inattendue pendant le rendu d'une route. Aucun détail technique (pile d'appels,
 * message brut) n'est affiché à l'utilisateur.
 */
export function RouteErrorPage() {
  const { t } = useTranslation();
  return (
    <main className="mx-auto max-w-xl space-y-4 px-4 py-10 text-center">
      <h1 className="text-2xl font-bold">{t('errors.unexpected.title')}</h1>
      <p className="text-ink-muted">{t('errors.unexpected.description')}</p>
      <Link
        to="/"
        className="inline-flex min-h-touch items-center rounded-md bg-brand px-4 font-medium text-brand-contrast"
      >
        {t('errors.notFound.backHome')}
      </Link>
    </main>
  );
}
