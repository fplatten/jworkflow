/**
 * Read-only workflow summaries, pending-work observations and history projections. Bounded list queries do
 * not acquire worker leases. Full timelines and projection replay load complete instance history; replay
 * callbacks own their external effects and do not mutate authoritative workflow state.
 */
package org.jworkflow.query;
