package com.fooyre.intelligence.application;

import java.util.List;

/**
 * Port vers le fournisseur d'IA (CLAUDE.md §9). Aucun module métier ne connaît le fournisseur ni
 * le modèle. Implémentations : {@code AnthropicAiClient} (SDK officiel), {@code FakeAiClient} (tests).
 */
public interface AiClient {

    /** Tour de conversation déjà échangé (texte seul, jamais de donnée non filtrée). */
    record Turn(boolean fromUser, String text) {
    }

    /** Réponse STRUCTURÉE attendue du modèle (validée ensuite par le serveur). */
    record AssistantPayload(String answer, boolean insufficientData, Action action) {
    }

    /**
     * Proposition d'action : {@code type} ∈ NONE, PREPARE_SALE, SEARCH_PRODUCT, PREPARE_CUSTOMER.
     * Rien n'est exécuté sans confirmation explicite de l'utilisatrice.
     */
    record Action(String type, String customerRef, List<ActionLine> lines, String query, String customerName,
            String customerPhone) {
    }

    record ActionLine(String productRef, String quantity) {
    }

    record Usage(long inputTokens, long outputTokens, String stopReason) {
    }

    record Result(AssistantPayload payload, Usage usage, String model) {
    }

    /** Vrai si la connexion au fournisseur est configurée (clé présente). */
    boolean configured();

    /**
     * @throws AiUnavailableException fournisseur injoignable, délai dépassé, erreur du service
     * @throws AiRefusalException     refus du modèle (motif d'arrêt {@code refusal})
     */
    Result complete(String systemPrompt, List<Turn> history, String question);

    /** Catégorie d'échec du fournisseur (jamais de contenu ni de secret). */
    enum Reason { NOT_CONFIGURED, AUTHENTICATION, RATE_LIMITED, NETWORK_OR_TIMEOUT, PROVIDER_ERROR, REQUEST_REJECTED,
        INVALID_RESPONSE }

    class AiUnavailableException extends RuntimeException {
        private final Reason reason;

        public AiUnavailableException(Reason reason, String message, Throwable cause) {
            super(message, cause);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }

    class AiRefusalException extends RuntimeException {
        public AiRefusalException() {
            super("refus du modèle");
        }
    }
}
