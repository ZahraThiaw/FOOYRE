import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PackageOpen } from 'lucide-react';
import { describe, expect, it, vi } from 'vitest';

import { initialsOf } from '@/shared/lib/initials';

import { Badge } from './Badge';
import { Button } from './Button';
import { EmptyState } from './EmptyState';
import { Field } from './Field';
import { PageHeader } from './PageHeader';
import { Panel } from './Panel';
import { Skeleton } from './Skeleton';
import { buttonClassName, describedBy, inputClassName } from './styles';

describe('Button', () => {
  it('est de type « button » par défaut et déclenche son action', async () => {
    const onClick = vi.fn();
    render(<Button onClick={onClick}>Enregistrer</Button>);
    const button = screen.getByRole('button', { name: 'Enregistrer' });
    expect(button).toHaveAttribute('type', 'button');
    await userEvent.setup().click(button);
    expect(onClick).toHaveBeenCalledTimes(1);
  });

  it('en cours : désactivé, occupé, libellé conservé (pas de double envoi)', async () => {
    const onClick = vi.fn();
    render(
      <Button loading onClick={onClick}>
        Enregistrer
      </Button>,
    );
    const button = screen.getByRole('button', { name: 'Enregistrer' });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute('aria-busy', 'true');
    await userEvent.setup().click(button);
    expect(onClick).not.toHaveBeenCalled();
  });

  it('variantes : couleurs de la charte, terre cuite réservée au danger', () => {
    expect(buttonClassName('primary')).toContain('bg-brand');
    expect(buttonClassName('accent')).toContain('bg-accent');
    expect(buttonClassName('danger')).toContain('bg-danger');
    expect(buttonClassName('secondary')).not.toContain('danger');
    // Zone tactile d'au moins 44 px, y compris en taille compacte sur mobile.
    expect(buttonClassName('primary', 'sm')).toContain('min-h-touch');
  });
});

describe('Field', () => {
  it('relie le libellé, l’aide et l’erreur au contrôle ; l’erreur a une icône et un texte', () => {
    const { rerender } = render(
      <Field id="nom" label="Nom" hint="Tel qu'affiché sur le reçu">
        <input id="nom" {...describedBy('nom', false, true)} className={inputClassName(false)} />
      </Field>,
    );
    const input = screen.getByLabelText('Nom');
    expect(input).toHaveAccessibleDescription("Tel qu'affiché sur le reçu");
    expect(input).not.toHaveAttribute('aria-invalid');

    rerender(
      <Field id="nom" label="Nom" hint="Tel qu'affiché sur le reçu" error="Le nom est obligatoire.">
        <input id="nom" {...describedBy('nom', true, true)} className={inputClassName(true)} />
      </Field>,
    );
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription('Le nom est obligatoire.');
    expect(input.className).toContain('border-danger');
    expect(screen.getByText('Le nom est obligatoire.').closest('p')).toHaveClass('text-danger');
    expect(screen.queryByText("Tel qu'affiché sur le reçu")).toBeNull();
  });
});

describe('surfaces et états', () => {
  it('Panel : section nommée par son titre', () => {
    render(
      <Panel title="Taxe sur la valeur ajoutée">
        <p>Contenu</p>
      </Panel>,
    );
    expect(screen.getByRole('region', { name: 'Taxe sur la valeur ajoutée' })).toHaveTextContent('Contenu');
  });

  it('PageHeader : un seul titre de niveau 1 et les actions', () => {
    render(<PageHeader title="Produits" description="Votre catalogue" actions={<Button>Ajouter</Button>} />);
    expect(screen.getByRole('heading', { level: 1, name: 'Produits' })).toBeVisible();
    expect(screen.getByRole('button', { name: 'Ajouter' })).toBeVisible();
  });

  it('EmptyState : titre, explication et action', () => {
    render(
      <EmptyState
        icon={PackageOpen}
        title="Aucun produit"
        description="Ajoutez votre premier produit."
        action={<Button>Ajouter un produit</Button>}
      />,
    );
    expect(screen.getByText('Aucun produit')).toBeVisible();
    expect(screen.getByText('Ajoutez votre premier produit.')).toBeVisible();
    expect(screen.getByRole('button', { name: 'Ajouter un produit' })).toBeVisible();
  });

  it('Badge : le libellé porte l’état (jamais la couleur seule)', () => {
    render(<Badge tone="danger">Rupture</Badge>);
    expect(screen.getByText('Rupture')).toHaveClass('text-danger');
  });

  it('Skeleton : décoratif, ignoré des lecteurs d’écran', () => {
    const { container } = render(<Skeleton className="h-4 w-24" />);
    expect(container.firstElementChild).toHaveAttribute('aria-hidden', 'true');
  });
});

describe('initiales', () => {
  it.each([
    ['Aminata Sarr', 'AS'],
    ['Administrateur Démo', 'AD'],
    ['  fatou  ', 'F'],
    ['Élise Ndiaye Diop', 'ÉD'],
    ['', ''],
  ])('%s → %s', (name, expected) => {
    expect(initialsOf(name)).toBe(expected);
  });
});
