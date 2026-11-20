import { ChevronsLeft, ChevronsRight, Loader2, LogOut, Plus, X } from 'lucide-react';
import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, NavLink } from 'react-router';

import { NAVIGATION, NAVIGATION_GROUPS, type NavigationItem } from '@/app/navigation';
import { hasPermission } from '@/features/session/api';
import type { MeResponse } from '@/features/session/types';
import { Avatar } from '@/shared/ui/Avatar';
import { BrandMark } from '@/shared/ui/BrandMark';

/** Anneau de focus sur le vert profond : or champagne (8,4:1). */
const FOCUS_ON_DARK = 'focus:outline-none focus-visible:ring-2 focus-visible:ring-accent';

interface SidebarProps {
  readonly me: MeResponse | undefined;
  /** `desktop` : barre fixe (repliable) ; `drawer` : contenu du tiroir mobile. */
  readonly variant: 'desktop' | 'drawer';
  /** Rail réduit aux icônes (ordinateur uniquement). */
  readonly collapsed?: boolean;
  readonly onToggleCollapse?: () => void;
  /** Fermeture du tiroir (variante `drawer`). */
  readonly onClose?: () => void;
  readonly loggingOut: boolean;
  readonly onLogout: () => void;
}

/**
 * Barre latérale « Le fil d'or » : écrin vert profond, logo en haut, action « Nouvelle vente »,
 * rubriques groupées, entrée active marquée d'un filet or, paramètres, profil et entreprise en
 * bas. Les entrées suivent les permissions de `/me` (affichage uniquement : le backend reste seul
 * juge de chaque requête).
 */
