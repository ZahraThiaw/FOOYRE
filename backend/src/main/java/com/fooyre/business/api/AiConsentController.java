package com.fooyre.business.api;

import com.fooyre.business.application.BusinessSettingsService;
import com.fooyre.business.application.BusinessSettingsService.AiConsentView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consentement IA de l'entreprise ({@code /business/ai-consent}, D15) : propriétaire seul
 * ({@code AI_CONSENT_MANAGE}). {@code enabled: true} + {@code termsVersion} pour consentir,
 * {@code enabled: false} pour révoquer à tout moment (effet immédiat, journalisé).
 */
@RestController
@RequestMapping("/api/v1/business/ai-consent")
public class AiConsentController {

    public record AiConsentRequest(@NotNull Boolean enabled, @Size(max = 20) String termsVersion) {
    }

    private final BusinessSettingsService settings;

    AiConsentController(BusinessSettingsService settings) {
        this.settings = settings;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('AI_CONSENT_MANAGE')")
    public AiConsentView get() {
        return settings.aiConsent();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('AI_CONSENT_MANAGE')")
    public AiConsentView update(@Valid @RequestBody AiConsentRequest request) {
        return settings.changeAiConsent(request.enabled(), request.termsVersion());
    }
}
