import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';

import { ApiError } from '@/shared/api/apiError';

import { ApiErrorMessage } from './ApiErrorMessage';

describe('ApiErrorMessage (R7)', () => {
  it('affiche le message traduit du code, sans le texte technique du serveur', () => {
    render(
      <ApiErrorMessage
        error={
          new ApiError({
            status: 409,
            code: 'INSUFFICIENT_STOCK',
            kind: 'http',
            message: 'ConstraintViolation stock_quantity',
          })
        }
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Stock insuffisant pour au moins un produit.',
    );
    expect(screen.queryByText(/ConstraintViolation/)).toBeNull();
  });

  it('429 : indique le délai Retry-After', () => {
    render(
      <ApiErrorMessage
        error={
          new ApiError({
            status: 429,
            code: 'RATE_LIMITED',
            kind: 'http',
            message: 'x',
            retryAfterSeconds: 30,
          })
        }
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Vous pourrez réessayer dans 30 seconde(s).',
    );
  });

  it('5xx : affiche la référence traceId', () => {
    render(
      <ApiErrorMessage
        error={
          new ApiError({
            status: 500,
            code: 'INTERNAL_ERROR',
            kind: 'http',
            message: 'x',
            traceId: 'abc-123',
          })
        }
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent(
      'Référence à communiquer au support : abc-123',
    );
  });

  it('4xx : n’affiche ni référence ni délai', () => {
    render(
      <ApiErrorMessage
        error={
          new ApiError({ status: 404, code: 'NOT_FOUND', kind: 'http', message: 'x', traceId: 't' })
        }
      />,
    );
    expect(screen.getByRole('alert')).toHaveTextContent("L'élément demandé est introuvable.");
    expect(screen.queryByText(/Référence/)).toBeNull();
    expect(screen.queryByText(/réessayer dans/)).toBeNull();
  });
});
