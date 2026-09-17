package org.jworkflow.example;

import org.junit.jupiter.api.Test;

final class MvpExampleContractSuiteTest {
    @Test void orderFulfillment() throws Exception { OrderFulfillmentExampleTest.main(new String[0]); }
    @Test void parallelOrderRouting() throws Exception { ParallelOrderRoutingExampleTest.main(new String[0]); }
    @Test void durableRestart() throws Exception { DurableOrderRestartExampleTest.main(new String[0]); }
    @Test void javaDefinitionBuilder() throws Exception { JavaDefinitionBuilderExampleTest.main(new String[0]); }
    @Test void employeeOnboardingRouting() throws Exception { EmployeeOnboardingRoutingExampleTest.main(new String[0]); }
}
