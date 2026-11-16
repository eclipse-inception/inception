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

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.zip.ZipEntry.STORED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayOutputStream;
import java.security.MessageDigest;
import java.time.Instant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipFile;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.Conclusion;
import de.tudarmstadt.ukp.inception.support.json.JSONUtil;
import picocli.CommandLine;
import tools.jackson.databind.JsonNode;

class LegalDocumentationGeneratorTest
{
    private static final String OWN_GROUP_ID = "org.example.app";

    private static final List<Conclusion> SPRING_BOOT_CONCLUSIONS = List.of( //
            new Conclusion("pkg:maven/org.springframework.boot/spring-boot-loader", "Apache-2.0"),
            new Conclusion("pkg:maven/org.springframework.boot/spring-boot-jarmode-tools",
                    "Apache-2.0"));

    @TempDir
    Path tempDir;

    private Path dependencies;
    private Path license;
    private Path output;
    private Path artifact;
    private Map<String, byte[]> artifactEntries;

    @BeforeEach
    void setup() throws Exception
    {
        dependencies = tempDir.resolve("DEPENDENCIES.json");
        Files.writeString(dependencies,
                """
                        { "dependencies": [
                          { "name": "App module", "groupId": "org.example.app",
                            "artifactId": "app-module", "version": "1.0", "licenses": [] },
                          { "name": "Library A", "groupId": "org.example", "artifactId": "library-a",
                            "version": "1.0",
                            "licenses": [ { "name": "The MIT License", "url": "https://opensource.org/licenses/MIT" } ] },
                          { "name": "Library B", "groupId": "org.example", "artifactId": "library-b",
                            "version": "2.0",
                            "licenses": [ { "name": "Apache-2.0" }, { "name": "EPL 2.0" } ] }
                        ] }
                        """);
        license = tempDir.resolve("LICENSE.txt");
        Files.writeString(license, "Project license");
        output = tempDir.resolve("legal");
        artifact = tempDir.resolve("app-standalone.jar");

        artifactEntries = new LinkedHashMap<>();
        artifactEntries.put("WEB-INF/lib/library-a-1.0.jar", jar(Map.of()));
        artifactEntries.put("WEB-INF/lib/library-b-2.0-classifier.jar", jar(Map.of()));
        artifactEntries.put("WEB-INF/lib/app-module-1.0.jar", jar(Map.of( //
                "META-INF/NPM-DEPENDENCIES.json", """
                        { "lib-c@3.0.0": { "licenses": "(MIT OR Apache-2.0)",
                                           "noticeText": "Lib C\\nCopyright The Lib C Authors" },
                          "@example/lib-d@1.2.3": { "licenses": "ISC" },
                          "@app/own-package@1.0.0": { "licenses": "Apache-2.0" } }
                        """, //
                "META-INF/DEPENDENCIES-EMBEDDED.spdx.json",
                """
                        { "packages": [ { "name": "Icon set", "versionInfo": "6.0",
                          "originator": "Organization: Example Inc.", "homepage": "https://example.org",
                          "licenseDeclared": "CC-BY-4.0",
                          "hasFiles": [ "org/example/Icon.svg" ] } ] }
                        """)));
        artifactEntries.put("WEB-INF/lib-provided/provided-x-1.5.jar", jar(Map.of( //
                "META-INF/maven/org.example/provided-x/pom.properties", """
                        groupId=org.example
                        artifactId=provided-x
                        version=1.5
                        """, //
                "META-INF/maven/org.example/provided-x/pom.xml", """
                        <project><licenses><license>
                          <name>The MIT License</name><url>https://opensource.org/licenses/MIT</url>
                        </license></licenses></project>
                        """)));
        artifactEntries.put("WEB-INF/lib/spring-boot-jarmode-tools-9.9.9.jar", jar(Map.of()));
        artifactEntries.put("org/springframework/boot/loader/Launcher.class", new byte[] { 1 });
    }