export function Sidebar({ me, variant, collapsed = false, onToggleCollapse, onClose, loggingOut, onLogout }: SidebarProps) {
  const { t } = useTranslation(['common', 'auth', 'session']);
  const baseId = useId();
  const rail = variant === 'desktop' && collapsed;
  const items = NAVIGATION.filter((item) => !item.permission || hasPermission(me, item.permission));
  const groups = NAVIGATION_GROUPS.map((group) => ({
    ...group,
    items: items.filter((item) => item.group === group.id),
  })).filter((group) => group.items.length > 0);
  const canSell = hasPermission(me, 'SALE_CREATE');

  return (
    <div className="flex min-h-full w-full min-w-0 flex-1 flex-col">
      {/* Marque (et fermeture du tiroir). */}
      <div className={`flex items-center py-4 ${rail ? 'justify-center px-2' : 'justify-between px-4'}`}>
        <Link to="/" className={`inline-flex min-h-touch items-center rounded-lg ${FOCUS_ON_DARK}`}>
          <BrandMark />
        </Link>
        {variant === 'drawer' && onClose && (
          <button
            type="button"
            onClick={onClose}
            aria-label={t('nav.closeMenu')}
            className={`inline-flex h-11 w-11 items-center justify-center rounded-lg text-brand-contrast/80 hover:bg-brand-800 hover:text-brand-contrast ${FOCUS_ON_DARK}`}
          >
            <X aria-hidden="true" className="h-5 w-5" />
          </button>
        )}
      </div>

      {canSell && (
        <div className={rail ? 'px-3' : 'px-4'}>
          <Link
            to="/sales/new"
            title={rail ? t('nav.newSale') : undefined}
            className={`flex min-h-touch items-center justify-center rounded-lg bg-accent font-semibold text-brand shadow-panel transition-colors hover:bg-accent/85 focus:outline-none focus-visible:ring-2 focus-visible:ring-brand-contrast focus-visible:ring-offset-2 focus-visible:ring-offset-brand ${
              rail ? 'px-0' : 'px-4'
            }`}
          >
            <Plus aria-hidden="true" className={`h-5 w-5 ${rail ? '' : 'mr-2'}`} />
            <span className={rail ? 'sr-only' : ''}>{t('nav.newSale')}</span>
          </Link>
        </div>
      )}

      <nav aria-label={t('nav.label')} className="mt-5 flex flex-1 flex-col pb-3">
        {groups.map((group) => {
          const titleId = `${baseId}-${group.id}`;
          const bottom = group.id === 'administration';
          return (
            <div key={group.id} className={bottom ? 'mt-auto border-t border-brand-contrast/10 pt-3' : 'mb-4'}>
              {group.labelKey &&
                (rail ? (
                  <span id={titleId} className="sr-only">
                    {t(group.labelKey)}
                  </span>
                ) : (
                  <p
                    id={titleId}
                    className="mb-1 px-6 text-[0.6875rem] font-semibold uppercase tracking-[0.14em] text-brand-contrast/60"
                  >
                    {t(group.labelKey)}
                  </p>
                ))}
              <ul aria-labelledby={group.labelKey ? titleId : undefined} className={`space-y-0.5 ${rail ? 'px-3' : 'px-3'}`}>
                {group.items.map((item) => (
                  <li key={item.to}>
                    <SidebarLink item={item} rail={rail} label={t(item.labelKey)} />
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </nav>

      {me && (
        <div
          role="group"
          aria-label={t('account.label')}
          className={`border-t border-brand-contrast/10 ${rail ? 'flex flex-col items-center space-y-2 px-2 py-3' : 'px-4 py-4'}`}
        >
          <div className={`flex items-center ${rail ? 'justify-center' : ''}`} title={rail ? me.fullName : undefined}>
            <Avatar name={me.fullName} onDark size="sm" />
            {!rail && (
              <div className="ml-3 min-w-0 leading-tight">
                <p className="truncate text-sm font-semibold text-brand-contrast">{me.fullName}</p>
                {me.activeBusiness && me.activeMembership && (
                  <p className="truncate text-xs text-accent">
                    {t('account.roleInBusiness', {
                      role: t(`session:roles.${me.activeMembership.role}`),
                      business: me.activeBusiness.name,
                    })}
                  </p>
                )}
              </div>
            )}
          </div>
          <button
            type="button"
            onClick={onLogout}
            disabled={loggingOut}
            title={rail ? t('auth:logout.action') : undefined}
            className={`inline-flex min-h-touch items-center justify-center rounded-lg text-sm font-medium text-brand-contrast/85 transition-colors hover:bg-brand-800 hover:text-brand-contrast disabled:opacity-70 ${FOCUS_ON_DARK} ${
              rail ? 'w-11' : 'mt-3 w-full border border-brand-contrast/15 px-3'
            }`}
          >
            {loggingOut ? (
              <Loader2 aria-hidden="true" className={`h-4 w-4 animate-spin ${rail ? '' : 'mr-2'}`} />
            ) : (
              <LogOut aria-hidden="true" className={`h-4 w-4 ${rail ? '' : 'mr-2'}`} />
            )}
            <span className={rail ? 'sr-only' : ''}>
              {loggingOut ? t('auth:logout.inProgress') : t('auth:logout.action')}
            </span>
          </button>
        </div>
      )}

      {variant === 'desktop' && onToggleCollapse && (
        <div className={`border-t border-brand-contrast/10 py-2 ${rail ? 'px-2' : 'px-3'}`}>
          <button
            type="button"
            onClick={onToggleCollapse}
            aria-label={collapsed ? t('nav.expand') : t('nav.collapse')}
            aria-expanded={!collapsed}
            className={`flex min-h-touch w-full items-center rounded-lg px-3 text-sm text-brand-contrast/70 transition-colors hover:bg-brand-800 hover:text-brand-contrast ${FOCUS_ON_DARK} ${
              rail ? 'justify-center' : ''
            }`}
          >
            {collapsed ? (
              <ChevronsRight aria-hidden="true" className="h-4 w-4" />
            ) : (
              <>
                <ChevronsLeft aria-hidden="true" className="mr-2 h-4 w-4" />
                <span aria-hidden="true">{t('nav.collapse')}</span>
              </>
            )}
          </button>
        </div>
      )}
    </div>
  );
}

function SidebarLink({ item, rail, label }: { readonly item: NavigationItem; readonly rail: boolean; readonly label: string }) {
  const Icon = item.icon;
  return (
    <NavLink
      to={item.to}
      end={item.end ?? false}
      title={rail ? label : undefined}
      className={({ isActive }) =>
        `relative flex min-h-touch items-center rounded-lg text-sm font-medium transition-colors ${FOCUS_ON_DARK} ${
          rail ? 'justify-center px-0' : 'px-3'
        } ${
          isActive
            ? 'bg-brand-800 text-accent before:absolute before:inset-y-2 before:left-0 before:w-0.5 before:rounded-full before:bg-accent'
            : 'text-brand-contrast/80 hover:bg-brand-800/70 hover:text-brand-contrast'
        }`
      }
    >
      <Icon aria-hidden="true" className={`h-5 w-5 flex-none ${rail ? '' : 'mr-3'}`} strokeWidth={1.75} />
      <span className={rail ? 'sr-only' : 'truncate'}>{label}</span>
    </NavLink>
  );
}
