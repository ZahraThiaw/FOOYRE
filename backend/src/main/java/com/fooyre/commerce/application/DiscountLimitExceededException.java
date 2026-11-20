package com.fooyre.commerce.application;

import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;

/**
 * Remise au‑delà de la limite de l'employé (D14) : {@code 422 DISCOUNT_LIMIT_EXCEEDED}. Levée
 * avant toute écriture métier, après l'événement d'audit ; déclarée {@code noRollbackFor} pour
 * que seul l'événement soit validé.
 */
class DiscountLimitExceededException extends ApiException {

    DiscountLimitExceededException() {
        super(ErrorCode.DISCOUNT_LIMIT_EXCEEDED);
    }
}
