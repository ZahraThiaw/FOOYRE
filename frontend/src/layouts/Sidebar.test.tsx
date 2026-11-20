import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useRef, useState } from 'react';
import { createMemoryRouter, RouterProvider } from 'react-router';
import { describe, expect, it, vi } from 'vitest';

import type { BusinessRole, MeResponse, Permission } from '@/features/session/types';
import { Drawer } from '@/shared/ui/Drawer';

import { Sidebar } from './Sidebar';

const OWNER: Permission[] = [
  'BUSINESS_READ',
  'BUSINESS_SETTINGS_READ',
  'CATALOG_READ',
  'STOCK_READ',
  'CUSTOMER_READ',
  'SALE_CREATE',
  'SALE_READ_ALL',
  'AI_USE',
];

function me(permissions: readonly string[], role: BusinessRole = 'BUSINESS_OWNER'): MeResponse {
  return {
    id: 'u-1',
    email: 'aminata@exemple.sn',
    fullName: 'Aminata Sarr',
    locale: 'fr',
    platformAdmin: false,
    activeBusiness: { id: 'b-1', name: 'Nafi Chic', currency: 'XOF' },
    activeMembership: { id: 'm-1', role, employeeMaxDiscountRate: null },
    permissions,
    businesses: [{ id: 'b-1', name: 'Nafi Chic', role }],
  };
}

function renderSidebar(props: Partial<Parameters<typeof Sidebar>[0]> & { me: MeResponse }, path = '/') {
  const router = createMemoryRouter(
    [{ path: '*', element: <Sidebar variant="desktop" loggingOut={false} onLogout={() => undefined} {...props} /> }],
    { initialEntries: [path] },
  );
  render(<RouterProvider router={router} />);
  return router;
}

describe('barre latérale', () => {
  it('rubriques groupées, dans l’ordre, avec les permissions de la propriétaire', () => {
    renderSidebar({ me: me(OWNER) });
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual([
      'Accueil',
      'Assistante IA',
      'Ventes',
      'Clients',
      'Produits',
      'Catégories',
      'Stock',
      'Paramètres',
    ]);
    expect(within(nav).getByRole('list', { name: 'Pilotage' })).toBeVisible();
    expect(within(nav).getByRole('list', { name: 'Commerce' })).toBeVisible();
    expect(within(nav).getByRole('list', { name: 'Catalogue et stock' })).toBeVisible();
    expect(screen.getByRole('link', { name: 'Nouvelle vente' })).toHaveAttribute('href', '/sales/new');
  });

  it('sans permission : entrées, groupes vides et action « Nouvelle vente » masqués', () => {
    renderSidebar({ me: me(['BUSINESS_READ', 'CATALOG_READ']) });
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).getAllByRole('link').map((link) => link.textContent)).toEqual(['Accueil', 'Produits', 'Catégories']);
    expect(within(nav).queryByRole('list', { name: 'Commerce' })).toBeNull();
    expect(screen.queryByRole('link', { name: 'Nouvelle vente' })).toBeNull();
    expect(within(nav).queryByRole('link', { name: 'Paramètres' })).toBeNull();
  });

  it('entrée active : aria-current et filet or ; l’accueil n’est actif que sur « / »', () => {
    renderSidebar({ me: me(OWNER) }, '/stock/history');
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' });
    const stock = within(nav).getByRole('link', { name: 'Stock' });
    expect(stock).toHaveAttribute('aria-current', 'page');
    expect(stock.className).toContain('before:bg-accent');
    expect(within(nav).getByRole('link', { name: 'Accueil' })).not.toHaveAttribute('aria-current');
  });

  it('profil : nom, rôle et entreprise active ; déconnexion', async () => {
    const onLogout = vi.fn();
    renderSidebar({ me: me(OWNER), onLogout });
    const account = screen.getByRole('group', { name: 'Compte connecté' });
    expect(within(account).getByText('Aminata Sarr')).toBeVisible();
    expect(within(account).getByText('Propriétaire · Nafi Chic')).toBeVisible();
    await userEvent.setup().click(within(account).getByRole('button', { name: 'Se déconnecter' }));
    expect(onLogout).toHaveBeenCalledTimes(1);
  });

  it('rail réduit : noms accessibles conservés et bouton pour déplier', async () => {
    const onToggleCollapse = vi.fn();
    renderSidebar({ me: me(OWNER), collapsed: true, onToggleCollapse });
    const nav = screen.getByRole('navigation', { name: 'Navigation principale' });
    expect(within(nav).getByRole('link', { name: 'Ventes' })).toHaveAttribute('title', 'Ventes');
    expect(screen.getByRole('link', { name: 'Nouvelle vente' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Se déconnecter' })).toBeVisible();
    const toggle = screen.getByRole('button', { name: 'Déplier le menu' });
    expect(toggle).toHaveAttribute('aria-expanded', 'false');
    await userEvent.setup().click(toggle);
    expect(onToggleCollapse).toHaveBeenCalledTimes(1);
  });
});

function DrawerHarness() {
  const [open, setOpen] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);
  return (
    <>
      <button ref={opener} type="button" onClick={() => setOpen(true)}>
        Ouvrir
      </button>
      <Drawer open={open} onClose={() => setOpen(false)} label="Menu" returnFocusRef={opener}>
        <button type="button">Premier</button>
        <a href="#dernier">Dernier</a>
      </Drawer>
    </>
  );
}

describe('tiroir mobile', () => {
  it('modal accessible : focus à l’intérieur, piégé, Échap ferme et rend le focus', async () => {
    const user = userEvent.setup();
    render(<DrawerHarness />);
    const opener = screen.getByRole('button', { name: 'Ouvrir' });
    await user.click(opener);

    const dialog = screen.getByRole('dialog', { name: 'Menu' });
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(screen.getByRole('button', { name: 'Premier' })).toHaveFocus();
    expect(document.body.style.overflow).toBe('hidden');

    await user.tab();
    expect(screen.getByRole('link', { name: 'Dernier' })).toHaveFocus();
    await user.tab(); // boucle vers le premier élément
    expect(screen.getByRole('button', { name: 'Premier' })).toHaveFocus();
    await user.tab({ shift: true }); // boucle vers le dernier
    expect(screen.getByRole('link', { name: 'Dernier' })).toHaveFocus();

    await user.keyboard('{Escape}');
    expect(screen.queryByRole('dialog')).toBeNull();
    expect(opener).toHaveFocus();
    expect(document.body.style.overflow).toBe('');
  });

  it('un clic sur le voile ferme le tiroir', async () => {
    const user = userEvent.setup();
    render(<DrawerHarness />);
    await user.click(screen.getByRole('button', { name: 'Ouvrir' }));
    const backdrop = document.querySelector('[data-slot="drawer-backdrop"]');
    expect(backdrop).not.toBeNull();
    await user.click(backdrop as Element);
    expect(screen.queryByRole('dialog')).toBeNull();
  });
});