    @Test
    void thatShippedThirdPartyComponentsAreListed() throws Exception
    {
        generate(SPRING_BOOT_CONCLUSIONS);

        assertThat(output.resolve("LICENSE")).hasContent("Project license");
        assertThat(Files.readAllLines(output.resolve("DEPENDENCIES"), UTF_8)).containsExactly( //
                "maven/mavencentral/org.example/library-a/1.0, MIT, pom, "
                        + "pkg:maven/org.example/library-a@1.0",
                "maven/mavencentral/org.example/library-b/2.0, Apache-2.0 OR EPL-2.0, pom, "
                        + "pkg:maven/org.example/library-b@2.0?classifier=classifier",
                "maven/mavencentral/org.example/provided-x/1.5, MIT, pom, "
                        + "pkg:maven/org.example/provided-x@1.5",
                "maven/mavencentral/org.springframework.boot/spring-boot-jarmode-tools/9.9.9, "
                        + "Apache-2.0, concluded, "
                        + "pkg:maven/org.springframework.boot/spring-boot-jarmode-tools@9.9.9",
                "maven/mavencentral/org.springframework.boot/spring-boot-loader/9.9.9, "
                        + "Apache-2.0, concluded, "
                        + "pkg:maven/org.springframework.boot/spring-boot-loader@9.9.9",
                "npm/npmjs/-/lib-c/3.0.0, MIT OR Apache-2.0, npm, pkg:npm/lib-c@3.0.0",
                "npm/npmjs/@example/lib-d/1.2.3, ISC, npm, pkg:npm/%40example/lib-d@1.2.3");
    }

    @Test
    void thatEmbeddedContentGoesIntoTheNotice() throws Exception
    {
        generate(SPRING_BOOT_CONCLUSIONS);

        assertThat(output.resolve("NOTICE.md")) //
                .content() //
                .contains("#### Icon set 6.0") //
                .contains("* License: CC-BY-4.0") //
                .contains("* Files: org/example/Icon.svg") //
                .contains("* Originator: Example Inc.\n") //
                .contains("* npm:lib-c:3.0.0\n\n```text\nLib C\nCopyright The Lib C Authors\n```") //
                .doesNotContain("${") //
                .doesNotContain("## Cryptography");
    }

    @Test
    void thatEachLibraryNoticeIsListedOnceWithItsLibraries() throws Exception
    {
        artifactEntries.put("WEB-INF/lib/library-a-1.0.jar", jar(Map.of( //
                "META-INF/NOTICE", "Shared notice\n  of a project\n")));
        artifactEntries.put("WEB-INF/lib/library-b-2.0-classifier.jar", jar(Map.of( //
                "notice.txt", "Shared notice of a project")));
        artifactEntries.put("WEB-INF/lib-provided/provided-x-1.5.jar", jar(Map.of( //
                "META-INF/maven/org.example/provided-x/pom.properties", """
                        groupId=org.example
                        artifactId=provided-x
                        version=1.5
                        """, //
                "META-INF/maven/org.example/provided-x/pom.xml", """
                        <project><licenses><license><name>MIT</name></license></licenses></project>
                        """, //
                "META-INF/NOTICE.md", "# Provided X\n\nUse ```code```.")));
        artifactEntries.put("WEB-INF/lib/app-other-1.0.jar", jar(Map.of( //
                "META-INF/NOTICE.txt", "Own notice")));
        listOwnModule("app-other");

        generate(SPRING_BOOT_CONCLUSIONS);

        var notice = Files.readString(output.resolve("NOTICE.md"));
        assertThat(notice) //
                .contains("""
                        * org.example:library-a:1.0
                        * org.example:library-b:2.0

                        ```text
                        Shared notice
                          of a project
                        ```""") //
                .contains("""
                        * org.example:provided-x:1.5

                        ````text
                        # Provided X

                        Use ```code```.
                        ````""") //
                .doesNotContain("Own notice") //
                .doesNotContain("${");
        assertThat(notice.split("Shared notice", -1)).hasSize(2);
    }

