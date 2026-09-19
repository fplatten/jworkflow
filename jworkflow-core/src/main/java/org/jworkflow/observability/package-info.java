/**
 * Framework-neutral lifecycle observations, logging and metrics adapters. Observations are best effort and
 * must not control workflow outcomes. JDBC built-ins defer successful notifications until outermost commit
 * and cleanup; observers are not a replacement for durable outbox delivery.
 */
package org.jworkflow.observability;
