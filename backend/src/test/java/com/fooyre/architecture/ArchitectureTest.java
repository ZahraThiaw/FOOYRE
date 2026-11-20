package com.fooyre.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.codeUnits;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaCodeUnit;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RestController;

/**
 * Règles d'architecture (CLAUDE.md §2 et §4, docs/architecture.md ADR‑01). Elles s'appliquent
 * au code livré uniquement (sources de test exclues). Les règles portant sur les modules
 * métier, encore vides en B1, tolèrent l'absence de classes ({@code allowEmptyShould}) : elles
 * s'appliqueront automatiquement dès la première classe ajoutée.
 */
@AnalyzeClasses(packages = "com.fooyre", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    static final List<String> MODULES = List.of("identity", "business", "gestion", "commerce", "finance",
            "dashboard", "reporting", "intelligence", "storefront", "platform");

    private static final Set<String> FORBIDDEN_NUMERIC_TYPES = Set.of(
            "float", "double", Float.class.getName(), Double.class.getName());

    // --- Modules -------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule modules_are_free_of_cycles = slices()
            .matching("com.fooyre.(*)..")
            .should().beFreeOfCycles()
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule shared_does_not_depend_on_business_modules = noClasses()
            .that().resideInAPackage("com.fooyre.shared..")
            .should().dependOnClassesThat().resideInAnyPackage(modulePackages(MODULES))
            .because("le socle transverse ne connaît aucun module métier");

    @ArchTest
    static final ArchRule modules_only_use_the_application_layer_of_other_modules =
            moduleBoundaries("com.fooyre", MODULES);

    /** Un module n'accède ni au domaine, ni à l'infrastructure, ni à l'API d'un autre module. */
    static ArchRule moduleBoundaries(String root, List<String> modules) {
        List<ArchRule> rules = modules.stream().map(module -> {
            String[] internals = modules.stream()
                    .filter(other -> !other.equals(module))
                    .flatMap(other -> List.of(root + "." + other + ".domain..",
                            root + "." + other + ".infrastructure..",
                            root + "." + other + ".api..").stream())
                    .toArray(String[]::new);
            return (ArchRule) noClasses().that().resideInAPackage(root + "." + module + "..")
                    .should().dependOnClassesThat().resideInAnyPackage(internals)
                    .because("un module n'utilise que le service applicatif public d'un autre module")
                    .allowEmptyShould(true);
        }).toList();
        return CompositeArchRule.of(rules);
    }

    // --- Isolation par entreprise (CLAUDE.md §3, architecture.md §5.2) ----------------------------

    /** Modules dont les données appartiennent à une entreprise (identity et business exclus : globaux). */
    static final List<String> TENANT_SCOPED_MODULES = List.of("gestion", "commerce", "finance", "dashboard",
            "reporting", "intelligence", "storefront");

    private static final Set<String> UNSCOPED_REPOSITORY_METHODS = Set.of(
            "findById", "findAllById", "getReferenceById", "getById", "getOne", "existsById", "deleteById",
            "deleteAllById", "delete", "deleteAll", "deleteAllInBatch", "deleteInBatch", "findAll");

    @ArchTest
    static final ArchRule tenant_scoped_modules_never_access_repositories_by_identifier_alone =
            tenantRepositoryAccess("com.fooyre", TENANT_SCOPED_MODULES);

    static ArchRule tenantRepositoryAccess(String root, List<String> modules) {
        return noClasses()
            .that().resideInAnyPackage(modules.stream().map(module -> root + "." + module + "..").toArray(String[]::new))
            .should().callMethodWhere(DescribedPredicate.describe(
                    "une méthode de repository non filtrée par entreprise (findById, findAll sans critère, delete…)",
                    call -> UNSCOPED_REPOSITORY_METHODS.contains(call.getTarget().getName())
                            && call.getTarget().getOwner().isAssignableTo(
                                    "org.springframework.data.repository.Repository")
                            // findAll(Specification, Pageable) : critères construits avec l'entreprise (ProductSearch).
                            && !(call.getTarget().getName().equals("findAll")
                                    && call.getTarget().getRawParameterTypes().stream().anyMatch(type ->
                                            type.getName().equals("org.springframework.data.jpa.domain.Specification")))))
            .because("toute lecture ou écriture par identifiant filtre par business_id (findByIdAndBusinessId)")
            .allowEmptyShould(true);
    }

    // --- Couches -------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule domain_does_not_depend_on_outer_layers = noClasses()
            .that().resideInAPackage("com.fooyre.*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "com.fooyre.*.application..", "com.fooyre.*.api..", "com.fooyre.*.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule application_does_not_depend_on_api_or_infrastructure = noClasses()
            .that().resideInAPackage("com.fooyre.*.application..")
            .should().dependOnClassesThat().resideInAnyPackage("com.fooyre.*.api..", "com.fooyre.*.infrastructure..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllers_live_in_api_packages = noClasses()
            .that().areAnnotatedWith(RestController.class).or().areAnnotatedWith(Controller.class)
            .should().resideOutsideOfPackage("com.fooyre.*.api..")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllers_never_expose_jpa_entities = noClasses()
            .that().areAnnotatedWith(RestController.class).or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().areAnnotatedWith("jakarta.persistence.Entity")
            .because("les entités JPA ne sortent jamais des contrôleurs : DTO obligatoires")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule controllers_never_use_repositories = noClasses()
            .that().areAnnotatedWith(RestController.class).or().areAnnotatedWith(Controller.class)
            .should().dependOnClassesThat().areAssignableTo(
                    "org.springframework.data.repository.Repository")
            .allowEmptyShould(true);

    // --- Argent : jamais float ni double (CLAUDE.md §4) -------------------------------------------

    @ArchTest
    static final ArchRule no_float_or_double_fields = noFields()
            .should().haveRawType(rawTypeIn(FORBIDDEN_NUMERIC_TYPES))
            .because("montants, coûts, quantités et taux : BigDecimal exclusivement")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule no_float_or_double_in_signatures = codeUnits()
            .should(notUseFloatingPointInSignature())
            .because("montants, coûts, quantités et taux : BigDecimal exclusivement");

    @ArchTest
    static final ArchRule no_big_decimal_from_double = noClasses()
            .should().callConstructor(BigDecimal.class, double.class)
            .orShould().callMethod(BigDecimal.class, "valueOf", double.class)
            .because("construire les BigDecimal depuis une chaîne ou BigDecimal.valueOf(long)");

    // --- Injection ---------------------------------------------------------------------------------

    @ArchTest
    static final ArchRule constructor_injection_only = noFields()
            .should().beAnnotatedWith(Autowired.class)
            .because("injection par constructeur (CLAUDE.md §11)")
            .allowEmptyShould(true);

    @ArchTest
    static final ArchRule configuration_properties_are_immutable_records = fields()
            .that().areDeclaredInClassesThat().areAnnotatedWith(
                    "org.springframework.boot.context.properties.ConfigurationProperties")
            .should().beFinal()
            .allowEmptyShould(true);

    // ---------------------------------------------------------------------------------------------

    private static String[] modulePackages(List<String> modules) {
        return modules.stream().map(module -> "com.fooyre." + module + "..").toArray(String[]::new);
    }

    private static DescribedPredicate<JavaClass> rawTypeIn(Set<String> names) {
        return DescribedPredicate.describe("float, double, Float ou Double",
                type -> names.contains(type.getName()));
    }

    private static ArchCondition<JavaCodeUnit> notUseFloatingPointInSignature() {
        return new ArchCondition<>("not use float, double, Float or Double in parameters or return type") {
            @Override
            public void check(JavaCodeUnit codeUnit, ConditionEvents events) {
                boolean returns = FORBIDDEN_NUMERIC_TYPES.contains(codeUnit.getRawReturnType().getName());
                boolean takes = codeUnit.getRawParameterTypes().stream()
                        .anyMatch(type -> FORBIDDEN_NUMERIC_TYPES.contains(type.getName()));
                if (returns || takes) {
                    events.add(SimpleConditionEvent.violated(codeUnit,
                            codeUnit.getFullName() + " utilise float/double dans sa signature"));
                }
            }
        };
    }
}
