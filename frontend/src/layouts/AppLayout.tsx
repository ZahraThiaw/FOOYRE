import { useQueryClient } from '@tanstack/react-query';
import { Building2, Menu, Sparkles } from 'lucide-react';
import { useCallback, useRef, useState, type MouseEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useNavigate } from 'react-router';

import { useRuntime } from '@/app/runtimeContext';
import { logout } from '@/features/auth/api';
import { hasPermission, useMe } from '@/features/session/api';
import type { LoginLocationState } from '@/features/session/components/RequireSession';
import { BrandMark } from '@/shared/ui/BrandMark';
import { DemoDataBanner } from '@/shared/ui/DemoDataBanner';
import { Drawer } from '@/shared/ui/Drawer';

import { Sidebar } from './Sidebar';

/**
 * Rail réduit par défaut sur tablette (768–1279 px), barre déployée sur grand écran. Le choix de
 * l'utilisatrice est conservé pendant la session (état de la mise en page, qui n'est pas
 * démontée entre les pages) mais n'est PAS enregistré : le stockage local du navigateur est
 * interdit dans tout le frontend (ADR‑04, règle de lint).
 */
function initialCollapsed(): boolean {
  if (typeof window.matchMedia !== 'function') return false;
  return window.matchMedia('(min-width: 768px) and (max-width: 1279px)').matches;
}

/**
 * Mise en page de l'espace connecté, direction « Le fil d'or » :
 * - ordinateur et tablette (≥ 768 px) : barre latérale vert profond, fixe, repliable en rail ;
 * - mobile : barre supérieure (menu, logo, assistante) et tiroir latéral accessible ;
 * - contenu sur le fond blanc cassé, feuilles de travail blanches.
 * Rendue uniquement sous RequireSession : le profil `/me` est déjà chargé.
 */
export function AppLayout() {
  const { t } = useTranslation(['common', 'auth']);
  const { mocksActive } = useRuntime();
  const { data: me } = useMe();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [loggingOut, setLoggingOut] = useState(false);
  const [collapsed, setCollapsed] = useState(initialCollapsed);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const menuButtonRef = useRef<HTMLButtonElement>(null);

  const toggleCollapsed = useCallback(() => {
    setCollapsed((current) => !current);
  }, []);

  const closeDrawer = useCallback(() => setDrawerOpen(false), []);
  // Tout lien suivi depuis le tiroir mobile le ferme.
  const closeDrawerOnLink = useCallback((event: MouseEvent<HTMLDivElement>) => {
    if (event.target instanceof Element && event.target.closest('a[href]')) setDrawerOpen(false);
  }, []);

  async function handleLogout() {
    setLoggingOut(true);
    const { serverConfirmed } = await logout(queryClient);
    const state: LoginLocationState = { loggedOut: true, logoutUnconfirmed: !serverConfirmed };
    void navigate('/login', { replace: true, state });
  }

  const canUseAssistant = hasPermission(me, 'AI_USE');
  const drawerId = 'fooyre-mobile-menu';

  return (
    <div className="min-h-screen bg-surface md:flex">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-2 focus:top-2 focus:z-[60] focus:rounded-lg focus:bg-sheet focus:px-3 focus:py-2 focus:shadow-raised"
      >
        {t('app.skipToContent')}
      </a>

      {/* Barre latérale (≥ 768 px). */}
      <aside
        data-slot="sidebar"
        data-collapsed={collapsed}
        className={`sticky top-0 hidden h-screen flex-none overflow-y-auto overflow-x-hidden border-r-2 border-accent/70 bg-brand text-brand-contrast md:flex ${
          collapsed ? 'w-[4.5rem]' : 'w-64'
        }`}
      >
        <Sidebar
          me={me}
          variant="desktop"
          collapsed={collapsed}
          onToggleCollapse={toggleCollapsed}
          loggingOut={loggingOut}
          onLogout={() => void handleLogout()}
        />
      </aside>

      <div className="flex min-w-0 flex-1 flex-col">
        {mocksActive && <DemoDataBanner />}

        {/* Barre supérieure. */}
        <header className="sticky top-0 z-30 border-b border-line bg-surface/95">
          <div className="flex h-16 items-center px-3 sm:px-6 lg:px-8">
            <button
              ref={menuButtonRef}
              type="button"
              onClick={() => setDrawerOpen(true)}
              aria-label={t('nav.openMenu')}
              aria-expanded={drawerOpen}
              aria-controls={drawerId}
              className="mr-2 inline-flex h-11 w-11 items-center justify-center rounded-lg text-ink hover:bg-brand-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-brand md:hidden"
            >
              <Menu aria-hidden="true" className="h-6 w-6" />
            </button>
            <Link
              to="/"
              className="inline-flex min-h-touch items-center rounded-lg focus:outline-none focus-visible:ring-2 focus-visible:ring-brand md:hidden"
            >
              <BrandMark />
            </Link>

            {me?.activeBusiness && (
              <p className="hidden min-w-0 items-center text-sm text-ink-muted md:flex">
                <Building2 aria-hidden="true" className="mr-2 h-4 w-4 flex-none text-accent-strong" />
                <span className="truncate font-semibold text-ink">{me.activeBusiness.name}</span>
              </p>
            )}

            {canUseAssistant && (
              <Link
                to="/assistant"
                aria-label={t('nav.openAssistant')}
                className="ml-auto inline-flex min-h-touch items-center rounded-full border border-accent bg-sheet px-3 text-sm font-semibold text-ink shadow-panel transition-colors hover:bg-accent-soft focus:outline-none focus-visible:ring-2 focus-visible:ring-brand sm:px-4"
              >
                <Sparkles aria-hidden="true" className="h-4 w-4 text-accent-strong sm:mr-2" />
                <span aria-hidden="true" className="hidden sm:inline">
                  {t('nav.assistantShort')}
                </span>
              </Link>
            )}
          </div>
        </header>

        <main id="main" tabIndex={-1} className="mx-auto w-full max-w-7xl flex-1 px-4 py-6 focus:outline-none sm:px-6 sm:py-8 lg:px-8">
          <Outlet />
        </main>
      </div>

      <Drawer open={drawerOpen} onClose={closeDrawer} label={t('nav.menu')} id={drawerId} returnFocusRef={menuButtonRef}>
        <div className="flex min-w-0 flex-1 flex-col" onClickCapture={closeDrawerOnLink}>
          <Sidebar me={me} variant="drawer" onClose={closeDrawer} loggingOut={loggingOut} onLogout={() => void handleLogout()} />
        </div>
      </Drawer>
    </div>
  );
}
