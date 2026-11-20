package com.fooyre.gestion.application;

import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;

/**
 * Sortie refusée pour stock insuffisant ({@code 409 INSUFFICIENT_STOCK}). Levée AVANT toute
 * écriture métier, après l'enregistrement de l'événement d'audit dans la même transaction :
 * {@link StockService#record} la déclare en {@code noRollbackFor}, si bien que la transaction
 * valide uniquement l'événement d'audit (aucun mouvement, aucune quantité modifiée).
 * Pas de transaction {@code REQUIRES_NEW} : elle exigerait une seconde connexion pendant que la
 * première détient le verrou du stock (risque d'épuisement du pool).
 */
class InsufficientStockException extends ApiException {

    InsufficientStockException() {
        super(ErrorCode.INSUFFICIENT_STOCK);
    }
}
