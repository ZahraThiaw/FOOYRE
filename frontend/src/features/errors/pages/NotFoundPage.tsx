import { Compass } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { buttonClassName, panelClassName } from '@/shared/ui/styles';

export function NotFoundPage() {
  const { t } = useTranslation();
  return (
    <section className={`${panelClassName} mx-auto max-w-xl px-6 py-12 text-center`}>
      <span aria-hidden="true" className="mx-auto flex h-14 w-14 items-center justify-center rounded-full bg-accent-soft ring-1 ring-accent">
        <Compass className="h-7 w-7" strokeWidth={1.75} />
      </span>
      <h1 className="mt-5 text-3xl text-ink sm:text-4xl">{t('errors.notFound.title')}</h1>
      <p className="mt-2 text-ink-muted">{t('errors.notFound.description')}</p>
      <Link to="/" className={`${buttonClassName('primary')} mt-6`}>
        {t('errors.notFound.backHome')}
      </Link>
    </section>
  );
}
