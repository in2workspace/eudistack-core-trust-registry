package es.in2.trustregistry.architecture;

import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** The dependency rule of ports and adapters, enforced instead of documented. */
@AnalyzeClasses(packages = "es.in2.trustregistry")
class HexagonalArchitectureTest {

    @ArchTest
    static final ArchRule domainDoesNotDependOnInfrastructure = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..");

    @ArchTest
    static final ArchRule domainDoesNotDependOnSpringWeb = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAPackage("org.springframework.web..");
}
