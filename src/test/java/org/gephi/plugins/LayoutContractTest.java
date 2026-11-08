package org.gephi.plugins;

import java.awt.HeadlessException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.gephi.graph.GraphGenerator;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Node;
import org.gephi.layout.LayoutModelImpl;
import org.gephi.layout.LayoutModelPersistenceProvider;
import org.gephi.layout.spi.Layout;
import org.gephi.layout.spi.LayoutBuilder;
import org.gephi.layout.spi.LayoutProperty;
import org.gephi.layout.spi.LayoutUI;
import org.gephi.project.api.Workspace;
import org.gephi.project.impl.WorkspaceImpl;
import org.gephi.project.io.utils.GephiFormat;
import org.gephi.utils.longtask.spi.LongTask;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * Contract tests for {@link Layout} plugins. Each registered {@link LayoutBuilder} is checked on its metadata and
 * properties, then run through the same init, go, end lifecycle as the layout controller against graphs known to
 * trip up algorithms (empty, single node, coincident nodes...).
 * <p>
 * The checks target failure modes that happened for real in Gephi: positions turning NaN (graphstore rejects them and
 * the layout thread dies), read locks leaked past a pass or past the end of the run, and properties lost when saving
 * a project.
 */
@RunWith(Parameterized.class)
public class LayoutContractTest {

    private static final int ITERATIONS = 20;
    private static final long TIMEOUT_SECONDS = 60;

    @Rule
    public final AdvisoryRule advisory = new AdvisoryRule();

    private final LayoutBuilder layoutBuilder;
    private Layout layout;
    private GraphModel graphModel;

    public LayoutContractTest(LayoutBuilder layout, String name) {
        this.layoutBuilder = layout;
    }

    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> configs() {
        // Named after the class, the default name of a builder has its identity hash
        List<Object[]> configs = new ArrayList<>();
        for (LayoutBuilder builder : PluginLookup.lookupAll(LayoutBuilder.class)) {
            configs.add(new Object[] {builder, builder.getClass().getName()});
        }
        return configs;
    }

    @Before
    public void setUp() {
        graphModel = TestGraphs.randomGraph();
        layout = layoutBuilder.buildLayout();
        layout.setGraphModel(graphModel);
    }

    @After
    public void cleanUp() {
        graphModel = null;
        layout = null;
    }

    @Test
    public void testLayoutBuilder() {
        Assert.assertNotNull("Layout builder can't be null", layoutBuilder);
        Assert.assertTrue("Layout builder name can't be null or empty",
            layoutBuilder.getName() != null && !layoutBuilder.getName().isEmpty());
    }

    @Test
    public void testLayoutUI() {
        Assert.assertNotNull("Layout builder needs to have a UI", layoutBuilder.getUI());
        LayoutUI layoutUI = layoutBuilder.getUI();
        Assert.assertNotNull("Layout UI needs to have a description", layoutUI.getDescription());

        int speed = layoutUI.getSpeedRank();
        int quality = layoutUI.getQualityRank();
        Assert.assertTrue("Speed appraisal must be either -1 or between 1 and 5",
            speed == -1 || (speed >= 1 && speed <= 5));
        Assert.assertTrue("Quality appraisal must be either -1 or between 1 and 5",
            quality == -1 || (quality >= 1 && quality <= 5));
    }

    @Test
    public void testLayoutProperties() {
        for (LayoutProperty property : layout.getProperties()) {
            Assert.assertNotNull("Property canonical name can't be null", property.getCanonicalName());
            Assert.assertTrue(
                "Property " + property.getCanonicalName() + " can't read, make sure the getters are set correctly",
                property.getProperty().canRead());
            Assert.assertTrue(
                "Property " + property.getCanonicalName() + " can't write, make sure the setters are set correctly",
                property.getProperty().canWrite());
        }

        Assert.assertEquals("Duplicate property canonical names, make sure they are unique",
            layout.getProperties().length, Arrays.stream(layout.getProperties()).map(
                LayoutProperty::getCanonicalName).collect(
                Collectors.toSet()).size());
    }

    @Test
    public void testResetProperties() {
        layout.resetPropertiesValues();
    }

    @Test
    public void testSerialization() throws Exception {
        LayoutModelImpl layoutModel = newLayoutModel();
        layout.resetPropertiesValues();
        layoutModel.saveProperties(layout);
        GephiFormat.testXMLPersistenceProvider(new LayoutModelPersistenceProvider(), layoutModel.getWorkspace());
    }