    @Test
    void thatNoticesThatAreNotUtf8AreReadAsLatin1()
    {
        assertThat(LibraryNotices.decode("Universität".getBytes(ISO_8859_1))) //
                .isEqualTo("Universität");
        assertThat(LibraryNotices.decode("Universität".getBytes(UTF_8))) //
                .isEqualTo("Universität");
    }

    @Test
    void thatTheTextsOfTheLicensesInUseAreWritten() throws Exception
    {
        generate(SPRING_BOOT_CONCLUSIONS);

        try (var files = Files.list(output.resolve("LICENSES"))) {
            assertThat(files.map(f -> f.getFileName().toString())) //
                    .containsExactlyInAnyOrder("Apache-2.0.txt", "CC-BY-4.0.txt", "EPL-2.0.txt",
                            "ISC.txt", "MIT.txt");
        }
        assertThat(output.resolve("LICENSES/MIT.txt")).content().startsWith("MIT License");
    }

    @Test
    void thatIncorporatedLicensesAndExceptionsShipTheirTexts() throws Exception
    {
        var conclusions = new ArrayList<>(SPRING_BOOT_CONCLUSIONS);
        conclusions.add(new Conclusion("pkg:maven/org.example/library-a", "LGPL-3.0-only"));
        conclusions.add(new Conclusion("pkg:maven/org.example/library-b",
                "(GPL-2.0-only WITH Classpath-exception-2.0)"));

        generate(conclusions);

        assertThat(output.resolve("LICENSES")) //
                .isDirectoryContaining("glob:**/LGPL-3.0-only.txt") //
                .isDirectoryContaining("glob:**/GPL-3.0-only.txt") //
                .isDirectoryContaining("glob:**/GPL-2.0-only.txt") //
                .isDirectoryContaining("glob:**/Classpath-exception-2.0.txt");
    }

    @Test
    void thatLicensesWithoutTextAreReported() throws Exception
    {
        var conclusions = new ArrayList<>(SPRING_BOOT_CONCLUSIONS);
        conclusions.add(new Conclusion("pkg:maven/org.example/library-a", "Zlib"));

        assertThatThrownBy(() -> generate(conclusions)) //
                .hasMessageContaining("No text for license [Zlib]");
    }

