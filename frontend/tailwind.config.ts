import type { Config } from 'tailwindcss';

/**
 * Tailwind CSS 3.4 — choisi pour la compatibilité avec les navigateurs plus anciens
 * (docs/architecture.md, ADR‑12). Approche mobile‑first : les classes sans préfixe
 * s'appliquent au mobile, les préfixes sm:/md:/lg: aux écrans plus larges.
 *
 * À éviter (support insuffisant sur la cible Safari/iOS 14) : utilitaires *-dvh/*-svh,
 * has-*, @container, text-balance ; préférer space-x/space-y à gap sur un conteneur flex.
 *
 * Couleurs : uniquement les jetons de src/shared/theme/tokens.css (charte FOOYRE et nuances
 * dérivées, plus la terre cuite réservée aux erreurs).
 */
export default {
  content: ['./index.html', './src/**/*.{ts,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          DEFAULT: 'rgb(var(--color-brand) / <alpha-value>)',
          800: 'rgb(var(--color-brand-800) / <alpha-value>)',
          soft: 'rgb(var(--color-brand-soft) / <alpha-value>)',
          contrast: 'rgb(var(--color-brand-contrast) / <alpha-value>)',
        },
        accent: {
          DEFAULT: 'rgb(var(--color-accent) / <alpha-value>)',
          strong: 'rgb(var(--color-accent-strong) / <alpha-value>)',
          soft: 'rgb(var(--color-accent-soft) / <alpha-value>)',
        },
        surface: 'rgb(var(--color-surface) / <alpha-value>)',
        sheet: 'rgb(var(--color-sheet) / <alpha-value>)',
        line: 'rgb(var(--color-line) / <alpha-value>)',
        ink: {
          DEFAULT: 'rgb(var(--color-ink) / <alpha-value>)',
          muted: 'rgb(var(--color-ink-muted) / <alpha-value>)',
        },
        danger: {
          DEFAULT: 'rgb(var(--color-danger) / <alpha-value>)',
          surface: 'rgb(var(--color-danger-surface) / <alpha-value>)',
        },
        warning: {
          DEFAULT: 'rgb(var(--color-warning) / <alpha-value>)',
          surface: 'rgb(var(--color-warning-surface) / <alpha-value>)',
        },
      },
      fontFamily: {
        // Grands titres uniquement : DM Serif Display, auto-hébergée (public/fonts, 24 Ko,
        // licence SIL OFL 1.1). Repli sur des polices à empattements système.
        display: ["'DM Serif Display'", 'Georgia', 'Cambria', "'Times New Roman'", 'serif'],
        // Corps : polices système, aucune police à télécharger (réseaux 3G).
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
      boxShadow: {
        // Ombres très légères teintées de vert (jamais de gris neutre).
        panel: '0 1px 2px rgb(17 47 43 / 0.04), 0 1px 3px rgb(17 47 43 / 0.06)',
        raised: '0 16px 40px -12px rgb(17 47 43 / 0.30), 0 4px 10px -4px rgb(17 47 43 / 0.12)',
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
