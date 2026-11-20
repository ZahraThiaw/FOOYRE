/**
 * Compatibilité : les styles partagés vivent désormais dans le système de design
 * (`@/shared/ui/styles`). Ce module les réexporte pour les écrans existants ; les nouveaux
 * imports doivent viser `@/shared/ui/styles` directement.
 */
export {
  describedBy,
  inputClassName,
  primaryButtonClassName,
  secondaryButtonClassName,
} from '@/shared/ui/styles';
