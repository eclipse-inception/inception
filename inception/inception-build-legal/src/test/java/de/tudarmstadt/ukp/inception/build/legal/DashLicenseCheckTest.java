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

import static de.tudarmstadt.ukp.inception.build.legal.DashLicenseCheck.INPUT_FILE;
import static de.tudarmstadt.ukp.inception.build.legal.DashLicenseCheck.REPORT_FILE;
import static de.tudarmstadt.ukp.inception.build.legal.DashLicenseCheck.containedLicense;
import static de.tudarmstadt.ukp.inception.build.legal.DashLicenseCheck.sameLicense;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.readString;
import static java.nio.file.Files.writeString;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.PrintWriter;
import java.io.Writer;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import picocli.CommandLine;

class DashLicenseCheckTest
{
    private static final String DEPENDENCIES = """
            maven/mavencentral/org.example/approved/1.0, Apache-2.0, pom, pkg:maven/org.example/approved@1.0
            maven/mavencentral/org.example/dual/2.0, EPL-2.0 OR Apache-2.0, concluded, pkg:maven/org.example/dual@2.0
            npm/npmjs/@example/widget/3.0, MIT, npm, pkg:npm/%40example/widget@3.0
            pkg:generic/openjdk@25.0.5, GPL-2.0-only WITH Classpath-exception-2.0, runtime, pkg:generic/openjdk@25.0.5
            """;

    private static final String APPROVED_SUMMARY = """
            maven/mavencentral/org.example/approved/1.0, Apache-2.0, approved, clearlydefined
            maven/mavencentral/org.example/dual/2.0, Apache-2.0 OR EPL-2.0, approved, #1234
            npm/npmjs/@example/widget/3.0, MIT, approved, clearlydefined
            """;

    @TempDir
    Path temp;

    @Test
    void thatApprovedComponentsPass() throws Exception
    {
        var exitCode = run(APPROVED_SUMMARY);

        assertThat(exitCode).isZero();
        assertThat(readString(temp.resolve("out").resolve(REPORT_FILE))).isEmpty();
    }

    @Test
    void thatOnlyComponentsWithClearlyDefinedIdsAreChecked() throws Exception
    {
        run(APPROVED_SUMMARY);

        // The Java runtime has no ClearlyDefined id
        assertThat(readString(temp.resolve("out").resolve(INPUT_FILE))).isEqualTo("""
                maven/mavencentral/org.example/approved/1.0
                maven/mavencentral/org.example/dual/2.0
                npm/npmjs/@example/widget/3.0
                """);
    }

    @Test
    void thatRestrictedMismatchingAndMissingComponentsFail() throws Exception
    {
        var exitCode = run("""
                maven/mavencentral/org.example/approved/1.0, BSD-3-Clause, approved, clearlydefined
                maven/mavencentral/org.example/dual/2.0, unknown, restricted, none
                """);

        assertThat(exitCode).isNotZero();
        assertThat(readString(temp.resolve("out").resolve(REPORT_FILE)).lines()) //
                .containsExactly( //
                        "maven/mavencentral/org.example/approved/1.0: license differs (Dash: "
                                + "BSD-3-Clause, authority clearlydefined; ours: Apache-2.0) "
                                + "- fix KnownLicenses or conclude the license in "
                                + "DEPENDENCIES-CONCLUDED.spdx.json",
                        "maven/mavencentral/org.example/dual/2.0: restricted (Dash: unknown, "
                                + "authority none; ours: EPL-2.0 OR Apache-2.0)",
                        "npm/npmjs/@example/widget/3.0: not in the Dash summary");
    }

    @Test
    void thatLicensesOfBundledContentAreNotedButPass() throws Exception
    {
        var exitCode = run(
                """
                        maven/mavencentral/org.example/approved/1.0, Apache-2.0 AND (Apache-2.0 AND CC0-1.0), approved, #1
                        maven/mavencentral/org.example/dual/2.0, (Apache-2.0 OR EPL-2.0) AND MIT, approved, #2
                        npm/npmjs/@example/widget/3.0, MIT, approved, clearlydefined
                        """);

        assertThat(exitCode).isZero();
        assertThat(readString(temp.resolve("out").resolve(REPORT_FILE)).lines()) //
                .containsExactly( //
                        "",
                        "# Not a problem: Dash describes the whole content, ours is the "
                                + "declared license",
                        "",
                        "maven/mavencentral/org.example/approved/1.0: Dash adds licenses of "
                                + "bundled content (Dash: Apache-2.0 AND (Apache-2.0 AND CC0-1.0), "
                                + "authority #1; ours: Apache-2.0)",
                        "maven/mavencentral/org.example/dual/2.0: Dash adds licenses of bundled "
                                + "content (Dash: (Apache-2.0 OR EPL-2.0) AND MIT, authority #2; "
                                + "ours: EPL-2.0 OR Apache-2.0)");
    }

