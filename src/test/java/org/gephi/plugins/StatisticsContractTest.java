package org.gephi.plugins;

import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.xml.stream.XMLStreamReader;
import javax.xml.stream.XMLStreamWriter;
import org.gephi.graph.GraphGenerator;
import org.gephi.graph.api.Column;
import org.gephi.graph.api.Edge;
import org.gephi.graph.api.Element;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphController;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.GraphView;
import org.gephi.graph.api.Interval;
import org.gephi.graph.api.Node;
import org.gephi.graph.api.Table;
import org.gephi.project.api.ProjectController;
import org.gephi.project.api.Workspace;
import org.gephi.project.impl.WorkspaceImpl;
import org.gephi.project.io.utils.GephiFormat;
import org.gephi.statistics.StatisticsModelImpl;
import org.gephi.statistics.api.StatisticsController;
import org.gephi.statistics.spi.DynamicStatistics;
import org.gephi.statistics.spi.Statistics;
import org.gephi.statistics.spi.StatisticsBuilder;
import org.gephi.statistics.spi.StatisticsUI;
import org.gephi.utils.longtask.spi.LongTask;
import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.openide.util.Lookup;

/**
 * Contract tests for {@link Statistics} plugins. Each registered {@link StatisticsBuilder} is checked on its own
 * metadata and UI, then executed against a set of graphs that are known to trip up algorithms (empty, single node,
 * self-loops, undirected, mixed, multi-edges, disconnected, filtered view, pre-existing columns of another type...).
 * <p>
 * The checks target failure modes that happened for real in Gephi: results that leak a graph read lock, columns that
 * can't be recreated when they already exist with another type, cancellation that leaves the graph locked,
 * {@link StatisticsUI} reading state of the wrong statistics instance, and reports that can't be saved in a project.
 */
@RunWith(Parameterized.class)
public class StatisticsContractTest {

    private static final long TIMEOUT_SECONDS = 60;

    /**
     * Core Gephi 0.11.4 builders failing a contract test, fixed in gephi/gephi#3399. They only matter when this
     * project validates itself against core Gephi, plugins are only tested on their own builders.
     * TODO remove once the parent pom is on a Gephi release that has the fix.
     */
    private static final Map<String, String> KNOWN_CORE_ISSUES = Map.of(
        "testExecuteWithExistingColumnsOfAnotherType[org.gephi.statistics.plugin.builder.ClusteringCoefficientBuilder]",
        "gephi/gephi#3399",
        "testDynamicStatistics[org.gephi.statistics.plugin.dynamic.builder.DynamicClusteringCoefficientBuilder]",
        "gephi/gephi#3399");

    private final StatisticsBuilder builder;

    public StatisticsContractTest(StatisticsBuilder builder, String name) {
        this.builder = builder;
    }

    @Parameterized.Parameters(name = "{1}")
    public static Collection<Object[]> configs() {
        List<Object[]> configs = new ArrayList<>();
        for (StatisticsBuilder builder : PluginLookup.lookupAll(StatisticsBuilder.class)) {
            configs.add(new Object[] {builder, builder.getClass().getName()});
        }
        return configs;
    }

    @Before
    public void setUp() {
        // Statistics UIs and the controller work on the current workspace
        Lookup.getDefault().lookup(ProjectController.class).newProject();
    }

    @After
    public void tearDown() {
        Lookup.getDefault().lookup(ProjectController.class).closeCurrentProject();
    }

    // Builder

