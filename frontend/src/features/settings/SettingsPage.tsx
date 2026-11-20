import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { Building2, Percent, ReceiptText, ShieldCheck, Sparkles, type LucideIcon } from 'lucide-react';
import { useId, useState, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';

import { hasPermission, useMe } from '@/features/session/api';
import { NegativeStockSetting } from '@/features/stock/components/NegativeStockSetting';
import { httpClient } from '@/shared/api/httpClient';
import { normalizeDecimal, validateDecimal } from '@/shared/lib/decimalInput';
import { compareDecimal } from '@/shared/lib/decimalMath';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { Badge } from '@/shared/ui/Badge';
import { Button } from '@/shared/ui/Button';
import { Field } from '@/shared/ui/Field';
import { PageHeader } from '@/shared/ui/PageHeader';
import { describedBy, inputClassName, panelClassName } from '@/shared/ui/styles';
import { useNotify } from '@/shared/ui/useNotify';

interface Settings {
  readonly allowNegativeStock: boolean;
  readonly vatEnabled: boolean;
  readonly vatRate: string;
  readonly priceEntryMode: 'HT' | 'TTC';
  readonly employeeMaxDiscountRate: string;
}

interface AiConsent {
  readonly enabled: boolean;
  readonly consentedAt: string | null;
  readonly termsVersion: string | null;
}

const MAX_RATE = '100';
const TERMS_VERSION = '2026-11-19';
const MODES = ['TTC', 'HT'] as const;
const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium' };
const CHECKBOX = 'mt-0.5 h-5 w-5 flex-none rounded border-line text-brand accent-brand focus:ring-2 focus:ring-accent';

/** Taux en % : décimal positif, 2 décimales au plus, au maximum 100. */
function invalidRate(value: string): boolean {
  return validateDecimal(value, 2, true) !== null || compareDecimal(normalizeDecimal(value), MAX_RATE) > 0;
}

/** Section de paramètres : ancre de la sous-navigation, icône, titre (h2) et contenu. */
function SettingsSection({ id, icon: Icon, title, children }: { readonly id: string; readonly icon: LucideIcon; readonly title: ReactNode; readonly children: ReactNode }) {
  const titleId = `${id}-title`;
  return (
    <section id={id} aria-labelledby={titleId} className={`${panelClassName} scroll-mt-24 p-4 sm:p-6`}>
      <div className="mb-4 flex items-center">
        <span aria-hidden="true" className="mr-3 flex h-9 w-9 flex-none items-center justify-center rounded-lg bg-accent-soft ring-1 ring-accent">
          <Icon className="h-4 w-4" />
        </span>
        <h2 id={titleId} className="text-base font-semibold">
          {title}
        </h2>
      </div>
      <div className="space-y-4">{children}</div>
    </section>
  );
}

function OwnerOnly() {
  const { t } = useTranslation(['settings']);
  return (
    <p className="flex items-center text-xs text-ink-muted">
      <ShieldCheck aria-hidden="true" className="mr-1.5 h-3.5 w-3.5 text-accent-strong" />
      <span>{t('settings:ownerOnly')}</span>
    </p>
  );
}

function VatSection({ settings, canUpdate }: { readonly settings: Settings; readonly canUpdate: boolean }) {
  const { t } = useTranslation(['settings']);
  const notify = useNotify();
  const queryClient = useQueryClient();
  const ids = { enabled: useId(), rate: useId(), mode: useId() };
  const [enabled, setEnabled] = useState(settings.vatEnabled);
  const [rate, setRate] = useState(settings.vatRate);
  const [mode, setMode] = useState(settings.priceEntryMode);
  const invalid = invalidRate(rate);
  const save = useMutation({
    mutationFn: async () =>
      (await httpClient.put<Settings>('/business/settings', { vatEnabled: enabled, vatRate: normalizeDecimal(rate), priceEntryMode: mode })).data,
    onSuccess: (saved) => {
      queryClient.setQueryData(['business', 'settings'], saved);
      notify('success', t('settings:saved'));
    },
    onError: () => notify('error', t('settings:failed')),
  });
  return (
    <SettingsSection id="vat" icon={ReceiptText} title={t('settings:vat.title')}>
      <label htmlFor={ids.enabled} className="flex items-start rounded-lg border border-line bg-surface/60 p-3">
        <input
          id={ids.enabled}
          type="checkbox"
          checked={enabled}
          disabled={!canUpdate}
          onChange={(event) => setEnabled(event.target.checked)}
          className={`${CHECKBOX} mr-3`}
        />
        <span className="font-medium">{t('settings:vat.enabled')}</span>
      </label>
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <Field id={ids.rate} label={t('settings:vat.rate')} error={invalid ? t('settings:vat.invalidRate') : null}>
          <input
            id={ids.rate}
            inputMode="decimal"
            value={rate}
            disabled={!canUpdate}
            onChange={(event) => setRate(event.target.value)}
            className={inputClassName(invalid)}
            {...describedBy(ids.rate, invalid, false)}
          />
        </Field>
        <Field id={ids.mode} label={t('settings:vat.mode')}>
          <select
            id={ids.mode}
            value={mode}
            disabled={!canUpdate}
            onChange={(event) => setMode(event.target.value === 'HT' ? 'HT' : 'TTC')}
            className={inputClassName(false)}
          >
            {MODES.map((value) => (
              <option key={value} value={value}>
                {t(`settings:vat.${value}`)}
              </option>
            ))}
          </select>
        </Field>
      </div>
      <p className="rounded-lg border-l-4 border-accent bg-accent-soft p-3 text-xs">{t('settings:vat.provisional')}</p>
      {canUpdate ? (
        <Button disabled={invalid} loading={save.isPending} onClick={() => save.mutate()}>
          {t('settings:save')}
        </Button>
      ) : (
        <OwnerOnly />
      )}
    </SettingsSection>
  );
}

function DiscountSection() {
  const { t } = useTranslation(['settings']);
  const policy = useQuery({
    queryKey: ['business', 'sales-policy'],
    queryFn: async () => (await httpClient.get<{ employeeMaxDiscountRate: string }>('/business/sales-policy')).data,
  });
  return (
    <SettingsSection id="discount" icon={Percent} title={t('settings:discount.title')}>
      {policy.isError ? (
        <ApiErrorMessage error={policy.error} />
      ) : policy.data ? (
        <DiscountForm key={policy.data.employeeMaxDiscountRate} initial={policy.data.employeeMaxDiscountRate} />
      ) : null}
    </SettingsSection>
  );
}

function DiscountForm({ initial }: { readonly initial: string }) {
  const { t } = useTranslation(['settings']);
  const notify = useNotify();
  const id = useId();
  const [value, setValue] = useState(initial);
  const invalid = value !== '' && invalidRate(value);
  const save = useMutation({
    mutationFn: async () =>
      (await httpClient.put<{ employeeMaxDiscountRate: string }>('/business/sales-policy', {
        employeeMaxDiscountRate: normalizeDecimal(value),
      })).data,
    onSuccess: () => notify('success', t('settings:saved')),
    onError: () => notify('error', t('settings:failed')),
  });
  return (
    <>
      <Field id={id} label={t('settings:discount.label')} hint={t('settings:discount.hint')} error={invalid ? t('settings:discount.invalid') : null}>
        <input
          id={id}
          inputMode="decimal"
          value={value}
          onChange={(event) => setValue(event.target.value)}
          className={`${inputClassName(invalid)} max-w-[10rem]`}
          {...describedBy(id, invalid, true)}
        />
      </Field>
      <Button disabled={invalid || value === ''} loading={save.isPending} onClick={() => save.mutate()}>
        {t('settings:save')}
      </Button>
    </>
  );
}

function AiSection() {
  const { t, i18n } = useTranslation(['settings']);
  const notify = useNotify();
  const queryClient = useQueryClient();
  const id = useId();
  const [accepted, setAccepted] = useState(false);
  const consent = useQuery({
    queryKey: ['business', 'ai-consent'],
    queryFn: async () => (await httpClient.get<AiConsent>('/business/ai-consent')).data,
  });
  const change = useMutation({
    mutationFn: async (enabled: boolean) =>
      (await httpClient.put<AiConsent>('/business/ai-consent', enabled ? { enabled, termsVersion: TERMS_VERSION } : { enabled }))
        .data,
    onSuccess: (saved) => {
      queryClient.setQueryData(['business', 'ai-consent'], saved);
      void queryClient.invalidateQueries({ queryKey: ['assistant'] });
      setAccepted(false);
      notify('success', saved.enabled ? t('settings:ai.enabled') : t('settings:ai.disabled'));
    },
    onError: () => notify('error', t('settings:failed')),
  });
  if (consent.isError) {
    return (
      <SettingsSection id="ai" icon={Sparkles} title={t('settings:ai.title')}>
        <ApiErrorMessage error={consent.error} />
      </SettingsSection>
    );
  }
  if (!consent.data) return null;
  return (
    <SettingsSection
      id="ai"
      icon={Sparkles}
      title={
        <>
          {t('settings:ai.title')} : {consent.data.enabled ? t('settings:ai.statusOn') : t('settings:ai.statusOff')}
        </>
      }
    >
      <p className="text-sm leading-relaxed">{t('settings:ai.explain')}</p>
      {consent.data.enabled ? (
        <>
          {consent.data.consentedAt && (
            <p className="text-xs text-ink-muted">
              {t('settings:ai.since', {
                date: new Intl.DateTimeFormat(i18n.language, DATE_FORMAT).format(new Date(consent.data.consentedAt)),
                version: consent.data.termsVersion,
              })}
            </p>
          )}
          <Button variant="secondary" loading={change.isPending} onClick={() => change.mutate(false)}>
            {t('settings:ai.disable')}
          </Button>
        </>
      ) : (
        <>
          <label htmlFor={id} className="flex items-start rounded-lg border border-accent bg-accent-soft p-3">
            <input id={id} type="checkbox" checked={accepted} onChange={(event) => setAccepted(event.target.checked)} className={`${CHECKBOX} mr-3`} />
            <span className="text-sm font-medium">{t('settings:ai.consent')}</span>
          </label>
          <Button disabled={!accepted} loading={change.isPending} onClick={() => change.mutate(true)}>
            {t('settings:ai.enable')}
          </Button>
        </>
      )}
    </SettingsSection>
  );
}

/** Paramètres de l'entreprise (lecture BUSINESS_SETTINGS_READ ; chaque section vérifie ses propres droits). */
export function SettingsPage() {
  const { t } = useTranslation(['settings', 'session', 'stock']);
  const { data: me } = useMe();
  const settings = useQuery({
    queryKey: ['business', 'settings'],
    queryFn: async () => (await httpClient.get<Settings>('/business/settings')).data,
  });
  const canUpdate = hasPermission(me, 'BUSINESS_SETTINGS_UPDATE');
  const canManagePolicy = hasPermission(me, 'SALES_POLICY_MANAGE');
  const canManageAi = hasPermission(me, 'AI_CONSENT_MANAGE');
  const sections = [
    { id: 'business', label: t('settings:sections.business') },
    { id: 'vat', label: t('settings:vat.title') },
    ...(canManagePolicy ? [{ id: 'discount', label: t('settings:discount.title') }] : []),
    { id: 'stock', label: t('stock:settings.title') },
    { id: 'ai', label: t('settings:ai.title') },
  ];
  return (
    <section className="space-y-6">
      <PageHeader title={t('settings:title')} description={t('settings:intro')} />
      <div className="lg:grid lg:grid-cols-[13rem_minmax(0,1fr)] lg:gap-8">
        <nav aria-label={t('settings:sections.label')} className="mb-6 lg:mb-0">
          <ul className="flex overflow-x-auto rounded-xl border border-line bg-sheet p-1 shadow-panel lg:sticky lg:top-24 lg:flex-col lg:overflow-visible">
            {sections.map((section) => (
              <li key={section.id} className="flex-none">
                <a
                  href={`#${section.id}`}
                  className="block whitespace-nowrap rounded-lg px-3 py-2.5 text-sm font-medium text-ink-muted transition-colors hover:bg-brand-soft hover:text-ink focus:outline-none focus-visible:ring-2 focus-visible:ring-brand"
                >
                  {section.label}
                </a>
              </li>
            ))}
          </ul>
        </nav>
        <div className="min-w-0 max-w-3xl space-y-6">
          {me?.activeBusiness && me.activeMembership && (
            <SettingsSection id="business" icon={Building2} title={t('settings:sections.business')}>
              <dl className="grid grid-cols-1 gap-4 text-sm sm:grid-cols-3">
                <div>
                  <dt className="text-ink-muted">{t('settings:business.name')}</dt>
                  <dd className="mt-1 font-semibold">{me.activeBusiness.name}</dd>
                </div>
                <div>
                  <dt className="text-ink-muted">{t('settings:business.currency')}</dt>
                  <dd className="mt-1 font-semibold">{me.activeBusiness.currency}</dd>
                </div>
                <div>
                  <dt className="text-ink-muted">{t('settings:business.yourRole')}</dt>
                  <dd className="mt-1">
                    <Badge tone="accent">{t(`session:roles.${me.activeMembership.role}`)}</Badge>
                  </dd>
                </div>
              </dl>
            </SettingsSection>
          )}
          {settings.isError && <ApiErrorMessage error={settings.error} />}
          {settings.data && <VatSection key={JSON.stringify(settings.data)} settings={settings.data} canUpdate={canUpdate} />}
          {canManagePolicy && <DiscountSection />}
          <NegativeStockSetting canUpdate={canUpdate} />
          {canManageAi ? (
            <AiSection />
          ) : (
            <section id="ai" className={`${panelClassName} scroll-mt-24 p-4 text-sm text-ink-muted sm:p-6`}>
              {t('settings:ai.ownerOnly')}
            </section>
          )}
        </div>
      </div>
    </section>
  );
}
