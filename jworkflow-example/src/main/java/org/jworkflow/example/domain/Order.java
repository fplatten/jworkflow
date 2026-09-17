package org.jworkflow.example.domain;

import java.util.Objects;

public record Order(String id) {
    public Order {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("order id is required");
        }
    }
}