    @Test
    public void testSerialisationWithColumn() throws Exception {
        layout.resetPropertiesValues();
        Optional<LayoutProperty>
            columnProperty = Arrays.stream(layout.getProperties()).filter(lp -> lp.getProperty().getValueType().equals(Column.class)).findFirst();
        LayoutModelImpl layoutModel = newLayoutModel();
        if (columnProperty.isPresent()) {
            columnProperty.get().getProperty().setValue(graphModel.getNodeTable().getColumn(GraphGenerator.INT_COLUMN));
            layoutModel.saveProperties(layout);
            GephiFormat.testXMLPersistenceProvider(new LayoutModelPersistenceProvider(), layoutModel.getWorkspace());
        }
    }

    @Test
    public void testSerializationWithModifiedValues() throws Exception {
        layout.resetPropertiesValues();
        Set<String> changed = new LinkedHashSet<>();
        for (LayoutProperty property : layout.getProperties()) {
            Object value = differentValue(read(property));
            if (value == null) {
                continue;
            }
            try {
                write(property, value);
                changed.add(property.getCanonicalName());
            } catch (Exception e) {
                // The setter rejects the value, nothing to save
            }
        }
        Assume.assumeFalse("Layout has no numeric or boolean property", changed.isEmpty());

        // Read once all are written, setters may adjust other properties
        Map<String, Object> expected = new LinkedHashMap<>();
        for (LayoutProperty property : layout.getProperties()) {
            if (changed.contains(property.getCanonicalName())) {
                expected.put(property.getCanonicalName(), read(property));
            }
        }

        LayoutModelImpl layoutModel = newLayoutModel();
        layoutModel.saveProperties(layout);
        Workspace restoredWorkspace =
            GephiFormat.testXMLPersistenceProvider(new LayoutModelPersistenceProvider(), layoutModel.getWorkspace());
        LayoutModelImpl restored = restoredWorkspace.getLookup().lookup(LayoutModelImpl.class);

        Layout reloaded = layoutBuilder.buildLayout();
        reloaded.setGraphModel(graphModel);
        restored.loadProperties(reloaded);
        for (LayoutProperty property : reloaded.getProperties()) {
            if (expected.containsKey(property.getCanonicalName())) {
                Assert.assertEquals("Property " + property.getCanonicalName() + " is changed by saving and " +
                        "loading a project, make sure its type can be serialized and its setter keeps the value",
                    expected.get(property.getCanonicalName()), read(property));
            }
        }
    }

    @Test
    public void testPropertiesWriteBack() throws Exception {
        layout.resetPropertiesValues();
        for (LayoutProperty property : layout.getProperties()) {
            Object value = read(property);
            if (value == null) {
                // E.g. a column that isn't set, the UI doesn't write that back
                continue;
            }
            write(property, value);
            Assert.assertTrue("Property " + property.getCanonicalName() + " is changed by writing back its own " +
                "value, make sure the getter returns what the setter was given", Objects.deepEquals(value,
                read(property)));
        }
    }

    // Builder

    @Test
    public void testBuilderIdentity() {
        Layout other = layoutBuilder.buildLayout();
        Assert.assertNotNull("Layout builder must build a layout", other);
        Assert.assertNotSame("Layout builder must return a new instance each time, layouts keep their state and " +
            "settings", layout, other);
        Assert.assertSame("Layout.getBuilder() must return the builder that built it, the layout controller uses " +
            "it to name the running task", layoutBuilder, layout.getBuilder());
    }

    // Execution

    @Test
    public void testExecute() {
        for (Map.Entry<String, GraphModel> scenario : scenarios().entrySet()) {
            Layout scenarioLayout;
            try {
                scenarioLayout = newLayout(scenario.getValue());
            } catch (HeadlessException e) {
                // The layout tells the user about this graph in a dialog
                continue;
            }
            runAndAssert(scenario.getKey(), scenario.getValue(), scenarioLayout, ITERATIONS);
        }
    }

    @Test
    @Advisory
    public void testExecuteDoesNotChangeStructure() {
        // Some layouts add nodes or edges on purpose (e.g. to draw axes), reviewers should know
        List<String> changes = new ArrayList<>();
        for (Map.Entry<String, GraphModel> scenario : scenarios().entrySet()) {
            GraphModel model = scenario.getValue();
            int nodes = model.getGraph().getNodeCount();
            int edges = model.getGraph().getEdgeCount();
            try {
                Layout toRun = newLayout(model);
                toRun.initAlgo();
                for (int i = 0; i < ITERATIONS && toRun.canAlgo(); i++) {
                    toRun.goAlgo();
                }
                toRun.endAlgo();
            } catch (Throwable t) {
                // testExecute reports that
                continue;
            }
            int nodesAfter = model.getGraph().getNodeCount();
            int edgesAfter = model.getGraph().getEdgeCount();
            if (nodesAfter > nodes || edgesAfter > edges) {
                changes.add("'" + scenario.getKey() + "' graph went from " + nodes + " nodes and " + edges +
                    " edges to " + nodesAfter + " and " + edgesAfter);
            }
        }
        Assert.assertTrue("Layout adds nodes or edges, it is expected to only move nodes: " +
            String.join("; ", changes), changes.isEmpty());
    }

