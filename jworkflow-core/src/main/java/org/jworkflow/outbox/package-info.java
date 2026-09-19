/**
 * Durable publication intent, routing and at-least-once transport services. Enqueue intent with workflow
 * state in one transaction. Claim and completion transactions are short; network publication occurs outside
 * them. A successful send followed by a recording failure may cause duplicate delivery, so receivers must
 * deduplicate stable message identities.
 */
package org.jworkflow.outbox;
