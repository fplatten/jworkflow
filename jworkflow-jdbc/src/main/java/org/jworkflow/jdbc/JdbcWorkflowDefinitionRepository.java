package org.jworkflow.jdbc;

import org.jworkflow.engine.WorkflowInfrastructureException;
import org.jworkflow.model.WorkflowDefinition;
import org.jworkflow.persistence.PersistenceConstraintException;
import org.jworkflow.persistence.PersistenceSerializationException;
import org.jworkflow.persistence.WorkflowDefinitionRepository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

final class JdbcWorkflowDefinitionRepository implements WorkflowDefinitionRepository {
    private final JdbcConnectionFactory connections;
    private final JdbcDefinitionCodec codec = new JdbcDefinitionCodec();

    JdbcWorkflowDefinitionRepository(JdbcConnectionFactory connections) { this.connections = Objects.requireNonNull(connections); }

    @Override public void save(WorkflowDefinition definition) {
        String canonical = codec.write(definition);
        String revision = definition.revision();
        Instant now = Instant.now();
        String sql = "insert into workflow_definition (id,workflow_key,workflow_version,workflow_revision,definition_text,checksum,created_at,updated_at,semantic_version,definition_source,canonical_json) values (?,?,?,?,?,?,?,?,?,?,?)";
        sql=connections.strategy().insertIgnoringDuplicate(sql,"workflow_key,workflow_version,workflow_revision");
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, revision); statement.setString(2, definition.name()); statement.setString(3, definition.version());
            statement.setString(4, revision); statement.setString(5, definition.sourceText()); statement.setString(6, revision);
            connections.strategy().bindInstant(statement, 7, now); connections.strategy().bindInstant(statement, 8, now); statement.setString(9, definition.version());
            statement.setString(10, definition.sourceText()); statement.setString(11, canonical);
            int affected=connections.strategy().executeDuplicateInsert(connections,connection,statement,"workflow_definition_pkey",()->
                    stored(connection,definition.name(),definition.version(),revision)
                            .filter(winner->revision.equals(winner.id)).isPresent());
            if(affected==0) {
                Stored winner=stored(connection,definition.name(),definition.version(),revision)
                        .orElseThrow(()->new PersistenceConstraintException("Workflow definition duplicate winner is no longer available"));
                verify(winner,canonical,revision);
            } else if(affected!=1) throw new SQLException("Unexpected definition insert count");
        } catch (SQLException failure) {
            throw new WorkflowInfrastructureException("Failed to save workflow definition " + definition.key(), failure);
        }
    }

    @Override public Optional<WorkflowDefinition> find(String name, String version) {
        return queryOne("select canonical_json,checksum from workflow_definition where workflow_key=? and workflow_version=? order by created_at desc,workflow_revision desc limit 1", name, version);
    }

    @Override public Optional<WorkflowDefinition> findRevision(String name, String version, String revision) {
        return queryOne("select canonical_json,checksum from workflow_definition where workflow_key=? and workflow_version=? and workflow_revision=?", name, version, revision);
    }

    @Override public List<WorkflowDefinition> findAll() {
        String sql = "select canonical_json,checksum from workflow_definition order by workflow_key,workflow_version,workflow_revision";
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            ArrayList<WorkflowDefinition> result = new ArrayList<>();
            while (rows.next()) result.add(decode(rows.getString(1), rows.getString(2)));
            return List.copyOf(result);
        } catch (SQLException failure) { throw new WorkflowInfrastructureException("Failed to enumerate workflow definitions", failure); }
    }

    private Optional<WorkflowDefinition> queryOne(String sql, String... values) {
        try (Connection connection = connections.open(); PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i=0;i<values.length;i++) statement.setString(i+1, values[i]);
            try (ResultSet rows = statement.executeQuery()) { return rows.next() ? Optional.of(decode(rows.getString(1), rows.getString(2))) : Optional.empty(); }
        } catch (SQLException failure) { throw new WorkflowInfrastructureException("Failed to load workflow definition", failure); }
    }

    private Optional<Stored> stored(Connection connection,String name,String version,String revision)throws SQLException {
        String sql = "select canonical_json,checksum,id from workflow_definition where workflow_key=? and workflow_version=? and workflow_revision=?";
        try (PreparedStatement statement=connection.prepareStatement(sql)) {
            statement.setString(1,name); statement.setString(2,version); statement.setString(3,revision);
            try (ResultSet rows=statement.executeQuery()) { return rows.next()?Optional.of(new Stored(rows.getString(1),rows.getString(2),rows.getString(3))):Optional.empty(); }
        }
    }

    private WorkflowDefinition decode(String canonical, String checksum) {
        if (canonical == null || canonical.isBlank()) throw new PersistenceSerializationException("Workflow definition canonical JSON is missing");
        WorkflowDefinition definition = codec.read(canonical);
        if (!definition.revision().equals(checksum)) throw new PersistenceSerializationException("Workflow definition checksum mismatch for " + definition.key());
        return definition;
    }

    private static void verify(Stored stored, String canonical, String checksum) {
        if (!checksum.equals(stored.checksum) || !canonical.equals(stored.canonical)) {
            throw new PersistenceConstraintException("Immutable workflow definition identity already contains different content");
        }
    }
    private record Stored(String canonical,String checksum,String id) { }
}
