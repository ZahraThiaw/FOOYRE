import { describe, expect, it } from 'vitest';

import { startMocking } from './entry.disabled';

describe('point d’entrée des mocks désactivé', () => {
  it('ne démarre aucune donnée fictive', async () => {
    await expect(startMocking()).resolves.toBe(false);
  });
});
