/*
 * Licensed to the Technische Universität Darmstadt under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The Technische Universität Darmstadt
 * licenses this file to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package de.tudarmstadt.ukp.inception.kb;

import static de.tudarmstadt.ukp.inception.kb.RepositoryType.LOCAL;
import static de.tudarmstadt.ukp.inception.kb.RepositoryType.REMOTE;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.restoreSslVerification;
import static de.tudarmstadt.ukp.inception.kb.http.PerThreadSslCheckingHttpClientUtils.suspendSslVerification;
import static de.tudarmstadt.ukp.inception.kb.util.TestFixtures.assumeEndpointIsAvailable;
import static org.junit.jupiter.api.Assumptions.abort;
import static org.junit.jupiter.api.parallel.ExecutionMode.SAME_THREAD;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.StandardOpenOption.APPEND;
import static java.nio.file.StandardOpenOption.CREATE;
import static java.nio.file.StandardOpenOption.TRUNCATE_EXISTING;
import static java.nio.file.StandardOpenOption.WRITE;
import static java.util.Collections.emptyMap;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.transaction.annotation.Transactional;

import de.tudarmstadt.ukp.inception.documents.api.RepositoryPropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.config.KnowledgeBasePropertiesImpl;
import de.tudarmstadt.ukp.inception.kb.graph.KBHandle;
import de.tudarmstadt.ukp.inception.kb.model.KnowledgeBase;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQuery;
import de.tudarmstadt.ukp.inception.kb.querybuilder.SPARQLQueryBuilder;
import de.tudarmstadt.ukp.inception.kb.util.TestFixtures;
import de.tudarmstadt.ukp.inception.kb.yaml.KnowledgeBaseProfile;

/**
 * Performance benchmark for the {@link SPARQLQueryBuilder} (and the
 * {@link KnowledgeBaseServiceImpl} read path) exercising a set of standard operations against all
 * knowledge bases for which we ship a profile.
 * <p>
 * The purpose of this benchmark is to detect whether changes to the query builder or to the KB
 * service improve or degrade performance. It is therefore <b>not</b> a correctness test and it does
 * <b>not</b> run during normal CI or local builds:
 * <ul>
 * <li>It is annotated with {@code @Tag("slow")} so it is excluded by the default surefire
 * configuration (see {@code excludedTestCategories} in {@code inception/pom.xml}).</li>
 * <li>It is additionally gated behind the system property {@code -Dinception.benchmark=true} via
 * {@link EnabledIfSystemProperty}, so even when the {@code slow} group is enabled the benchmark
 * only runs when explicitly requested.</li>
 * </ul>
 * <p>
 * Run it with e.g.:
 *
 * <pre>
 * mvn -pl inception/inception-kb test \
 *     -Dinception.benchmark=true \
 *     -DexcludedTestCategories=none \
 *     -Dtest=SPARQLQueryBuilderBenchmarkTest
 * </pre>
 *
 * <p>
 * Tuning system properties (all optional):
 * <ul>
 * <li>{@code inception.benchmark.warmup} - number of warmup iterations per operation (default
 * {@value #DEFAULT_WARMUP})</li>
 * <li>{@code inception.benchmark.iterations} - number of measured iterations per operation (default
 * {@value #DEFAULT_ITERATIONS})</li>
 * <li>{@code inception.benchmark.kbs} - comma-separated profile keys to restrict the run (e.g.
 * {@code wikidata,mesh})</li>
 * <li>{@code inception.benchmark.output} - CSV file the results are written to (default
 * {@value #DEFAULT_OUTPUT}, relative to the module directory). Set to an empty value to disable
 * writing.</li>
 * <li>{@code inception.benchmark.baseline} - CSV file from a previous run to compare against; when
 * set, per-operation median deltas are logged.</li>
 * </ul>
 * <p>
 * Each measured iteration rebuilds the query via the {@link SPARQLQueryBuilder} and executes it
 * through {@link KnowledgeBaseServiceImpl#read} (which bypasses the read-only result cache used by
 * the {@code listXxx} service methods), so every iteration reflects the true cost of query
 * construction plus execution rather than a cache hit.
 * <p>
 * Results are written to the log (a human-readable table plus {@code BENCHMARK_CSV,...} lines) and
 * to a CSV file. To compare a change against a baseline, run the benchmark before the change, keep
 * that CSV, then run it again after the change passing the saved file via
 * {@code -Dinception.benchmark.baseline=...}:
 *
 * <pre>
 * # baseline
 * mvn ... -Dinception.benchmark.output=baseline.csv
 * # after the change
 * mvn ... -Dinception.benchmark.output=after.csv -Dinception.benchmark.baseline=baseline.csv
 * </pre>
 */
