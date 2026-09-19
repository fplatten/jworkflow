package org.jworkflow.jdbc;

import org.jworkflow.persistence.PersistenceSerializationException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.DateTimeException;
import java.time.Instant;

/** Exact epoch seconds for numeric(30,9); no timestamp or floating-point conversion. */
final class PostgresqlInstantCodec {
    private PostgresqlInstantCodec() { }

    static BigDecimal encode(Instant value) {
        return BigDecimal.valueOf(value.getEpochSecond())
                .add(BigDecimal.valueOf(value.getNano(), 9)).setScale(9, RoundingMode.UNNECESSARY);
    }

    static Instant decode(BigDecimal value) {
        if (value == null) return null;
        try {
            BigDecimal exact = value.setScale(9, RoundingMode.UNNECESSARY);
            BigDecimal seconds = exact.setScale(0, RoundingMode.FLOOR);
            int nanos = exact.subtract(seconds).movePointRight(9).intValueExact();
            return Instant.ofEpochSecond(seconds.longValueExact(), nanos);
        } catch (ArithmeticException | DateTimeException failure) {
            throw new PersistenceSerializationException("Invalid persisted PostgreSQL Instant", failure);
        }
    }
}
