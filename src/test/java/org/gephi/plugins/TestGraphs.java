package org.gephi.plugins;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.gephi.graph.GraphGenerator;
import org.gephi.graph.api.Graph;
import org.gephi.graph.api.GraphModel;
import org.gephi.graph.api.Node;
import org.junit.Assert;

/**
 * Graphs known to trip up algorithms, shared by the contract tests.
 */
final class TestGraphs {

    private static final long LOCK_TIMEOUT_SECONDS = 10;

    private TestGraphs() {
    }

    static Map<String, GraphModel> scenarios() {
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

    static GraphModel randomGraph() {
        return GraphGenerator.build().generateSmallRandomGraph().addIntNodeColumn().getGraphModel();
    }

    static GraphModel largeRandomGraph() {
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

    static GraphModel build(Consumer<GraphModel> populate) {
        GraphModel model = GraphGenerator.build().getGraphModel();
        populate.accept(model);
        return model;
    }

    /**
     * Fails if the graph still holds a read lock, which would make taking the write lock impossible.
     *
     * @param culprit what is expected to release its locks, e.g. "Statistics"
     */
    static void assertGraphUnlocked(String culprit, String scenario, GraphModel graphModel) {
        Graph graph = graphModel.getGraph();
        // Taking the write lock waits for every read lock, including one leaked by a thread that is gone, so it
        // can't be done on the test thread
        CountDownLatch acquired = new CountDownLatch(1);
        Thread probe = new Thread(() -> {
            graph.writeLock();
            graph.writeUnlock();
            acquired.countDown();
        }, "graph-lock-probe");
        probe.setDaemon(true);
        probe.start();

        boolean unlocked;
        try {
            unlocked = acquired.await(LOCK_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            unlocked = false;
        }
        if (!unlocked) {
            graph.readUnlockAll();
            throw new AssertionError(culprit + " left a read lock on the graph, make sure every readLock() is " +
                "released, including when it is cancelled or fails ('" + scenario + "' graph)");
        }
    }
}
