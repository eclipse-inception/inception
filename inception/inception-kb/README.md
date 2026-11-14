# INCEpTION - Knowledge Base - Core

This module contains the knowledge base core, including the `SPARQLQueryBuilder`
and `KnowledgeBaseServiceImpl`.

## SPARQL query builder benchmark

`SPARQLQueryBuilderBenchmarkTest` is a performance benchmark that runs a set of
standard operations against the knowledge bases for which we ship a profile. Its
purpose is to detect whether a change to the `SPARQLQueryBuilder` or to
`KnowledgeBaseServiceImpl` improves or degrades performance.

It is **not** a correctness test and does **not** run during normal CI or local
builds. It is gated twice:

- It is tagged `@Tag("slow")`, so it is excluded by the default surefire
  configuration (see `excludedTestCategories` in `inception/pom.xml`).
- It is additionally gated behind `-Dinception.benchmark=true` via
  `@EnabledIfSystemProperty`, so even when the `slow` group is enabled it only
  runs when explicitly requested.

### What it measures

For each knowledge base it benchmarks these standard operations, building the
query the same way the corresponding `KnowledgeBaseServiceImpl` method does:

- `listRootConcepts`
- `listProperties`
- `listChildConcepts`
- `getParentConceptList`
- `labelStartingWith` (auto-complete style prefix label search / FTS)
- `labelContaining` (substring / token label search / FTS)

Each measured iteration rebuilds the query via the `SPARQLQueryBuilder` and
executes it through `KnowledgeBaseServiceImpl.read(...)`. This deliberately
bypasses the read-only result cache used by the `listXxx` service methods —
otherwise every iteration after the first would be a cache hit and the numbers
would be meaningless. Each iteration therefore reflects the true cost of query
construction plus execution.

The knowledge bases come straight from the bundled
`knowledgebase-profiles.yaml`: the LOCAL `hpo` (downloaded and imported, so
free of network noise during the measurement) plus the REMOTE `wikidata`, `db_pedia`, `zbw-stw-economics`, `mesh` and
`agrovoc`. Remote endpoints that are unreachable are skipped automatically.

### Running it

    mvn -pl inception/inception-kb test \
        -Dinception.benchmark=true \
        -DexcludedTestCategories=none \
        -Dtest=SPARQLQueryBuilderBenchmarkTest

The `-DexcludedTestCategories=none` is required to re-enable the `slow` group;
`-Dinception.benchmark=true` then activates the benchmark itself.

### Configuration

All of the following system properties are optional:

| Property | Default | Description |
| --- | --- | --- |
| `inception.benchmark` | (unset) | Must be `true` for the benchmark to run at all. |
| `inception.benchmark.warmup` | `3` | Warmup iterations per operation (not measured). |
| `inception.benchmark.iterations` | `10` | Measured iterations per operation. |
| `inception.benchmark.kbs` | (all) | Comma-separated profile keys to restrict the run, e.g. `wikidata,mesh`. |
| `inception.benchmark.output` | `target/sparql-benchmark-results.csv` | CSV file the results are written to. Set to an empty value to disable writing. |
| `inception.benchmark.baseline` | (unset) | CSV file from a previous run to compare against; when set, per-operation median deltas are logged. |

### Output

Results are logged as a human-readable table and as `BENCHMARK_CSV,...` lines,
and the same rows are written to the output CSV file. The CSV columns are:

    kb,operation,iterations,minMs,medianMs,maxMs,meanMs,results

Example log table:

    operation                 min(ms) median(ms)    max(ms)   mean(ms)    results
    listRootConcepts            28.38      32.68      35.48      32.18          6
    listChildConcepts            8.75       9.44      10.23       9.47         34
    labelContaining             11.21      12.39      14.14      12.58         12

### Comparing against a baseline

Capture a baseline before your change, then compare after it:

    # capture baseline before the change
    mvn -pl inception/inception-kb test \
        -Dinception.benchmark=true \
        -DexcludedTestCategories=none \
        -Dtest=SPARQLQueryBuilderBenchmarkTest \
        -Dinception.benchmark.output=baseline.csv

    # after editing the builder / KBServiceImpl
    mvn -pl inception/inception-kb test \
        -Dinception.benchmark=true \
        -DexcludedTestCategories=none \
        -Dtest=SPARQLQueryBuilderBenchmarkTest \
        -Dinception.benchmark.output=after.csv \
        -Dinception.benchmark.baseline=baseline.csv

The second run logs a comparison of the medians (`+` = slower / regression,
`-` = faster); operations not present in the baseline are marked `new`:

    operation                  base(ms)      now(ms)      delta
    listRootConcepts              56.42        32.68     -42.1%
    labelStartingWith             13.79        16.49     +19.6%

### Committed reference results

Reference results are kept under `benchmark/` with the producing commit hash in
the file name (e.g. `benchmark/sparql-benchmark-74bed534d8.csv`). Each file has a
metadata header recording the commit, date, settings and machine. These files
double as baselines, so you can compare a working copy directly against a
recorded reference:

    mvn -pl inception/inception-kb test \
        -Dinception.benchmark=true \
        -DexcludedTestCategories=none \
        -Dtest=SPARQLQueryBuilderBenchmarkTest \
        -Dinception.benchmark.baseline=benchmark/sparql-benchmark-74bed534d8.csv

When recording a new reference, run on a clean working tree so the commit hash in
the file name and header actually identifies the code that produced it, and add
the hash to the file name.

### Notes

- A comparison is only meaningful when both runs use the **same KB set and
  iteration counts** under similar conditions. The baseline file is keyed by
  `kb|operation` but does not enforce that the iteration count or environment
  matched — keep those consistent between the baseline and the after-run.
- For the REMOTE knowledge bases the timings are dominated by network and server
  load, so trust the **median over many iterations** rather than a single number,
  and run the baseline and the after-run back-to-back under the same network
  conditions. The LOCAL `hpo` gives a network-noise-free signal.
- The test logs at `INFO`; the benchmark logger is enabled in
  `src/test/resources/log4j2-test.xml` so the report is visible (the module's
  default log level is `WARN`).
