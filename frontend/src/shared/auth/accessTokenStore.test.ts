import { describe, expect, it, vi } from 'vitest';

import {
  clearAccessToken,
  getAccessToken,
  setAccessToken,
  subscribeToAccessToken,
} from './accessTokenStore';

describe('accessTokenStore', () => {
  it('conserve le jeton en mémoire et le supprime', () => {
    expect(getAccessToken()).toBeNull();
    setAccessToken('jeton-de-test');
    expect(getAccessToken()).toBe('jeton-de-test');
    clearAccessToken();
    expect(getAccessToken()).toBeNull();
  });

  it('n’écrit jamais dans localStorage ni sessionStorage', () => {
    const setItem = vi.spyOn(Storage.prototype, 'setItem');
    setAccessToken('jeton-de-test');
    clearAccessToken();
    expect(setItem).not.toHaveBeenCalled();
    // Lecture volontaire, uniquement pour vérifier que rien n'y a été écrit.
    // eslint-disable-next-line no-restricted-properties
    expect(window.localStorage.length).toBe(0);
    // eslint-disable-next-line no-restricted-properties
    expect(window.sessionStorage.length).toBe(0);
  });

  it('notifie les abonnés', () => {
    const listener = vi.fn();
    const unsubscribe = subscribeToAccessToken(listener);
    setAccessToken('a');
    clearAccessToken();
    unsubscribe();
    setAccessToken('b');
    expect(listener.mock.calls).toEqual([['a'], [null]]);
  });
});
