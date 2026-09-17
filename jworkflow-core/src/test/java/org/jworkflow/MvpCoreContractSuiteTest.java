package org.jworkflow;

import org.junit.jupiter.api.Test;

final class MvpCoreContractSuiteTest {
    @Test void coreRuntime() throws Exception { org.jworkflow.engine.CoreRuntimeContractTest.main(new String[0]); }
    @Test void forkJoin() throws Exception { org.jworkflow.engine.ForkJoinCoreScenarioTest.main(new String[0]); }
    @Test void persistence() { org.jworkflow.persistence.PersistenceContractTest.main(new String[0]); }
    @Test void definitionActivation() throws Exception { org.jworkflow.definition.DefinitionActivationContractTest.main(new String[0]); }
    @Test void javaDefinitionBuilder() throws Exception { org.jworkflow.definition.JavaDefinitionBuilderContractTest.main(new String[0]); }
    @Test void observability() throws Exception { org.jworkflow.observability.ObservabilityContractTest.main(new String[0]); }
    @Test void eventTaxonomyAndSecurity() { org.jworkflow.events.EventTaxonomySecurityContractTest.main(new String[0]); }
}
