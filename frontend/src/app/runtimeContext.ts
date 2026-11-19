import { createContext, useContext } from 'react';

/** Informations d'exécution connues au démarrage (ex. : données fictives actives). */
export interface RuntimeInfo {
  readonly mocksActive: boolean;
}

export const RuntimeContext = createContext<RuntimeInfo>({ mocksActive: false });

export function useRuntime(): RuntimeInfo {
  return useContext(RuntimeContext);
}
