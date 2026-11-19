import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <section className="space-y-4 text-center">
      <h1 className="text-2xl font-bold">{t('errors.notFound.title')}</h1>
      <p className="text-ink-muted">{t('errors.notFound.description')}</p>
      <Link
        to="/"
        className="inline-flex min-h-touch items-center rounded-md bg-brand px-4 font-medium text-brand-contrast"
      >
        {t('errors.notFound.backHome')}
      </Link>
    </section>
  );
}
