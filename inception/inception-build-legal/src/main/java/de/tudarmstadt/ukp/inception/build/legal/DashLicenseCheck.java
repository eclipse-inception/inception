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
package de.tudarmstadt.ukp.inception.build.legal;

import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.CONCLUSIONS_RESOURCE;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.readAllLines;
import static java.nio.file.Files.size;
import static java.nio.file.Files.writeString;
import static java.util.Locale.ROOT;
import static java.util.regex.Pattern.compile;
import static java.util.stream.Collectors.joining;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Checks the third-party components listed in a {@code DEPENDENCIES} file written by
 * {@link LegalDocumentationGenerator} with the Eclipse Dash License Tool, i.e. against the Eclipse
 * Foundation's IP database and ClearlyDefined. Fails if Dash does not approve a component, if it
 * reports another license than ours, or if it does not report a component at all.
 * <p>
 * Dash needs network access to the Eclipse API and ClearlyDefined, so this is not part of the
 * normal build. It runs the self-contained Dash command-line jar in a separate process, so that
 * Dash and its dependencies stay off the classpath of this module.
 */
@Command( //
        name = "dash-license-check", //
        mixinStandardHelpOptions = true, //
        description = "Checks the components in a DEPENDENCIES file with the Eclipse Dash "
                + "License Tool.")
