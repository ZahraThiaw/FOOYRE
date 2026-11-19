import { useTranslation } from 'react-i18next';

import { useSession, type SessionState } from '@/features/session/api';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';

/** Page d'accueil PROVISOIRE du socle technique (aucune donnée métier). */
export function HomePage() {
  const { t } = useTranslation(['common', 'session']);
  const session = useSession();

  return (
    <section className="space-y-6">
      <div className="space-y-2">
        <h1 className="text-2xl font-bold sm:text-3xl">{t('home.title')}</h1>
        <p className="text-ink-muted">{t('home.intro')}</p>
      </div>

      <div className="rounded-lg border border-ink/10 bg-white p-4 shadow-sm">
        <h2 className="mb-2 text-base font-semibold">{t('home.session.title')}</h2>
        <SessionSummary session={session} />
      </div>
    </section>
  );
}

function SessionSummary({ session }: { readonly session: SessionState }) {
  const { t } = useTranslation(['common', 'session']);

  switch (session.status) {
    case 'loading':
      return <p className="text-ink-muted">{t('session:loading')}</p>;
    case 'expired':
      return <p role="alert">{t('session:expired')}</p>;
    case 'forbidden':
      return <p role="alert">{t('session:forbidden')}</p>;
    case 'error':
      return (
        <div className="space-y-1">
          <p>{t('session:error')}</p>
          <ApiErrorMessage error={session.error} />
        </div>
      );
    case 'no-business': {
      const { me } = session;
      return (
        <div className="space-y-2">
          <p>{t('home.session.signedInAs', { name: me.fullName })}</p>
          <p className="font-medium">{t('session:noBusiness.title')}</p>
          {me.businesses.length > 0 ? (
            <>
              <p className="text-ink-muted">{t('session:noBusiness.chooseBusiness')}</p>
              <ul className="list-disc pl-5">
                {me.businesses.map((business) => (
                  <li key={business.id}>{business.name}</li>
                ))}
              </ul>
            </>
          ) : (
            <p className="text-ink-muted">
              {me.platformAdmin
                ? t('session:noBusiness.platformAdmin')
                : t('session:noBusiness.noMembership')}
            </p>
          )}
        </div>
      );
    }
    case 'ready':
      return (
        <div className="space-y-1">
          <p>{t('home.session.signedInAs', { name: session.me.fullName })}</p>
          {session.me.activeBusiness && (
            <p className="text-ink-muted">
              {t('home.session.activeBusiness', { business: session.me.activeBusiness.name })}
            </p>
          )}
        </div>
      );
  }
}
