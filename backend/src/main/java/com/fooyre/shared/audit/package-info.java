/**
 * Journal d'audit (MVP-1.1) : table audit_log en insertion seule, service AuditService
 * (dans la transaction de l'opération pour les succès, transaction séparée pour les échecs),
 * métadonnées limitées à la liste blanche AuditDetail. Aucune interface de consultation.
 */
package com.fooyre.shared.audit;