    @Test
    public void testStatisticsBuilder() {
        Assert.assertNotNull("Statistics builder can't be null", builder);
        Assert.assertTrue("Statistics builder name can't be null or empty",
            builder.getName() != null && !builder.getName().isEmpty());

        Statistics statistics = builder.getStatistics();
        Assert.assertNotNull("Statistics builder must build a statistics", statistics);
        Assert.assertNotNull("Statistics builder must declare its statistics class", builder.getStatisticsClass());
        Assert.assertEquals("getStatisticsClass() must be the exact class of the built statistics, it is used to "
                + "find the builder and the UI back", builder.getStatisticsClass(), statistics.getClass());
        Assert.assertNotSame("Statistics builder must return a new instance each time, statistics keep their "
            + "results and settings", statistics, builder.getStatistics());

        Assert.assertSame("The statistics controller must find the builder from the statistics class",
            builder, Lookup.getDefault().lookup(StatisticsController.class).getBuilder(statistics.getClass()));
    }

    // UI

    @Test
    public void testStatisticsUI() {
        List<StatisticsUI> uis = uis();
        Assert.assertFalse("Statistics needs to have a StatisticsUI, otherwise it can't be run from the user "
            + "interface", uis.isEmpty());

        for (StatisticsUI ui : uis) {
            Assert.assertTrue("Statistics UI display name can't be null or empty",
                ui.getDisplayName() != null && !ui.getDisplayName().isEmpty());
            Assert.assertTrue("Statistics UI category can't be null or empty",
                ui.getCategory() != null && !ui.getCategory().isEmpty());

            Statistics statistics = builder.getStatistics();
            ui.getSettingsPanel();
            ui.setup(statistics);
            ui.unsetup();
        }
    }

    @Test
    public void testStatisticsUIValueAfterExecution() {
        for (StatisticsUI ui : uis()) {
            Statistics statistics = executeOn(randomGraph());
            ui.setup(statistics);
            try {
                // The value is optional (e.g. statistics that only write columns), but must be readable
                ui.getValue(statistics);
            } finally {
                ui.unsetup();
            }
        }
    }

    @Test
    public void testStatisticsUIValueIsPerStatisticsInstance() throws Exception {
        for (StatisticsUI ui : uis()) {
            Method method = ui.getClass().getMethod("getValue", Statistics.class);
            // StatisticsUI implementations are singletons shared by all workspaces, getValue() can only read the
            // statistics last passed to setup(). Plugins implementing getValue(Statistics) opt in to being safe.
            Assume.assumeFalse(ui.getClass().getSimpleName() + " does not implement getValue(Statistics)",
                method.isDefault());

            Statistics first = executeOn(randomGraph());
            Statistics second = executeOn(GraphGenerator.build().getGraphModel());

            ui.setup(first);
            String expected = ui.getValue(first);
            ui.unsetup();

            ui.setup(second);
            try {
                Assert.assertEquals("getValue(Statistics) must read the statistics it is given, not the last one "
                    + "passed to setup()", expected, ui.getValue(first));
            } finally {
                ui.unsetup();
            }
        }
    }

    // Execution

    @Test
    public void testExecute() {
        Assume.assumeFalse(isDynamic());
        for (Map.Entry<String, GraphModel> scenario : scenarios().entrySet()) {
            String name = scenario.getKey();
            GraphModel graphModel = scenario.getValue();
            int nodes = graphModel.getGraph().getNodeCount();
            int edges = graphModel.getGraph().getEdgeCount();
            Map<String, Class<?>> columnsBefore = columns(graphModel);

            Statistics statistics = builder.getStatistics();
            try {
                statistics.execute(graphModel);
            } catch (Throwable t) {
                throw new AssertionError("Statistics must handle the '" + name + "' graph: " + t, t);
            }

            assertGraphUntouched(name, graphModel, nodes, edges);
            Assert.assertNotNull("Report can't be null on the '" + name + "' graph", statistics.getReport());
            assertNoInvalidNumbers(name, graphModel, columnsBefore);
        }
    }

    @Test
    public void testExecuteReport() {
        Assume.assumeFalse(isDynamic());
        Statistics statistics = executeOn(randomGraph());
        String report = statistics.getReport();
        Assert.assertTrue("Report can't be null or empty after the statistics has been executed",
            report != null && !report.trim().isEmpty());
    }

