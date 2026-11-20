package com.fooyre.architecture;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Auto‑test : les règles d'ArchitectureTest échouent réellement sur du code fautif
 * (classes de test du package fixtures). Garantit qu'elles ne passent pas « à vide ».
 */
class ArchitectureRulesSelfTest {

    private static final String FIXTURES = "com.fooyre.architecture.fixtures";
    private static final JavaClasses BAD_CODE = new ClassFileImporter().importPackages(FIXTURES);

    private static void assertViolated(ArchRule rule, String expectedFragment) {
        assertThatThrownBy(() -> rule.check(BAD_CODE))
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining(expectedFragment);
    }

    @Test
    void moduleBoundaryRuleDetectsAccessToAnotherModuleDomain() {
        assertViolated(ArchitectureTest.moduleBoundaries(FIXTURES, List.of("sales", "stock")), "StockItem");
    }

    @Test
    void floatAndDoubleFieldsAreDetected() {
        assertViolated(ArchitectureTest.no_float_or_double_fields, "total");
    }

    @Test
    void floatAndDoubleSignaturesAreDetected() {
        assertViolated(ArchitectureTest.no_float_or_double_in_signatures, "rate");
    }

    @Test
    void bigDecimalFromDoubleIsDetected() {
        assertViolated(ArchitectureTest.no_big_decimal_from_double, "BigDecimal.<init>(double)");
    }

    @Test
    void unscopedRepositoryAccessIsDetected() {
        assertViolated(ArchitectureTest.tenantRepositoryAccess(FIXTURES, List.of("catalog")), "findById");
    }

    @Test
    void fieldInjectionIsDetected() {
        assertViolated(ArchitectureTest.constructor_injection_only, "injected");
    }
}