    @Test
    void thatAChoiceIsNotContainedInACombination()
    {
        assertThat(containedLicense("Apache-2.0", "Apache-2.0 AND CC0-1.0")).isTrue();
        assertThat(containedLicense("Apache-2.0 AND MIT", "MIT AND CC0-1.0 AND Apache-2.0"))
                .isTrue();
        assertThat(containedLicense("LGPL-2.1-or-later OR Apache-2.0",
                "Apache-2.0 AND LGPL-2.1-or-later")).isFalse();
        assertThat(containedLicense("BSD-3-Clause", "Apache-2.0 AND MIT")).isFalse();
        assertThat(containedLicense("Apache-2.0", "Apache-2.0 OR MIT")).isFalse();
    }

    @Test
    void thatLicenseComparisonIgnoresOperandOrderAndSpacing()
    {
        assertThat(sameLicense("MIT", "MIT")).isTrue();
        assertThat(sameLicense("MIT OR Apache-2.0", "Apache-2.0  OR MIT")).isTrue();
        assertThat(sameLicense("MIT AND BSD-3-Clause", "BSD-3-Clause AND MIT")).isTrue();
        assertThat(sameLicense("MIT OR Apache-2.0", "MIT AND Apache-2.0")).isFalse();
        assertThat(sameLicense("MIT", "MIT OR Apache-2.0")).isFalse();
        assertThat(sameLicense("(MIT OR Apache-2.0) AND BSD-3-Clause",
                "BSD-3-Clause AND (Apache-2.0 OR MIT)")).isTrue();
        // AND binds tighter than OR
        assertThat(sameLicense("(MIT OR Apache-2.0) AND BSD-3-Clause",
                "MIT OR Apache-2.0 AND BSD-3-Clause")).isFalse();
        assertThat(sameLicense("GPL-2.0-only WITH Classpath-exception-2.0",
                "GPL-2.0-only WITH Classpath-exception-2.0")).isTrue();
    }

    @Test
    void thatLicenseComparisonIgnoresNotationSeenInDashSummaries()
    {
        // Lower-case operator, no parentheses around WITH (which binds tighter anyway)
        assertThat(sameLicense("EPL-2.0 OR (GPL-2.0-only WITH Classpath-exception-2.0)",
                "EPL-2.0 OR GPL-2.0-only with Classpath-exception-2.0")).isTrue();
        // Case of an exception id
        assertThat(sameLicense("GPL-2.0-only WITH Classpath-exception-2.0",
                "GPL-2.0-only WITH Classpath-Exception-2.0")).isTrue();
        // Redundant nesting and repetition
        assertThat(sameLicense("Apache-2.0 AND MIT", "Apache-2.0 AND (Apache-2.0 AND MIT)"))
                .isTrue();
        assertThat(sameLicense("Apache-2.0", "(Apache-2.0)")).isTrue();
        // Deprecated GNU ids
        assertThat(sameLicense("CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0",
                "CDDL-1.1 OR GPL-2.0 WITH Classpath-exception-2.0")).isTrue();
        // A different combination stays different
        assertThat(
                sameLicense("LGPL-2.1-or-later OR Apache-2.0", "Apache-2.0 AND LGPL-2.1-or-later"))
                        .isFalse();
        // Text that does not parse is compared as text
        assertThat(sameLicense("unknown", "unknown")).isTrue();
        assertThat(sameLicense("MIT", "unknown")).isFalse();
    }

    private int run(String aSummary) throws Exception
    {
        var dependencies = temp.resolve("DEPENDENCIES");
        writeString(dependencies, DEPENDENCIES, UTF_8);
        var summary = temp.resolve("summary");
        writeString(summary, aSummary, UTF_8);

        var commandLine = new CommandLine(new DashLicenseCheck());
        commandLine.setErr(new PrintWriter(Writer.nullWriter()));
        return commandLine.execute( //
                "--dependencies", dependencies.toString(), //
                "--summary", summary.toString(), //
                "--output", temp.resolve("out").toString());
    }
}
