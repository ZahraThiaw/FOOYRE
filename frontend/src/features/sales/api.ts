/**
 * Appels des ventes et du tableau de bord. Création et confirmation portent chacune une clé
 * d'idempotence générée UNE fois par action (un double clic ne crée ni deux ventes ni deux sorties).
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { PageResponse } from '@/features/catalog/types';
import { httpClient } from '@/shared/api/httpClient';

import type { Dashboard, Sale, SaleInput, SaleQuote, SaleStatus, SaleSummary } from './types';

export const salesKeys = {
  all: ['sales'] as const,
  list: (status: string, page: number, customerId: string) => ['sales', 'list', status, page, customerId] as const,
  item: (id: string) => ['sales', 'item', id] as const,
  dashboard: ['dashboard'] as const,
};

const path = (id: string) => `/sales/${encodeURIComponent(id)}`;

export function useSales(status: SaleStatus | 'ALL', page: number, customerId = '') {
  return useQuery({
    queryKey: salesKeys.list(status, page, customerId),
    queryFn: async () => {
      const params: Record<string, string | number> = { status, page, size: 20 };
      if (customerId !== '') params.customerId = customerId;
      return (await httpClient.get<PageResponse<SaleSummary>>('/sales', { params })).data;
    },
    placeholderData: keepPreviousData,
  });
}

export function useSale(id: string) {
  return useQuery({
    queryKey: salesKeys.item(id),
    queryFn: async () => (await httpClient.get<Sale>(path(id))).data,
    enabled: id !== '',
  });
}

export async function quoteSale(input: SaleInput): Promise<SaleQuote> {
  return (await httpClient.post<SaleQuote>('/sales/quote', input)).data;
}

export function useDashboard(enabled: boolean) {
  return useQuery({
    queryKey: salesKeys.dashboard,
    queryFn: async () => (await httpClient.get<Dashboard>('/dashboard')).data,
    enabled,
  });
}

/** Enregistre le brouillon (création ou mise à jour), puis confirme si demandé. */
export function useSaveSale() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (args: {
      readonly existing: Sale | undefined;
      readonly input: SaleInput;
      readonly createKey: string;
      readonly confirmKey: string | null;
    }) => {
      const draft = args.existing
        ? (await httpClient.put<Sale>(path(args.existing.id), { ...args.input, version: args.existing.version })).data
        : (await httpClient.post<Sale>('/sales', args.input, { headers: { 'Idempotency-Key': args.createKey } })).data;
      if (args.confirmKey === null) return draft;
      return (
        await httpClient.post<Sale>(`${path(draft.id)}/confirm`, null, {
          headers: { 'Idempotency-Key': args.confirmKey },
        })
      ).data;
    },
    onSuccess: (sale) => {
      queryClient.setQueryData(salesKeys.item(sale.id), sale);
      void queryClient.invalidateQueries({ queryKey: salesKeys.all });
      void queryClient.invalidateQueries({ queryKey: salesKeys.dashboard });
      void queryClient.invalidateQueries({ queryKey: ['stock'] });
    },
  });
}

export function useCancelDraft(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => (await httpClient.post<Sale>(`${path(id)}/cancel`)).data,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: salesKeys.all }),
  });
}

/** Création d'un brouillon (proposition de l'assistante confirmée par l'utilisatrice). */
export async function createDraft(input: SaleInput, idempotencyKey: string): Promise<Sale> {
  return (await httpClient.post<Sale>('/sales', input, { headers: { 'Idempotency-Key': idempotencyKey } })).data;
}