@Tag("slow")
@Tag("benchmark")
@EnabledIfSystemProperty(named = "inception.benchmark", matches = "true", //
        disabledReason = "Benchmark only runs when -Dinception.benchmark=true is set")
@Transactional
@DataJpaTest( //
        showSql = false, //
        properties = { //
                "spring.main.banner-mode=off" })
@Execution(SAME_THREAD)
public class SPARQLQueryBuilderBenchmarkTest
{
    private static final Logger LOG = LoggerFactory.getLogger(MethodHandles.lookup().lookupClass());

    private static final int DEFAULT_WARMUP = 3;
    private static final int DEFAULT_ITERATIONS = 10;

    private static final int MAX_RESULTS = 1000;

    private static final String DEFAULT_OUTPUT = "target/sparql-benchmark-results.csv";
    private static final String CSV_HEADER = //
            "kb,operation,iterations,minMs,medianMs,maxMs,meanMs,results";

    /** Median ms keyed by {@code kb|operation}, loaded from the baseline file (if any). */
    private static Map<String, Double> baselineMedians = emptyMap();

    private final int warmup = Integer.getInteger("inception.benchmark.warmup", DEFAULT_WARMUP);
    private final int iterations = Integer.getInteger("inception.benchmark.iterations",
            DEFAULT_ITERATIONS);

    private KnowledgeBaseServiceImpl sut;

    @TempDir
    File repoPath;

    @Autowired
    private TestEntityManager testEntityManager;

    @BeforeAll
    public static void initOutputAndBaseline() throws IOException
    {
        var out = outputFile();
        if (out != null) {
            if (out.toAbsolutePath().getParent() != null) {
                Files.createDirectories(out.toAbsolutePath().getParent());
            }
            // Start a fresh file and write the header. Each test then appends its rows.
            Files.writeString(out, CSV_HEADER + System.lineSeparator(), UTF_8, CREATE,
                    TRUNCATE_EXISTING, WRITE);
            LOG.info("Writing benchmark results to [{}]", out.toAbsolutePath());
        }

        baselineMedians = loadBaselineMedians();
    }

    @BeforeEach
    public void testWatcher(TestInfo aTestInfo)
    {
        var methodName = aTestInfo.getTestMethod().map(Method::getName).orElse("<unknown>");
        LOG.info("=== {} === {} =====================", methodName, aTestInfo.getDisplayName());

        suspendSslVerification();
    }

    public void setUp(BenchmarkConfiguration aConfig) throws Exception
    {
        var kb = aConfig.kb;

        if (kb.getType() == REMOTE) {
            assumeEndpointIsAvailable(aConfig.url);
        }

        var repoProps = new RepositoryPropertiesImpl();
        repoProps.setPath(repoPath);
        var kbProperties = new KnowledgeBasePropertiesImpl();
        var entityManager = testEntityManager.getEntityManager();
        var testFixtures = new TestFixtures(testEntityManager);
        sut = new KnowledgeBaseServiceImpl(repoProps, kbProperties, entityManager);
        var project = testFixtures.createProject("Benchmark project");
        kb.setProject(project);
        if (kb.getType() == LOCAL) {
            sut.registerKnowledgeBase(kb, sut.getNativeConfig());
            sut.updateKnowledgeBase(kb, sut.getKnowledgeBaseConfig(kb));
            try {
                importKnowledgeBase(kb, aConfig.url);
            }
            catch (IOException e) {
                // Ontologies downloaded for import (rather than bundled on the classpath) require
                // their source to be reachable - skip rather than fail if it is not.
                abort("Could not import [" + aConfig.url + "]: " + e.getMessage());
            }
        }
        else if (kb.getType() == REMOTE) {
            assumeEndpointIsAvailable(aConfig.url);
            sut.registerKnowledgeBase(kb, sut.getRemoteConfig(aConfig.url));
            sut.updateKnowledgeBase(kb, sut.getKnowledgeBaseConfig(kb));
        }
        else {
            throw new IllegalStateException("Unknown type: " + kb.getType());
        }
    }