public class DashLicenseCheck
    implements Callable<Integer>
{
    static final String INPUT_FILE = "dash-input.txt";
    static final String SUMMARY_FILE = "summary";
    static final String REPORT_FILE = "report.txt";

    static final String APPROVED = "approved";

    /** The form of the ids Dash reads from a flat file and writes into its summary. */
    private static final Pattern CLEARLY_DEFINED_ID = compile(
            "[^/\\s]+/[^/\\s]+/[^/\\s]+/[^/\\s]+/[^/\\s]+");

    @Option(names = "--dependencies", required = true, //
            description = "The DEPENDENCIES file to check")
    private Path dependencies;

    @Option(names = "--dash-jar", //
            description = "The Dash License Tool command-line jar; required unless --summary is "
                    + "given")
    private Path dashJar;

    @Option(names = "--summary", //
            description = "Check an existing Dash summary instead of running Dash")
    private Path summary;

    @Option(names = "--output", required = true, //
            description = "Directory for the Dash input, its summary and the report")
    private Path output;

    /**
     * Dash's default of 500 ids per request made the Eclipse API answer with 504 and ClearlyDefined
     * time out (2026-11-21, 540 components).
     */
    @Option(names = "--batch", defaultValue = "50", //
            description = "Number of ids Dash sends per request")
    private int batch;

    @Option(names = "--timeout", defaultValue = "120", //
            description = "Timeout of Dash's requests, in seconds")
    private int timeout;

    public static void main(String[] aArgs)
    {
        var commandLine = new CommandLine(new DashLicenseCheck());
        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            cmd.getErr().println(ex.getMessage());
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        });

        // Runs inside the Maven JVM through exec:java, so fail with an exception instead of
        // calling System.exit()
        var exitCode = commandLine.execute(aArgs);
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Checking the licenses with Dash failed (exit code " + exitCode + ")");
        }
    }

    @Override
    public Integer call() throws IOException, InterruptedException
    {
        createDirectories(output);

        var components = readDependencies(dependencies);
        var input = output.resolve(INPUT_FILE);
        writeString(input, components.keySet().stream() //
                .map(id -> id + "\n") //
                .collect(joining()), UTF_8);

        var summaryFile = summary;
        if (summaryFile == null) {
            if (dashJar == null) {
                throw new IllegalArgumentException("Either --dash-jar or --summary is required");
            }
            summaryFile = output.resolve(SUMMARY_FILE);
            runDash(input, summaryFile);
        }

        var result = check(components, readSummary(summaryFile));
        var report = output.resolve(REPORT_FILE);
        writeString(report, result.report(), UTF_8);
        if (!result.problems().isEmpty()) {
            throw new IllegalStateException("Dash does not confirm " + result.problems().size()
                    + " of " + components.size() + " components (report: " + report + "):\n  - "
                    + join("\n  - ", result.problems()));
        }

        System.out.println("Dash approves the licenses of all " + components.size()
                + " components in " + dependencies + "; " + result.notes().size()
                + " with licenses of bundled content, see " + report);
        return 0;
    }

    private void runDash(Path aInput, Path aSummary) throws IOException, InterruptedException
    {
        deleteIfExists(aSummary);
        var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        var command = List.of(java, "-jar", dashJar.toString(), //
                "-batch", String.valueOf(batch), //
                "-timeout", String.valueOf(timeout), //
                "-summary", aSummary.toString(), //
                aInput.toString());
        // Dash exits with a non-zero code also when components need a review; the summary has
        // the details. After an error it may leave an empty summary.
        var exitCode = new ProcessBuilder(command).inheritIO().start().waitFor();
        if (!exists(aSummary) || size(aSummary) == 0) {
            throw new IllegalStateException("Dash reported no results (exit code " + exitCode
                    + ") - see its output above; on timeouts or errors 504 of the Eclipse API, "
                    + "try a smaller --batch or a longer --timeout");
        }
    }

    /**
     * @return the license of every component in a {@code DEPENDENCIES} file, by its ClearlyDefined
     *         id. Components without such an id (a bundled Java runtime) are left out, since Dash
     *         cannot check them.
     */
    static Map<String, String> readDependencies(Path aDependencies) throws IOException
    {
        var components = new LinkedHashMap<String, String>();
        for (var line : readAllLines(aDependencies, UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            // id, license, authority, purl
            var columns = line.split(", ");
            if (columns.length != 4) {
                throw new IllegalArgumentException(
                        "Not a line of a DEPENDENCIES file: [" + line + "]");
            }
            if (CLEARLY_DEFINED_ID.matcher(columns[0]).matches()) {
                components.put(columns[0], columns[1]);
            }
        }
        return components;
    }

    /**
     * Reads the summary Dash writes: one line per component with its id, license, status and
     * authority. The license is an SPDX expression or {@code unknown}.
     */
    static Map<String, DashResult> readSummary(Path aSummary) throws IOException
    {
        var results = new LinkedHashMap<String, DashResult>();
        for (var line : readAllLines(aSummary, UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            var first = line.indexOf(", ");
            var last = line.lastIndexOf(", ");
            var beforeLast = line.lastIndexOf(", ", last - 1);
            if (first < 0 || beforeLast <= first) {
                throw new IllegalArgumentException("Not a line of a Dash summary: [" + line + "]");
            }
            results.put(line.substring(0, first),
                    new DashResult(line.substring(first + 2, beforeLast),
                            line.substring(beforeLast + 2, last), line.substring(last + 2)));
        }
        return results;
    }

    /**
     * Compares our licenses with Dash's. Dash describes the whole content of a package, so its
     * license often adds the licenses of code bundled into it with {@code AND} (e.g.
     * {@code Apache-2.0 AND CC0-1.0} for Guava), while ours is the license the package declares.
     * Such a component is listed as a note; it is not a problem.
     */
    static CheckResult check(Map<String, String> aComponents, Map<String, DashResult> aResults)
    {
        var problems = new ArrayList<String>();
        var notes = new ArrayList<String>();
        for (var component : aComponents.entrySet()) {
            var id = component.getKey();
            var ours = component.getValue();
            var result = aResults.get(id);
            if (result == null) {
                problems.add(id + ": not in the Dash summary");
                continue;
            }
            if (!APPROVED.equals(result.status())) {
                problems.add(id + ": " + result.status() + " (Dash: " + result.license()
                        + ", authority " + result.authority() + "; ours: " + ours + ")");
                continue;
            }
            if (sameLicense(ours, result.license())) {
                continue;
            }
            var comparison = "(Dash: " + result.license() + ", authority " + result.authority()
                    + "; ours: " + ours + ")";
            if (containedLicense(ours, result.license())) {
                notes.add(id + ": Dash adds licenses of bundled content " + comparison);
            }
            else {
                problems.add(id + ": license differs " + comparison + " - fix KnownLicenses "
                        + "or conclude the license in " + CONCLUSIONS_RESOURCE);
            }
        }
        return new CheckResult(problems, notes);
    }

    /**
     * @return whether Dash's license combines ours with other licenses using {@code AND}, e.g. ours
     *         {@code Apache-2.0} and Dash's {@code Apache-2.0 AND CC0-1.0}. A choice between
     *         licenses ({@code OR}) in ours must appear as the same choice in Dash's.
     */
    static boolean containedLicense(String aOurs, String aDash)
    {
        var dashOperands = andOperands(canonical(aDash));
        return dashOperands.size() > 1 && dashOperands.containsAll(andOperands(canonical(aOurs)));
    }

    private static Set<String> andOperands(String aCanonical)
    {
        if (aCanonical.startsWith("(")) {
            var operands = ExpressionParser
                    .splitTopLevel(aCanonical.substring(1, aCanonical.length() - 1), " and ");
            if (operands.size() > 1) {
                return new TreeSet<>(operands);
            }
        }
        return Set.of(aCanonical);
    }

    record CheckResult(List<String> problems, List<String> notes) {
        String report()
        {
            var report = new StringBuilder();
            problems.forEach(p -> report.append(p).append("\n"));
            if (!notes.isEmpty()) {
                report.append("\n# Not a problem: Dash describes the whole content, ours is the ")
                        .append("declared license\n\n");
                notes.forEach(n -> report.append(n).append("\n"));
            }
            return report.toString();
        }
    }

    /**
     * Compares two SPDX license expressions by what they mean, not how they are written: ignores
     * spacing, the case of ids and operators (SPDX matches both case-insensitively), redundant
     * parentheses, the order and repetition of the operands of {@code AND} and {@code OR}, and the
     * deprecated GNU ids without {@code -only} or {@code -or-later}. An expression that does not
     * parse is compared as text.
     */
    static boolean sameLicense(String aOurs, String aDash)
    {
        return canonical(aOurs).equals(canonical(aDash));
    }

    static String canonical(String aExpression)
    {
        try {
            var parser = new ExpressionParser(aExpression);
            var result = parser.parseOr();
            if (parser.hasMore()) {
                throw new IllegalArgumentException("Unexpected input");
            }
            return result;
        }
        catch (IllegalArgumentException e) {
            return aExpression.trim().replaceAll("\\s+", " ").toLowerCase(ROOT);
        }
    }

    /**
     * Parses an SPDX license expression into a canonical form. {@code WITH} binds tighter than
     * {@code AND}, which binds tighter than {@code OR}.
     */
    private static class ExpressionParser
    {
        private static final Pattern TOKEN = compile("\\(|\\)|[^\\s()]+");

        /** Deprecated SPDX ids and the ids that replace them. */
        private static final Map<String, String> DEPRECATED = Map.of( //
                "gpl-2.0", "gpl-2.0-only", //
                "gpl-2.0+", "gpl-2.0-or-later", //
                "gpl-3.0", "gpl-3.0-only", //
                "gpl-3.0+", "gpl-3.0-or-later", //
                "lgpl-2.0", "lgpl-2.0-only", //
                "lgpl-2.1", "lgpl-2.1-only", //
                "lgpl-2.1+", "lgpl-2.1-or-later", //
                "lgpl-3.0", "lgpl-3.0-only", //
                "lgpl-3.0+", "lgpl-3.0-or-later");

        private final List<String> tokens = new ArrayList<>();
        private int position;

        ExpressionParser(String aExpression)
        {
            var matcher = TOKEN.matcher(aExpression);
            while (matcher.find()) {
                tokens.add(matcher.group());
            }
        }

        boolean hasMore()
        {
            return position < tokens.size();
        }

        String parseOr()
        {
            return combine("or", this::parseAnd);
        }

        private String parseAnd()
        {
            return combine("and", this::parseWith);
        }

        private String combine(String aOperator, Supplier<String> aOperand)
        {
            var operands = new TreeSet<String>();
            addOperand(operands, aOperator, aOperand.get());
            while (hasMore() && tokens.get(position).equalsIgnoreCase(aOperator)) {
                position++;
                addOperand(operands, aOperator, aOperand.get());
            }
            if (operands.size() == 1) {
                return operands.first();
            }
            return "(" + join(" " + aOperator + " ", operands) + ")";
        }

        /**
         * Adds an operand, flattening an operand that is itself combined with the same operator, so
         * that {@code A AND (B AND C)} is {@code A AND B AND C}.
         */
        private static void addOperand(Set<String> aOperands, String aOperator, String aOperand)
        {
            var prefix = "(";
            var separator = " " + aOperator + " ";
            if (aOperand.startsWith(prefix) && isCombinedWith(aOperand, separator)) {
                for (var part : splitTopLevel(aOperand.substring(1, aOperand.length() - 1),
                        separator)) {
                    aOperands.add(part);
                }
                return;
            }
            aOperands.add(aOperand);
        }

        private static boolean isCombinedWith(String aOperand, String aSeparator)
        {
            return splitTopLevel(aOperand.substring(1, aOperand.length() - 1), aSeparator)
                    .size() > 1;
        }

        private static List<String> splitTopLevel(String aText, String aSeparator)
        {
            var parts = new ArrayList<String>();
            var depth = 0;
            var start = 0;
            for (var i = 0; i < aText.length(); i++) {
                var c = aText.charAt(i);
                if (c == '(') {
                    depth++;
                }
                else if (c == ')') {
                    depth--;
                }
                else if (depth == 0 && aText.startsWith(aSeparator, i)) {
                    parts.add(aText.substring(start, i));
                    start = i + aSeparator.length();
                    i = start - 1;
                }
            }
            parts.add(aText.substring(start));
            return parts;
        }

        private String parseWith()
        {
            var license = parseAtom();
            if (hasMore() && tokens.get(position).equalsIgnoreCase("with")) {
                position++;
                if (!hasMore()) {
                    throw new IllegalArgumentException("Missing exception");
                }
                return license + " with " + tokens.get(position++).toLowerCase(ROOT);
            }
            return license;
        }

        private String parseAtom()
        {
            if (!hasMore()) {
                throw new IllegalArgumentException("Missing operand");
            }
            var token = tokens.get(position++);
            if (token.equals("(")) {
                var inner = parseOr();
                if (!hasMore() || !tokens.get(position++).equals(")")) {
                    throw new IllegalArgumentException("Missing closing parenthesis");
                }
                return inner;
            }
            if (token.equals(")")
                    || List.of("and", "or", "with").contains(token.toLowerCase(ROOT))) {
                throw new IllegalArgumentException("Unexpected " + token);
            }
            var id = token.toLowerCase(ROOT);
            return DEPRECATED.getOrDefault(id, id);
        }
    }

    record DashResult(String license, String status, String authority) {}
}
