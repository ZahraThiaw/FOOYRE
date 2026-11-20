import { Boxes, Building2, PackagePlus, Plus, Sparkles, UserPlus, type LucideIcon } from 'lucide-react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { DashboardPanel } from '@/features/sales/components/DashboardPanel';
import { hasPermission, useSession } from '@/features/session/api';
import type { MeResponse, Permission } from '@/features/session/types';
import { Badge } from '@/shared/ui/Badge';
import { buttonClassName, panelClassName } from '@/shared/ui/styles';

const DAY_FORMAT: Intl.DateTimeFormatOptions = { weekday: 'long', day: 'numeric', month: 'long', year: 'numeric' };

interface QuickAction {
  readonly to: string;
  readonly labelKey:
    | 'sales:new'
    | 'sales:dashboard.newProduct'
    | 'sales:dashboard.newCustomer'
    | 'sales:dashboard.stockOperation'
    | 'sales:dashboard.askAssistant';
  readonly icon: LucideIcon;
  readonly permission: Permission;
}

/** Raccourcis : affichés selon les permissions (confort uniquement, le serveur contrôle). */
const QUICK_ACTIONS: readonly QuickAction[] = [
  { to: '/sales/new', labelKey: 'sales:new', icon: Plus, permission: 'SALE_CREATE' },
  { to: '/products/new', labelKey: 'sales:dashboard.newProduct', icon: PackagePlus, permission: 'CATALOG_WRITE' },
  { to: '/clients/new', labelKey: 'sales:dashboard.newCustomer', icon: UserPlus, permission: 'CUSTOMER_WRITE' },
  { to: '/stock', labelKey: 'sales:dashboard.stockOperation', icon: Boxes, permission: 'STOCK_ADJUST' },
  { to: '/assistant', labelKey: 'sales:dashboard.askAssistant', icon: Sparkles, permission: 'AI_USE' },
];

/** Accueil de l'espace connecté : bienvenue, raccourcis et tableau de bord selon les droits. */
export function HomePage() {
  const session = useSession();
  // RequireSession garantit un profil chargé ; les autres états n'affichent rien ici.
  if (session.status === 'ready' || session.status === 'no-business') {
    return <Welcome me={session.me} />;
  }
  return null;
}

function Welcome({ me }: { readonly me: MeResponse }) {
  const { t, i18n } = useTranslation(['common', 'session', 'sales']);
  const business = me.activeBusiness;
  const membership = me.activeMembership;
  const actions = QUICK_ACTIONS.filter((action) => hasPermission(me, action.permission));
  const today = new Intl.DateTimeFormat(i18n.language, DAY_FORMAT).format(new Date());
  const showsDashboard = business && (hasPermission(me, 'DASHBOARD_FULL') || hasPermission(me, 'DASHBOARD_OWN_DAY'));

  return (
    <section className="space-y-8">
      <div className="space-y-5">
        <div className="min-w-0 space-y-3">
          <p className="text-sm font-medium capitalize text-accent-strong">{today}</p>
          <h1 className="text-3xl text-ink sm:text-4xl">{t('home.welcome', { name: me.fullName })}</h1>
          <div aria-hidden="true" className="h-0.5 w-12 rounded-full bg-accent" />
          {business && membership && (
            <p className="flex flex-wrap items-center text-sm text-ink-muted">
              <Building2 aria-hidden="true" className="mr-2 h-4 w-4 text-accent-strong" />
              <span className="mr-3 font-semibold text-ink">{business.name}</span>
              <Badge tone="accent">{t(`session:roles.${membership.role}`)}</Badge>
            </p>
          )}
        </div>
        {actions.length > 0 && (
          <nav aria-label={t('sales:dashboard.quickActions')} className="flex flex-wrap">
            {actions.map((action, index) => {
              const Icon = action.icon;
              return (
                <Link
                  key={action.to}
                  to={action.to}
                  className={`${buttonClassName(index === 0 ? 'primary' : 'secondary', 'sm')} mb-2 mr-2`}
                >
                  <Icon aria-hidden="true" className="mr-2 h-4 w-4" />
                  <span>{t(action.labelKey)}</span>
                </Link>
              );
            })}
          </nav>
        )}
      </div>

      {!business || !membership ? (
        <div className={`${panelClassName} p-5`}>
          <p className="font-medium">{t('session:noBusiness.title')}</p>
          {me.businesses.length > 0 ? (
            <>
              <p className="mt-1 text-ink-muted">{t('session:noBusiness.chooseBusiness')}</p>
              <ul className="mt-2 list-disc pl-5">
                {me.businesses.map((item) => (
                  <li key={item.id}>{item.name}</li>
                ))}
              </ul>
            </>
          ) : (
            <p className="mt-1 text-ink-muted">
              {me.platformAdmin ? t('session:noBusiness.platformAdmin') : t('session:noBusiness.noMembership')}
            </p>
          )}
        </div>
      ) : showsDashboard ? (
        <DashboardPanel />
      ) : (
        <p className="rounded-lg border-l-4 border-accent bg-accent-soft p-4 text-sm">{t('home.noDashboard')}</p>
      )}
    </section>
  );
}