    @Test
    public void testStopAtAnyIteration() {
        // The layout controller can stop a run at any time, endAlgo() is called whatever the last pass was
        for (int iterations : new int[] {0, 1, 2, 5}) {
            GraphModel model = TestGraphs.randomGraph();
            runAndAssert("stopped after " + iterations + " iterations", model, newLayout(model), iterations);
        }
    }

    @Test
    public void testExecuteTwice() {
        GraphModel model = TestGraphs.randomGraph();
        // A layout may replace what an earlier run added (e.g. axes), it can't remove what was there to begin with
        TestGraphs.Snapshot original = new TestGraphs.Snapshot(model);
        Layout reused = newLayout(model);
        runAndAssert("first run", model, reused, ITERATIONS, original);
        runAndAssert("second run", model, reused, ITERATIONS, original);

        reused.resetPropertiesValues();
        runAndAssert("run after reset", model, reused, ITERATIONS, original);
    }

    @Test
    public void testWriteBackPropertiesDuringExecution() throws Exception {
        GraphModel model = TestGraphs.randomGraph();
        Layout running = newLayout(model);
        running.initAlgo();
        try {
            for (int i = 0; i < ITERATIONS && running.canAlgo(); i++) {
                // Users edit properties while the layout is running
                for (LayoutProperty property : running.getProperties()) {
                    Object value = read(property);
                    if (value != null) {
                        write(property, value);
                    }
                }
                running.goAlgo();
            }
        } catch (HeadlessException e) {
            Assume.assumeNoException("Layout opened a dialog, which can't be checked in a headless run", e);
        } finally {
            running.endAlgo();
        }
        assertFinitePositions("random", model);
        TestGraphs.assertGraphUnlocked("Layout", "random", model);
    }

    // Cancellation

