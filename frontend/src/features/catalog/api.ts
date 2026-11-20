/**
 * Appels du catalogue (TanStack Query + httpClient). Toutes les données viennent du serveur et sont
 * persistées par lui : aucune donnée métier n'est conservée dans le navigateur au‑delà du cache
 * mémoire de TanStack Query, vidé à la déconnexion.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { httpClient } from '@/shared/api/httpClient';

import type {
  Category,
  CategoryInput,
  PageResponse,
  Product,
  ProductInput,
  ProductQuery,
  StatusFilter,
} from './types';

/** Taille maximale de page acceptée par le serveur (liste déroulante des catégories). */
export const MAX_PAGE_SIZE = 100;

export const catalogKeys = {
  all: ['catalog'] as const,
  categories: (status: StatusFilter, page: number, size: number) =>
    ['catalog', 'categories', status, page, size] as const,
  category: (id: string) => ['catalog', 'category', id] as const,
  products: (query: ProductQuery) => ['catalog', 'products', query] as const,
  product: (id: string) => ['catalog', 'product', id] as const,
};

// ─── Catégories ──────────────────────────────────────────────────────────────────────────────

export async function fetchCategories(
  status: StatusFilter,
  page = 0,
  size = MAX_PAGE_SIZE,
): Promise<PageResponse<Category>> {
  const response = await httpClient.get<PageResponse<Category>>('/categories', {
    params: { status, page, size },
  });
  return response.data;
}

export function useCategories(status: StatusFilter, page = 0, size = MAX_PAGE_SIZE) {
  return useQuery({
    queryKey: catalogKeys.categories(status, page, size),
    queryFn: () => fetchCategories(status, page, size),
    placeholderData: keepPreviousData,
  });
}

export async function createCategory(input: CategoryInput): Promise<Category> {
  return (await httpClient.post<Category>('/categories', input)).data;
}

export async function updateCategory(id: string, input: CategoryInput): Promise<Category> {
  return (await httpClient.put<Category>(`/categories/${encodeURIComponent(id)}`, input)).data;
}

export async function archiveCategory(id: string): Promise<void> {
  await httpClient.delete(`/categories/${encodeURIComponent(id)}`);
}

// ─── Produits ────────────────────────────────────────────────────────────────────────────────

export async function fetchProducts(query: ProductQuery): Promise<PageResponse<Product>> {
  const params: Record<string, string | number> = {
    status: query.status,
    sort: query.sort,
    page: query.page,
    size: query.size,
  };
  if (query.q.trim() !== '') params.q = query.q.trim();
  if (query.categoryId !== '') params.categoryId = query.categoryId;
  const response = await httpClient.get<PageResponse<Product>>('/products', { params });
  return response.data;
}

export function useProducts(query: ProductQuery) {
  return useQuery({
    queryKey: catalogKeys.products(query),
    queryFn: () => fetchProducts(query),
    placeholderData: keepPreviousData,
  });
}

export async function fetchProduct(id: string): Promise<Product> {
  return (await httpClient.get<Product>(`/products/${encodeURIComponent(id)}`)).data;
}

export function useProduct(id: string) {
  return useQuery({ queryKey: catalogKeys.product(id), queryFn: () => fetchProduct(id), enabled: id !== '' });
}

export async function createProduct(input: ProductInput): Promise<Product> {
  return (await httpClient.post<Product>('/products', input)).data;
}

export async function updateProduct(id: string, input: ProductInput): Promise<Product> {
  return (await httpClient.put<Product>(`/products/${encodeURIComponent(id)}`, input)).data;
}

export async function archiveProduct(id: string): Promise<void> {
  await httpClient.delete(`/products/${encodeURIComponent(id)}`);
}

// ─── Mutations (cache du catalogue invalidé après chaque écriture confirmée) ──────────────────

export function useCatalogMutation<TVariables, TResult>(
  mutationFn: (variables: TVariables) => Promise<TResult>,
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: catalogKeys.all }),
  });
}
