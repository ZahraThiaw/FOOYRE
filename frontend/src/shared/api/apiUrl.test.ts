import { describe, expect, it } from 'vitest';

import { apiRelativePath, isApiUrl } from './apiUrl';

const ORIGIN = 'https://app.fooyre.example';

describe('apiUrl — appartenance à l’API FOOYRE', () => {
  it.each([
    ['/api/v1/me', 'me'],
    ['/api/v1/auth/refresh', 'auth/refresh'],
    ['/api/v1', ''],
    ['/api/v1/', ''],
    ['/api/v1/sales?page=0', 'sales'],
    [`${ORIGIN}/api/v1/me`, 'me'],
  ])('%s appartient à l’API (chemin relatif « %s »)', (url, expected) => {
    expect(apiRelativePath(url, '/api/v1', ORIGIN)).toBe(expected);
  });

  it.each([
    'https://autre-domaine.example/api/v1/me',
    '//autre-domaine.example/api/v1/me',
    'http://app.fooyre.example/api/v1/me', // autre schéma
    'https://app.fooyre.example:8443/api/v1/me', // autre port
    `${ORIGIN}/autre/chemin`,
    `${ORIGIN}/api/v10/me`, // préfixe trompeur
    `${ORIGIN}/api/v1-old/me`,
    `${ORIGIN}/api/v1/../admin`,
    `${ORIGIN}/api/v1/%2e%2e/admin`,
    'https://user:secret@app.fooyre.example/api/v1/me',
  ])('%s n’appartient pas à l’API', (url) => {
    expect(isApiUrl(url, '/api/v1', ORIGIN)).toBe(false);
  });

  it('accepte une URL de base absolue et ignore une barre oblique finale', () => {
    expect(isApiUrl(`${ORIGIN}/api/v1/me`, `${ORIGIN}/api/v1/`, 'https://ailleurs.example')).toBe(
      true,
    );
    expect(isApiUrl('https://ailleurs.example/api/v1/me', `${ORIGIN}/api/v1`, ORIGIN)).toBe(false);
  });

  it('considère une URL invalide comme externe', () => {
    expect(isApiUrl('http://[invalide', '/api/v1', ORIGIN)).toBe(false);
  });
});
