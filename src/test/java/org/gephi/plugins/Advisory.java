package org.gephi.plugins;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a contract test whose failure is a warning, not an error: the plugin works, but does something users or
 * reviewers should know about (e.g. a layout that adds nodes). Needs an {@link AdvisoryRule} on the test class.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface Advisory {
}
