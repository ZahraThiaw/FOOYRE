package com.fooyre.shared.tenant;

import static com.fooyre.support.TenantTestSupport.runAsBusiness;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fooyre.shared.error.ApiException;
import java.lang.reflect.Field;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** {@link TenantScopedEntity} : business_id toujours celui de l'entreprise active, jamais un autre. */
class TenantScopedEntityTest {

    static class Sample extends TenantScopedEntity {
    }

    private static void forceBusiness(Sample sample, UUID businessId) throws Exception {
        Field field = TenantScopedEntity.class.getDeclaredField("businessId");
        field.setAccessible(true);
        field.set(sample, businessId);
    }

    @Test
    void theActiveBusinessIsAssignedOnInsert() {
        UUID business = UUID.randomUUID();
        Sample sample = new Sample();
        runAsBusiness(business, sample::assignBusiness);
        assertThat(sample.getBusinessId()).isEqualTo(business);
    }

    @Test
    void insertWithoutActiveBusinessIsRefused() {
        assertThatThrownBy(new Sample()::assignBusiness).isInstanceOf(ApiException.class);
    }

    @Test
    void anEntityCarryingAnotherBusinessIsRefused() throws Exception {
        Sample sample = new Sample();
        forceBusiness(sample, UUID.randomUUID());
        assertThatThrownBy(() -> runAsBusiness(UUID.randomUUID(), sample::assignBusiness))
                .isInstanceOf(IllegalStateException.class);
    }
}