    @AfterEach
    public void tearDown() throws Exception
    {
        if (testEntityManager != null) {
            testEntityManager.clear();
        }

        if (sut != null) {
            sut.destroy();
        }

        restoreSslVerification();
    }

    @ParameterizedTest(name = "{index}: KB = {0}")
    @MethodSource("data")
    public void benchmark(BenchmarkConfiguration aConfig) throws Exception
    {
        setUp(aConfig);

        var kb = aConfig.kb;

        // The standard operations to benchmark. Each supplier rebuilds the query so that the cost
        // of
        // building the query via the SPARQLQueryBuilder is included in every measured iteration.
        // The
        // queries mirror the ones issued by the corresponding KnowledgeBaseServiceImpl methods.
        var operations = new LinkedHashMap<String, Supplier<SPARQLQuery>>();

        // listRootConcepts(kb, true)
        operations.put("listRootConcepts", () -> SPARQLQueryBuilder.forClasses(kb) //
                .roots() //
                .retrieveLabel() //
                .retrieveDescription() //
                .retrieveDeprecation());

        // listProperties(kb, true, true)
        operations.put("listProperties", () -> SPARQLQueryBuilder.forProperties(kb) //
                .retrieveLabel() //
                .retrieveDescription() //
                .retrieveDeprecation() //
                .retrieveDomainAndRange() //
                .includeInferred(true));

        if (aConfig.parentForChildren != null) {
            // listChildConcepts(kb, parent, true)
            operations.put("listChildConcepts", () -> SPARQLQueryBuilder.forClasses(kb) //
                    .childrenOf(aConfig.parentForChildren) //
                    .retrieveLabel() //
                    .retrieveDescription() //
                    .retrieveDeprecation() //
                    .limit(kb.getMaxResults()));
        }

        if (aConfig.leafForAncestors != null) {
            // getParentConceptList(kb, leaf, true)
            operations.put("getParentConceptList", () -> SPARQLQueryBuilder.forClasses(kb) //
                    .ancestorsOf(aConfig.leafForAncestors) //
                    .retrieveLabel() //
                    .retrieveDescription() //
                    .retrieveDeprecation());
        }

        if (aConfig.labelPrefix != null) {
            // Label-matching requires the effective pref-label / additional-match properties to be
            // resolved from the repository first (mirrors ConceptLinkingServiceImpl). This is done
            // once per KB and reused across the label operations, so it is not part of the measured
            // time - we want to benchmark the label query itself, not the property resolution.
            try {
                var prefLabelProperties = sut.read(kb,
                        conn -> SPARQLQueryBuilder.forItems(kb).resolvePrefLabelProperties(conn));
                var additionalMatchProperties = sut.read(kb, conn -> SPARQLQueryBuilder.forItems(kb)
                        .resolveAdditionalMatchingProperties(conn));

                // Auto-complete style prefix label search (FTS) - see ConceptLinkingServiceImpl
                operations.put("labelStartingWith", () -> SPARQLQueryBuilder.forItems(kb) //
                        .withPrefLabelProperties(prefLabelProperties) //
                        .withAdditionalMatchingProperties(additionalMatchProperties) //
                        .withLabelStartingWith(aConfig.labelPrefix) //
                        .retrieveLabel() //
                        .retrieveDescription() //
                        .retrieveDeprecation());

                // Substring / token label search (FTS)
                operations.put("labelContaining", () -> SPARQLQueryBuilder.forItems(kb) //
                        .withPrefLabelProperties(prefLabelProperties) //
                        .withAdditionalMatchingProperties(additionalMatchProperties) //
                        .withLabelContainingAnyOf(aConfig.labelPrefix) //
                        .retrieveLabel() //
                        .retrieveDescription() //
                        .retrieveDeprecation());
            }
            catch (Exception e) {
                LOG.warn("[{}] - skipping label-search operations, could not resolve label "
                        + "properties: {}", kb.getName(), e.toString());
            }
        }

        var results = new ArrayList<OpResult>();
        for (var op : operations.entrySet()) {
            results.add(measure(kb, op.getKey(), op.getValue()));
        }

        report(kb, results);
    }

