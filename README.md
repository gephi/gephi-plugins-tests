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
  * [x] Properties can be serialized via the persistence providers

## Development

### Requirements

Developing in the repository requires JDK 17 or later and [Maven](http://maven.apache.org/).

### Create a new contract test

Tests live in `src/test/java` and are packaged as a test-jar, so consuming plugins can depend on this project (with `<type>test-jar</type>`) and get these tests run automatically against whatever they implement, via Surefire's `dependenciesToScan`. You can extend an existing class with new tests. If you create a new class, make sure its name ends with `Test` so Surefire picks it up both here and in consumers.

Add new dependencies in the `pom.xml` to support testing new types of plugins.

To validate your contract tests, it can be helpful to run them against the core Gephi. For instance, we've added a test dependency to the `layout-plugin` module. This allows to run the tests against the core layouts.