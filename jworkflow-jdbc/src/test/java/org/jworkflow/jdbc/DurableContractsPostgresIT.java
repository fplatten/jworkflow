package org.jworkflow.jdbc;

import org.junit.jupiter.api.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.stream.Stream;

/** Executes the same semantic assertions as the SQLite contracts, with a schema per fixture. */
@Timeout(180)
class DurableContractsPostgresIT {
    private static PostgresTestDatabase database;
    @BeforeAll static void start(){database=PostgresTestDatabase.start();}
    @AfterAll static void stop() throws Exception {if(database!=null)database.close();}

    @TestFactory Stream<DynamicTest> applicationContracts() {
        return Stream.of(JdbcRepositoryContractTest.class,JdbcJsonCodecContractTest.class,
                JdbcWorkflowEngineDurabilityTest.class,JdbcRecoveryContractTest.class,
                JdbcStartupScalabilityTest.class,JdbcInboxLifecycleTest.class,JdbcOutboxLifecycleTest.class,
                JdbcEventRoutingContractTest.class,JdbcQueryContractTest.class,
                JdbcSecurityCaptureContractTest.class,JdbcObservabilityContractTest.class)
                .flatMap(type->{
                    List<Method> scenarios=Arrays.stream(type.getDeclaredMethods())
                            .filter(m->!m.isSynthetic() && Modifier.isStatic(m.getModifiers()) && m.getReturnType()==void.class
                                    && m.getParameterCount()==0)
                            .sorted(Comparator.comparing(Method::getName)).toList();
                    if(scenarios.isEmpty())try{scenarios=List.of(type.getMethod("main",String[].class));}
                    catch(NoSuchMethodException e){throw new AssertionError(e);}
                    return scenarios.stream().map(method->DynamicTest.dynamicTest(type.getSimpleName()+"."+method.getName(),()->{
                        try(var backend=new ContractBackend(database)){
                            method.setAccessible(true);
                            try{method.invoke(null,method.getParameterCount()==0 ? new Object[0] : new Object[]{new String[0]});}
                            catch(InvocationTargetException e){throw e.getCause();}
                        }
                    }));
                });
    }
}
