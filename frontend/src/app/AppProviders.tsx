import { QueryClientProvider, type QueryClient } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { I18nextProvider } from 'react-i18next';

import i18n from '@/shared/i18n';

import { RuntimeContext, type RuntimeInfo } from './runtimeContext';

interface AppProvidersProps {
  readonly queryClient: QueryClient;
  readonly runtime: RuntimeInfo;
  readonly children: ReactNode;
}

export function AppProviders({ queryClient, runtime, children }: AppProvidersProps) {
  return (
    <RuntimeContext.Provider value={runtime}>
      <I18nextProvider i18n={i18n}>
        <QueryClientProvider client={queryClient}>{children}</QueryClientProvider>
      </I18nextProvider>
    </RuntimeContext.Provider>
  );
}
