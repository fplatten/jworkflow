/**
 * Explicit routing of events to existing workflow instances by exact identity or scoped
 * correlation/business identity. Single-target ambiguity is rejected; fan-out must be explicit. Built-in
 * JDBC routing rejects tenant scopes because tenant-aware persistence is not implemented.
 */
package org.jworkflow.routing;