    @Test
    void thatTheSbomListsTheDistributionAndItsComponents() throws Exception
    {
        generate(SPRING_BOOT_CONCLUSIONS);

        var sbom = JSONUtil.getObjectMapper().readTree(output.resolve("sbom.spdx.json").toFile());
        assertThat(sbom.get("spdxVersion").asString()).isEqualTo("SPDX-2.3");
        assertThat(sbom.get("creationInfo").get("created").asString())
                .isEqualTo("2026-01-02T03:04:05Z");

        var packages = new HashMap<String, JsonNode>();
        sbom.get("packages").forEach(p -> packages.put(p.get("name").asString(), p));
        assertThat(packages).containsOnlyKeys("App", "org.example:library-a",
                "org.example:library-b", "org.example:provided-x",
                "org.springframework.boot:spring-boot-jarmode-tools",
                "org.springframework.boot:spring-boot-loader", "lib-c", "@example/lib-d",
                "Icon set");

        // The project license is given by its name in the POM
        assertThat(packages.get("App").get("licenseDeclared").asString()).isEqualTo("Apache-2.0");

        var libraryA = packages.get("org.example:library-a");
        assertThat(libraryA.get("packageFileName").asString())
                .isEqualTo("WEB-INF/lib/library-a-1.0.jar");
        assertThat(libraryA.get("checksums").get(0).get("checksumValue").asString())
                .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(artifactEntries.get("WEB-INF/lib/library-a-1.0.jar"))));
        assertThat(libraryA.get("externalRefs").get(0).get("referenceLocator").asString())
                .isEqualTo("pkg:maven/org.example/library-a@1.0");
        assertThat(packages.get("org.springframework.boot:spring-boot-loader")
                .get("licenseDeclared").asString()).isEqualTo("NOASSERTION");
        assertThat(packages.get("Icon set").get("comment").asString())
                .isEqualTo("Embedded in: org/example/Icon.svg");

        // The distribution describes and contains everything else
        assertThat(sbom.get("relationships")).hasSize(packages.size());
    }

    @Test
    void thatLicenseRefsAreDefinedInTheSbom() throws Exception
    {
        artifactEntries.put("WEB-INF/lib/app-other-1.0.jar", jar(Map.of( //
                "META-INF/DEPENDENCIES-EMBEDDED.spdx.json", """
                        { "packages": [
                          { "name": "Data set", "licenseDeclared": "LicenseRef-Public-Domain" } ] }
                        """)));
        listOwnModule("app-other");

        generate(SPRING_BOOT_CONCLUSIONS);

        var sbom = JSONUtil.getObjectMapper().readTree(output.resolve("sbom.spdx.json").toFile());
        assertThat(sbom.get("hasExtractedLicensingInfos").get(0).get("licenseId").asString())
                .isEqualTo("LicenseRef-Public-Domain");
    }

    @Test
    void thatOriginatorsWithoutTypeAreReported() throws Exception
    {
        artifactEntries.put("WEB-INF/lib/app-other-1.0.jar", jar(Map.of( //
                "META-INF/DEPENDENCIES-EMBEDDED.spdx.json", """
                        { "packages": [ { "name": "Data set", "originator": "Example Inc.",
                          "licenseDeclared": "MIT" } ] }
                        """)));
        listOwnModule("app-other");

        assertThatThrownBy(() -> generate(SPRING_BOOT_CONCLUSIONS)) //
                .hasMessageContaining("[Data set]") //
                .hasMessageContaining("Example Inc.");
    }

    @Test
    void thatTheSbomIsCreatedAtThePinnedOutputTimestamp()
    {
        var now = Instant.parse("2030-01-01T00:00:00Z");
        assertThat(SpdxSbom.creationTime("2026-01-02T03:04:05Z", now))
                .isEqualTo("2026-01-02T03:04:05Z");
        assertThat(SpdxSbom.creationTime("1767323045", now)).isEqualTo("2026-01-02T03:04:05Z");
        // Maven's placeholder when the property is not set, and an unresolved property
        assertThat(SpdxSbom.creationTime("1980-02-01T00:00:00Z", now)).isEqualTo(now);
        assertThat(SpdxSbom.creationTime("${project.build.outputTimestamp}", now)).isEqualTo(now);
    }

    @Test
    void thatAnApplicationImageIsAnalysedWithItsJavaRuntime() throws Exception
    {
        var image = applicationImage("""
                The GNU General Public License (GPL)

                Version 2, June 1991
                ...
                "CLASSPATH" EXCEPTION TO THE GPL
                """);

        generate(image, List.of(), "--distribution-name", "App-1.0-x86_64.dmg");

        assertThat(Files.readAllLines(output.resolve("DEPENDENCIES"), UTF_8)) //
                .contains("maven/mavencentral/org.example/library-a/1.0, MIT, pom, "
                        + "pkg:maven/org.example/library-a@1.0") //
                .contains("pkg:generic/openjdk@25.0.5, GPL-2.0-only WITH "
                        + "Classpath-exception-2.0, runtime, pkg:generic/openjdk@25.0.5") //
                .noneMatch(line -> line.contains("jrt-fs"));
        assertThat(output.resolve("NOTICE.md")).content() //
                .contains("The distribution includes the Java runtime Temurin-25.0.5+8 by "
                        + "Eclipse Adoptium") //
                .contains("in the folder runtime/legal of the application") //
                .contains("## Cryptography");

        var sbom = JSONUtil.getObjectMapper().readTree(output.resolve("sbom.spdx.json").toFile());
        assertThat(sbom.get("documentNamespace").asString())
                .isEqualTo("https://app.example.org/spdx/App-1.0-x86_64.dmg");
        var runtime = new HashMap<String, JsonNode>();
        sbom.get("packages").forEach(p -> runtime.put(p.get("name").asString(), p));
        assertThat(runtime.get("openjdk").get("licenseConcluded").asString())
                .isEqualTo("NOASSERTION");
        assertThat(runtime.get("openjdk").get("licenseDeclared").asString())
                .isEqualTo("GPL-2.0-only WITH Classpath-exception-2.0");
        assertThat(output.resolve("LICENSES")) //
                .isDirectoryContaining("glob:**/GPL-2.0-only.txt") //
                .isDirectoryContaining("glob:**/Classpath-exception-2.0.txt");
    }

    @Test
    void thatAJavaRuntimeUnderAnotherLicenseIsReported() throws Exception
    {
        var image = applicationImage("Oracle No-Fee Terms and Conditions (NFTC)");

        assertThatThrownBy(() -> generate(image, List.of())) //
                .hasMessageContaining("Java runtime in runtime") //
                .hasMessageContaining("is not licensed under");
    }

    @Test
    void thatAJavaRuntimeWithoutVersionIsReported() throws Exception
    {
        var image = applicationImage("GPL");
        Files.writeString(image.resolve("runtime/release"), "IMPLEMENTOR=\"Example\"\n");

        assertThatThrownBy(() -> generate(image, List.of())) //
                .hasMessageContaining("has no JAVA_VERSION");
    }

    @Test
    void thatEmbeddedContentDeclaredByTwoModulesIsListedOnce() throws Exception
    {
        artifactEntries.put("WEB-INF/lib/app-other-1.0.jar", jar(Map.of( //
                "META-INF/DEPENDENCIES-EMBEDDED.spdx.json", """
                        { "packages": [ { "name": "Icon set", "versionInfo": "6.0",
                          "originator": "Organization: Example Inc.",
                          "licenseDeclared": "CC-BY-4.0",
                          "hasFiles": [ "org/example/Other.svg" ] } ] }
                        """)));
        listOwnModule("app-other");

        generate(SPRING_BOOT_CONCLUSIONS);

        var notice = Files.readString(output.resolve("NOTICE.md"));
        assertThat(notice.split("#### Icon set", -1)).hasSize(2);
        assertThat(notice).contains("* Files: org/example/Icon.svg, org/example/Other.svg");
    }

    @Test
    void thatAMissingArtifactCanBeSkipped() throws Exception
    {
        generate(tempDir.resolve("not-built"), List.of(), "--skip-if-missing");

        assertThat(output).doesNotExist();
    }

    /**
     * Writes a folder like a jpackage application image: the application's jars and a Java runtime
     * with its release file, its module image, its own jar and its license.
     */
    private Path applicationImage(String aRuntimeLicense) throws Exception
    {
        var image = tempDir.resolve("image");
        var app = Files.createDirectories(image.resolve("app"));
        Files.write(app.resolve("app-module-1.0.jar"), jar(Map.of( //
                "META-INF/version.properties", "version=1.0", //
                "META-INF/DEPENDENCIES.json", Files.readString(dependencies))));
        Files.write(app.resolve("library-a-1.0.jar"), jar(Map.of()));

        var runtime = Files.createDirectories(image.resolve("runtime"));
        Files.writeString(runtime.resolve("release"), """
                IMPLEMENTOR="Eclipse Adoptium"
                IMPLEMENTOR_VERSION="Temurin-25.0.5+8"
                JAVA_VERSION="25.0.5"
                """);
        Files.createDirectories(runtime.resolve("lib"));
        Files.write(runtime.resolve("lib/modules"), new byte[] { 0 });
        Files.write(runtime.resolve("lib/jrt-fs.jar"), jar(Map.of()));
        Files.createDirectories(runtime.resolve("legal/java.base"));
        Files.writeString(runtime.resolve("legal/java.base/LICENSE"), aRuntimeLicense);
        return image;
    }

    @Test
    void thatTheFilesArePatchedIntoTheArtifact() throws Exception
    {
        var sbomCopy = tempDir.resolve("app-standalone.spdx.json");
        generate(SPRING_BOOT_CONCLUSIONS, "--patch", "--sbom-copy", sbomCopy.toString());

        assertThat(sbomCopy).hasSameBinaryContentAs(output.resolve("sbom.spdx.json"));

        try (var zip = new ZipFile(artifact.toFile())) {
            var manifestTime = zip.getEntry("META-INF/MANIFEST.MF").getTime();
            for (var file : List.of("LICENSE", "NOTICE.md", "DEPENDENCIES", "LICENSES/MIT.txt",
                    "sbom.spdx.json")) {
                var entry = zip.getEntry("META-INF/" + file);
                assertThat(entry).as(file).isNotNull();
                assertThat(entry.getTime()).as(file).isEqualTo(manifestTime);
            }
            // A folder created by the patch must not carry the build time
            assertThat(zip.getEntry("META-INF/LICENSES/").getTime()).isEqualTo(manifestTime);
            // The Spring Boot loader requires nested jars to stay stored uncompressed
            assertThat(zip.getEntry("WEB-INF/lib/library-a-1.0.jar").getMethod()) //
                    .isEqualTo(STORED);
            assertThat(new String(
                    zip.getInputStream(zip.getEntry("META-INF/DEPENDENCIES")).readAllBytes(),
                    UTF_8)).contains("pkg:maven/org.example/library-a@1.0");
        }
    }

    @Test
    void thatLibrariesWithoutAnyMetadataAreReported() throws Exception
    {
        artifactEntries.put("WEB-INF/lib/unknown-1.0.jar", jar(Map.of()));

        assertThatThrownBy(() -> generate(SPRING_BOOT_CONCLUSIONS)) //
                .hasMessageContaining("neither listed") //
                .hasMessageContaining("unknown-1.0.jar");
    }

    @Test
    void thatUnmappedLicensesNeedAConclusion() throws Exception
    {
        Files.writeString(dependencies, Files.readString(dependencies) //
                .replace("The MIT License", "Some Custom License") //
                .replace("https://opensource.org/licenses/MIT", ""));

        assertThatThrownBy(() -> generate(SPRING_BOOT_CONCLUSIONS)) //
                .hasMessageContaining("pkg:maven/org.example/library-a") //
                .hasMessageContaining("Some Custom License");

        var conclusions = new ArrayList<>(SPRING_BOOT_CONCLUSIONS);
        conclusions.add(new Conclusion("pkg:maven/org.example/library-a", "BSD-3-Clause"));
        generate(conclusions);

        assertThat(Files.readAllLines(output.resolve("DEPENDENCIES"), UTF_8))
                .contains("maven/mavencentral/org.example/library-a/1.0, BSD-3-Clause, "
                        + "concluded, pkg:maven/org.example/library-a@1.0");
    }

    @Test
    void thatUnusedConclusionsAreReported() throws Exception
    {
        var conclusions = new ArrayList<>(SPRING_BOOT_CONCLUSIONS);
        conclusions.add(new Conclusion("pkg:maven/org.example/gone", "MIT"));

        assertThatThrownBy(() -> generate(conclusions, "--fail-on-unused-conclusions")) //
                .hasMessageContaining("pkg:maven/org.example/gone") //
                .hasMessageContaining("not used");

        // Without the option, e.g. for an installer, which lacks some concluded components
        generate(conclusions);
    }

    @Test
    void thatNpmPackagesWithoutVersionAreReportedOnce() throws Exception
    {
        var npm = Map.of("META-INF/NPM-DEPENDENCIES.json", """
                { "lib-e": { "licenses": "MIT" } }
                """);
        artifactEntries.put("WEB-INF/lib/app-other-1.0.jar", jar(npm));
        artifactEntries.put("WEB-INF/lib/app-third-1.0.jar", jar(npm));
        listOwnModule("app-other");
        listOwnModule("app-third");

        assertThatThrownBy(() -> generate(SPRING_BOOT_CONCLUSIONS)) //
                .satisfies(
                        e -> assertThat(e.getMessage().split("without a version", -1)).hasSize(2));
    }

    @Test
    void thatEmbeddedContentAcceptsLicenseRefsAndNeedsAName() throws Exception
    {
        artifactEntries.put("WEB-INF/lib/app-other-1.0.jar", jar(Map.of( //
                "META-INF/DEPENDENCIES-EMBEDDED.spdx.json", """
                        { "packages": [
                          { "name": "Data set", "licenseDeclared": "LicenseRef-Public-Domain" },
                          { "licenseDeclared": "MIT" } ] }
                        """)));
        listOwnModule("app-other");

        assertThatThrownBy(() -> generate(SPRING_BOOT_CONCLUSIONS)) //
                .hasMessageContaining("Embedded content without a name") //
                .hasMessageNotContaining("Data set");
    }

    @Test
    void thatTheBundledConclusionsAreValid() throws Exception
    {
        assertThat(LegalDocumentationGenerator.loadConclusions()).isNotEmpty();
    }

    private void generate(List<Conclusion> aConclusions, String... aExtraArgs) throws Exception
    {
        writeArtifact();
        generate(artifact, aConclusions, aExtraArgs);
    }

    private void generate(Path aArtifact, List<Conclusion> aConclusions, String... aExtraArgs)
        throws Exception
    {
        var args = new ArrayList<String>(List.of( //
                "--artifact", aArtifact.toString(), //
                "--license", license.toString(), //
                "--output", output.toString(), //
                "--own-group-id", OWN_GROUP_ID, //
                "--own-npm-scope", "@app", //
                "--project-name", "App", //
                "--project-version", "1.0", //
                "--project-license", "Apache License Version 2.0", //
                "--project-url", "https://app.example.org", //
                "--output-timestamp", "2026-01-02T03:04:05Z"));
        args.addAll(List.of(aExtraArgs));

        var generator = new LegalDocumentationGenerator(aConclusions);
        new CommandLine(generator).parseArgs(args.toArray(String[]::new));
        generator.generate();
    }

    private void listOwnModule(String aArtifactId) throws Exception
    {
        var json = Files.readString(dependencies);
        var end = json.lastIndexOf(']');
        Files.writeString(dependencies,
                json.substring(0, end) + ", { \"name\": \"" + aArtifactId + "\", \"groupId\": \""
                        + OWN_GROUP_ID + "\", \"artifactId\": \"" + aArtifactId
                        + "\", \"version\": \"1.0\", \"licenses\": [] } " + json.substring(end));
    }

    /**
     * Writes a Spring Boot style executable archive: nested jars stored uncompressed, the Spring
     * Boot version in the manifest, the application's resolved dependencies in its classes.
     */
    private void writeArtifact() throws Exception
    {
        artifactEntries.put("WEB-INF/classes/META-INF/version.properties",
                "version=1.0".getBytes(UTF_8));
        artifactEntries.put("WEB-INF/classes/META-INF/DEPENDENCIES.json",
                Files.readAllBytes(dependencies));

        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().putValue("Spring-Boot-Version", "9.9.9");

        try (var jos = new JarOutputStream(Files.newOutputStream(artifact), manifest)) {
            for (var entry : artifactEntries.entrySet()) {
                var data = entry.getValue();
                var jarEntry = new JarEntry(entry.getKey());
                if (entry.getKey().endsWith(".jar")) {
                    var crc = new CRC32();
                    crc.update(data);
                    jarEntry.setMethod(STORED);
                    jarEntry.setSize(data.length);
                    jarEntry.setCompressedSize(data.length);
                    jarEntry.setCrc(crc.getValue());
                }
                jos.putNextEntry(jarEntry);
                jos.write(data);
                jos.closeEntry();
            }
        }
    }

    private static byte[] jar(Map<String, String> aEntries) throws Exception
    {
        var manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");

        var bytes = new ByteArrayOutputStream();
        try (var jos = new JarOutputStream(bytes, manifest)) {
            for (var entry : aEntries.entrySet()) {
                jos.putNextEntry(new JarEntry(entry.getKey()));
                jos.write(entry.getValue().getBytes(UTF_8));
                jos.closeEntry();
            }
        }
        return bytes.toByteArray();
    }
}
