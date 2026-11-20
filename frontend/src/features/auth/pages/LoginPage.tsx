import { AlertCircle, Eye, EyeOff, Info, Loader2, LogIn } from 'lucide-react';
import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate } from 'react-router';

import { useLogin } from '@/features/auth/api';
import type { LoginLocationState } from '@/features/session/components/RequireSession';
import { toApiError } from '@/shared/api/apiError';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { BrandMark } from '@/shared/ui/BrandMark';

/** Vérification de forme uniquement : le backend reste seul juge des identifiants. */
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
const EMAIL_MAX_LENGTH = 254;
const PASSWORD_MAX_LENGTH = 128;

type FieldErrorKey =
  | 'login.validation.emailRequired'
  | 'login.validation.emailInvalid'
  | 'login.validation.passwordRequired';

interface FieldErrors {
  readonly email?: FieldErrorKey | undefined;
  readonly password?: FieldErrorKey | undefined;
}

function validate(email: string, password: string): FieldErrors {
  const trimmed = email.trim();
  return {
    email:
      trimmed === ''
        ? 'login.validation.emailRequired'
        : EMAIL_PATTERN.test(trimmed)
          ? undefined
          : 'login.validation.emailInvalid',
    password: password === '' ? 'login.validation.passwordRequired' : undefined,
  };
}

/** Chemin de retour après connexion : uniquement un chemin interne de l'application. */
function safeReturnPath(from: string | undefined): string {
  if (!from || !from.startsWith('/') || from.startsWith('//') || from.startsWith('/login')) {
    return '/';
  }
  return from;
}

const VALUE_POINTS = ['auth:login.values.sales', 'auth:login.values.stock', 'auth:login.values.assistant'] as const;

const inputClass =
  'block w-full min-h-touch rounded-lg border bg-sheet px-3 text-base text-ink placeholder-ink-muted ' +
  'focus:border-brand focus:outline-none focus:ring-2 focus:ring-accent/60 disabled:opacity-60';

/**
 * Page de connexion (MVP‑1). Charte FOOYRE : vert profond (structure, bouton), or champagne
 * (accents), blanc cassé (fond). Mobile d'abord ; sur grand écran, panneau de marque à gauche.
 */
