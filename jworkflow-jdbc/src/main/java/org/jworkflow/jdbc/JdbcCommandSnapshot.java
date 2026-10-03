package org.jworkflow.jdbc;

import org.jworkflow.model.*;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON-compatible immutable command-time snapshot; relational schema and JSON envelope stay unchanged. */
final class JdbcCommandSnapshot {
    private JdbcCommandSnapshot() { }
    // A missing snapshot is persisted as JSON null; an empty map would change the stored format.
    @SuppressWarnings("java:S1168")
    static Map<String,Object> encode(WorkflowSnapshot s) {
        if(s==null)return null;
        Map<String,Object> value=new LinkedHashMap<>();
        value.put("instanceId",s.instanceId().toString());value.put("workflowKey",s.workflowKey());
        value.put("workflowVersion",s.workflowVersion());value.put("workflowRevision",s.workflowRevision());
        value.put("businessKey",s.businessKey());value.put("correlationId",s.correlationId());
        value.put("state",s.state());value.put("status",s.status().name());value.put("variables",s.variables());
        value.put("lockVersion",s.lockVersion());value.put("createdAt",s.createdAt().toString());value.put("updatedAt",s.updatedAt().toString());
        return value;
    }
    @SuppressWarnings("unchecked")
    static WorkflowSnapshot decode(Map<?,?> value) {
        return new WorkflowSnapshot(WorkflowInstanceId.fromString((String)value.get("instanceId")),
                (String)value.get("workflowKey"),(String)value.get("workflowVersion"),(String)value.get("workflowRevision"),
                (String)value.get("businessKey"),(String)value.get("correlationId"),(String)value.get("state"),
                WorkflowStatus.valueOf((String)value.get("status")),(Map<String,Object>)value.get("variables"),
                ((Number)value.get("lockVersion")).longValue(),Instant.parse((String)value.get("createdAt")),Instant.parse((String)value.get("updatedAt")));
    }
}
