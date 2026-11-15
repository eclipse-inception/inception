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
import static java.nio.file.Files.getLastModifiedTime;
import static java.nio.file.Files.newInputStream;
import static java.nio.file.Files.setLastModifiedTime;
import static java.nio.file.Files.write;
import static java.nio.file.Files.writeString;
import static java.nio.file.StandardCopyOption.REPLACE_EXISTING;
import static java.util.Comparator.comparing;
import static java.util.regex.Pattern.compile;

import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.Callable;
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
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

/**
 * Generates the legal documentation that goes into the root of a distribution artifact: the project
 * {@code LICENSE}, a {@code NOTICE.md} and a {@code DEPENDENCIES} table of all third-party
 * components with their SPDX license expressions.
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
        description = "Generates LICENSE, NOTICE.md and DEPENDENCIES for a distribution artifact.")
public class LegalDocumentationGenerator
    implements Callable<Integer>
{
    private static final String LICENSE_FILE = "LICENSE";
    private static final String NOTICE_MD = "NOTICE.md";
    private static final String DEPENDENCIES_FILE = "DEPENDENCIES";
    private static final String PROP_CRYPTOGRAPHY = "${cryptography}";
    private static final String PROP_EMBEDDED_CONTENT = "${embeddedContent}";

    /** Resources in the package of this class. */
    static final String CONCLUSIONS_RESOURCE = "DEPENDENCIES-CONCLUDED.spdx.json";
    static final String NOTICE_TEMPLATE_RESOURCE = "NOTICE.md.template";

    private static final String NPM_DEPENDENCIES = "META-INF/NPM-DEPENDENCIES.json";
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
            description = "The distribution artifact to analyse, e.g. the standalone jar")
    private Path artifact;

    @Option(names = "--patch", //
            description = "Also write the files into META-INF/ of the artifact")
    private boolean patch;

    @Option(names = "--dependencies", required = true, //
            description = "The application's resolved DEPENDENCIES.json")
    private Path dependencies;

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

    private Map<String, Conclusion> conclusions;
    private final Set<String> usedConclusions = new HashSet<>();
    private final List<String> errors = new ArrayList<>();

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
        conclusions = index(aConclusions);
    }

    @Override
    public Integer call() throws IOException
    {
        if (conclusions == null) {
            conclusions = index(loadConclusions());
        }
        generate();
        return 0;
    }

    void generate() throws IOException
    {
        var content = scanArtifact(artifact);

        var maven = collectMavenComponents(content);
        var npm = collectNpmComponents(content.entries());
        var embedded = collectEmbeddedContent(content.entries());

        for (var unused : conclusions.keySet()) {
            if (!usedConclusions.contains(unused)) {
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
        writeNotice(maven, embedded);

        if (patch) {
            patch(artifact, output, List.of(LICENSE_FILE, NOTICE_MD, DEPENDENCIES_FILE));
        }
    }

    private List<Component> collectMavenComponents(ArtifactContent aContent) throws IOException
    {
        var listed = new HashMap<String, MavenDependency>();
        try (var is = newInputStream(dependencies)) {
            for (var dep : loadMavenDependencies(is).getDependencies()) {
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
                errors.add("Library in the artifact is neither listed in " + dependencies
                        + " nor carries a POM, and no concluded license names its file: "
                        + entry.path());
                continue;
            }

            var c = coordinates.get();
            if (ownGroupId.equals(c.groupId())) {
                continue;
            }

            var key = "pkg:maven/" + c.groupId() + "/" + c.artifactId();
            resolveLicense(key, c.licenses()).ifPresent(l -> components.add(
                    maven(c.groupId(), c.artifactId(), c.version(), l, authority(key, "pom"))));
        }

        if (aContent.springBootLoader()) {
            if (aContent.springBootVersion() == null) {
                errors.add("The artifact contains Spring Boot loader classes but its manifest has "
                        + "no " + SPRING_BOOT_VERSION);
            }
            else {
                var key = "pkg:maven/" + SPRING_BOOT_GROUP_ID + "/" + SPRING_BOOT_LOADER;
                resolveLicense(key, List.of()).ifPresent(
                        l -> components.add(maven(SPRING_BOOT_GROUP_ID, SPRING_BOOT_LOADER,
                                aContent.springBootVersion(), l, authority(key, "pom"))));
            }
        }

        return components;
    }

    /**
     * Determines the Maven coordinates and declared licenses of a library: from the application's
     * resolved dependencies, otherwise from the POM embedded in the jar, otherwise from the Spring
     * Boot version for what the repackaging adds, otherwise from a concluded license naming the
     * file.
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

        for (var conclusion : conclusions.values()) {
            if (conclusion.fileStem() != null && base.startsWith(conclusion.fileStem() + "-")) {
                var version = base.substring(conclusion.fileStem().length() + 1);
                return Optional.of(new Coordinates(conclusion.groupId(), conclusion.artifactId(),
                        version, List.of()));
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

    private static String removeJarSuffix(String aFileName)
    {
        return aFileName.endsWith(".jar")
                ? aFileName.substring(0, aFileName.length() - ".jar".length())
                : aFileName;
    }

    private List<Component> collectNpmComponents(List<ScannedEntry> aEntries) throws IOException
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

                var key = "pkg:npm/" + name.replace("@", "%40");
                var declared = dep.getLicenses() == null ? List.<String> of()
                        : List.of(dep.getLicenses());
                resolveLicense(key, declared).ifPresent(l -> components.put(dep.getName(),
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

                var declared = embeddedLicense(pkg);
                if (licenseExpression(declared).isEmpty()) {
                    errors.add("Embedded content [" + pkg.getName() + "] in " + entry.path()
                            + " declares an unknown license: " + declared);
                    continue;
                }
                packages.add(pkg);
            }
        }
        packages.sort(comparing(SpdxPackage::getName, CASE_INSENSITIVE_ORDER));
        return packages;
    }

    private static String embeddedLicense(SpdxPackage aPackage)
    {
        return aPackage.getLicenseConcluded() != null ? aPackage.getLicenseConcluded()
                : aPackage.getLicenseDeclared();
    }

    /**
     * @return the SPDX expression for a license declaration, accepting a custom
     *         {@code LicenseRef-...} as it is.
     */
    private static Optional<String> licenseExpression(String aDeclared)
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
            var loader = false;
            for (var entry = archive.getNextJarEntry(); entry != null; entry = archive
                    .getNextJarEntry()) {
                var name = entry.getName();
                if (name.endsWith(".jar") && LIBRARY_DIRS.stream().anyMatch(name::startsWith)) {
                    entries.add(scanJar(name, new CloseShieldInputStream(archive)));
                }
                else if (CLASSES_DIRS.stream().anyMatch(d -> name.equals(d + NPM_DEPENDENCIES))) {
                    classesNpm = archive.readAllBytes();
                }
                else if (CLASSES_DIRS.stream()
                        .anyMatch(d -> name.equals(d + EMBEDDED_DEPENDENCIES))) {
                    classesEmbedded = archive.readAllBytes();
                }
                else if (name.startsWith(SPRING_BOOT_LOADER_PACKAGE)) {
                    loader = true;
                }
            }

            if (classesNpm != null || classesEmbedded != null) {
                entries.add(new ScannedEntry(aArtifact + "!classes", false, null, classesNpm,
                        classesEmbedded));
            }

            return new ArtifactContent(springBootVersion, loader, entries);
        }
    }

    static ScannedEntry scanJar(String aPath, InputStream aJar) throws IOException
    {
        try (var jar = new JarInputStream(aJar)) {
            byte[] npmDependencies = null;
            byte[] embeddedDependencies = null;
            var pomProperties = new LinkedHashMap<String, Properties>();
            var pomXmls = new HashMap<String, byte[]>();
            for (var entry = jar.getNextJarEntry(); entry != null; entry = jar.getNextJarEntry()) {
                var name = entry.getName();
                if (NPM_DEPENDENCIES.equals(name)) {
                    npmDependencies = jar.readAllBytes();
                }
                else if (EMBEDDED_DEPENDENCIES.equals(name)) {
                    embeddedDependencies = jar.readAllBytes();
                }
                else if (POM_PROPERTIES.matcher(name).matches()) {
                    var properties = new Properties();
                    properties.load(new ByteArrayInputStream(jar.readAllBytes()));
                    pomProperties.put(name.substring(0, name.lastIndexOf('/')), properties);
                }
                else if (POM_XML.matcher(name).matches()) {
                    pomXmls.put(name.substring(0, name.lastIndexOf('/')), jar.readAllBytes());
                }
            }

            var pom = embeddedPom(aPath, pomProperties, pomXmls);
            return new ScannedEntry(aPath, true, pom, npmDependencies, embeddedDependencies);
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
    private Optional<String> resolveLicense(String aKey, List<String> aDeclared)
    {
        var concluded = conclusions.get(aKey);
        if (concluded != null) {
            usedConclusions.add(aKey);
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
        lines.sort(String::compareTo);
        write(output.resolve(DEPENDENCIES_FILE), lines, UTF_8);
    }

    private void writeNotice(List<Component> aMaven, List<SpdxPackage> aEmbedded) throws IOException
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
                embedded.append("* Originator: ").append(pkg.getOriginator()).append("\n");
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

        var cryptography = aMaven.stream()
                .anyMatch(c -> c.groupId() != null && CRYPTOGRAPHY_GROUP_IDS.contains(c.groupId()))
                        ? CRYPTOGRAPHY_STATEMENT
                        : "";

        var notice = readResource(NOTICE_TEMPLATE_RESOURCE) //
                .replace(PROP_EMBEDDED_CONTENT, embedded.toString().strip()) //
                .replace(PROP_CRYPTOGRAPHY, cryptography.strip());
        writeString(output.resolve(NOTICE_MD), notice.strip() + "\n", UTF_8);
    }

    /**
     * Loads the licenses we concluded for components whose own metadata does not state a usable
     * license. The file is an SPDX package list: each package identifies the component by a purl
     * without version, gives the concluded license with the evidence in its comment, and may name
     * the stem of the jar file in {@code packageFileName} for jars that carry no metadata at all.
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

            conclusions.add(new Conclusion(purl, license, pkg.getPackageFileName()));
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
     * A license we concluded for a component. The purl has no version. The file stem, if given,
     * identifies jars that carry no metadata, e.g. {@code hamcrest} for {@code hamcrest-3.0.jar}.
     */
    record Conclusion(String purl, String license, String fileStem) {
        String groupId()
        {
            return purlPart(0);
        }

        String artifactId()
        {
            return purlPart(1);
        }

        private String purlPart(int aIndex)
        {
            var parts = purl.substring("pkg:maven/".length()).split("/");
            return parts[aIndex];
        }
    }

    record Coordinates(String groupId, String artifactId, String version, List<String> licenses) {}

    record ArtifactContent(String springBootVersion, boolean springBootLoader,
            List<ScannedEntry> entries)
    {}

    /**
     * What was read from a library in the artifact, or from the application's own classes.
     */
    record ScannedEntry(String path, boolean library, Coordinates pom, byte[] npmDependencies,
            byte[] embeddedDependencies)
    {
        String fileName()
        {
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    /**
     * Lets a nested jar be read through its own stream without closing the archive around it.
     */
    private static final class CloseShieldInputStream
        extends FilterInputStream
    {
        CloseShieldInputStream(InputStream aIn)
        {
            super(aIn);
        }

        @Override
        public void close()
        {
            // The enclosing archive stays open
        }
    }

    record Component(String kind, String groupId, String name, String version, String license,
            String authority)
    {
        static Component maven(String aGroupId, String aArtifactId, String aVersion,
                String aLicense, String aAuthority)
        {
            return new Component("maven", aGroupId, aArtifactId, aVersion, aLicense, aAuthority);
        }

        static Component npm(String aName, String aVersion, String aLicense, String aAuthority)
        {
            return new Component("npm", null, aName, aVersion, aLicense, aAuthority);
        }

        String clearlyDefinedId()
        {
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
            if ("npm".equals(kind)) {
                return "pkg:npm/" + name.replace("@", "%40") + "@" + version;
            }
            return "pkg:maven/" + groupId + "/" + name + "@" + version;
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
