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

import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.Component.maven;
import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.Component.npm;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadEmbeddedSpdxDependencies;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadMavenDependencies;
import static de.tudarmstadt.ukp.inception.support.about.ApplicationInformation.loadNpmDependencies;
import static de.tudarmstadt.ukp.inception.support.about.KnownLicenses.toSpdxId;
import static java.lang.String.CASE_INSENSITIVE_ORDER;
import static java.lang.String.join;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.nio.file.Files.copy;
import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.exists;
import static java.nio.file.Files.isDirectory;
import static java.nio.file.Files.isRegularFile;
import static java.nio.file.Files.deleteIfExists;
import static java.nio.file.Files.getLastModifiedTime;
import static java.nio.file.Files.list;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.setLastModifiedTime;
import static java.nio.file.Files.write;
import static java.util.stream.Collectors.joining;
import static java.nio.file.Files.writeString;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Comparator.comparing;
import static java.util.regex.Pattern.compile;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import java.util.jar.JarInputStream;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.xml.sax.SAXException;

import de.tudarmstadt.ukp.inception.support.about.EmbeddedSpdxDependencies;
import de.tudarmstadt.ukp.inception.support.about.MavenDependency;
import de.tudarmstadt.ukp.inception.support.about.MavenLicense;
import de.tudarmstadt.ukp.inception.support.about.SpdxExternalRef;
import de.tudarmstadt.ukp.inception.support.about.SpdxPackage;
import de.tudarmstadt.ukp.inception.support.json.JSONUtil;
import tools.jackson.core.util.DefaultIndenter;
import tools.jackson.core.util.DefaultPrettyPrinter;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Generates the legal documentation that goes into the root of a distribution artifact: the project
 * {@code LICENSE}, a {@code NOTICE.md}, a {@code DEPENDENCIES} table of all third-party components
 * with their SPDX license expressions, the texts of these licenses in {@code LICENSES/}, and an
 * SPDX 2.3 SBOM.
 * <p>
 * Analyses what the artifact actually contains, so that the files describe exactly what ships: the
 * libraries in a Spring Boot executable archive ({@code WEB-INF/lib}, {@code WEB-INF/lib-provided}
 * or {@code BOOT-INF/lib}), the Spring Boot loader classes, and the npm packages and embedded
 * third-party content that our modules declare. Licenses come from the application's resolved
 * {@code DEPENDENCIES.json}, otherwise from the POM embedded in a jar, otherwise from the licenses
 * concluded in {@value #CONCLUSIONS_RESOURCE}.
 */
@Command( //
        name = "generate-legal-documentation", //
        mixinStandardHelpOptions = true, //
        description = "Generates LICENSE, NOTICE.md, DEPENDENCIES, LICENSES/ and an SPDX SBOM "
                + "for a distribution artifact.")
public class LegalDocumentationGenerator
    implements Callable<Integer>
{
    private static final String LICENSE_FILE = "LICENSE";
    private static final String NOTICE_MD = "NOTICE.md";
    private static final String DEPENDENCIES_FILE = "DEPENDENCIES";
    private static final String LICENSES_DIR = "LICENSES";
    private static final String SBOM_FILE = "sbom.spdx.json";
    private static final String PROP_CRYPTOGRAPHY = "${cryptography}";
    private static final String PROP_EMBEDDED_CONTENT = "${embeddedContent}";
    private static final String PROP_LIBRARY_FILES = "${libraryFiles}";
    private static final String PROP_BUNDLED_CONTENT = "${bundledContent}";

    /**
     * A sign of shaded code: classes under a folder named like this, e.g.
     * {@code org/apache/kafka/shaded/com/google/protobuf/...}. The copies for other Java versions
     * in a multi-release jar ({@code META-INF/versions/<n>/}) are the same code.
     */
    private static final Pattern SHADED_CLASSES = compile("^(?:META-INF/versions/\\d+/)?"
            + "((?:[^/]+/)*?(?:shaded|repackaged|relocated|thirdparty|third_party)/)([^/]+/)?.*\\.class$");
    private static final String PROP_JAVA_RUNTIME = "${javaRuntime}";

    /**
     * The license of OpenJDK builds, checked against the {@code legal/java.base/LICENSE} of a
     * bundled runtime.
     */
    static final String JAVA_RUNTIME_LICENSE = "GPL-2.0-only WITH Classpath-exception-2.0";

    /** Resources in the package of this class. */
    static final String CONCLUSIONS_RESOURCE = "DEPENDENCIES-CONCLUDED.spdx.json";
    static final String NOTICE_TEMPLATE_RESOURCE = "NOTICE.md.template";
    /**
     * One text per SPDX license or exception id, taken unchanged from the text folder of the SPDX
     * license list data, v3.29.0 (https://github.com/spdx/license-list-data). The
     * {@code LicenseRef-*} texts are our own.
     */
    static final String LICENSE_TEXTS_RESOURCE = "licenses/";

    /**
     * Licenses whose text incorporates another license by reference, so that the other text has to
     * ship as well.
     */
    private static final Map<String, String> INCORPORATED_LICENSES = Map.of( //
            "LGPL-3.0-only", "GPL-3.0-only", //
            "LGPL-3.0-or-later", "GPL-3.0-or-later");

    private static final String NPM_DEPENDENCIES = "META-INF/NPM-DEPENDENCIES.json";
    /** Only the application has it, see {@code ApplicationInformation}. */
    private static final String APPLICATION_MARKER = "META-INF/version.properties";
    private static final String APPLICATION_DEPENDENCIES = "META-INF/DEPENDENCIES.json";
    private static final String EMBEDDED_DEPENDENCIES = "META-INF/DEPENDENCIES-EMBEDDED.spdx.json";

    /** Where Spring Boot executable archives keep the libraries and the application classes. */
    private static final List<String> LIBRARY_DIRS = List.of("WEB-INF/lib/",
            "WEB-INF/lib-provided/", "BOOT-INF/lib/");
    private static final List<String> CLASSES_DIRS = List.of("WEB-INF/classes/",
            "BOOT-INF/classes/");

    /**
     * What the Spring Boot repackaging adds to an executable archive in its own version, recorded
     * in the manifest as {@code Spring-Boot-Version}: the loader classes (copied in loose, so there
     * is no jar) and the jar mode tools (a jar without POM).
     */
    private static final String SPRING_BOOT_VERSION = "Spring-Boot-Version";
    private static final String SPRING_BOOT_GROUP_ID = "org.springframework.boot";
    private static final String SPRING_BOOT_LOADER = "spring-boot-loader";
    private static final String SPRING_BOOT_LOADER_PACKAGE = "org/springframework/boot/loader/";
    private static final List<String> SPRING_BOOT_JARS = List.of("spring-boot-jarmode-tools");

    private static final Pattern POM_PROPERTIES = compile(
            "META-INF/maven/[^/]+/[^/]+/pom\\.properties");
    private static final Pattern POM_XML = compile("META-INF/maven/[^/]+/[^/]+/pom\\.xml");

    /**
     * SPDX custom license reference, for licenses without an SPDX identifier.
     */
    private static final Pattern LICENSE_REF = compile("LicenseRef-[A-Za-z0-9.-]+");

    /**
     * Group ids of libraries that implement cryptography. Their presence requires the cryptography
     * statement in the NOTICE file.
     */
    private static final Set<String> CRYPTOGRAPHY_GROUP_IDS = Set.of("org.bouncycastle");

    private static final String CRYPTOGRAPHY_STATEMENT = //
            """
                    ## Cryptography

                    Content may contain encryption software. The country in which you are currently may
                    have restrictions on the import, possession, and use, and/or re-export to another
                    country, of encryption software. BEFORE using any encryption software, please check
                    the country's laws, regulations and policies concerning the import, possession, or
                    use, and re-export of encryption software, to see if this is permitted.
                    """;

    @Option(names = "--artifact", required = true, //
            description = "The distribution artifact to analyse: an archive such as the "
                    + "standalone jar, or a folder such as a jpackage application image")
    private Path artifact;

    @Option(names = "--skip-if-missing", //
            description = "Do nothing if the artifact does not exist, e.g. when building an "
                    + "installer for another architecture was skipped")
    private boolean skipIfMissing;

    @Option(names = "--distribution-name", //
            description = "File name of the distribution as published, e.g. the DMG for an "
                    + "analysed application image; names the SBOM and makes its namespace "
                    + "unique. Defaults to the artifact's file name")
    private String distributionName;

    @Option(names = "--sbom-copy", //
            description = "Also write the SBOM to this file, e.g. named after the artifact so "
                    + "that it can be published next to it")
    private Path sbomCopy;

    @Option(names = "--fail-on-unused-conclusions", //
            description = "Fail if a concluded license is used by no component. The concluded "
                    + "licenses are shared by all distributions, so only the distribution that "
                    + "contains all their components should check this")
    private boolean failOnUnusedConclusions;

    @Option(names = "--patch", //
            description = "Also write the files into META-INF/ of the artifact (archives only)")
    private boolean patch;

    @Option(names = "--license", required = true, description = "The project's license file")
    private Path license;

    @Option(names = "--output", required = true, description = "Directory to write the files to")
    private Path output;

    @Option(names = "--own-group-id", required = true, //
            description = "Group id of our own modules, which are not third-party content")
    private String ownGroupId;

    @Option(names = "--own-npm-scope", required = true, //
            description = "npm scope of our own packages, which are not third-party content")
    private String ownNpmScope;

    @Option(names = "--project-name", required = true, description = "Name of the project")
    private String projectName;

    @Option(names = "--project-version", required = true, description = "Version of the project")
    private String projectVersion;

    @Option(names = "--project-license", required = true, //
            description = "License of the project: an SPDX expression or a license name that "
                    + "maps to one, such as the name in the POM")
    private String projectLicense;

    @Option(names = "--project-url", required = true, //
            description = "Home page of the project, also the base of the SBOM namespace")
    private String projectUrl;

    @Option(names = "--output-timestamp", //
            description = "The build's project.build.outputTimestamp, for a reproducible SBOM")
    private String outputTimestamp;

    private Map<String, Conclusion> conclusions;
    private final Set<String> usedConclusions = new HashSet<>();
    private final List<String> errors = new ArrayList<>();
    private final StandardLicenseTexts standardLicenseTexts = new StandardLicenseTexts();
    private List<ShadedContent> shadedContent;
    private final Set<String> usedShadedContainers = new HashSet<>();
    private final List<ShadedContent> shippedShadedContent = new ArrayList<>();

    public static void main(String[] aArgs)
    {
        var commandLine = new CommandLine(new LegalDocumentationGenerator());
        // Report only the message; the full stack trace adds nothing to the list of problems
        commandLine.setExecutionExceptionHandler((ex, cmd, parseResult) -> {
            cmd.getErr().println(ex.getMessage());
            return cmd.getCommandSpec().exitCodeOnExecutionException();
        });

        // Runs inside the Maven JVM through exec:java, so fail with an exception instead of
        // calling System.exit()
        var exitCode = commandLine.execute(aArgs);
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "Generating the legal documentation failed (exit code " + exitCode + ")");
        }
    }

    LegalDocumentationGenerator()
    {
        // Conclusions are loaded from the bundled resource when the command runs
    }

    LegalDocumentationGenerator(List<Conclusion> aConclusions)
    {
        this(aConclusions, List.of());
    }

    LegalDocumentationGenerator(List<Conclusion> aConclusions, List<ShadedContent> aShadedContent)
    {
        conclusions = index(aConclusions);
        shadedContent = aShadedContent;
    }

    @Override
    public Integer call() throws IOException
    {
        if (conclusions == null) {
            conclusions = index(loadConclusions());
        }
        if (shadedContent == null) {
            shadedContent = ShadedContent.load();
        }
        generate();
        return 0;
    }

    void generate() throws IOException
    {
        if (skipIfMissing && !exists(artifact)) {
            System.out.println(
                    "Not generating the legal documentation: " + artifact + " does not exist");
            return;
        }

        var directory = isDirectory(artifact);
        if (patch && directory) {
            throw new IllegalArgumentException("Only archives can be patched: " + artifact);
        }

        var content = directory ? scanDirectory(artifact) : scanArtifact(artifact);

        var libraryFiles = new LibraryFiles();
        var maven = collectMavenComponents(content, libraryFiles);
        var runtime = content.javaRuntime();
        if (runtime != null) {
            if (!runtime.gplWithClasspathException()) {
                errors.add("The Java runtime in " + runtime.path() + " is not licensed under "
                        + JAVA_RUNTIME_LICENSE + " according to its legal/java.base/LICENSE");
            }
            maven.add(runtime.toComponent());
        }
        var npm = collectNpmComponents(content.entries(), libraryFiles);
        var embedded = collectEmbeddedContent(content.entries());
        var licensed = new ArrayList<>(embedded);
        shippedShadedContent.forEach(sc -> licensed.add(sc.spdxPackage()));
        var licenses = collectLicenseTexts(maven, npm, licensed);

        var components = new ArrayList<>(maven);
        components.addAll(npm);
        var projectSpdxLicense = licenseExpression(projectLicense);
        if (projectSpdxLicense.isEmpty()) {
            errors.add("The project license does not map to an SPDX id: " + projectLicense);
        }
        var project = new SpdxSbom.Project(projectName, projectVersion,
                projectSpdxLicense.orElse(projectLicense), projectUrl,
                distributionName != null ? distributionName : artifact.getFileName().toString(),
                SpdxSbom.creationTime(outputTimestamp, Instant.now()));
        var sbom = SpdxSbom.build(project, components, embedded, shippedShadedContent, licenses);
        for (var violation : SpdxSbom.validate(sbom)) {
            errors.add("The SBOM violates the SPDX 2.3 schema: " + violation);
        }

        for (var shaded : shadedContent) {
            if (failOnUnusedConclusions && !usedShadedContainers.contains(shaded.containerKey())) {
                errors.add("[" + shaded.spdxPackage().getName() + "] is declared as bundled into ["
                        + shaded.containerKey() + "], which does not ship - remove it from "
                        + ShadedContent.RESOURCE);
            }
        }

        for (var unused : conclusions.keySet()) {
            if (failOnUnusedConclusions && !usedConclusions.contains(unused)) {
                errors.add("Concluded license for [" + unused
                        + "] is not used by any component - remove it from "
                        + CONCLUSIONS_RESOURCE);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalStateException(
                    "Unable to generate the legal documentation:\n  - " + join("\n  - ", errors));
        }

        createDirectories(output);
        copy(license, output.resolve(LICENSE_FILE), REPLACE_EXISTING);
        writeDependencies(maven, npm);
        writeNotice(maven, embedded, libraryFiles, runtime);
        var licenseFiles = writeLicenseTexts(licenses);
        deleteRecursively(output.resolve(LibraryFiles.LIBRARIES_DIR));
        var hoistedFiles = libraryFiles.writeTo(output);
        // "\n" on every platform, so that the files do not depend on where they were built (the
        // default pretty printer and Files.write(lines) use the platform's line separator)
        var printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"));
        writeString(output.resolve(SBOM_FILE),
                JSONUtil.getObjectMapper().writer().with(printer).writeValueAsString(sbom), UTF_8);
        if (sbomCopy != null) {
            copy(output.resolve(SBOM_FILE), sbomCopy, REPLACE_EXISTING);
        }

        if (patch) {
            var files = new ArrayList<>(
                    List.of(LICENSE_FILE, NOTICE_MD, DEPENDENCIES_FILE, SBOM_FILE));
            files.addAll(licenseFiles);
            files.addAll(hoistedFiles);
            patch(artifact, output, files);
        }
    }

    /**
     * @return the texts of all licenses in use, by SPDX id, including the licenses they incorporate
     *         by reference.
     */
    private Map<String, byte[]> collectLicenseTexts(List<Component> aMaven, List<Component> aNpm,
            List<SpdxPackage> aEmbedded)
        throws IOException
    {
        var expressions = new ArrayList<String>();
        aMaven.forEach(c -> expressions.add(c.license()));
        aNpm.forEach(c -> expressions.add(c.license()));
        aEmbedded.forEach(p -> licenseExpression(embeddedLicense(p)).ifPresent(expressions::add));

        var ids = new TreeSet<String>();
        for (var expression : expressions) {
            for (var id : licenseIds(expression)) {
                ids.add(id);
                if (INCORPORATED_LICENSES.containsKey(id)) {
                    ids.add(INCORPORATED_LICENSES.get(id));
                }
            }
        }

        var texts = new TreeMap<String, byte[]>();
        for (var id : ids) {
            try (var is = LegalDocumentationGenerator.class
                    .getResourceAsStream(LICENSE_TEXTS_RESOURCE + id + ".txt")) {
                if (is == null) {
                    errors.add("No text for license [" + id + "] - add " + LICENSE_TEXTS_RESOURCE
                            + id + ".txt from the text folder of the SPDX license list data "
                            + "(https://github.com/spdx/license-list-data)");
                    continue;
                }
                texts.put(id, is.readAllBytes());
            }
        }
        return texts;
    }

    /**
     * @return the license and exception ids in an SPDX license expression.
     */
    static Set<String> licenseIds(String aExpression)
    {
        var ids = new LinkedHashSet<String>();
        for (var token : aExpression.replace('(', ' ').replace(')', ' ').trim().split("\\s+")) {
            if (!token.isEmpty() && !List.of("AND", "OR", "WITH").contains(token)) {
                ids.add(token);
            }
        }
        return ids;
    }

    /**
     * Writes the license texts into a folder of their own, cleared first so that a text no longer
     * in use does not linger from an earlier build.
     *
     * @return the paths of the written files, relative to the output directory.
     */
    private List<String> writeLicenseTexts(Map<String, byte[]> aTexts) throws IOException
    {
        var dir = output.resolve(LICENSES_DIR);
        createDirectories(dir);
        try (var existing = list(dir)) {
            for (var file : existing.toList()) {
                deleteIfExists(file);
            }
        }

        var files = new ArrayList<String>();
        for (var text : aTexts.entrySet()) {
            var file = LICENSES_DIR + "/" + text.getKey() + ".txt";
            write(output.resolve(file), text.getValue());
            files.add(file);
        }
        return files;
    }

    private static void deleteRecursively(Path aDir) throws IOException
    {
        if (!exists(aDir)) {
            return;
        }
        try (var walk = Files.walk(aDir)) {
            for (var path : walk.sorted(Comparator.reverseOrder()).toList()) {
                deleteIfExists(path);
            }
        }
    }

    private List<Component> collectMavenComponents(ArtifactContent aContent,
            LibraryFiles aLibraryFiles)
        throws IOException
    {
        var listed = new HashMap<String, MavenDependency>();
        if (aContent.applicationDependencies() == null) {
            errors.add("The artifact contains no application DEPENDENCIES.json, i.e. no jar or "
                    + "classes folder with " + APPLICATION_MARKER);
        }
        else {
            var deps = loadMavenDependencies(
                    new ByteArrayInputStream(aContent.applicationDependencies()));
            for (var dep : deps.getDependencies()) {
                listed.put(dep.getArtifactId() + "-" + dep.getVersion(), dep);
            }
        }

        var components = new ArrayList<Component>();
        for (var entry : aContent.entries()) {
            if (!entry.library()) {
                continue;
            }

            var coordinates = identify(entry, listed, aContent.springBootVersion());
            if (coordinates.isEmpty()) {
                errors.add("Library in the artifact is neither listed in the application's "
                        + "DEPENDENCIES.json nor carries a POM: " + entry.path());
                continue;
            }

            var c = coordinates.get();
            if (ownGroupId.equals(c.groupId())) {
                continue;
            }

            var key = "pkg:maven/" + c.groupId() + "/" + c.artifactId();
            var classifier = classifier(entry.fileName(), c.artifactId(), c.version());
            var license = resolveLicense(key, c.version(), c.licenses());
            if (license.isPresent()) {
                components.add(maven(c.groupId(), c.artifactId(), c.version(), classifier,
                        license.get(), authority(key, "pom"), entry.path(), entry.sha256()));
                hoistLegalFiles(entry, c.groupId() + ":" + c.artifactId() + ":" + c.version(),
                        license.get(), aLibraryFiles);
            }
            checkShadedContent(entry, key, c);
        }

        if (aContent.springBootLoader()) {
            if (aContent.springBootVersion() == null) {
                errors.add("The artifact contains Spring Boot loader classes but its manifest has "
                        + "no " + SPRING_BOOT_VERSION);
            }
            else {
                var key = "pkg:maven/" + SPRING_BOOT_GROUP_ID + "/" + SPRING_BOOT_LOADER;
                // The loader classes are copied in loose, so there is no jar to name or checksum
                resolveLicense(key, aContent.springBootVersion(), List.of())
                        .ifPresent(l -> components.add(maven(SPRING_BOOT_GROUP_ID,
                                SPRING_BOOT_LOADER, aContent.springBootVersion(), null, l,
                                authority(key, "pom"), null, null)));
            }
        }

        return components;
    }

    /**
     * Takes the code declared as bundled into a library, checked for the version that ships, and
     * fails for a library that shows signs of code from other projects but has no declaration:
     * classes in a folder named like {@code shaded}, or the POM of an artifact from another group
     * embedded in the jar.
     */
    private void checkShadedContent(ScannedEntry aEntry, String aKey, Coordinates aCoordinates)
    {
        var declared = shadedContent.stream() //
                .filter(sc -> sc.containerKey().equals(aKey)) //
                .toList();
        if (!declared.isEmpty()) {
            usedShadedContainers.add(aKey);
        }

        // Every entry, not only the first, must have been checked for the version that ships
        var stale = declared.stream() //
                .filter(sc -> !sc.containerVersion().equals(aCoordinates.version())) //
                .map(sc -> sc.spdxPackage().getName() + " (" + sc.containerVersion() + ")") //
                .toList();
        if (!stale.isEmpty()) {
            errors.add("The code bundled into [" + aKey + "] was checked for another version "
                    + "than " + aCoordinates.version() + ", which ships: " + join(", ", stale)
                    + " - check what it bundles now and update its entries in "
                    + ShadedContent.RESOURCE);
            return;
        }

        // Every sign of bundled code must be covered by an entry, also in a library that has
        // entries already: an upgrade may bundle more
        var uncovered = new TreeSet<String>();
        aEntry.shadedRoots().stream() //
                .filter(root -> declared.stream().noneMatch(sc -> sc.coversPath(root))) //
                .forEach(uncovered::add);
        aEntry.pomGroupIds().stream() //
                .filter(g -> !g.equals(aCoordinates.groupId())) //
                .filter(g -> declared.stream().noneMatch(sc -> sc.hasGroupId(g))) //
                .forEach(g -> uncovered.add("embedded POM of " + g));
        if (!uncovered.isEmpty()) {
            errors.add("[" + aKey + "] contains code of other projects (" + join(", ", uncovered)
                    + "), but " + ShadedContent.RESOURCE + " declares none "
                    + (declared.isEmpty() ? "" : "covering it ") + "for it - check what it "
                    + "bundles and declare it there, with the folders in hasFiles");
            return;
        }

        shippedShadedContent.addAll(declared);
    }

    /**
     * Passes on the license and notice files of a library that the deduplicated license texts
     * cannot replace: all but the license files that are just the standard text of one of the
     * library's licenses.
     */
    private void hoistLegalFiles(ScannedEntry aEntry, String aLibrary, String aLicense,
            LibraryFiles aLibraryFiles)
        throws IOException
    {
        for (var file : aEntry.legalFiles().entrySet()) {
            if (LibraryFiles.isLicenseFile(file.getKey())
                    && standardLicenseTexts.isStandard(file.getValue(), licenseIds(aLicense))) {
                continue;
            }
            aLibraryFiles.add(aLibrary, removeJarSuffix(aEntry.fileName()), file.getKey(),
                    file.getValue());
        }
    }

    /**
     * Determines the Maven coordinates and declared licenses of a library: from the application's
     * resolved dependencies, otherwise from the POM embedded in the jar, otherwise from the Spring
     * Boot version for what the repackaging adds.
     */
    private Optional<Coordinates> identify(ScannedEntry aEntry,
            Map<String, MavenDependency> aListed, String aSpringBootVersion)
    {
        var fileName = aEntry.fileName();
        var listed = findListed(aListed, fileName);
        if (listed.isPresent()) {
            var dep = listed.get();
            var declared = dep.getLicenses() == null ? List.<String> of()
                    : dep.getLicenses().stream().map(Object::toString).toList();
            return Optional.of(new Coordinates(dep.getGroupId(), dep.getArtifactId(),
                    dep.getVersion(), declared));
        }

        if (aEntry.pom() != null) {
            return Optional.of(aEntry.pom());
        }

        var base = removeJarSuffix(fileName);
        if (aSpringBootVersion != null) {
            for (var artifactId : SPRING_BOOT_JARS) {
                if (base.equals(artifactId + "-" + aSpringBootVersion)) {
                    return Optional.of(new Coordinates(SPRING_BOOT_GROUP_ID, artifactId,
                            aSpringBootVersion, List.of()));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Finds the listed dependency of a jar file: exactly by {@code artifactId-version}, otherwise
     * by prefix for jars with a classifier such as {@code jakarta.json-2.0.1-module.jar}.
     */
    static Optional<MavenDependency> findListed(Map<String, MavenDependency> aListed,
            String aFileName)
    {
        var base = removeJarSuffix(aFileName);

        var exact = aListed.get(base);
        if (exact != null) {
            return Optional.of(exact);
        }

        return aListed.entrySet().stream() //
                .filter(e -> base.startsWith(e.getKey() + "-")) //
                .max(comparing(e -> e.getKey().length())) //
                .map(Map.Entry::getValue);
    }

    /**
     * @return the classifier in a jar's file name, e.g. {@code module} for
     *         {@code jakarta.json-2.0.1-module.jar}, if any.
     */
    static String classifier(String aFileName, String aArtifactId, String aVersion)
    {
        var base = removeJarSuffix(aFileName);
        var prefix = aArtifactId + "-" + aVersion + "-";
        return base.startsWith(prefix) && base.length() > prefix.length()
                ? base.substring(prefix.length())
                : null;
    }

    private static String removeJarSuffix(String aFileName)
    {
        return aFileName.endsWith(".jar")
                ? aFileName.substring(0, aFileName.length() - ".jar".length())
                : aFileName;
    }

    private List<Component> collectNpmComponents(List<ScannedEntry> aEntries,
            LibraryFiles aLibraryFiles)
        throws IOException
    {
        var components = new TreeMap<String, Component>();
        // The same package appears in several of our modules; check and report it only once
        var seen = new HashSet<String>();
        for (var entry : aEntries) {
            if (entry.npmDependencies() == null) {
                continue;
            }

            var deps = loadNpmDependencies(new ByteArrayInputStream(entry.npmDependencies()));
            for (var dep : deps.values()) {
                if (!seen.add(dep.getName())) {
                    continue;
                }

                // Same split into name and version as on the about page
                var dependency = dep.toDependency();
                var name = dependency.getName();
                var version = dependency.getVersion();
                if (dep.getName().startsWith(ownNpmScope + "/")) {
                    continue;
                }

                if (name == null || name.isBlank() || version == null || version.isBlank()) {
                    errors.add("npm package without a version in " + entry.path() + ": "
                            + dep.getName());
                    continue;
                }

                if (dep.getNoticeText() != null) {
                    aLibraryFiles.add("npm:" + name + ":" + version,
                            // "+" for the scope separator: @foo/bar must not become foo-bar
                            "npm-" + name.replace("@", "").replace('/', '+') + "-" + version,
                            "NOTICE", dep.getNoticeText().getBytes(UTF_8));
                }

                var key = "pkg:npm/" + name.replace("@", "%40");
                var declared = dep.getLicenses() == null ? List.<String> of()
                        : List.of(dep.getLicenses());
                resolveLicense(key, version, declared).ifPresent(l -> components.put(dep.getName(),
                        npm(name, version, l, authority(key, "npm"))));
            }
        }
        return new ArrayList<>(components.values());
    }

    private List<SpdxPackage> collectEmbeddedContent(List<ScannedEntry> aEntries) throws IOException
    {
        var packages = new ArrayList<SpdxPackage>();
        for (var entry : aEntries) {
            if (entry.embeddedDependencies() == null) {
                continue;
            }

            var deps = loadEmbeddedSpdxDependencies(
                    new ByteArrayInputStream(entry.embeddedDependencies()));
            for (var pkg : deps.getPackages()) {
                if (pkg.getName() == null || pkg.getName().isBlank()) {
                    errors.add("Embedded content without a name in " + entry.path());
                    continue;
                }

                Stream.of(pkg.getOriginator(), pkg.getSupplier()) //
                        .filter(Objects::nonNull) //
                        .filter(entity -> !SpdxSbom.isEntity(entity)) //
                        .forEach(entity -> errors.add("Embedded content [" + pkg.getName() + "] in "
                                + entry.path() + " names its originator or supplier "
                                + "without \"Person: \" or \"Organization: \": " + entity));

                var declared = embeddedLicense(pkg);
                if (licenseExpression(declared).isEmpty()) {
                    errors.add("Embedded content [" + pkg.getName() + "] in " + entry.path()
                            + " declares an unknown license: " + declared);
                    continue;
                }
                var same = packages.stream().filter(p -> isSameContent(p, pkg)).findFirst();
                if (same.isPresent()) {
                    merge(same.get(), pkg);
                }
                else {
                    packages.add(pkg);
                }
            }
        }
        packages.sort(comparing(SpdxPackage::getName, CASE_INSENSITIVE_ORDER));
        return packages;
    }

    /**
     * Several of our modules can embed parts of the same third-party content, e.g. icons from the
     * same icon set. They declare it separately, but it is one component.
     */
    private static boolean isSameContent(SpdxPackage aOne, SpdxPackage aOther)
    {
        return Objects.equals(aOne.getName(), aOther.getName())
                && Objects.equals(aOne.getVersionInfo(), aOther.getVersionInfo())
                && Objects.equals(embeddedLicense(aOne), embeddedLicense(aOther))
                && Objects.equals(aOne.getOriginator(), aOther.getOriginator());
    }

    private static void merge(SpdxPackage aTarget, SpdxPackage aOther)
    {
        var files = new TreeSet<String>();
        Stream.of(aTarget.getHasFiles(), aOther.getHasFiles()) //
                .filter(Objects::nonNull) //
                .forEach(files::addAll);
        aTarget.setHasFiles(new ArrayList<>(files));
        aTarget.setDescription(joinDistinct(aTarget.getDescription(), aOther.getDescription()));
        aTarget.setLicenseComments(
                joinDistinct(aTarget.getLicenseComments(), aOther.getLicenseComments()));
    }

    private static String joinDistinct(String aOne, String aOther)
    {
        if (aOne == null || aOne.equals(aOther)) {
            return aOther != null ? aOther : aOne;
        }
        return aOther == null ? aOne : aOne + " " + aOther;
    }

    static String embeddedLicense(SpdxPackage aPackage)
    {
        return aPackage.getLicenseConcluded() != null ? aPackage.getLicenseConcluded()
                : aPackage.getLicenseDeclared();
    }

    /**
     * @return the SPDX expression for a license declaration, accepting a custom
     *         {@code LicenseRef-...} as it is.
     */
    static Optional<String> licenseExpression(String aDeclared)
    {
        if (aDeclared != null && LICENSE_REF.matcher(aDeclared.trim()).matches()) {
            return Optional.of(aDeclared.trim());
        }
        return toSpdxId(aDeclared);
    }

    /**
     * Reads what the generator needs from an executable archive in one pass, including the jars
     * nested in it. Everything is read as a stream and closed again, so that no jar stays open in
     * the JVM running the build.
     */
    static ArtifactContent scanArtifact(Path aArtifact) throws IOException
    {
        try (var archive = new JarInputStream(newInputStream(aArtifact))) {
            var manifest = archive.getManifest();
            var springBootVersion = manifest != null
                    ? manifest.getMainAttributes().getValue(SPRING_BOOT_VERSION)
                    : null;

            var entries = new ArrayList<ScannedEntry>();
            byte[] classesNpm = null;
            byte[] classesEmbedded = null;
            byte[] classesDependencies = null;
            var classesApplication = false;
            var loader = false;
            for (var entry = archive.getNextJarEntry(); entry != null; entry = archive
                    .getNextJarEntry()) {
                var name = entry.getName();
                if (name.endsWith(".jar") && LIBRARY_DIRS.stream().anyMatch(name::startsWith)) {
                    entries.add(scanJar(name, archive.readAllBytes()));
                }
                else if (CLASSES_DIRS.stream().anyMatch(d -> name.equals(d + NPM_DEPENDENCIES))) {
                    classesNpm = archive.readAllBytes();
                }
                else if (CLASSES_DIRS.stream()
                        .anyMatch(d -> name.equals(d + EMBEDDED_DEPENDENCIES))) {
                    classesEmbedded = archive.readAllBytes();
                }
                else if (CLASSES_DIRS.stream()
                        .anyMatch(d -> name.equals(d + APPLICATION_DEPENDENCIES))) {
                    classesDependencies = archive.readAllBytes();
                }
                else if (CLASSES_DIRS.stream().anyMatch(d -> name.equals(d + APPLICATION_MARKER))) {
                    classesApplication = true;
                }
                else if (name.startsWith(SPRING_BOOT_LOADER_PACKAGE)) {
                    loader = true;
                }
            }

            if (classesNpm != null || classesEmbedded != null) {
                entries.add(new ScannedEntry(aArtifact + "!classes", false, null, null, classesNpm,
                        classesEmbedded, Map.of(), Set.of(), Set.of(), null));
            }

            var applicationDependencies = classesApplication ? classesDependencies
                    : applicationDependencies(entries);
            return new ArtifactContent(springBootVersion, loader, entries, applicationDependencies,
                    null);
        }
    }

    /**
     * Reads what the generator needs from a folder, such as a jpackage application image: the jars
     * in it, and the Java runtime bundled with it, recognised by its {@code release} file next to
     * {@code lib/modules}. The runtime's own jars, such as {@code jrt-fs.jar}, belong to the
     * runtime and are not libraries of the application.
     */
    static ArtifactContent scanDirectory(Path aDirectory) throws IOException
    {
        List<Path> files;
        try (var walk = Files.walk(aDirectory)) {
            files = walk.filter(Files::isRegularFile).sorted().toList();
        }

        var runtimes = new ArrayList<JavaRuntime>();
        for (var file : files) {
            if ("release".equals(file.getFileName().toString())
                    && isRegularFile(file.getParent().resolve("lib/modules"))) {
                runtimes.add(JavaRuntime.read(file.getParent(),
                        relativePath(aDirectory, file.getParent())));
            }
        }
        if (runtimes.size() > 1) {
            throw new IOException("More than one Java runtime in " + aDirectory + ": "
                    + runtimes.stream().map(JavaRuntime::path).toList());
        }

        var entries = new ArrayList<ScannedEntry>();
        for (var file : files) {
            var path = relativePath(aDirectory, file);
            if (path.endsWith(".jar")
                    && runtimes.stream().noneMatch(rt -> path.startsWith(rt.path() + "/"))) {
                entries.add(scanJar(path, Files.readAllBytes(file)));
            }
        }

        return new ArtifactContent(null, false, entries, applicationDependencies(entries),
                runtimes.isEmpty() ? null : runtimes.get(0));
    }

    private static String relativePath(Path aBase, Path aFile)
    {
        return aBase.relativize(aFile).toString().replace(File.separatorChar, '/');
    }

    /**
     * @return the {@code DEPENDENCIES.json} of the one jar that is the application, if any.
     */
    private static byte[] applicationDependencies(List<ScannedEntry> aEntries) throws IOException
    {
        var applications = aEntries.stream() //
                .filter(e -> e.applicationDependencies() != null) //
                .toList();
        if (applications.size() > 1) {
            throw new IOException("More than one jar in the artifact is an application: "
                    + applications.stream().map(ScannedEntry::path).toList());
        }
        return applications.isEmpty() ? null : applications.get(0).applicationDependencies();
    }

    static ScannedEntry scanJar(String aPath, byte[] aJar) throws IOException
    {
        try (var jar = new JarInputStream(new ByteArrayInputStream(aJar))) {
            byte[] npmDependencies = null;
            byte[] embeddedDependencies = null;
            var pomProperties = new LinkedHashMap<String, Properties>();
            var pomXmls = new HashMap<String, byte[]>();
            var legalFiles = new TreeMap<String, byte[]>();
            var shadedRoots = new TreeSet<String>();
            var pomGroupIds = new TreeSet<String>();
            byte[] applicationDependencies = null;
            var application = false;
            for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                var name = entry.getName();
                if (NPM_DEPENDENCIES.equals(name)) {
                    npmDependencies = jar.readAllBytes();
                }
                else if (EMBEDDED_DEPENDENCIES.equals(name)) {
                    embeddedDependencies = jar.readAllBytes();
                }
                else if (POM_PROPERTIES.matcher(name).matches()) {
                    pomGroupIds.add(name.split("/")[2]);
                    var properties = new Properties();
                    properties.load(new ByteArrayInputStream(jar.readAllBytes()));
                    pomProperties.put(name.substring(0, name.lastIndexOf('/')), properties);
                }
                else if (POM_XML.matcher(name).matches()) {
                    pomXmls.put(name.substring(0, name.lastIndexOf('/')), jar.readAllBytes());
                }
                else if (APPLICATION_DEPENDENCIES.equals(name)) {
                    applicationDependencies = jar.readAllBytes();
                }
                else if (APPLICATION_MARKER.equals(name)) {
                    application = true;
                }
                else if (LibraryFiles.isLegalFile(name)) {
                    legalFiles.put(name, jar.readAllBytes());
                }
                else {
                    var shaded = SHADED_CLASSES.matcher(name);
                    if (shaded.matches()) {
                        shadedRoots.add(
                                shaded.group(1) + (shaded.group(2) != null ? shaded.group(2) : ""));
                    }
                }
            }

            var pom = embeddedPom(aPath, pomProperties, pomXmls);
            return new ScannedEntry(aPath, true, pom, sha256(aJar), npmDependencies,
                    embeddedDependencies, legalFiles, shadedRoots, pomGroupIds,
                    application ? applicationDependencies : null);
        }
    }

    private static String sha256(byte[] aData)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(aData));
        }
        catch (NoSuchAlgorithmException e) {
            // Every Java platform has to support SHA-256
            throw new IllegalStateException(e);
        }
    }

    /**
     * Picks the POM that describes the jar itself: the one matching the file name, or the only one.
     * Shaded jars carry the POMs of what they include as well.
     */
    private static Coordinates embeddedPom(String aPath, Map<String, Properties> aProperties,
            Map<String, byte[]> aPomXmls)
        throws IOException
    {
        var base = removeJarSuffix(aPath.substring(aPath.lastIndexOf('/') + 1));
        String chosen = null;
        for (var entry : aProperties.entrySet()) {
            var p = entry.getValue();
            if (base.startsWith(p.getProperty("artifactId") + "-" + p.getProperty("version"))) {
                chosen = entry.getKey();
                break;
            }
        }
        if (chosen == null && aProperties.size() == 1) {
            chosen = aProperties.keySet().iterator().next();
        }
        if (chosen == null) {
            return null;
        }

        var p = aProperties.get(chosen);
        var pomXml = aPomXmls.get(chosen);
        var licenses = pomXml != null ? pomLicenses(pomXml) : List.<String> of();
        return new Coordinates(p.getProperty("groupId"), p.getProperty("artifactId"),
                p.getProperty("version"), licenses);
    }

    /**
     * @return the licenses declared in a POM, formatted like {@link MavenLicense#toString()} so
     *         that they map the same way as those in {@code DEPENDENCIES.json}.
     */
    static List<String> pomLicenses(byte[] aPomXml) throws IOException
    {
        try {
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var document = factory.newDocumentBuilder().parse(new ByteArrayInputStream(aPomXml));
            var licenses = new ArrayList<String>();
            var nodes = document.getElementsByTagName("license");
            for (var i = 0; i < nodes.getLength(); i++) {
                var element = (Element) nodes.item(i);
                var license = new MavenLicense();
                license.setName(childText(element, "name"));
                license.setUrl(childText(element, "url"));
                licenses.add(license.toString());
            }
            return licenses;
        }
        catch (ParserConfigurationException | SAXException e) {
            throw new IOException("Unable to read embedded POM", e);
        }
    }

    private static String childText(Element aElement, String aName)
    {
        var children = aElement.getElementsByTagName(aName);
        return children.getLength() > 0 ? children.item(0).getTextContent().trim() : null;
    }

    /**
     * Writes the generated files into {@code META-INF/} of the artifact. The zip file system copies
     * the existing entries unchanged, so the nested jars stay stored uncompressed as the Spring
     * Boot loader requires. The new entries take the time stamp of the manifest, so the archive
     * stays reproducible.
     */
    static void patch(Path aArtifact, Path aDirectory, List<String> aFiles) throws IOException
    {
        try (var fs = FileSystems.newFileSystem(aArtifact)) {
            var time = getLastModifiedTime(fs.getPath("META-INF/MANIFEST.MF"));
            for (var file : aFiles) {
                var target = fs.getPath("META-INF", file);
                // New folder entries would otherwise carry the current time
                var folder = target.getParent();
                var missing = new ArrayList<Path>();
                while (folder != null && !exists(folder)) {
                    missing.add(0, folder);
                    folder = folder.getParent();
                }
                for (var newFolder : missing) {
                    createDirectories(newFolder);
                    setLastModifiedTime(newFolder, time);
                }
                copy(aDirectory.resolve(file), target, REPLACE_EXISTING);
                setLastModifiedTime(target, time);
            }
        }
    }

    /**
     * Maps the declared licenses of a component to one SPDX expression. Several declared licenses
     * are read as alternatives, which is what multiple licenses in a POM usually mean. A component
     * whose declarations do not all map needs a concluded license.
     */
    private Optional<String> resolveLicense(String aKey, String aVersion, List<String> aDeclared)
    {
        var concluded = conclusions.get(aKey);
        if (concluded != null) {
            usedConclusions.add(aKey);
            if (!concluded.covers(aVersion)) {
                errors.add("The concluded license of [" + aKey + "] was checked for version "
                        + concluded.versions() + ", but version " + aVersion + " ships - check "
                        + "the license of this version and update the entry in "
                        + CONCLUSIONS_RESOURCE);
                return Optional.empty();
            }
            return Optional.of(concluded.license());
        }

        var ids = new LinkedHashSet<String>();
        for (var declared : aDeclared) {
            var id = toSpdxId(declared);
            if (id.isEmpty()) {
                errors.add("License of [" + aKey + "] does not map to an SPDX id: [" + declared
                        + "] - extend KnownLicenses or add an entry to " + CONCLUSIONS_RESOURCE);
                return Optional.empty();
            }
            ids.add(id.get());
        }

        if (ids.isEmpty()) {
            errors.add(
                    "[" + aKey + "] declares no license - add an entry to " + CONCLUSIONS_RESOURCE);
            return Optional.empty();
        }

        if (ids.size() == 1) {
            return Optional.of(ids.iterator().next());
        }

        return Optional.of(join(" OR ", ids.stream() //
                .map(id -> id.contains(" ") ? "(" + id + ")" : id) //
                .toList()));
    }

    /**
     * @return where the license of a component was taken from: a concluded license, or the
     *         component's own metadata.
     */
    private String authority(String aKey, String aMetadata)
    {
        return conclusions.containsKey(aKey) ? "concluded" : aMetadata;
    }

    private void writeDependencies(List<Component> aMaven, List<Component> aNpm) throws IOException
    {
        var lines = new ArrayList<String>();
        for (var component : aMaven) {
            lines.add(component.toDependenciesLine());
        }
        for (var component : aNpm) {
            lines.add(component.toDependenciesLine());
        }
        // Code bundled into libraries, where its Maven coordinates and version are known: the
        // format needs coordinates, so code without them is listed in NOTICE.md and the SBOM only
        for (var shaded : shippedShadedContent) {
            var purl = shaded.purl();
            if (purl.isPresent() && purl.get().startsWith("pkg:maven/")) {
                var coordinates = purl.get().substring("pkg:maven/".length());
                var groupId = coordinates.substring(0, coordinates.indexOf('/'));
                var artifactId = coordinates.substring(coordinates.indexOf('/') + 1,
                        coordinates.indexOf('@'));
                lines.add(maven(groupId, artifactId, shaded.spdxPackage().getVersionInfo(), null,
                        shaded.license(), "shaded", null, null).toDependenciesLine());
            }
        }
        // The same code bundled into two libraries (FastDoubleParser in Jackson 2 and 3) is one
        // component; DEPENDENCIES lists components, the SBOM where each copy is contained
        writeString(output.resolve(DEPENDENCIES_FILE),
                lines.stream().distinct().sorted().map(l -> l + "\n").collect(joining()), UTF_8);
    }

    private void writeNotice(List<Component> aMaven, List<SpdxPackage> aEmbedded,
            LibraryFiles aLibraryFiles, JavaRuntime aRuntime)
        throws IOException
    {
        var embedded = new StringBuilder();
        for (var pkg : aEmbedded) {
            embedded.append("#### ").append(pkg.getName());
            if (pkg.getVersionInfo() != null) {
                embedded.append(" ").append(pkg.getVersionInfo());
            }
            embedded.append("\n\n");
            var lic = pkg.getLicenseConcluded() != null ? pkg.getLicenseConcluded()
                    : pkg.getLicenseDeclared();
            embedded.append("* License: ").append(licenseExpression(lic).orElseThrow())
                    .append("\n");
            if (pkg.getOriginator() != null) {
                embedded.append("* Originator: ")
                        .append(SpdxPackage.entityName(pkg.getOriginator())).append("\n");
            }
            var source = pkg.getHomepage() != null ? pkg.getHomepage() : pkg.getDownloadLocation();
            if (source != null) {
                embedded.append("* Source: ").append(source).append("\n");
            }
            if (pkg.getHasFiles() != null && !pkg.getHasFiles().isEmpty()) {
                embedded.append("* Files: ").append(join(", ", pkg.getHasFiles())).append("\n");
            }
            if (pkg.getLicenseComments() != null) {
                embedded.append("\n").append(pkg.getLicenseComments()).append("\n");
            }
            embedded.append("\n");
        }

        // A Java runtime always brings cryptography of its own (e.g. SunJCE, SunEC)
        var cryptography = aRuntime != null || aMaven.stream()
                .anyMatch(c -> c.groupId() != null && CRYPTOGRAPHY_GROUP_IDS.contains(c.groupId()))
                        ? CRYPTOGRAPHY_STATEMENT
                        : "";

        var libraryFiles = aLibraryFiles.isEmpty()
                ? "None of the libraries comes with notices or license texts of its own."
                : aLibraryFiles.toMarkdown();

        // Without cryptography, its placeholder goes with the blank lines after it
        var notice = readResource(NOTICE_TEMPLATE_RESOURCE) //
                .replace(PROP_EMBEDDED_CONTENT, embedded.toString().strip()) //
                .replace(PROP_BUNDLED_CONTENT, bundledContent()) //
                .replace(PROP_JAVA_RUNTIME + "\n\n",
                        aRuntime == null ? "" : javaRuntimeSection(aRuntime) + "\n\n") //
                .replace(PROP_CRYPTOGRAPHY + "\n\n",
                        cryptography.isEmpty() ? "" : cryptography.strip() + "\n\n") //
                .replace(PROP_LIBRARY_FILES, libraryFiles);
        writeString(output.resolve(NOTICE_MD), notice.strip() + "\n", UTF_8);
    }

    private String bundledContent()
    {
        if (shippedShadedContent.isEmpty()) {
            return "None.";
        }

        var md = new StringBuilder();
        var sorted = shippedShadedContent.stream() //
                .sorted(comparing((ShadedContent sc) -> sc.spdxPackage().getName(),
                        CASE_INSENSITIVE_ORDER).thenComparing(ShadedContent::containerPurl)) //
                .toList();
        for (var shaded : sorted) {
            var pkg = shaded.spdxPackage();
            md.append("#### ").append(pkg.getName());
            if (pkg.getVersionInfo() != null) {
                md.append(" ").append(pkg.getVersionInfo());
            }
            md.append("\n\n* License: ").append(shaded.license()).append("\n");
            if (pkg.getOriginator() != null) {
                md.append("* Originator: ").append(SpdxPackage.entityName(pkg.getOriginator()))
                        .append("\n");
            }
            if (pkg.getHomepage() != null) {
                md.append("* Source: ").append(pkg.getHomepage()).append("\n");
            }
            md.append("* Contained in: ").append(shaded.containerPurl()).append("\n\n");
            // Notices the library itself does not carry, e.g. the NOTICE of an Apache-2.0
            // component; four backticks, since a notice may contain a code block
            for (var text : Optional.ofNullable(pkg.getAttributionTexts()).orElse(List.of())) {
                md.append("````\n").append(text.strip()).append("\n````\n\n");
            }
        }
        return md.toString().strip();
    }

    private static String javaRuntimeSection(JavaRuntime aRuntime)
    {
        return "### Java runtime\n\n" //
                + "The distribution includes the Java runtime " + aRuntime.displayName() //
                + (aRuntime.implementor() != null ? " by " + aRuntime.implementor() : "") //
                + ", licensed under " + JAVA_RUNTIME_LICENSE + ". The notices and licenses of "
                + "the runtime and of the third-party content it includes are in the folder "
                + aRuntime.path() + "/legal of the application.";
    }

    /**
     * Loads the licenses we concluded for components whose own metadata does not state a usable
     * license. The file is an SPDX package list: each package identifies the component by a purl
     * without version, gives the version the license was checked for in {@code versionInfo}
     * (exactly, or as a range such as {@code [4.0,5)} where the license cannot change with the
     * version) and the concluded license with the evidence in its comment.
     */
    static List<Conclusion> loadConclusions() throws IOException
    {
        EmbeddedSpdxDependencies packages;
        try (var is = LegalDocumentationGenerator.class.getResourceAsStream(CONCLUSIONS_RESOURCE)) {
            if (is == null) {
                throw new IOException("Resource not found: " + CONCLUSIONS_RESOURCE);
            }
            packages = loadEmbeddedSpdxDependencies(is);
        }

        var conclusions = new ArrayList<Conclusion>();
        for (var pkg : packages.getPackages()) {
            var purl = Optional.ofNullable(pkg.getExternalRefs()).orElse(List.of()).stream() //
                    .filter(ref -> SpdxExternalRef.TYPE_PURL.equals(ref.getReferenceType())) //
                    .map(SpdxExternalRef::getReferenceLocator) //
                    .findFirst() //
                    .orElseThrow(() -> new IOException("Package [" + pkg.getName() + "] in "
                            + CONCLUSIONS_RESOURCE + " has no purl"));

            if (purl.contains("@")) {
                throw new IOException("The purl of [" + pkg.getName() + "] in "
                        + CONCLUSIONS_RESOURCE + " must not contain a version: " + purl);
            }

            var license = pkg.getLicenseConcluded();
            if (license == null || (!LICENSE_REF.matcher(license).matches()
                    && toSpdxId(license).filter(license::equals).isEmpty())) {
                throw new IOException("The concluded license of [" + purl
                        + "] is not a known SPDX license expression: " + license);
            }

            var versions = pkg.getVersionInfo();
            if (versions == null || versions.isBlank()) {
                throw new IOException("The entry of [" + purl + "] in " + CONCLUSIONS_RESOURCE
                        + " needs a versionInfo: the version its license was checked for");
            }

            conclusions.add(new Conclusion(purl, license, versions));
        }
        return conclusions;
    }

    private static Map<String, Conclusion> index(List<Conclusion> aConclusions)
    {
        var index = new TreeMap<String, Conclusion>();
        for (var conclusion : aConclusions) {
            if (index.put(conclusion.purl(), conclusion) != null) {
                throw new IllegalArgumentException(
                        "Duplicate entry in " + CONCLUSIONS_RESOURCE + ": " + conclusion.purl());
            }
        }
        return index;
    }

    private static String readResource(String aResource) throws IOException
    {
        try (var is = LegalDocumentationGenerator.class.getResourceAsStream(aResource)) {
            if (is == null) {
                throw new IOException("Resource not found: " + aResource);
            }
            return new String(is.readAllBytes(), UTF_8);
        }
    }

    /**
     * A license we concluded for a component. The purl has no version; the versions are those the
     * license was checked for: one version, or a range in interval notation such as
     * {@code [4.0,5)}, with either end open, e.g. {@code [1.0,)}.
     */
    record Conclusion(String purl, String license, String versions) {
        Conclusion
        {
            // Fail early on a malformed range. The fields are not set yet in a compact
            // constructor, so this works on the parameter.
            covers(versions, "0");
        }

        boolean covers(String aVersion)
        {
            return covers(versions, aVersion);
        }

        private static boolean covers(String aVersions, String aVersion)
        {
            var spec = aVersions.trim();
            if (!spec.startsWith("[") && !spec.startsWith("(")) {
                return spec.equals(aVersion);
            }

            if (!(spec.endsWith("]") || spec.endsWith(")")) || spec.indexOf(',') < 0) {
                throw new IllegalArgumentException("Not a version range: " + aVersions);
            }

            var lower = spec.substring(1, spec.indexOf(',')).trim();
            var upper = spec.substring(spec.indexOf(',') + 1, spec.length() - 1).trim();
            if (!lower.isEmpty()) {
                var c = compareVersions(aVersion, lower);
                if (c < 0 || (c == 0 && spec.startsWith("("))) {
                    return false;
                }
            }
            if (!upper.isEmpty()) {
                var c = compareVersions(aVersion, upper);
                if (c > 0 || (c == 0 && spec.endsWith(")"))) {
                    return false;
                }
            }
            return true;
        }

        /**
         * Compares versions segment by segment, numerically where both segments are numbers, e.g.
         * {@code 4.10 > 4.9}. Missing segments count as 0.
         */
        static int compareVersions(String aOne, String aOther)
        {
            var one = aOne.split("[.+-]");
            var other = aOther.split("[.+-]");
            for (var i = 0; i < Math.max(one.length, other.length); i++) {
                var a = i < one.length ? one[i] : "0";
                var b = i < other.length ? other[i] : "0";
                int c;
                if (a.matches("\\d+") && b.matches("\\d+")) {
                    c = Long.compare(Long.parseLong(a), Long.parseLong(b));
                }
                else {
                    c = a.compareTo(b);
                }
                if (c != 0) {
                    return c;
                }
            }
            return 0;
        }
    }

    record Coordinates(String groupId, String artifactId, String version, List<String> licenses) {}

    /**
     * What was read from an artifact. The application dependencies are the
     * {@code DEPENDENCIES.json} of the application itself; the Java runtime is the one bundled by
     * an installer, if any.
     */
    record ArtifactContent(String springBootVersion, boolean springBootLoader,
            List<ScannedEntry> entries, byte[] applicationDependencies, JavaRuntime javaRuntime)
    {}

    /**
     * What was read from a library in the artifact, or from the application's own classes.
     */
    record ScannedEntry(String path, boolean library, Coordinates pom, String sha256,
            byte[] npmDependencies, byte[] embeddedDependencies, Map<String, byte[]> legalFiles,
            Set<String> shadedRoots, Set<String> pomGroupIds, byte[] applicationDependencies)
    {
        String fileName()
        {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /**
     * A third-party component. The file name and checksum are those of the jar in the artifact; npm
     * packages, the Spring Boot loader classes and a bundled Java runtime have none. For a runtime,
     * the group id holds its implementor.
     */
    record Component(String kind, String groupId, String name, String version, String classifier,
            String license, String authority, String fileName, String sha256)
    {
        static Component maven(String aGroupId, String aArtifactId, String aVersion,
                String aClassifier, String aLicense, String aAuthority, String aFileName,
                String aSha256)
        {
            return new Component("maven", aGroupId, aArtifactId, aVersion, aClassifier, aLicense,
                    aAuthority, aFileName, aSha256);
        }

        static Component npm(String aName, String aVersion, String aLicense, String aAuthority)
        {
            return new Component("npm", null, aName, aVersion, null, aLicense, aAuthority, null,
                    null);
        }

        /**
         * The license of a runtime is taken from its own license file. The file name is the
         * runtime's folder in the artifact.
         */
        static Component runtime(String aImplementor, String aVersion, String aLicense,
                String aPath)
        {
            return new Component("runtime", aImplementor, "openjdk", aVersion, null, aLicense,
                    "runtime", aPath, null);
        }

        String clearlyDefinedId()
        {
            if ("runtime".equals(kind)) {
                // ClearlyDefined has no coordinates for Java runtimes
                return purl();
            }
            if ("npm".equals(kind)) {
                var slash = name.indexOf('/');
                var namespace = name.startsWith("@") && slash > 0 ? name.substring(0, slash) : "-";
                var simpleName = slash > 0 && name.startsWith("@") ? name.substring(slash + 1)
                        : name;
                return "npm/npmjs/" + namespace + "/" + simpleName + "/" + version;
            }
            return "maven/mavencentral/" + groupId + "/" + name + "/" + version;
        }

        String purl()
        {
            if ("runtime".equals(kind)) {
                return "pkg:generic/" + name + "@" + version;
            }
            if ("npm".equals(kind)) {
                return "pkg:npm/" + name.replace("@", "%40") + "@" + version;
            }
            return "pkg:maven/" + groupId + "/" + name + "@" + version
                    + (classifier != null ? "?classifier=" + classifier : "");
        }

        String toDependenciesLine()
        {
            // Close to the layout of the Eclipse Dash license tool's summary, but without its
            // approval status, which only the Dash license tool can supply. The last two columns
            // say where the license was taken from and give the purl.
            return join(", ", clearlyDefinedId(), license, authority, purl());
        }
    }
}
