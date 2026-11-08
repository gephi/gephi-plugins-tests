[![build](https://github.com/gephi/gephi-plugins-tests/actions/workflows/release.yml/badge.svg)](https://github.com/gephi/gephi-plugins-tests/actions/workflows/release.yml)

# Gephi Plugins Tests

This library contains integration tests we run against [plugins](https://github.com/gephi/gephi-plugins) to validate them.

## Coverage

We cover a number of plugin types and expectations and plan to add more overtime.

* [x] Layout
  * [x] Layout has a proper builder and UI
  * [x] Layout UI has a proper description
  * [x] Speed and quality appraisal are within the expected ranges
  * [x] Properties can be read and written
  * [x] Properties can be serialized via the persistence providers, including non-default values
  * [x] Properties accept their own value written back, also between iterations of a run
  * [x] Builder returns a new instance each time and the layout points back to it
  * [x] Runs the init, go, end lifecycle on edge cases (empty, single node, self-loops, undirected, mixed, multigraph, disconnected, coincident and zero-size nodes)
  * [x] Doesn't leave the graph locked or write NaN/infinite positions
  * [x] Doesn't remove nodes or edges
  * [x] Doesn't add nodes or edges (warning)
  * [x] Can be stopped after any iteration, run again and reset
  * [x] Cancelling at any moment doesn't throw or leave the graph locked
* [x] Statistics
  * [x] Builder returns a new instance of the declared class, and the controller can find it
  * [x] Statistics has a UI (warning)
  * [x] Statistics UI has a name and category, and its settings panel and value can be read
  * [x] Executes on edge cases (empty, single node, self-loops, undirected, mixed, multigraph, disconnected, filtered view)
  * [x] Doesn't leave the graph locked or write NaN/infinite values
  * [x] Doesn't remove nodes or edges
  * [x] Doesn't add nodes or edges (warning)
  * [x] Can be executed again
  * [x] Replaces existing columns of another type (warning)
  * [x] Cancelling at any moment doesn't throw or leave the graph locked
  * [x] Report is not empty and can be read back from a saved project
  * [x] Report is unchanged by saving and loading (warning)
  * [x] Dynamic statistics run through the controller

## Development

### Requirements

Developing in the repository requires JDK 17 or later and [Maven](http://maven.apache.org/).

### Create a new contract test

Tests live in `src/test/java` and are packaged as a test-jar, so consuming plugins can depend on this project (with `<type>test-jar</type>`) and get these tests run automatically against whatever they implement, via Surefire's `dependenciesToScan`. You can extend an existing class with new tests. If you create a new class, make sure its name ends with `Test` so Surefire picks it up both here and in consumers.

### Warnings

A test annotated with `@Advisory` (the test class needs the `AdvisoryRule`) doesn't fail when its check does, it reports a warning instead. Use it for things that don't break the plugin but that users or reviewers should know about, for instance a layout that adds nodes on purpose. The warning is printed, and appended to the file set in the `gephi.plugins.tests.warnings` system property, as one `<test name><TAB><message>` line each. gephi-plugins' CI harness sets it to turn them into GitHub annotations and a job summary.

Currently advisory: nodes or edges being added, existing columns of another type, reports that change when saved and loaded, and a statistics without a user interface, and `cancel()` throwing when it is called right as the run starts (reported by the cancellation tests, which can't assert it since the race decides whether it happens).

Add new dependencies in the `pom.xml` to support testing new types of plugins.

To validate your contract tests, it can be helpful to run them against the core Gephi. For instance, we've added a test dependency to the `layout-plugin` module. This allows to run the tests against the core layouts.