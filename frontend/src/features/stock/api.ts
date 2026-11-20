/**
 * Appels du stock (TanStack Query + httpClient). Toute opération porte une clé d'idempotence
 * générée à la saisie ; le serveur verrouille, contrôle et enregistre le mouvement.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import { catalogKeys } from '@/features/catalog/api';
import { httpClient } from '@/shared/api/httpClient';

import type {
  BusinessSettings,
  OperationResponse,
  StockItem,
  StockMovement,
  StockQuery,
  StockSummary,
} from './types';
import type { PageResponse } from '@/features/catalog/types';

export const stockKeys = {
  all: ['stock'] as const,
  list: (query: StockQuery) => ['stock', 'list', query] as const,
  item: (productId: string) => ['stock', 'item', productId] as const,
  summary: ['stock', 'summary'] as const,
  movements: (productId: string, page: number) => ['stock', 'movements', productId, page] as const,
  settings: ['business', 'settings'] as const,
};

const path = (productId: string) => `/stock/${encodeURIComponent(productId)}`;

export function useStockList(query: StockQuery) {
  return useQuery({
    queryKey: stockKeys.list(query),
    queryFn: async () => {
      const params: Record<string, string | number> = {
        status: query.status,
        sort: query.sort,
        page: query.page,
        size: query.size,
      };
      if (query.q.trim() !== '') params.q = query.q.trim();
      if (query.categoryId !== '') params.categoryId = query.categoryId;
      return (await httpClient.get<PageResponse<StockItem>>('/stock', { params })).data;
    },
    placeholderData: keepPreviousData,
  });
}

export function useStockSummary() {
  return useQuery({
    queryKey: stockKeys.summary,
    queryFn: async () => (await httpClient.get<StockSummary>('/stock/summary')).data,
  });
}

export function useStockItem(productId: string) {
  return useQuery({
    queryKey: stockKeys.item(productId),
    queryFn: async () => (await httpClient.get<StockItem>(path(productId))).data,
  });
}

/** `productId` vide : historique de toute l'entreprise. */
export function useStockMovements(productId: string, page: number, size = 20, enabled = true) {
  return useQuery({
    queryKey: [...stockKeys.movements(productId, page), size],
    queryFn: async () => {
      const url = productId === '' ? '/stock/movements' : `${path(productId)}/movements`;
      return (await httpClient.get<PageResponse<StockMovement>>(url, { params: { page, size } })).data;
    },
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** Paramètres sensibles (lecture : BUSINESS_SETTINGS_READ). */
export function useBusinessSettings(enabled: boolean) {
  return useQuery({
    queryKey: stockKeys.settings,
    queryFn: async () => (await httpClient.get<BusinessSettings>('/business/settings')).data,
    enabled,
  });
}

export function useUpdateBusinessSettings() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (settings: BusinessSettings) =>
      (await httpClient.put<BusinessSettings>('/business/settings', settings)).data,
    onSuccess: (saved) => queryClient.setQueryData(stockKeys.settings, saved),
  });
}

export interface OperationRequest {
  readonly productId: string;
  readonly endpoint: 'initialize' | 'entries' | 'exits' | 'adjustments';
  readonly body: Record<string, string | null>;
  /** Générée à l'ouverture du formulaire, réutilisée à chaque nouvel envoi. */
  readonly idempotencyKey: string;
}

export function useStockOperation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ productId, endpoint, body, idempotencyKey }: OperationRequest) =>
      (
        await httpClient.post<OperationResponse>(`${path(productId)}/${endpoint}`, body, {
          headers: { 'Idempotency-Key': idempotencyKey },
        })
      ).data,
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: stockKeys.all });
      void queryClient.invalidateQueries({ queryKey: catalogKeys.all });
    },
  });
}

export function useChangeMinimum() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async ({ productId, minimumQuantity }: { productId: string; minimumQuantity: string }) =>
      (await httpClient.put<StockItem>(`${path(productId)}/minimum-quantity`, { minimumQuantity })).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: stockKeys.all }),
  });
}
