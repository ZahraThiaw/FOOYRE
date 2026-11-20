package com.fooyre.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fooyre.identity.application.DemoAccountService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** L'initialiseur des comptes de démonstration n'existe QUE dans les profils dev et demo (jamais prod seul). */
class DemoAccountInitializerProfileTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties({DemoAccountProperties.class, DemoSecondAccountProperties.class})
    static class Fixture {

        @Bean
        DemoAccountService demoAccountService() {
            return mock(DemoAccountService.class);
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(Fixture.class, DemoAccountInitializer.class)
            .withPropertyValues(
                    "fooyre.demo.owner-email=demo@fooyre.local",
                    "fooyre.demo.owner-full-name=Administrateur Démo",
                    "fooyre.demo.owner-password=Valeur-de-test-uniquement-1",
                    "fooyre.demo.business-name=FOOYRE Commerce Démo");

    @ParameterizedTest
    @ValueSource(strings = {"prod", "test", "default"})
    void initializerIsAbsentOutsideDevAndDemo(String profile) {
        runner.withPropertyValues("spring.profiles.active=" + profile)
                .run(context -> assertThat(context).hasNotFailed().doesNotHaveBean(DemoAccountInitializer.class));
    }

    /** Démonstration publique : prod + demo, activés explicitement ensemble. */
    @Test
    void initializerExistsAndRunsWithProdAndDemo() {
        runner.withPropertyValues("spring.profiles.active=prod,demo").run(context -> {
            assertThat(context).hasSingleBean(DemoAccountInitializer.class);
            DemoAccountService service = context.getBean(DemoAccountService.class);
            when(service.ensureDemoAccount(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(DemoAccountService.Result.CREATED);
            context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
            verify(service).ensureDemoAccount("demo@fooyre.local", "Administrateur Démo",
                    "Valeur-de-test-uniquement-1", "FOOYRE Commerce Démo");
        });
    }

    @Test
    void initializerExistsAndRunsInDev() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasSingleBean(DemoAccountInitializer.class);
            DemoAccountService service = context.getBean(DemoAccountService.class);
            when(service.ensureDemoAccount(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(DemoAccountService.Result.CREATED);
            context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
            verify(service).ensureDemoAccount("demo@fooyre.local", "Administrateur Démo",
                    "Valeur-de-test-uniquement-1", "FOOYRE Commerce Démo");
        });
    }

    @Test
    void withoutPasswordNothingIsCreated() {
        runner.withPropertyValues("spring.profiles.active=dev", "fooyre.demo.owner-password=").run(context -> {
            context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
            verify(context.getBean(DemoAccountService.class), never())
                    .ensureDemoAccount(anyString(), anyString(), anyString(), anyString());
        });
    }

    private static final String[] SECOND = {
            "fooyre.demo2.owner-email=aminata.demo@fooyre.local",
            "fooyre.demo2.owner-full-name=Aminata Sarr",
            "fooyre.demo2.owner-password=Autre-valeur-de-test-2",
            "fooyre.demo2.business-name=Nafi Chic"};

    @Test
    void bothAccountsAreProvisionedThroughTheSameService() {
        runner.withPropertyValues("spring.profiles.active=dev").withPropertyValues(SECOND).run(context -> {
            DemoAccountService service = context.getBean(DemoAccountService.class);
            when(service.ensureDemoAccount(anyString(), anyString(), anyString(), anyString()))
                    .thenReturn(DemoAccountService.Result.CREATED);
            context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
            verify(service).ensureDemoAccount("demo@fooyre.local", "Administrateur Démo",
                    "Valeur-de-test-uniquement-1", "FOOYRE Commerce Démo");
            verify(service).ensureDemoAccount("aminata.demo@fooyre.local", "Aminata Sarr",
                    "Autre-valeur-de-test-2", "Nafi Chic");
        });
    }

    @Test
    void secondAccountDoesNotDependOnTheFirst() {
        runner.withPropertyValues("spring.profiles.active=dev", "fooyre.demo.owner-password=").withPropertyValues(SECOND)
                .run(context -> {
                    DemoAccountService service = context.getBean(DemoAccountService.class);
                    when(service.ensureDemoAccount(anyString(), anyString(), anyString(), anyString()))
                            .thenReturn(DemoAccountService.Result.CREATED);
                    context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
                    verify(service, never()).ensureDemoAccount(eq("demo@fooyre.local"), anyString(), anyString(), anyString());
                    verify(service).ensureDemoAccount("aminata.demo@fooyre.local", "Aminata Sarr",
                            "Autre-valeur-de-test-2", "Nafi Chic");
                });
    }

    @Test
    void failureOfTheFirstDoesNotPreventTheSecond() {
        runner.withPropertyValues("spring.profiles.active=dev").withPropertyValues(SECOND).run(context -> {
            DemoAccountService service = context.getBean(DemoAccountService.class);
            when(service.ensureDemoAccount(eq("demo@fooyre.local"), anyString(), anyString(), anyString()))
                    .thenThrow(new IllegalArgumentException("mot de passe trop court"));
            when(service.ensureDemoAccount(eq("aminata.demo@fooyre.local"), anyString(), anyString(), anyString()))
                    .thenReturn(DemoAccountService.Result.CREATED);
            context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
            verify(service).ensureDemoAccount("aminata.demo@fooyre.local", "Aminata Sarr",
                    "Autre-valeur-de-test-2", "Nafi Chic");
        });
    }

    @Test
    void secondAccountWithoutPasswordIsNotCreated() {
        runner.withPropertyValues("spring.profiles.active=dev").withPropertyValues(SECOND)
                .withPropertyValues("fooyre.demo2.owner-password=").run(context -> {
                    when(context.getBean(DemoAccountService.class).ensureDemoAccount(anyString(), anyString(), anyString(),
                            anyString())).thenReturn(DemoAccountService.Result.ALREADY_PRESENT);
                    context.getBean(DemoAccountInitializer.class).run(new DefaultApplicationArguments());
                    verify(context.getBean(DemoAccountService.class), never())
                            .ensureDemoAccount(eq("aminata.demo@fooyre.local"), anyString(), anyString(), anyString());
                });
    }

    @Test
    void propertiesNeverPrintThePassword() {
        assertThat(new DemoSecondAccountProperties("a", "b", "secret-de-test-2", "c").toString())
                .doesNotContain("secret-de-test-2");
        assertThat(new DemoAccountProperties("a", "b", "secret-de-test", "c").toString()).doesNotContain("secret-de-test");
    }
}