export function LoginPage() {
  const { t } = useTranslation(['auth', 'common']);
  const navigate = useNavigate();
  const location = useLocation();
  const state = (location.state ?? {}) as LoginLocationState;
  const loginMutation = useLogin();

  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [showPassword, setShowPassword] = useState(false);
  const [errors, setErrors] = useState<FieldErrors>({});
  const emailRef = useRef<HTMLInputElement>(null);
  const passwordRef = useRef<HTMLInputElement>(null);

  const emailId = useId();
  const passwordId = useId();
  const emailErrorId = `${emailId}-error`;
  const passwordErrorId = `${passwordId}-error`;

  useEffect(() => {
    document.title = `${t('auth:login.pageTitle')} · ${t('common:app.name')}`;
  }, [t]);

  const submitting = loginMutation.isPending;
  const apiError = loginMutation.error ? toApiError(loginMutation.error) : null;
  const invalidCredentials = apiError?.code === 'INVALID_CREDENTIALS';

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    if (submitting) return;
    const found = validate(email, password);
    setErrors(found);
    if (found.email) {
      emailRef.current?.focus();
      return;
    }
    if (found.password) {
      passwordRef.current?.focus();
      return;
    }
    loginMutation.mutate(
      { email: email.trim(), password },
      {
        onSuccess: () => {
          void navigate(safeReturnPath(state.from), { replace: true });
        },
        onError: () => {
          setPassword('');
          passwordRef.current?.focus();
        },
      },
    );
  }

  return (
    <div className="flex min-h-screen flex-col bg-surface lg:flex-row">
      {/* Panneau de marque (grand écran) / bandeau (mobile). */}
      <div className="bg-brand text-brand-contrast lg:flex lg:w-5/12 lg:flex-col lg:justify-between lg:p-12">
        <div className="flex items-center justify-between px-6 py-5 lg:block lg:p-0">
          <BrandMark size="lg" />
        </div>
        <div className="hidden lg:block">
          <div aria-hidden="true" className="mb-6 h-0.5 w-16 rounded-full bg-accent" />
          <p className="max-w-sm font-display text-4xl leading-tight">{t('common:app.tagline')}</p>
          <ul className="mt-8 max-w-sm space-y-3 text-sm text-brand-contrast/85">
            {VALUE_POINTS.map((key) => (
              <li key={key} className="flex items-start">
                <span aria-hidden="true" className="mr-3 mt-2 h-1.5 w-1.5 flex-none rounded-full bg-accent" />
                <span>{t(key)}</span>
              </li>
            ))}
          </ul>
        </div>
        <div aria-hidden="true" className="h-1 bg-accent lg:hidden" />
      </div>

      <main id="main" className="flex flex-1 items-center justify-center px-4 py-10 sm:px-6">
        <div className="w-full max-w-md">
          <div className="rounded-2xl border border-line bg-sheet p-6 shadow-raised sm:p-8">
            <h1 className="text-3xl text-ink">{t('auth:login.heading')}</h1>
            <div aria-hidden="true" className="mt-3 h-0.5 w-12 rounded-full bg-accent" />
            <p className="mt-2 text-sm text-ink-muted">{t('auth:login.intro')}</p>

            {state.sessionExpired && !apiError && (
              <p
                role="status"
                className="mt-5 flex items-start rounded-lg border border-accent bg-accent-soft p-3 text-sm text-ink"
              >
                <Info aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none" />
                <span>{t('auth:login.sessionExpired')}</span>
              </p>
            )}
            {state.loggedOut && !state.sessionExpired && !apiError && (
              <p
                role="status"
                className="mt-5 flex items-start rounded-lg border border-accent bg-accent-soft p-3 text-sm text-ink"
              >
                <Info aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none" />
                <span>
                  {state.logoutUnconfirmed
                    ? t('auth:login.loggedOutLocalOnly')
                    : t('auth:login.loggedOut')}
                </span>
              </p>
            )}

            {apiError && (
              <div
                className="mt-5 flex items-start rounded-lg border border-l-4 border-danger/30 border-l-danger bg-danger-surface p-3 text-sm font-medium text-danger"
              >
                <AlertCircle aria-hidden="true" className="mr-2 mt-0.5 h-4 w-4 flex-none" />
                {invalidCredentials ? (
                  <p role="alert">{t('auth:login.invalidCredentials')}</p>
                ) : (
                  <ApiErrorMessage error={apiError} />
                )}
              </div>
            )}

            <form noValidate onSubmit={handleSubmit} className="mt-6 space-y-5" aria-busy={submitting}>
              <div className="space-y-1.5">
                <label htmlFor={emailId} className="block text-sm font-medium text-ink">
                  {t('auth:login.email')}
                </label>
                <input
                  ref={emailRef}
                  id={emailId}
                  name="email"
                  type="email"
                  inputMode="email"
                  autoComplete="username"
                  autoCapitalize="none"
                  spellCheck={false}
                  maxLength={EMAIL_MAX_LENGTH}
                  value={email}
                  disabled={submitting}
                  onChange={(event) => setEmail(event.target.value)}
                  aria-invalid={errors.email ? true : undefined}
                  aria-describedby={errors.email ? emailErrorId : undefined}
                  className={`${inputClass} ${errors.email ? 'border-2 border-danger' : 'border-line'}`}
                />
                {errors.email && (
                  <p id={emailErrorId} className="text-sm font-medium text-danger">
                    {t(`auth:${errors.email}`)}
                  </p>
                )}
              </div>

              <div className="space-y-1.5">
                <label htmlFor={passwordId} className="block text-sm font-medium text-ink">
                  {t('auth:login.password')}
                </label>
                <div className="relative">
                  <input
                    ref={passwordRef}
                    id={passwordId}
                    name="password"
                    type={showPassword ? 'text' : 'password'}
                    autoComplete="current-password"
                    maxLength={PASSWORD_MAX_LENGTH}
                    value={password}
                    disabled={submitting}
                    onChange={(event) => setPassword(event.target.value)}
                    aria-invalid={errors.password ? true : undefined}
                    aria-describedby={errors.password ? passwordErrorId : undefined}
                    className={`${inputClass} pr-12 ${errors.password ? 'border-2 border-danger' : 'border-line'}`}
                  />
                  <button
                    type="button"
                    onClick={() => setShowPassword((visible) => !visible)}
                    aria-pressed={showPassword}
                    aria-label={showPassword ? t('auth:login.hidePassword') : t('auth:login.showPassword')}
                    title={showPassword ? t('auth:login.hidePassword') : t('auth:login.showPassword')}
                    className="absolute inset-y-0 right-0 flex min-w-touch items-center justify-center rounded-r-md text-ink-muted hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                  >
                    {showPassword ? (
                      <EyeOff aria-hidden="true" className="h-5 w-5" />
                    ) : (
                      <Eye aria-hidden="true" className="h-5 w-5" />
                    )}
                  </button>
                </div>
                {errors.password && (
                  <p id={passwordErrorId} className="text-sm font-medium text-danger">
                    {t(`auth:${errors.password}`)}
                  </p>
                )}
              </div>

              <button
                type="submit"
                disabled={submitting}
                className="flex w-full min-h-touch items-center justify-center rounded-lg bg-brand px-4 font-semibold text-brand-contrast shadow-panel transition-colors hover:bg-brand-800 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand focus-visible:ring-offset-2 disabled:cursor-not-allowed disabled:opacity-70"
              >
                {submitting ? (
                  <>
                    <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
                    <span>{t('auth:login.submitting')}</span>
                  </>
                ) : (
                  <>
                    <LogIn aria-hidden="true" className="mr-2 h-5 w-5" />
                    <span>{t('auth:login.submit')}</span>
                  </>
                )}
              </button>
            </form>
          </div>
          <p className="mt-6 text-center text-xs text-ink-muted">{t('common:app.tagline')}</p>
        </div>
      </main>
    </div>
  );
}
