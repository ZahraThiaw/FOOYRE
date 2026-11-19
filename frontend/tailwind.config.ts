import type { Config } from 'tailwindcss';

/**
 * Tailwind CSS 3.4 — choisi pour la compatibilité avec les navigateurs plus anciens
 * (docs/architecture.md, ADR‑12). Approche mobile‑first : les classes sans préfixe
 * s'appliquent au mobile, les préfixes sm:/md:/lg: aux écrans plus larges.
 *
 * À éviter (support insuffisant sur la cible Safari/iOS 14) : utilitaires *-dvh/*-svh,
 * has-*, @container, text-balance ; préférer space-x/space-y à gap sur un conteneur flex.
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: 'rgb(var(--color-brand) / <alpha-value>)',
          contrast: 'rgb(var(--color-brand-contrast) / <alpha-value>)',
        },
        surface: 'rgb(var(--color-surface) / <alpha-value>)',
        ink: {
          DEFAULT: 'rgb(var(--color-ink) / <alpha-value>)',
          muted: 'rgb(var(--color-ink-muted) / <alpha-value>)',
        },
        warning: {
          DEFAULT: 'rgb(var(--color-warning) / <alpha-value>)',
          surface: 'rgb(var(--color-warning-surface) / <alpha-value>)',
        },
      },
      fontFamily: {
        // Polices système : aucune police web à télécharger (réseaux 3G).
        sans: [
          'system-ui',
          '-apple-system',
          'Segoe UI',
          'Roboto',
          'Helvetica Neue',
          'Arial',
          'sans-serif',
        ],
      },
      minHeight: {
        touch: '44px',
      },
      minWidth: {
        touch: '44px',
      },
    },
  },
  plugins: [],
} satisfies Config;
