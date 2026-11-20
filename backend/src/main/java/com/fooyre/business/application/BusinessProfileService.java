package com.fooyre.business.application;

import com.fooyre.business.domain.BusinessRepository;
import com.fooyre.shared.error.ApiException;
import com.fooyre.shared.error.ErrorCode;
import com.fooyre.shared.tenant.TenantContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profil de l'entreprise ACTIVE : l'identifiant vient uniquement de {@link TenantContext}. */
@Service
public class BusinessProfileService {

    private final BusinessRepository businesses;

    BusinessProfileService(BusinessRepository businesses) {
        this.businesses = businesses;
    }

    @Transactional(readOnly = true)
    public BusinessProfileView activeBusinessProfile() {
        return businesses.findById(TenantContext.requireBusinessId())
                .map(business -> new BusinessProfileView(business.getId(), business.getName(),
                        business.getLegalName(), business.getCurrency(), business.getTimezone(),
                        business.getDefaultLocale()))
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND));
    }
}
