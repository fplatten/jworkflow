package org.jworkflow.jdbc;

import org.jworkflow.events.EventMessage;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Maps the common inbox/outbox event envelope to its shared JDBC columns. */
final class JdbcEventMessageCodec {
    private final JdbcJsonCodec json = new JdbcJsonCodec();

    void bind(PreparedStatement statement, int index, EventMessage message) throws SQLException {
        Object payload = message.payload();
        if (payload instanceof byte[] binary) {
            statement.setNull(index, Types.VARCHAR);
            statement.setBytes(index + 1, binary);
        } else {
            statement.setString(index, json.write(payload));
            statement.setNull(index + 1, Types.BLOB);
        }
        statement.setString(index + 2, message.contentType());
        statement.setString(index + 3, message.schemaName());
        statement.setString(index + 4, message.schemaVersion());
        statement.setString(index + 5, json.write(message.attributes()));
        statement.setString(index + 6, message.redacted() ? "REDACTED" : "VISIBLE");
    }

    EventMessage read(ResultSet rows) throws SQLException {
        byte[] binary = rows.getBytes("message_payload_blob");
        String jsonPayload = rows.getString("message_payload");
        Object payload;
        if (binary != null) {
            payload = binary;
        } else if (jsonPayload == null) {
            payload = null;
        } else {
            payload = json.read(jsonPayload);
        }
        String metadata = rows.getString("message_metadata_json");
        Map<String, String> attributes = metadata == null ? Map.of() : stringValues(json.readMap(metadata));
        return new EventMessage(
                payload,
                rows.getString("message_content_type"),
                rows.getString("message_schema_name"),
                rows.getString("message_schema_version"),
                "REDACTED".equals(rows.getString("message_redaction_status")),
                attributes);
    }

    private static Map<String, String> stringValues(Map<String, Object> source) {
        LinkedHashMap<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(key, value == null ? null : String.valueOf(value)));
        return Collections.unmodifiableMap(result);
    }
}