    private OpResult measure(KnowledgeBase aKB, String aName, Supplier<SPARQLQuery> aQueryFactory)
    {
        // Warmup - not measured, also lets us fail fast / record errors before timing.
        try {
            for (int i = 0; i < warmup; i++) {
                execute(aKB, aQueryFactory.get());
            }
        }
        catch (Exception e) {
            LOG.warn("[{}] / [{}] failed during warmup: {}", aKB.getName(), aName, e.toString());
            return OpResult.failed(aName, e);
        }

        var timingsNanos = new long[iterations];
        int lastResultSize = 0;
        try {
            for (int i = 0; i < iterations; i++) {
                var query = aQueryFactory.get();
                long start = System.nanoTime();
                lastResultSize = execute(aKB, query).size();
                timingsNanos[i] = System.nanoTime() - start;
            }
        }
        catch (Exception e) {
            LOG.warn("[{}] / [{}] failed during measurement: {}", aKB.getName(), aName,
                    e.toString());
            return OpResult.failed(aName, e);
        }

        return OpResult.of(aName, timingsNanos, lastResultSize);
    }

    private List<KBHandle> execute(KnowledgeBase aKB, SPARQLQuery aQuery)
    {
        // Deliberately go through read() rather than the listXxx service methods: for read-only
        // (typically remote) KBs the service caches results, which would turn every iteration after
        // the first into a cache hit and make the benchmark meaningless. read() executes the query
        // against a fresh connection every time.
        return sut.read(aKB, conn -> aQuery.asHandles(conn, true));
    }

    private void report(KnowledgeBase aKB, List<OpResult> aResults)
    {
        LOG.info(
                "================================================================================");
        LOG.info("Benchmark results for KB [{}] (warmup={}, iterations={})", aKB.getName(), warmup,
                iterations);
        LOG.info(
                "--------------------------------------------------------------------------------");
        LOG.info(String.format(Locale.ROOT, "%-22s %10s %10s %10s %10s %10s", "operation",
                "min(ms)", "median(ms)", "max(ms)", "mean(ms)", "results"));
        for (var r : aResults) {
            if (r.error != null) {
                LOG.info(String.format(Locale.ROOT, "%-22s %10s %10s %10s %10s %10s", r.operation,
                        "ERR", "ERR", "ERR", "ERR", "-"));
            }
            else {
                LOG.info(String.format(Locale.ROOT, "%-22s %10.2f %10.2f %10.2f %10.2f %10d",
                        r.operation, r.minMs, r.medianMs, r.maxMs, r.meanMs, r.resultSize));
            }
        }
        LOG.info(
                "--------------------------------------------------------------------------------");
        // Machine-readable lines for easy extraction and diffing across runs. The same rows
        // (without the BENCHMARK_CSV prefix) are appended to the output file.
        LOG.info("BENCHMARK_CSV header: {}", CSV_HEADER);
        var csvRows = new ArrayList<String>();
        for (var r : aResults) {
            String row;
            if (r.error != null) {
                row = String.format(Locale.ROOT, "%s,%s,%d,,,,,ERROR:%s", aKB.getName(),
                        r.operation, iterations, r.error);
            }
            else {
                row = String.format(Locale.ROOT, "%s,%s,%d,%.2f,%.2f,%.2f,%.2f,%d", aKB.getName(),
                        r.operation, iterations, r.minMs, r.medianMs, r.maxMs, r.meanMs,
                        r.resultSize);
            }
            LOG.info("BENCHMARK_CSV,{}", row);
            csvRows.add(row);
        }

        writeRows(csvRows);
        reportBaselineComparison(aKB, aResults);

        LOG.info(
                "================================================================================");
    }

