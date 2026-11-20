import { useTranslation } from 'react-i18next';
import { Link } from 'react-router';

import { hasPermission, useMe } from '@/features/session/api';
import type { StockMovement } from '@/features/stock/types';
import { formatMoney } from '@/shared/lib/formatMoney';
import { formatQuantity } from '@/shared/lib/formatQuantity';

const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'short', timeStyle: 'short' };

/**
 * Historique en LECTURE SEULE (aucune action de modification ni de suppression) : tableau sur grand
 * écran, cartes sur mobile.
 */
export function MovementsTable({
  movements,
  showProduct,
}: {
  readonly movements: readonly StockMovement[];
  readonly showProduct: boolean;
}) {
  const { t, i18n } = useTranslation(['stock']);
  const dateFormat = new Intl.DateTimeFormat(i18n.language, DATE_FORMAT);
  const { data: me } = useMe();
  const currency = me?.activeBusiness?.currency ?? '';
  // Le serveur ne renvoie jamais le coût sans COST_READ ; la colonne n'est pas affichée non plus.
  const showsCost = hasPermission(me, 'COST_READ') && currency !== '';
  const costOf = (movement: StockMovement) =>
    movement.unitCost !== undefined ? formatMoney(movement.unitCost, currency) : '—';
  const unitOf = (movement: StockMovement) => (movement.unit ? t(`stock:unitShort.${movement.unit}`) : '');
  const signed = (movement: StockMovement) =>
    `${movement.type === 'EXIT' || movement.type === 'ADJUSTMENT_OUT' ? '−' : '+'}${formatQuantity(movement.quantity)}`;

  return (
    <>
      <div className="hidden overflow-x-auto lg:block">
      <table className="w-full text-left text-sm">
        <thead className="border-b border-line text-xs uppercase tracking-wide text-ink-muted">
          <tr>
            <th scope="col" className="py-2 pr-3 font-medium">{t('stock:history.date')}</th>
            {showProduct && <th scope="col" className="py-2 pr-3 font-medium">{t('stock:history.product')}</th>}
            <th scope="col" className="py-2 pr-3 font-medium">{t('stock:history.type')}</th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">{t('stock:history.quantity')}</th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">{t('stock:history.before')}</th>
            <th scope="col" className="py-2 pr-3 text-right font-medium">{t('stock:history.after')}</th>
            {showsCost && (
              <th scope="col" className="py-2 pr-3 text-right font-medium">
                {t('stock:history.unitCost')}
              </th>
            )}
            <th scope="col" className="py-2 pr-3 font-medium">{t('stock:history.reason')}</th>
            <th scope="col" className="py-2 font-medium">{t('stock:history.author')}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-line">
          {movements.map((movement) => (
            <tr key={movement.id} className="hover:bg-brand-soft/50">
              <td className="py-2 pr-3 whitespace-nowrap">{dateFormat.format(new Date(movement.createdAt))}</td>
              {showProduct && (
                <td className="py-2 pr-3">
                  <Link to={`/stock/${movement.productId}`} className="font-medium hover:underline">
                    {movement.productName}
                  </Link>
                </td>
              )}
              <td className="py-2 pr-3">{t(`stock:movementTypes.${movement.type}`)}</td>
              <td className={`py-3 pr-3 text-right font-semibold whitespace-nowrap ${movement.type === 'EXIT' || movement.type === 'ADJUSTMENT_OUT' ? 'text-ink-muted' : 'text-ink'}`}>
                {signed(movement)} {unitOf(movement)}
              </td>
              <td className="py-2 pr-3 text-right">{formatQuantity(movement.quantityBefore)}</td>
              <td className="py-2 pr-3 text-right">{formatQuantity(movement.quantityAfter)}</td>
              {showsCost && <td className="py-2 pr-3 text-right whitespace-nowrap">{costOf(movement)}</td>}
              <td className="py-2 pr-3">
                {movement.reason}
                {movement.reference && <span className="block text-xs text-ink-muted">{movement.reference}</span>}
              </td>
              <td className="py-2">{movement.author.fullName ?? t('stock:history.unknownAuthor')}</td>
            </tr>
          ))}
        </tbody>
      </table>
      </div>
      <ul className="divide-y divide-line lg:hidden">
        {movements.map((movement) => (
          <li key={movement.id} className="py-3 text-sm">
            <div className="flex justify-between">
              <span className="font-medium">{t(`stock:movementTypes.${movement.type}`)}</span>
              <span className="font-semibold">
                {signed(movement)} {unitOf(movement)}
              </span>
            </div>
            {showProduct && <p className="mt-0.5">{movement.productName}</p>}
            <p className="mt-0.5 text-ink-muted">
              {formatQuantity(movement.quantityBefore)} → {formatQuantity(movement.quantityAfter)} ·{' '}
              {dateFormat.format(new Date(movement.createdAt))}
            </p>
            {showsCost && movement.unitCost !== undefined && (
              <p className="mt-0.5">
                {t('stock:history.unitCost')} : {costOf(movement)}
              </p>
            )}
            {movement.reason && <p className="mt-0.5">{movement.reason}</p>}
            <p className="mt-0.5 text-xs text-ink-muted">
              {movement.author.fullName ?? t('stock:history.unknownAuthor')}
            </p>
          </li>
        ))}
      </ul>
    </>
  );
}
