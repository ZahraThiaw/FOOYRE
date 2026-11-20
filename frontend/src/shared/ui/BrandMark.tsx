import { useTranslation } from 'react-i18next';

/**
 * Logo officiel FOOYRE, dérivé de l'original de référence `public/images/fooyre-logo.png` (conservé
 * intact), sans redessin :
 * - `lg` (connexion) : logo COMPLET avec slogan, simple redimensionnement 512 × 512 ;
 * - `md` (en‑tête) : variante SANS slogan (symbole « F » + mot FOOYRE), simple recadrage de
 *   l'original redimensionné en 512 × 426.
 * Dimensions intrinsèques déclarées : place réservée, aucun décalage au chargement.
 */
const LOGOS = {
  lg: { src: '/images/fooyre-logo-512.png', width: 512, height: 512 },
  md: { src: '/images/fooyre-logo-header.png', width: 512, height: 426 },
} as const;

interface BrandMarkProps {
  /** `md` : en‑tête ; `lg` : page de connexion. */
  readonly size?: 'md' | 'lg';
  readonly className?: string;
}

/**
 * Marque FOOYRE (emplacement unique `data-slot="brand-logo"`, en‑tête et connexion). Le logo
 * officiel est sur fond blanc cassé OPAQUE : il est présenté dans un écrin arrondi de la même
 * teinte (#F8F8F4) avec un liseré or champagne, lisible sur le vert profond comme sur un fond
 * clair. Taille FIXE et carrée (proportions d'origine, `object-contain`) : aucune déformation,
 * aucun décalage de la navigation.
 */
export function BrandMark({ size = 'md', className = '' }: BrandMarkProps) {
  const { t } = useTranslation();
  const logo = LOGOS[size];
  const frame =
    size === 'lg'
      ? 'h-28 w-28 rounded-2xl p-1.5 sm:h-32 sm:w-32 lg:h-40 lg:w-40'
      : 'h-11 w-11 rounded-lg p-0.5 sm:h-12 sm:w-12';
  return (
    <span
      data-slot="brand-logo"
      className={`inline-flex flex-none items-center justify-center overflow-hidden bg-surface ring-1 ring-accent/70 ${frame} ${className}`}
    >
      <img
        src={logo.src}
        alt={t('app.logoAlt')}
        width={logo.width}
        height={logo.height}
        decoding="async"
        className="h-full w-full object-contain"
      />
    </span>
  );
}
