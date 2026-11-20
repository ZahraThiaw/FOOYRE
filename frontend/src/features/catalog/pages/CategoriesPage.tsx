import { Archive, FolderOpen, Loader2, Pencil, Plus, Tag } from 'lucide-react';
import { useId, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';

import {
  archiveCategory,
  createCategory,
  updateCategory,
  useCatalogMutation,
  useCategories,
} from '@/features/catalog/api';
import { useCatalogAccess } from '@/features/catalog/access';
import { FormField } from '@/features/catalog/components/FormField';
import { Badge } from '@/shared/ui/Badge';
import { PageHeader } from '@/shared/ui/PageHeader';
import { describedBy, inputClassName, panelClassName, primaryButtonClassName, secondaryButtonClassName } from '@/shared/ui/styles';
import type { Category, CategoryInput, StatusFilter } from '@/features/catalog/types';
import { toApiError } from '@/shared/api/apiError';
import { fieldErrorMessageKey } from '@/shared/api/errorMessages';
import { ApiErrorMessage } from '@/shared/ui/ApiErrorMessage';
import { ConfirmDialog } from '@/shared/ui/ConfirmDialog';
import { useNotify } from '@/shared/ui/useNotify';
import { Pagination } from '@/shared/ui/Pagination';

const PAGE_SIZE = 20;
const NAME_MAX = 100;
const DESCRIPTION_MAX = 500;
const STATUS_FILTERS: readonly StatusFilter[] = ['ACTIVE', 'ARCHIVED', 'ALL'];
/** Identifiants des contrôles (techniques, non affichés). */
const NAME_ID = 'category-name';
const DESCRIPTION_ID = 'category-description';

/** `null` : formulaire fermé ; `'new'` : création ; une catégorie : modification. */
type Editing = null | 'new' | Category;

/** Catégories : consultation, création, modification et archivage, nombre réel de produits actifs. */
export function CategoriesPage() {
  const { t } = useTranslation(['catalog']);
  const { canWrite } = useCatalogAccess();
  const notify = useNotify();
  const [status, setStatus] = useState<StatusFilter>('ACTIVE');
  const [page, setPage] = useState(0);
  const categories = useCategories(status, page, PAGE_SIZE);
  const [editing, setEditing] = useState<Editing>(null);
  const [toArchive, setToArchive] = useState<Category | null>(null);
  const archive = useCatalogMutation((category: Category) => archiveCategory(category.id));
  const statusId = useId();

  return (
    <section className="space-y-6">
      <PageHeader
        title={t('catalog:categories.title')}
        description={t('catalog:categories.intro')}
        actions={
          canWrite &&
          editing === null && (
            <button type="button" onClick={() => setEditing('new')} className={primaryButtonClassName}>
              <Plus aria-hidden="true" className="mr-2 h-5 w-5" />
              <span>{t('catalog:categories.add')}</span>
            </button>
          )
        }
      />

      {canWrite && editing !== null && (
        <CategoryForm
          category={editing === 'new' ? null : editing}
          onDone={(saved, created) => {
            notify(
              'success',
              t(created ? 'catalog:categories.created' : 'catalog:categories.updated', { name: saved.name }),
            );
            setEditing(null);
          }}
          onCancel={() => setEditing(null)}
        />
      )}

      <div className="max-w-xs">
        <label htmlFor={statusId} className="mb-1 block text-xs font-medium text-ink-muted">
          {t('catalog:categories.statusFilter')}
        </label>
        <select
          id={statusId}
          value={status}
          onChange={(event) => {
            setStatus(event.target.value as StatusFilter);
            setPage(0);
          }}
          className={inputClassName(false)}
        >
          {STATUS_FILTERS.map((value) => (
            <option key={value} value={value}>
              {t(`catalog:categoryStatus.${value}`)}
            </option>
          ))}
        </select>
      </div>

      {categories.isPending ? (
        <p role="status" className="flex items-center text-ink-muted">
          <Loader2 aria-hidden="true" className="mr-2 h-5 w-5 animate-spin" />
          <span>{t('catalog:common.loading')}</span>
        </p>
      ) : categories.isError ? (
        <div className="space-y-3 rounded-xl border border-line bg-sheet shadow-panel p-4">
          <p>{t('catalog:common.loadError')}</p>
          <ApiErrorMessage error={categories.error} />
          <button type="button" onClick={() => void categories.refetch()} className={secondaryButtonClassName}>
            {t('catalog:common.retry')}
          </button>
        </div>
      ) : categories.data.content.length === 0 ? (
        <div className="rounded-xl border border-dashed border-ink/20 bg-sheet/60 p-8 text-center">
          <FolderOpen aria-hidden="true" className="mx-auto h-10 w-10 text-ink-muted" />
          {status === 'ARCHIVED' ? (
            <p className="mt-3 font-medium">{t('catalog:categories.empty.archived')}</p>
          ) : (
            <>
              <p className="mt-3 font-medium">{t('catalog:categories.empty.title')}</p>
              <p className="mt-1 text-sm text-ink-muted">{t('catalog:categories.empty.description')}</p>
            </>
          )}
        </div>
      ) : (
        <>
          <ul className={`${panelClassName} divide-y divide-line overflow-hidden`}>
            {categories.data.content.map((category) => {
              // Effectifs entiers (nombre de produits) : largeur de la barre uniquement.
              const largest = Math.max(1, ...categories.data.content.map((item) => item.activeProductCount));
              return (
                <li key={category.id} className="flex flex-col p-4 transition-colors hover:bg-brand-soft/40 sm:flex-row sm:items-center sm:px-5">
                  <span aria-hidden="true" className="mb-3 mr-4 hidden h-10 w-10 flex-none items-center justify-center rounded-lg bg-accent-soft ring-1 ring-accent sm:mb-0 sm:flex">
                    <Tag className="h-5 w-5" strokeWidth={1.75} />
                  </span>
                  <div className="min-w-0 flex-1">
                    <div className="flex flex-wrap items-center">
                      <h2 className="mr-2 font-semibold">{category.name}</h2>
                      {category.status === 'ARCHIVED' && <Badge tone="neutral">{t('catalog:categoryStatus.ARCHIVED')}</Badge>}
                    </div>
                    {category.description && <p className="mt-0.5 text-sm text-ink-muted">{category.description}</p>}
                  </div>
                  <div className="mt-3 sm:mx-6 sm:mt-0 sm:w-48">
                    <p className="text-sm font-semibold">
                      {t('catalog:categories.productCount', { count: category.activeProductCount })}
                    </p>
                    <div aria-hidden="true" className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-surface">
                      <div className="h-full rounded-full bg-accent" style={{ width: `${(category.activeProductCount / largest) * 100}%` }} />
                    </div>
                  </div>
                  {canWrite && category.status === 'ACTIVE' && (
                    <div className="mt-4 flex space-x-2 sm:mt-0">
                      <button
                        type="button"
                        onClick={() => setEditing(category)}
                        className={secondaryButtonClassName}
                      >
                        <Pencil aria-hidden="true" className="mr-2 h-4 w-4" />
                        <span>{t('catalog:common.edit')}</span>
                      </button>
                      <button type="button" onClick={() => setToArchive(category)} className={secondaryButtonClassName}>
                        <Archive aria-hidden="true" className="mr-2 h-4 w-4" />
                        <span>{t('catalog:common.archive')}</span>
                      </button>
                    </div>
                  )}
                </li>
              );
            })}
          </ul>
          <Pagination
            page={categories.data.page.number}
            totalPages={categories.data.page.totalPages}
            totalElements={categories.data.page.totalElements}
            onChange={setPage}
          />
        </>
      )}

      <ConfirmDialog
        open={toArchive !== null}
        title={t('catalog:categories.archiveTitle')}
        description={t('catalog:categories.archiveDescription', { name: toArchive?.name ?? '' })}
        confirmLabel={t('catalog:common.archive')}
        cancelLabel={t('catalog:common.cancel')}
        pending={archive.isPending}
        onCancel={() => setToArchive(null)}
        onConfirm={() => {
          if (!toArchive) return;
          const category = toArchive;
          archive.mutate(category, {
            onSuccess: () => notify('success', t('catalog:categories.archived', { name: category.name })),
            onError: () => notify('error', t('catalog:categories.archiveFailed')),
            onSettled: () => setToArchive(null),
          });
        }}
      />
    </section>
  );
}

interface CategoryFormProps {
  readonly category: Category | null;
  readonly onDone: (saved: Category, created: boolean) => void;
  readonly onCancel: () => void;
}

function CategoryForm({ category, onDone, onCancel }: CategoryFormProps) {
  const { t } = useTranslation(['catalog', 'apiErrors']);
  const notify = useNotify();
  const [name, setName] = useState(category?.name ?? '');
  const [description, setDescription] = useState(category?.description ?? '');
  const [errors, setErrors] = useState<{ name?: string; description?: string }>({});
  const save = useCatalogMutation((input: CategoryInput) =>
    category ? updateCategory(category.id, input) : createCategory(input),
  );

  function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault();
    const found: { name?: string; description?: string } = {};
    if (name.trim() === '') found.name = t('catalog:productForm.errors.required');
    else if (name.trim().length > NAME_MAX) found.name = t('catalog:productForm.errors.tooLong', { max: NAME_MAX });
    if (description.trim().length > DESCRIPTION_MAX) {
      found.description = t('catalog:productForm.errors.tooLong', { max: DESCRIPTION_MAX });
    }
    setErrors(found);
    if (found.name || found.description) {
      document.getElementById(found.name ? NAME_ID : DESCRIPTION_ID)?.focus();
      return;
    }
    const input: CategoryInput = {
      name: name.trim(),
      description: description.trim() === '' ? null : description.trim(),
      ...(category ? { version: category.version } : {}),
    };
    save.mutate(input, {
      onSuccess: (saved) => onDone(saved, category === null),
      onError: (error) => {
        const fieldErrors: { name?: string; description?: string } = {};
        toApiError(error).fieldErrors.forEach((fieldError) => {
          if (fieldError.field === 'name' || fieldError.field === 'description') {
            fieldErrors[fieldError.field] = t(fieldErrorMessageKey(fieldError));
          }
        });
        setErrors(fieldErrors);
        notify('error', t('catalog:categories.saveFailed'));
      },
    });
  }

  const serverError = save.error && toApiError(save.error).fieldErrors.length === 0 ? save.error : null;

  return (
    <form noValidate onSubmit={handleSubmit} className="space-y-4 rounded-xl border border-accent bg-sheet shadow-panel p-4 sm:p-6">
      <h2 className="text-lg font-semibold">
        {category ? t('catalog:categories.editTitle') : t('catalog:categories.createTitle')}
      </h2>
      {serverError && <ApiErrorMessage error={serverError} />}
      <FormField id={NAME_ID} label={t('catalog:categories.name')} error={errors.name ?? null}>
        <input
          id={NAME_ID}
          value={name}
          maxLength={NAME_MAX}
          onChange={(event) => setName(event.target.value)}
          autoComplete="off"
          className={inputClassName(Boolean(errors.name))}
          {...describedBy(NAME_ID, Boolean(errors.name), false)}
        />
      </FormField>
      <FormField
        id={DESCRIPTION_ID}
        label={t('catalog:categories.description')}
        optionalLabel={t('catalog:common.optional')}
        error={errors.description ?? null}
      >
        <textarea
          id={DESCRIPTION_ID}
          rows={2}
          value={description}
          onChange={(event) => setDescription(event.target.value)}
          className={`${inputClassName(Boolean(errors.description))} py-2`}
          {...describedBy(DESCRIPTION_ID, Boolean(errors.description), false)}
        />
      </FormField>
      <div className="flex flex-col-reverse sm:flex-row sm:justify-end">
        <button type="button" onClick={onCancel} className={`${secondaryButtonClassName} mt-2 sm:mr-2 sm:mt-0`}>
          {t('catalog:common.cancel')}
        </button>
        <button type="submit" disabled={save.isPending} className={primaryButtonClassName}>
          {save.isPending && <Loader2 aria-hidden="true" className="mr-2 h-4 w-4 animate-spin" />}
          <span>{save.isPending ? t('catalog:common.saving') : t('catalog:common.save')}</span>
        </button>
      </div>
    </form>
  );
}
