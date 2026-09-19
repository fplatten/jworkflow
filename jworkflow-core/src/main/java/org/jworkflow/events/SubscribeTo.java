package org.jworkflow.events;


import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declares the integration event names handled by an infrastructure subscriber method. Registration support
 * depends on the host adapter; the annotation alone does not create a subscription.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SubscribeTo {
    /**
     * Lists event names handled by the annotated method.
     * @return the resulting string[]
     */
    String[] value();
}