    @Test
    public void testExecuteOnVisibleView() {
        Assume.assumeFalse(isDynamic());
        GraphModel graphModel = randomGraph();
        Graph mainGraph = graphModel.getGraph();
        int nodes = mainGraph.getNodeCount();
        int edges = mainGraph.getEdgeCount();

        // Keep half of the nodes in a filtered view, as filters do
        GraphView view = graphModel.createView();
        Graph viewGraph = graphModel.getGraph(view);
        int i = 0;
        for (Node node : mainGraph.getNodes().toArray()) {
            if (i++ % 2 == 0) {
                viewGraph.addNode(node);
            }
        }
        for (Edge edge : mainGraph.getEdges().toArray()) {
            if (viewGraph.contains(edge.getSource()) && viewGraph.contains(edge.getTarget())) {
                viewGraph.addEdge(edge);
            }
        }
        graphModel.setVisibleView(view);

        Statistics statistics = builder.getStatistics();
        statistics.execute(graphModel);

        assertGraphUntouched("filtered", graphModel, nodes, edges);
        Assert.assertNotNull("Report can't be null on a filtered graph", statistics.getReport());
    }

    @Test
    public void testExecuteTwice() {
        Assume.assumeFalse(isDynamic());
        GraphModel graphModel = randomGraph();
        Map<String, Class<?>> columnsBefore = columns(graphModel);

        builder.getStatistics().execute(graphModel);
        Map<String, Class<?>> columnsAfterFirst = columns(graphModel);

        // Same statistics results are expected to overwrite their columns, not duplicate or fail on them
        builder.getStatistics().execute(graphModel);
        Assert.assertEquals("Executing a second time must not add columns",
            columnsAfterFirst, columns(graphModel));

        // Statistics can be re-run after changing the graph, e.g. a filter
        Statistics reused = builder.getStatistics();
        reused.execute(graphModel);
        reused.execute(graphModel);
        Assert.assertEquals("Executing the same instance twice must not add columns",
            columnsAfterFirst, columns(graphModel));
        Assert.assertTrue("Statistics must keep the existing columns", columnsAfterFirst.keySet()
            .containsAll(columnsBefore.keySet()));
    }

    @Test
    public void testExecuteWithExistingColumnsOfAnotherType() {
        assumeNoKnownCoreIssue("testExecuteWithExistingColumnsOfAnotherType");
        Assume.assumeFalse(isDynamic());
        // Imported graphs commonly already have columns named like the results (e.g. 'degree'), typed as strings
        Map<String, Class<?>> created = new LinkedHashMap<>();
        {
            GraphModel reference = randomGraph();
            Map<String, Class<?>> before = columns(reference);
            builder.getStatistics().execute(reference);
            columns(reference).forEach((id, type) -> {
                if (!before.containsKey(id)) {
                    created.put(id, type);
                }
            });
        }
        Assume.assumeFalse("Statistics doesn't create any column", created.isEmpty());

        GraphModel graphModel = randomGraph();
        for (Map.Entry<String, Class<?>> entry : created.entrySet()) {
            String id = entry.getKey();
            String tableId = id.substring(0, id.indexOf(':'));
            String columnId = id.substring(id.indexOf(':') + 1);
            Table table = tableId.equals("node") ? graphModel.getNodeTable() : graphModel.getEdgeTable();
            Class<?> otherType = entry.getValue().equals(String.class) ? Integer.class : String.class;
            table.addColumn(columnId, otherType);
        }

        try {
            builder.getStatistics().execute(graphModel);
        } catch (Throwable t) {
            throw new AssertionError("Statistics must replace or tolerate existing columns " + created.keySet()
                + " of another type: " + t, t);
        }
    }

    @Test
    public void testExecuteLeavesGraphUnlocked() {
        Assume.assumeFalse(isDynamic());
        GraphModel graphModel = randomGraph();
        builder.getStatistics().execute(graphModel);
        assertGraphUnlocked("random", graphModel);
    }

