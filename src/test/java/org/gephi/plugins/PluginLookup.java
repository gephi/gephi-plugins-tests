package org.gephi.plugins;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.util.Collection;
import java.util.stream.Collectors;
import org.openide.util.Lookup;

/**
 * Finds the SPI implementations contract tests run against.
 * <p>
 * When the {@value #JAR_PROPERTY} system property is set to a jar file name (as gephi-plugins' CI
 * harness does with the plugin under test), only implementations loaded from that jar are returned,
 * so implementations brought in by the plugin's dependencies, such as core Gephi modules, aren't
 * tested in the plugin's name. Otherwise every registered implementation is returned.
 */
public final class PluginLookup {

    public static final String JAR_PROPERTY = "gephi.plugins.tests.jar";

    private PluginLookup() {
    }

    public static <T> Collection<? extends T> lookupAll(Class<T> service) {
        Collection<? extends T> all = Lookup.getDefault().lookupAll(service);
        String jar = System.getProperty(JAR_PROPERTY);
        if (jar == null || jar.isEmpty()) {
            return all;
        }
        return all.stream().filter(impl -> isLoadedFrom(impl.getClass(), jar)).collect(Collectors.toList());
    }

    private static boolean isLoadedFrom(Class<?> clazz, String jar) {
        CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
        if (codeSource == null || codeSource.getLocation() == null) {
            return false;
        }
        try {
            Path fileName = Paths.get(codeSource.getLocation().toURI()).getFileName();
            return fileName != null && fileName.toString().equals(jar);
        } catch (URISyntaxException | IllegalArgumentException e) {
            return false;
        }
    }
}
