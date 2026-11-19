import { FlaskConical } from 'lucide-react';
import { useTranslation } from 'react-i18next';

/** Bandeau permanent affiché tant que des données fictives (MSW) sont actives. */
export function DemoDataBanner() {
  const { t } = useTranslation();
  return (
    <div
      role="status"
      className="flex items-center justify-center bg-warning-surface px-4 py-2 text-sm font-medium text-warning"
    >
      <FlaskConical aria-hidden="true" className="mr-2 h-4 w-4 flex-none" />
      <span>{t('demo.banner')}</span>
    </div>
  );
}