    private void reportBaselineComparison(KnowledgeBase aKB, List<OpResult> aResults)
    {
        if (baselineMedians.isEmpty()) {
            return;
        }

        LOG.info(
                "--------------------------------------------------------------------------------");
        LOG.info("Comparison vs baseline (median, + = slower/regression, - = faster):");
        LOG.info(String.format(Locale.ROOT, "%-22s %12s %12s %10s", "operation", "base(ms)",
                "now(ms)", "delta"));
        for (var r : aResults) {
            if (r.error != null) {
                continue;
            }

            var base = baselineMedians.get(key(aKB.getName(), r.operation));
            if (base == null) {
                LOG.info(String.format(Locale.ROOT, "%-22s %12s %12.2f %10s", r.operation, "n/a",
                        r.medianMs, "new"));
                continue;
            }

            var delta = base != 0.0 ? (r.medianMs - base) / base * 100.0 : 0.0;
            LOG.info(String.format(Locale.ROOT, "%-22s %12.2f %12.2f %+9.1f%%", r.operation, base,
                    r.medianMs, delta));
        }
    }

    private void writeRows(List<String> aRows)
    {
        var out = outputFile();
        if (out == null) {
            return;
        }

        try {
            Files.write(out, aRows, UTF_8, CREATE, APPEND, WRITE);
        }
        catch (IOException e) {
            LOG.warn("Could not append benchmark results to [{}]: {}", out, e.toString());
        }
    }

    private static Path outputFile()
    {
        var path = System.getProperty("inception.benchmark.output", DEFAULT_OUTPUT);
        if (path == null || path.isBlank()) {
            return null;
        }
        return Paths.get(path);
    }

    private static Map<String, Double> loadBaselineMedians() throws IOException
    {
        var path = System.getProperty("inception.benchmark.baseline");
        if (path == null || path.isBlank()) {
            return emptyMap();
        }

        var file = Paths.get(path);
        if (!Files.exists(file)) {
            LOG.warn("Baseline file [{}] does not exist - skipping comparison", file);
            return emptyMap();
        }

        var medians = new LinkedHashMap<String, Double>();
        var lines = Files.readAllLines(file, UTF_8);
        for (var line : lines) {
            if (line.isBlank() || line.startsWith("kb,")) {
                continue; // header / blank
            }
            // kb,operation,iterations,minMs,medianMs,maxMs,meanMs,results
            var fields = line.split(",", -1);
            if (fields.length < 5 || fields[4].isBlank()) {
                continue; // malformed or an error row (empty timings)
            }
            try {
                medians.put(key(fields[0], fields[1]), Double.parseDouble(fields[4]));
            }
            catch (NumberFormatException e) {
                // skip unparseable rows
            }
        }
        LOG.info("Loaded {} baseline median(s) from [{}]", medians.size(), file.toAbsolutePath());
        return medians;
    }

    private static String key(String aKbName, String aOperation)
    {
        return aKbName + "|" + aOperation;
    }

    private void importKnowledgeBase(KnowledgeBase aKnowledgeBase, String aResource)
        throws Exception
    {
        // LOCAL profiles either point at a bundled classpath resource or at a remote
        // file to download once and import into the local repository (e.g. the OBO ontologies).
        if (aResource.startsWith("http://") || aResource.startsWith("https://")
                || aResource.startsWith("file:")) {
            LOG.info("Downloading [{}] for KB [{}] ...", aResource, aKnowledgeBase.getName());
            var url = new URI(aResource).toURL();
            try (InputStream is = url.openStream()) {
                sut.importData(aKnowledgeBase, aResource, is);
            }
            return;
        }

        var classLoader = SPARQLQueryBuilderBenchmarkTest.class.getClassLoader();
        var fileName = classLoader.getResource(aResource).getFile();
        try (InputStream is = classLoader.getResourceAsStream(aResource)) {
            sut.importData(aKnowledgeBase, fileName, is);
        }
    }

