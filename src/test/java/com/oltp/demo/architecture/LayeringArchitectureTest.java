package com.oltp.demo.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

/**
 * Architecture tests using ArchUnit.
 *
 * Enforces architectural rules and best practices:
 * - Layered architecture (controller → service → repository → domain)
 * - Naming conventions
 * - Dependency rules
 * - Package structure
 *
 * These tests ensure:
 * - Controllers don't directly access repositories
 * - Services are properly annotated
 * - Repositories don't depend on controllers
 * - Domain entities are pure (no service dependencies)
 *
 * Implements constitution.md principle VII: "Simplicity & Pragmatism"
 * by enforcing clean architecture boundaries.
 *
 * @see <a href="https://www.archunit.org/">ArchUnit Documentation</a>
 */
class LayeringArchitectureTest {

    private static JavaClasses importedClasses;

    @BeforeAll
    static void setUp() {
        importedClasses = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.oltp.demo");
    }

    /**
     * Tests layered architecture rules.
     *
     * Enforces:
     * - Controller layer can access Service layer
     * - Service layer can access Repository and Domain layers
     * - Repository layer can access Domain layer
     * - Domain layer is independent (no dependencies)
     * - Util layer can be accessed by all layers
     */
    @Test
    void layerDependenciesShouldBeRespected() {
        ArchRule rule = layeredArchitecture()
            .consideringAllDependencies()
            .layer("Controller").definedBy("..controller..")
            .layer("Service").definedBy("..service..")
            .layer("Repository").definedBy("..repository..")
            .layer("Domain").definedBy("..domain..")
            .layer("Config").definedBy("..config..")
            .layer("Util").definedBy("..util..")

            .whereLayer("Controller").mayNotBeAccessedByAnyLayer()
            .whereLayer("Service").mayOnlyBeAccessedByLayers("Controller", "Service")
            .whereLayer("Repository").mayOnlyBeAccessedByLayers("Service", "Config")
            .whereLayer("Domain").mayOnlyBeAccessedByLayers("Controller", "Service", "Repository", "Config");

        rule.check(importedClasses);
    }

    /**
     * Tests that services have proper naming convention.
     *
     * All classes in service package must end with "Service".
     */
    @Test
    void servicesShouldHaveServiceSuffix() {
        ArchRule rule = classes()
            .that().resideInAPackage("..service..")
            .and().areNotInterfaces()
            .should().haveSimpleNameEndingWith("Service");

        rule.check(importedClasses);
    }

    /**
     * Tests that repositories are interfaces.
     *
     * Spring Data repositories should be interfaces, not classes.
     */
    @Test
    void repositoriesShouldBeInterfaces() {
        ArchRule rule = classes()
            .that().resideInAPackage("..repository..")
            .and().haveSimpleNameEndingWith("Repository")
            .should().beInterfaces();

        rule.check(importedClasses);
    }

    /**
     * Tests that services are annotated with @Service.
     */
    @Test
    void servicesShouldBeAnnotated() {
        ArchRule rule = classes()
            .that().resideInAPackage("..service..")
            .and().haveSimpleNameEndingWith("Service")
            .should().beAnnotatedWith(org.springframework.stereotype.Service.class);

        rule.check(importedClasses);
    }

    /**
     * Tests that repositories are annotated with @Repository.
     */
    @Test
    void repositoriesShouldBeAnnotated() {
        ArchRule rule = classes()
            .that().resideInAPackage("..repository..")
            .and().haveSimpleNameEndingWith("Repository")
            .should().beAnnotatedWith(org.springframework.stereotype.Repository.class);

        rule.check(importedClasses);
    }

    /**
     * Tests that controllers don't directly access repositories.
     *
     * Controllers should only call services, not repositories.
     */
    @Test
    void controllersShouldNotAccessRepositories() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..controller..")
            .should().dependOnClassesThat().resideInAPackage("..repository..");

        rule.check(importedClasses);
    }

    /**
     * Tests that domain entities don't depend on services.
     *
     * Domain layer should be independent of service layer.
     */
    @Test
    void domainShouldNotDependOnServices() {
        ArchRule rule = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..service..");

        rule.check(importedClasses);
    }
}