    // Cancellation

    @Test
    public void testCancelDuringExecution() throws Exception {
        Assume.assumeFalse(isDynamic());
        Assume.assumeTrue("Statistics is not cancellable", builder.getStatistics() instanceof LongTask);

        for (long delayMs : new long[] {-1, 0, 1, 5, 25}) {
            GraphModel graphModel = largeRandomGraph();
            int nodes = graphModel.getGraph().getNodeCount();
            int edges = graphModel.getGraph().getEdgeCount();
            Statistics statistics = builder.getStatistics();
            LongTask task = (LongTask) statistics;

            Thread canceller = null;
            if (delayMs < 0) {
                // Cancelled before it even started
                task.cancel();
            } else {
                canceller = new Thread(() -> {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException ignored) {
                    }
                    task.cancel();
                });
            }

            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread runner = new Thread(() -> {
                try {
                    statistics.execute(graphModel);
                } catch (Throwable t) {
                    failure.set(t);
                }
            }, "statistics-contract-test");
            runner.start();
            if (canceller != null) {
                canceller.start();
            }
            runner.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));

            Assert.assertFalse("Cancelled statistics must return, it is still running (cancel after " + delayMs +
                "ms)", runner.isAlive());
            if (failure.get() != null) {
                throw new AssertionError("Cancelled statistics must not throw (cancel after " + delayMs + "ms): " +
                    failure.get(), failure.get());
            }
            assertGraphUntouched("cancelled", graphModel, nodes, edges);
        }
    }

    // Dynamic

    @Test
    public void testDynamicStatistics() {
        assumeNoKnownCoreIssue("testDynamicStatistics");
        Assume.assumeTrue("Statistics is not dynamic", isDynamic());

        DynamicStatistics statistics = (DynamicStatistics) builder.getStatistics();
        GraphModel graphModel = Lookup.getDefault().lookup(GraphController.class).getGraphModel();
        Node n1 = graphModel.factory().newNode("1");
        Node n2 = graphModel.factory().newNode("2");
        Node n3 = graphModel.factory().newNode("3");
        n1.addInterval(new Interval(0, 4));
        n2.addInterval(new Interval(0, 2));
        n3.addInterval(new Interval(2, 4));
        Edge e1 = graphModel.factory().newEdge("1", n1, n2, 0, 1.0, true);
        Edge e2 = graphModel.factory().newEdge("2", n1, n3, 0, 1.0, true);
        e1.addInterval(new Interval(0, 2));
        e2.addInterval(new Interval(2, 4));
        graphModel.getGraph().addAllNodes(List.of(n1, n2, n3));
        graphModel.getGraph().addAllEdges(List.of(e1, e2));
        int nodes = graphModel.getGraph().getNodeCount();
        int edges = graphModel.getGraph().getEdgeCount();
        statistics.setBounds(new Interval(0, 4));
        statistics.setWindow(1);
        statistics.setTick(1);

        Lookup.getDefault().lookup(StatisticsController.class).execute(statistics);

        assertGraphUntouched("dynamic", graphModel, nodes, edges);
        Assert.assertNotNull("Report can't be null after the dynamic statistics has been executed",
            statistics.getReport());
    }

    // Persistence

    @Test
    public void testReportSerialization() throws Exception {
        Assume.assumeFalse(isDynamic());
        Statistics statistics = executeOn(randomGraph());

        Workspace workspace = new WorkspaceImpl(null, 0);
        StatisticsModelImpl model = new StatisticsModelImpl(workspace);
        model.addReport(statistics);

        String xml = write(model);
        StatisticsModelImpl restored = new StatisticsModelImpl(workspace);
        read(restored, xml);

        Assert.assertEquals("Report is changed by saving and loading it, make sure it only uses plain HTML and " +
            "images written to temporary files", xml, write(restored));
        String report = restored.getReport(statistics.getClass());
        Assert.assertTrue("Report can't be read back from a saved project",
            report != null && !report.trim().isEmpty());
    }

    // Utilities

    private void assumeNoKnownCoreIssue(String test) {
        String issue = KNOWN_CORE_ISSUES.get(test + "[" + builder.getClass().getName() + "]");
        Assume.assumeTrue("Known core issue " + issue, issue == null);
    }

    private boolean isDynamic() {
        return builder.getStatistics() instanceof DynamicStatistics;
    }

    private List<StatisticsUI> uis() {
        return Lookup.getDefault().lookupAll(StatisticsUI.class).stream()
            .filter(ui -> builder.getStatisticsClass().equals(ui.getStatisticsClass()))
            .collect(Collectors.toList());
    }

    private Statistics executeOn(GraphModel graphModel) {
        Statistics statistics = builder.getStatistics();
        statistics.execute(graphModel);
        return statistics;
    }

    private static void assertGraphUntouched(String scenario, GraphModel graphModel, int nodes, int edges) {
        Assert.assertEquals("Statistics must not add or remove nodes ('" + scenario + "' graph)", nodes,
            graphModel.getGraph().getNodeCount());
        Assert.assertEquals("Statistics must not add or remove edges ('" + scenario + "' graph)", edges,
            graphModel.getGraph().getEdgeCount());
        assertGraphUnlocked(scenario, graphModel);
    }

    private static void assertGraphUnlocked(String scenario, GraphModel graphModel) {
        Graph graph = graphModel.getGraph();
        try {
            graph.writeLock();
            graph.writeUnlock();
        } catch (IllegalMonitorStateException e) {
            graph.readUnlockAll();
            throw new AssertionError("Statistics left a read lock on the graph, make sure every readLock() is " +
                "released, including when the statistics is cancelled or fails ('" + scenario + "' graph)", e);
        }
    }

    private static void assertNoInvalidNumbers(String scenario, GraphModel graphModel,
                                               Map<String, Class<?>> columnsBefore) {
        Map<String, Class<?>> after = columns(graphModel);
        for (Map.Entry<String, Class<?>> entry : after.entrySet()) {
            if (columnsBefore.containsKey(entry.getKey())) {
                continue;
            }
            Class<?> type = entry.getValue();
            if (!type.equals(Double.class) && !type.equals(Float.class)) {
                continue;
            }
            String id = entry.getKey();
            boolean nodeColumn = id.startsWith("node:");
            Column column = (nodeColumn ? graphModel.getNodeTable() : graphModel.getEdgeTable())
                .getColumn(id.substring(id.indexOf(':') + 1));
            Iterable<? extends Element> elements =
                nodeColumn ? graphModel.getGraph().getNodes() : graphModel.getGraph().getEdges();
            for (Element element : elements) {
                Object value = element.getAttribute(column);
                if (value instanceof Number) {
                    double d = ((Number) value).doubleValue();
                    Assert.assertTrue("Column '" + column.getId() + "' has a NaN or infinite value on the '" +
                        scenario + "' graph, it can't be ranked or exported", !Double.isNaN(d) &&
                        !Double.isInfinite(d));
                }
            }
        }
    }

    /**
     * Column ids prefixed with the table, to their value type.
     */
    private static Map<String, Class<?>> columns(GraphModel graphModel) {
        Map<String, Class<?>> columns = new HashMap<>();
        for (Column column : graphModel.getNodeTable()) {
            columns.put("node:" + column.getId(), column.getTypeClass());
        }
        for (Column column : graphModel.getEdgeTable()) {
            columns.put("edge:" + column.getId(), column.getTypeClass());
        }
        return columns;
    }

    private static Map<String, GraphModel> scenarios() {
        Map<String, GraphModel> scenarios = new LinkedHashMap<>();
        scenarios.put("empty", GraphGenerator.build().getGraphModel());
        scenarios.put("single node", build(model -> model.getGraph().addNode(model.factory().newNode("1"))));
        scenarios.put("single node with self-loop", build(model -> {
            Node n = model.factory().newNode("1");
            model.getGraph().addNode(n);
            model.getGraph().addEdge(model.factory().newEdge("1", n, n, 0, 1.0, true));
        }));
        scenarios.put("directed", GraphGenerator.build().generateTinyGraph().getGraphModel());
        scenarios.put("undirected", GraphGenerator.build().generateTinyUndirectedGraph().getGraphModel());
        scenarios.put("mixed", GraphGenerator.build().generateTinyMixedGraph().getGraphModel());
        scenarios.put("multigraph", GraphGenerator.build().generateTinyMultiGraph().getGraphModel());
        scenarios.put("disconnected", build(model -> {
            Graph g = model.getGraph();
            Node[] n = new Node[5];
            for (int i = 0; i < n.length; i++) {
                n[i] = model.factory().newNode(String.valueOf(i));
                g.addNode(n[i]);
            }
            g.addEdge(model.factory().newEdge("0", n[0], n[1], 0, 1.0, false));
            g.addEdge(model.factory().newEdge("1", n[1], n[2], 0, 1.0, false));
            g.addEdge(model.factory().newEdge("2", n[0], n[2], 0, 1.0, false));
            g.addEdge(model.factory().newEdge("3", n[3], n[3], 0, 1.0, false));
            // n[4] is isolated
        }));
        scenarios.put("weighted with zero and negative weights", build(model -> {
            Graph g = model.getGraph();
            Node a = model.factory().newNode("a");
            Node b = model.factory().newNode("b");
            Node c = model.factory().newNode("c");
            g.addNode(a);
            g.addNode(b);
            g.addNode(c);
            g.addEdge(model.factory().newEdge("0", a, b, 0, 0.0, true));
            g.addEdge(model.factory().newEdge("1", b, c, 0, 2.5, true));
            g.addEdge(model.factory().newEdge("2", c, a, 0, -1.0, true));
        }));
        scenarios.put("random", randomGraph());
        return scenarios;
    }

    private static GraphModel randomGraph() {
        return GraphGenerator.build().generateSmallRandomGraph().addIntNodeColumn().getGraphModel();
    }

    private static GraphModel largeRandomGraph() {
        return build(model -> {
            Random random = new Random(42);
            Graph g = model.getGraph();
            int n = 1500;
            Node[] nodes = new Node[n];
            for (int i = 0; i < n; i++) {
                nodes[i] = model.factory().newNode(String.valueOf(i));
                g.addNode(nodes[i]);
            }
            for (int i = 0; i < 6000; i++) {
                Node source = nodes[random.nextInt(n)];
                Node target = nodes[random.nextInt(n)];
                if (g.getEdge(source, target) == null) {
                    g.addEdge(model.factory().newEdge(source, target, 0, 1.0, true));
                }
            }
        });
    }

    private static GraphModel build(Consumer<GraphModel> populate) {
        GraphModel model = GraphGenerator.build().getGraphModel();
        populate.accept(model);
        return model;
    }

    private static String write(StatisticsModelImpl model) throws Exception {
        StringWriter stringWriter = new StringWriter();
        XMLStreamWriter writer = GephiFormat.newXMLWriter(stringWriter);
        writer.writeStartDocument("UTF-8", "1.0");
        writer.writeStartElement("statisticsmodel");
        model.writeXML(writer);
        writer.writeEndElement();
        writer.writeEndDocument();
        writer.close();
        return stringWriter.toString();
    }

    private static void read(StatisticsModelImpl model, String xml) throws Exception {
        XMLStreamReader reader = GephiFormat.newXMLReader(new StringReader(xml));
        model.readXML(reader);
        reader.close();
    }
}
