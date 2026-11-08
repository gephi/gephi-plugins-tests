package org.gephi.plugins;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

/**
 * Turns the failure of an {@link Advisory} test into a warning. The test passes, and the warning is printed and, when
 * the {@value #WARNINGS_PROPERTY} system property is set to a file, appended to it as one
 * {@code <test name><TAB><message>} line each (single line, so it can be read with plain shell tools). gephi-plugins'
 * CI harness reads that file to annotate the build.
 */
public final class AdvisoryRule implements TestRule {

    public static final String WARNINGS_PROPERTY = "gephi.plugins.tests.warnings";

    private static final int MAX_MESSAGE_LENGTH = 500;
    private static final Object LOCK = new Object();

    @Override
    public Statement apply(Statement base, Description description) {
        if (description.getAnnotation(Advisory.class) == null) {
            return base;
        }
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                try {
                    base.evaluate();
                } catch (AssumptionViolatedException e) {
                    throw e;
                } catch (Throwable t) {
                    warn(description.getMethodName(), t instanceof AssertionError ? t.getMessage() : t.toString());
                }
            }
        };
    }

    /**
     * Reports a warning from a test that is not {@link Advisory}, for a finding that can't be raised as an assertion,
     * e.g. an exception thrown in another thread.
     */
    public static void warn(String test, String message) {
        String line = message == null ? "" : message.replaceAll("\\s+", " ").trim();
        if (line.length() > MAX_MESSAGE_LENGTH) {
            line = line.substring(0, MAX_MESSAGE_LENGTH - 3) + "...";
        }
        System.err.println("WARNING " + test + ": " + line);

        String file = System.getProperty(WARNINGS_PROPERTY);
        if (file == null || file.isEmpty()) {
            return;
        }
        Path path = Paths.get(file);
        synchronized (LOCK) {
            try {
                if (path.getParent() != null) {
                    Files.createDirectories(path.getParent());
                }
                Files.write(path, (test + "\t" + line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                throw new IllegalStateException("Can't write the warnings to " + file, e);
            }
        }
    }
}
