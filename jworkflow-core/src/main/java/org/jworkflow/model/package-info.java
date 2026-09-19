/**
 * Workflow graph definitions, immutable state values and structural validation. Persisted snapshots retain
 * the exact definition revision and an optimistic lock version. Value transformations do not persist
 * changes or validate database leases; repositories and command transactions provide those guarantees.
 */
package org.jworkflow.model;
