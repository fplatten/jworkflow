package org.jworkflow.model;


/**
 * Selection policy for a declarative gateway: exclusive first match or the supported multi-route behavior.
 */
public enum GatewayType {
    /**
     * Selects a single matching gateway branch.
     */
    EXCLUSIVE,
    /**
     * Selects parallel gateway branches.
     */
    PARALLEL,
    /**
     * Selects matching gateway branches under the inclusive policy.
     */
    INCLUSIVE
}
