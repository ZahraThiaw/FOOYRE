/**
 * Appels des clients (TanStack Query + httpClient). Recherche, filtres et pagination côté serveur.
 * La création porte une clé d'idempotence générée à l'ouverture du formulaire : un double envoi
 * ne crée jamais deux clients.
 */
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

import type { PageResponse } from '@/features/catalog/types';
import { httpClient } from '@/shared/api/httpClient';

import type { Customer, CustomerInput, CustomerQuery } from './types';

export const customerKeys = {
  all: ['customers'] as const,
  list: (query: CustomerQuery) => ['customers', 'list', query] as const,
  item: (id: string) => ['customers', 'item', id] as const,
};

const path = (id: string) => `/customers/${encodeURIComponent(id)}`;

export function useCustomers(query: CustomerQuery) {
  return useQuery({
    queryKey: customerKeys.list(query),
    queryFn: async () => {
      const params: Record<string, string | number> = {
        status: query.status,
        sort: query.sort,
        page: query.page,
        size: query.size,
      };
      if (query.q.trim() !== '') params.q = query.q.trim();
      return (await httpClient.get<PageResponse<Customer>>('/customers', { params })).data;
    },
    placeholderData: keepPreviousData,
  });
}

export function useCustomer(id: string) {
  return useQuery({
    queryKey: customerKeys.item(id),
    queryFn: async () => (await httpClient.get<Customer>(path(id))).data,
    enabled: id !== '',
  });
}

export function useSaveCustomer(existing: Customer | undefined, idempotencyKey: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (input: CustomerInput) =>
      existing
        ? (await httpClient.put<Customer>(path(existing.id), input)).data
        : (await httpClient.post<Customer>('/customers', input, { headers: { 'Idempotency-Key': idempotencyKey } }))
            .data,
    onSuccess: (saved) => {
      queryClient.setQueryData(customerKeys.item(saved.id), saved);
      void queryClient.invalidateQueries({ queryKey: customerKeys.all });
    },
  });
}

export function useArchiveCustomer(id: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async () => {
      await httpClient.delete(path(id));
    },
    onSuccess: () => queryClient.invalidateQueries({ queryKey: customerKeys.all }),
  });
}
