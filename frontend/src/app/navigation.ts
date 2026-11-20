import { Boxes, Home, Package, ReceiptText, Settings, Sparkles, Tags, UsersRound, type LucideIcon } from 'lucide-react';

import type { Permission } from '@/features/session/types';

/**
 * Navigation principale, regroupée par rubriques. Chaque module ajoute ici son entrée, avec la
 * permission qui conditionne son AFFICHAGE (confort uniquement : le backend contrôle chaque
 * requête). Un groupe sans entrée autorisée n'est pas affiché.
 */
export type NavigationGroupId = 'steering' | 'commerce' | 'catalog' | 'administration';

export interface NavigationGroup {
  readonly id: NavigationGroupId;
  /** Clé de traduction du titre (espace `common`) ; absente = groupe sans titre (bas de menu). */
  readonly labelKey?: 'nav.groups.steering' | 'nav.groups.commerce' | 'nav.groups.catalog';
}

export interface NavigationItem {
  readonly to: string;
  /** Clé de traduction (espace `common`). */
  readonly labelKey: 'nav.home' | 'nav.products' | 'nav.categories' | 'nav.stock' | 'nav.customers' | 'nav.sales' | 'nav.assistant' | 'nav.settings';
  readonly icon: LucideIcon;
  readonly group: NavigationGroupId;
  /** Permission requise pour afficher l'entrée ; absente = tout utilisateur connecté. */
  readonly permission?: Permission;
  /** L'entrée est active uniquement sur ce chemin exact (accueil). */
  readonly end?: boolean;
}

/** Ordre d'affichage des groupes ; « administration » est placé en bas de la barre latérale. */
export const NAVIGATION_GROUPS: readonly NavigationGroup[] = [
  { id: 'steering', labelKey: 'nav.groups.steering' },
  { id: 'commerce', labelKey: 'nav.groups.commerce' },
  { id: 'catalog', labelKey: 'nav.groups.catalog' },
  { id: 'administration' },
];

export const NAVIGATION: readonly NavigationItem[] = [
  { to: '/', labelKey: 'nav.home', icon: Home, group: 'steering', end: true },
  { to: '/assistant', labelKey: 'nav.assistant', icon: Sparkles, group: 'steering', permission: 'AI_USE' },
  { to: '/sales', labelKey: 'nav.sales', icon: ReceiptText, group: 'commerce', permission: 'SALE_READ_ALL' },
  { to: '/clients', labelKey: 'nav.customers', icon: UsersRound, group: 'commerce', permission: 'CUSTOMER_READ' },
  { to: '/products', labelKey: 'nav.products', icon: Package, group: 'catalog', permission: 'CATALOG_READ' },
  { to: '/categories', labelKey: 'nav.categories', icon: Tags, group: 'catalog', permission: 'CATALOG_READ' },
  { to: '/stock', labelKey: 'nav.stock', icon: Boxes, group: 'catalog', permission: 'STOCK_READ' },
  { to: '/settings', labelKey: 'nav.settings', icon: Settings, group: 'administration', permission: 'BUSINESS_SETTINGS_READ' },
];
