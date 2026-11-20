package com.fooyre.shared.audit;

/** Résultat de l'opération journalisée. */
public enum AuditOutcome {
    SUCCESS,
    /** L'opération a échoué (ex. identifiants refusés). */
    FAILURE,
    /** L'opération a été refusée par une règle de sécurité (ex. limitation). */
    DENIED
}
