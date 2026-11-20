package com.fooyre;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import com.fooyre.support.TestAccounts;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

/**
 * Test d'intégration complet : contexte Spring réel, PostgreSQL 18 (Testcontainers), MockMvc
 * avec la chaîne Spring Security. Une configuration unique permet de réutiliser le même
 * contexte (et le même conteneur) dans toutes les classes de test.
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import({TestcontainersConfiguration.class, TestAccounts.class})
public @interface IntegrationTest {
}
