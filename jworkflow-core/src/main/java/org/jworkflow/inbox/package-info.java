/**
 * Durable first-arrival acceptance, command translation and retry/reprocessing services. Acceptance
 * deduplicates by external source/event identity. Processing and its workflow effects must share a
 * transaction and validate the acquisition token. Transport acknowledgement and application command
 * translation remain host responsibilities.
 */
package org.jworkflow.inbox;
