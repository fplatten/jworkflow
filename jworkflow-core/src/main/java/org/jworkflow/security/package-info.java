/**
 * Event capture and redaction policies applied before persistence and observation. The default preserves
 * payloads; hosts must deliberately configure filtering. Capture policy does not replace authorization, TLS
 * or data-retention controls, and must preserve metadata needed for identity and routing.
 */
package org.jworkflow.security;