    public static List<BenchmarkConfiguration> data() throws Exception
    {
        var profiles = KnowledgeBaseProfile.readKnowledgeBaseProfiles();

        var configs = new ArrayList<BenchmarkConfiguration>();

        // LOCAL - Human Phenotype Ontology (downloaded as OWL and imported into a local repo).
        // A non-trivial, network-noise-free data set.
        configs.add(BenchmarkConfiguration.fromProfile("hpo", profiles) //
                .parentForChildren("http://purl.obolibrary.org/obo/HP_0000118") // Phenotypic
                                                                                // abnorm.
                .leafForAncestors("http://purl.obolibrary.org/obo/HP_0001250") // Seizure
                .labelPrefix("Abnormal"));

        // LOCAL - Gene Ontology (GO-basic, downloaded as OWL and imported into a local repo). A
        // good large-but-local data point.
        configs.add(BenchmarkConfiguration.fromProfile("go", profiles) //
                .parentForChildren("http://purl.obolibrary.org/obo/GO_0008150") // biological_process
                .leafForAncestors("http://purl.obolibrary.org/obo/GO_0006915") // apoptotic process
                .labelPrefix("membrane"));

        // REMOTE - Wikidata (direct mapping, MediaWiki API FTS)
        configs.add(BenchmarkConfiguration.fromProfile("wikidata", profiles) //
                .parentForChildren("http://www.wikidata.org/entity/Q35120") // entity
                .leafForAncestors("http://www.wikidata.org/entity/Q5") // human
                .labelPrefix("Albert"));

        // REMOTE - DBpedia (Virtuoso bif:contains FTS)
        configs.add(BenchmarkConfiguration.fromProfile("db_pedia", profiles) //
                .parentForChildren("http://www.w3.org/2002/07/owl#Thing") //
                .leafForAncestors("http://dbpedia.org/ontology/Organisation") //
                .labelPrefix("Berlin"));

        // REMOTE - STW Thesaurus for Economics (SKOS, text:query FTS)
        configs.add(BenchmarkConfiguration.fromProfile("zbw-stw-economics", profiles) //
                .parentForChildren("http://zbw.eu/stw/thsys/a") //
                .leafForAncestors("http://zbw.eu/stw/thsys/71020") //
                .labelPrefix("labour"));

        // REMOTE - MeSH (Virtuoso bif:contains FTS, multi-dataset profile, see #6125)
        configs.add(BenchmarkConfiguration.fromProfile("mesh", profiles) //
                .parentForChildren("http://id.nlm.nih.gov/mesh/D004703") // Endocrine System
                .leafForAncestors("http://id.nlm.nih.gov/mesh/D007328") // Insulin
                .labelPrefix("Insulin"));

        // REMOTE - AGROVOC (SKOS, text:query FTS)
        configs.add(BenchmarkConfiguration.fromProfile("agrovoc", profiles) //
                .labelPrefix("wheat"));

        // REMOTE - DBLP Computer Science Bibliography (QLever-backed, contains-word FTS). The
        // structural operations run against the small RDF schema; FTS matches publications/persons.
        configs.add(BenchmarkConfiguration.fromProfile("dblp", profiles) //
                .parentForChildren("https://dblp.org/rdf/schema#Publication") //
                .leafForAncestors("https://dblp.org/rdf/schema#Person") //
                .labelPrefix("Knuth"));

        // Allow restricting the set via -Dinception.benchmark.kbs=wikidata,mesh
        var filter = System.getProperty("inception.benchmark.kbs");
        if (filter != null && !filter.isBlank()) {
            var wanted = Arrays.stream(filter.split(",")).map(String::trim).toList();
            configs.removeIf(c -> !wanted.contains(c.profileKey));
        }

        return configs;
    }

    /**
     * One operation's measured timings.
     */
    private static final class OpResult
    {
        final String operation;
        final double minMs;
        final double medianMs;
        final double maxMs;
        final double meanMs;
        final int resultSize;
        final String error;

