/**
 * Event envelopes, typed identities and local publication/subscription contracts. Event metadata carries
 * correlation and provenance, not authentication or tenant isolation. Local publication is not durable
 * transport; applications needing reliable external delivery should use the outbox. Subscription handles
 * remove registrations without closing their publishers.
 */
package org.jworkflow.events;
