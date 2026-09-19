package org.jworkflow.example.domain;

import java.util.Objects;

/**
 * Order example domain value, independent of workflow engine APIs.
 * @param id the identifier of the requested value
 */
public record Order(String id) {
    /**
     * Creates this value from the supplied components.
     * @param id the identifier of the requested value
     * @throws NullPointerException if id is null
     * @throws IllegalArgumentException if the supplied values violate the operation's constraints
     */
    public Order {
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("order id is required");
        }
    }
}
