package org.jworkflow.jdbc;

import org.junit.jupiter.api.Test;

final class MvpJdbcContractSuiteTest {
    @Test void engineBuilder() throws Exception { WorkflowEngineBuilderTest.main(new String[0]); }
    @Test void jsonCodec() throws Exception { JdbcJsonCodecContractTest.main(new String[0]); }
    @Test void transactionAndSchema() throws Exception { JdbcTransactionSchemaContractTest.main(new String[0]); }
    @Test void repositories() throws Exception { JdbcRepositoryContractTest.main(new String[0]); }
    @Test void engineDurability() throws Exception { JdbcWorkflowEngineDurabilityTest.main(new String[0]); }
    @Test void recovery() throws Exception { JdbcRecoveryContractTest.main(new String[0]); }
    @Test void inbox() throws Exception { JdbcInboxLifecycleTest.main(new String[0]); }
    @Test void outbox() throws Exception { JdbcOutboxLifecycleTest.main(new String[0]); }
    @Test void queries() throws Exception { JdbcQueryContractTest.main(new String[0]); }
    @Test void observability() throws Exception { JdbcObservabilityContractTest.main(new String[0]); }
    @Test void eventRouting() throws Exception { JdbcEventRoutingContractTest.main(new String[0]); }
    @Test void startupScalability() throws Exception { JdbcStartupScalabilityTest.main(new String[0]); }
    @Test void securityCapture() throws Exception { JdbcSecurityCaptureContractTest.main(new String[0]); }
}
