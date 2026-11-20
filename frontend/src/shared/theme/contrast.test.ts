// @vitest-environment node
/// <reference types="node" />
import { readFileSync } from 'node:fs';

import { describe, expect, it } from 'vitest';

const tokensCss = readFileSync(new URL('./tokens.css', import.meta.url), 'utf8');

/**
 * Garde d'accessibilité : contrastes WCAG 2.1 des associations de couleurs du système de design,
 * calculés à partir des VRAIES valeurs de tokens.css (une modification de jeton qui dégrade la
 * lisibilité fait échouer le test).
 */
function token(name: string): [number, number, number] {
  const match = new RegExp(`--color-${name}:\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s*;`).exec(tokensCss);
  if (!match) throw new Error(`Jeton introuvable : ${name}`);
  return [Number(match[1]), Number(match[2]), Number(match[3])];
}

function luminance([r, g, b]: [number, number, number]): number {
  const channel = (value: number) => {
    const c = value / 255;
    return c <= 0.03928 ? c / 12.92 : ((c + 0.055) / 1.055) ** 2.4;
  };
  return 0.2126 * channel(r) + 0.7152 * channel(g) + 0.0722 * channel(b);
}

function contrast(a: string, b: string): number {
  const first = luminance(token(a));
  const second = luminance(token(b));
  return (Math.max(first, second) + 0.05) / (Math.min(first, second) + 0.05);
}

describe('contrastes du système de design', () => {
  it('la charte officielle est conservée', () => {
    expect(token('brand')).toEqual([17, 47, 43]); // #112F2B
    expect(token('accent')).toEqual([213, 198, 154]); // #D5C69A
    expect(token('surface')).toEqual([248, 248, 244]); // #F8F8F4
    expect(token('sheet')).toEqual([255, 255, 255]); // #FFFFFF
    expect(token('danger')).toEqual([166, 75, 50]); // #A64B32 (validée le 2026-11-20)
  });

  it.each([
    // Texte sur fonds clairs
    ['ink', 'sheet'],
    ['ink', 'surface'],
    ['ink-muted', 'sheet'],
    ['ink-muted', 'surface'],
    ['ink', 'brand-soft'],
    ['ink', 'accent-soft'],
    ['ink-muted', 'accent-soft'],
    ['accent-strong', 'sheet'],
    ['accent-strong', 'surface'],
    // Erreurs (terre cuite)
    ['danger', 'sheet'],
    ['danger', 'surface'],
    ['danger', 'danger-surface'],
    // Barre latérale vert profond
    ['accent', 'brand'],
    ['accent', 'brand-800'],
    ['brand-contrast', 'brand'],
    ['brand-contrast', 'brand-800'],
    // Bouton or (texte vert profond)
    ['brand', 'accent'],
  ])('%s sur %s : contraste ≥ 4,5:1 (texte normal)', (foreground, background) => {
    expect(contrast(foreground, background)).toBeGreaterThanOrEqual(4.5);
  });

  it('texte blanc sur un bouton terre cuite : ≥ 4,5:1', () => {
    expect(contrast('sheet', 'danger')).toBeGreaterThanOrEqual(4.5);
  });
});