        private OpResult(String aOperation, double aMinMs, double aMedianMs, double aMaxMs,
                double aMeanMs, int aResultSize, String aError)
        {
            operation = aOperation;
            minMs = aMinMs;
            medianMs = aMedianMs;
            maxMs = aMaxMs;
            meanMs = aMeanMs;
            resultSize = aResultSize;
            error = aError;
        }

        static OpResult failed(String aOperation, Throwable aError)
        {
            var msg = aError.getClass().getSimpleName()
                    + (aError.getMessage() != null ? ": " + aError.getMessage() : "");
            return new OpResult(aOperation, 0, 0, 0, 0, -1, msg.replaceAll("[\\r\\n,]", " "));
        }

        static OpResult of(String aOperation, long[] aTimingsNanos, int aResultSize)
        {
            var sorted = aTimingsNanos.clone();
            Arrays.sort(sorted);

            double min = sorted[0] / 1_000_000.0;
            double max = sorted[sorted.length - 1] / 1_000_000.0;

            double median;
            int mid = sorted.length / 2;
            if (sorted.length % 2 == 0) {
                median = (sorted[mid - 1] + sorted[mid]) / 2.0 / 1_000_000.0;
            }
            else {
                median = sorted[mid] / 1_000_000.0;
            }

            long sum = 0;
            for (var t : sorted) {
                sum += t;
            }
            double mean = (sum / (double) sorted.length) / 1_000_000.0;

            return new OpResult(aOperation, min, median, max, mean, aResultSize, null);
        }
    }

    /**
     * Describes one knowledge base to benchmark plus the identifiers / search terms used to drive
     * the individual operations.
     */
    private static final class BenchmarkConfiguration
    {
        private final String profileKey;
        private final String url;
        private final KnowledgeBase kb;

        private String parentForChildren;
        private String leafForAncestors;
        private String labelPrefix;

        private BenchmarkConfiguration(String aProfileKey, String aUrl, KnowledgeBase aKb)
        {
            profileKey = aProfileKey;
            url = aUrl;
            kb = aKb;
        }

        static BenchmarkConfiguration fromProfile(String aProfileKey,
                Map<String, KnowledgeBaseProfile> aProfiles)
        {
            var aProfile = aProfiles.get(aProfileKey);
            if (aProfile == null) {
                throw new IllegalArgumentException(
                        "No bundled knowledge base profile with key [" + aProfileKey + "]");
            }

            var kb = new KnowledgeBase();
            kb.setName(aProfile.getName());
            kb.setType(aProfile.getType());
            kb.setReification(aProfile.getReification());
            kb.setFullTextSearchIri(aProfile.getAccess().getFullTextSearchIri());
            kb.applyMapping(aProfile.getMapping());
            kb.applyRootConcepts(aProfile);
            kb.setDefaultLanguage(aProfile.getDefaultLanguage());
            kb.setMaxResults(MAX_RESULTS);
            if (aProfile.getDefaultDataset() != null) {
                kb.setDefaultDatasetIri(aProfile.getDefaultDataset());
            }
            if (aProfile.getAdditionalDatasets() != null) {
                kb.setAdditionalDatasetIris(aProfile.getAdditionalDatasets());
            }

            var accessUrl = aProfile.getAccess().getAccessUrl();
            if (aProfile.getType() == LOCAL) {
                accessUrl = accessUrl.replaceFirst("^classpath:", "");
            }

            return new BenchmarkConfiguration(aProfileKey, accessUrl, kb);
        }

        BenchmarkConfiguration parentForChildren(String aIri)
        {
            parentForChildren = aIri;
            return this;
        }

        BenchmarkConfiguration leafForAncestors(String aIri)
        {
            leafForAncestors = aIri;
            return this;
        }

        BenchmarkConfiguration labelPrefix(String aTerm)
        {
            labelPrefix = aTerm;
            return this;
        }

        @Override
        public String toString()
        {
            return kb.getName();
        }
    }

    @SpringBootConfiguration
    @EnableAutoConfiguration
    @EntityScan(basePackages = { //
            "de.tudarmstadt.ukp.inception.kb.model", //
            "de.tudarmstadt.ukp.clarin.webanno.model" })
    public static class SpringConfig
    {
        // No content
    }
}
