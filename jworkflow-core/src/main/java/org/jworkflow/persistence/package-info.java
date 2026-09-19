/**
 * Framework-neutral repository and transaction SPI. A persistence bundle must share one transaction
 * boundary across snapshots, events, timers, command results and message state. Token-fenced claims guard
 * dependent writes against stale acquisitions; custom implementations must explicitly implement fencing.
 * Default transaction synchronization preserves legacy managers but cannot defer callbacks unless the
 * manager implements that capability.
 */
package org.jworkflow.persistence;
