/**
 * Programmatic workflow authoring and source activation. Builders are mutable and intended for one
 * authoring thread; built definitions are immutable. Activation compiles and validates candidates and
 * retains last-known-good definitions when a candidate is rejected.
 */
package org.jworkflow.definition;