    @Test
    public void testCancelDuringExecution() throws Exception {
        Assume.assumeTrue("Layout is not cancellable", layoutBuilder.buildLayout() instanceof LongTask);

        for (long delayMs : new long[] {-1, 0, 1, 5, 25}) {
            GraphModel model = TestGraphs.largeRandomGraph();
            TestGraphs.Snapshot snapshot = new TestGraphs.Snapshot(model);
            Layout cancellable = newLayout(model);
            LongTask task = (LongTask) cancellable;
            // Same as the layout controller, which also stops looping when cancelled
            AtomicBoolean stop = new AtomicBoolean();
            Runnable cancel = () -> {
                stop.set(true);
                task.cancel();
            };

            Thread canceller = null;
            if (delayMs < 0) {
                // Cancelled before it even started
                cancel.run();
            } else {
                canceller = new Thread(() -> {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                    }
                    cancel.run();
                });
            }

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    cancellable.initAlgo();
                    while (cancellable.canAlgo() && !stop.get()) {
                        cancellable.goAlgo();
                    }
                    cancellable.endAlgo();
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "layout-contract-test");
            // A layout ignoring cancel() would otherwise keep the test JVM alive after the failure
            runner.setDaemon(true);
            runner.start();
            if (canceller != null) {
                canceller.start();
            }
            runner.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            Assert.assertFalse("Cancelled layout must return, it is still running (cancel after " + delayMs + "ms)",
                runner.isAlive());
            if (failure.get() != null) {
                throw new AssertionError("Cancelled layout must not throw (cancel after " + delayMs + "ms): " +
                    failure.get(), failure.get());
            }
            assertFinitePositions("cancelled", model);
            TestGraphs.assertGraphUnlocked("Layout", "cancelled", model);
            snapshot.assertNoneRemoved("Layout", "cancelled", model);
        }
    }

    @Test
    public void testNoProgressTicket() {
        Layout ticketless = layoutBuilder.buildLayout();
        Assume.assumeTrue("Layout is not a LongTask", ticketless instanceof LongTask);

        // The layout controller forwards the ticket as is, it is null when the task isn't tracked
        ((LongTask) ticketless).setProgressTicket(null);
        GraphModel model = TestGraphs.randomGraph();
        ticketless.setGraphModel(model);
        ticketless.resetPropertiesValues();
        runAndAssert("random", model, ticketless, ITERATIONS);
    }

    // Utilities

    private Layout newLayout(GraphModel model) {
        Layout newLayout = layoutBuilder.buildLayout();
        newLayout.setGraphModel(model);
        newLayout.resetPropertiesValues();
        return newLayout;
    }

    private static Map<String, GraphModel> scenarios() {
        Map<String, GraphModel> scenarios = new LinkedHashMap<>(TestGraphs.scenarios());
        // Repulsion and overlap removal divide by distances
        scenarios.put("coincident nodes", TestGraphs.build(model -> {
            Graph g = model.getGraph();
            Node[] nodes = new Node[10];
            for (int i = 0; i < nodes.length; i++) {
                nodes[i] = model.factory().newNode(String.valueOf(i));
                nodes[i].setX(0f);
                nodes[i].setY(0f);
                nodes[i].setSize(10f);
                g.addNode(nodes[i]);
            }
            for (int i = 1; i < nodes.length; i++) {
                g.addEdge(model.factory().newEdge(nodes[0], nodes[i], 0, 1.0, false));
            }
        }));
        scenarios.put("zero-size nodes", TestGraphs.build(model -> {
            Graph g = model.getGraph();
            Node a = model.factory().newNode("a");
            Node b = model.factory().newNode("b");
            a.setSize(0f);
            b.setSize(0f);
            b.setX(1f);
            g.addNode(a);
            g.addNode(b);
            g.addEdge(model.factory().newEdge(a, b, 0, 1.0, false));
        }));
        return scenarios;
    }

    /**
     * Runs the layout like the layout controller does, checking the graph is not left locked between the steps.
     */
    private void runAndAssert(String scenario, GraphModel model, Layout toRun, int iterations) {
        runAndAssert(scenario, model, toRun, iterations, new TestGraphs.Snapshot(model));
    }

    private void runAndAssert(String scenario, GraphModel model, Layout toRun, int iterations,
                              TestGraphs.Snapshot original) {
        try {
            toRun.initAlgo();
            TestGraphs.assertGraphUnlocked("Layout.initAlgo()", scenario, model);
            for (int i = 0; i < iterations && toRun.canAlgo(); i++) {
                toRun.goAlgo();
                TestGraphs.assertGraphUnlocked("Layout.goAlgo()", scenario, model);
            }
            toRun.endAlgo();
        } catch (AssertionError e) {
            throw e;
        } catch (Throwable t) {
            if (opensDialog(t)) {
                // The layout tells the user about this graph in a dialog, which can't open in a headless run
                return;
            }
            throw new AssertionError("Layout must handle the '" + scenario + "' graph: " + t, t);
        }
        TestGraphs.assertGraphUnlocked("Layout.endAlgo()", scenario, model);
        original.assertNoneRemoved("Layout", scenario, model);

        assertFinitePositions(scenario, model);
    }

    private static boolean opensDialog(Throwable t) {
        for (; t != null; t = t.getCause()) {
            if (t instanceof HeadlessException) {
                return true;
            }
        }
        return false;
    }

    private static void assertFinitePositions(String scenario, GraphModel model) {
        for (Node node : model.getGraph().getNodes()) {
            Assert.assertTrue("Node " + node.getId() + " has a NaN or infinite position on the '" + scenario +
                    "' graph, graphstore rejects it and the layout thread dies",
                Float.isFinite(node.x()) && Float.isFinite(node.y()) && Float.isFinite(node.z()));
        }
    }

    private static Object read(LayoutProperty property) throws Exception {
        return property.getProperty().getValue();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void write(LayoutProperty property, Object value) throws Exception {
        property.getProperty().setValue(value);
    }

    /**
     * A value of the same type as the given one, or null if the type isn't one the test can change.
     */
    private static Object differentValue(Object value) {
        if (value instanceof Boolean) {
            return !(Boolean) value;
        } else if (value instanceof Integer) {
            return (Integer) value + 1;
        } else if (value instanceof Long) {
            return (Long) value + 1;
        } else if (value instanceof Float) {
            return (Float) value + 1f;
        } else if (value instanceof Double) {
            return (Double) value + 1.0;
        }
        return null;
    }

    public static LayoutModelImpl newLayoutModel() {
        // The workspace already creates its models, the persistence provider only serializes that one
        WorkspaceImpl workspace = new WorkspaceImpl(null, 0);
        LayoutModelImpl model = workspace.getLookup().lookup(LayoutModelImpl.class);
        Assert.assertNotNull("The workspace has no layout model, is the layout controller registered?", model);
        return model;
    }
}
