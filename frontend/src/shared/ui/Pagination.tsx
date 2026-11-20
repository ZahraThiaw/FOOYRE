import { ChevronLeft, ChevronRight } from 'lucide-react';
import { useTranslation } from 'react-i18next';

import { buttonClassName } from './styles';

interface PaginationProps {
  /** Page courante, à partir de 0 (convention de l'API). */
  readonly page: number;
  readonly totalPages: number;
  readonly totalElements: number;
  readonly onChange: (page: number) => void;
}

/** Pagination serveur : page précédente / suivante et position courante. */
export function Pagination({ page, totalPages, totalElements, onChange }: PaginationProps) {
  const { t } = useTranslation();
  if (totalPages <= 1) {
    return <p className="text-sm text-ink-muted">{t('pagination.total', { count: totalElements })}</p>;
  }
  const buttonClass = `${buttonClassName('secondary', 'sm')} min-w-touch`;
  return (
    <nav aria-label={t('pagination.label')} className="flex flex-wrap items-center justify-between">
      <p className="mr-4 text-sm text-ink-muted">
        {t('pagination.position', { page: page + 1, totalPages, count: totalElements })}
      </p>
      <div className="flex space-x-2">
        <button
          type="button"
          className={buttonClass}
          onClick={() => onChange(page - 1)}
          disabled={page <= 0}
          aria-label={t('pagination.previous')}
        >
          <ChevronLeft aria-hidden="true" className="h-4 w-4" />
        </button>
        <button
          type="button"
          className={buttonClass}
          onClick={() => onChange(page + 1)}
          disabled={page >= totalPages - 1}
          aria-label={t('pagination.next')}
        >
          <ChevronRight aria-hidden="true" className="h-4 w-4" />
        </button>
      </div>
    </nav>
  );
}
