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
package de.tudarmstadt.ukp.inception.doc;

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.createDirectory;
import static java.nio.file.Files.createSymbolicLink;
import static java.nio.file.Files.exists;
import static org.apache.commons.io.FileUtils.copyFile;
import static org.apache.commons.io.FileUtils.deleteQuietly;
import static org.apache.commons.io.FileUtils.listFiles;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.io.filefilter.TrueFileFilter;
import org.asciidoctor.Asciidoctor;
import org.asciidoctor.Attributes;
import org.asciidoctor.Options;
import org.asciidoctor.Placement;
import org.asciidoctor.SafeMode;

public class GenerateDocumentation
{
    private static Path asciiDocPath = Paths.get("src", "main", "resources", "META-INF",
            "asciidoc");

    private static List<Path> getAsciiDocs(Path dir) throws IOException
    {
        return Files.list(dir).filter(Files::isDirectory) //
                .filter(p -> exists(p.resolve("pom.xml"))) //
                .map(p -> p.resolve(asciiDocPath)) //
                .filter(Files::isDirectory) //
                .toList();
    }

    private static void buildDoc(String type, Path outputDir) throws IOException
    {
        var attributes = Attributes.builder().attribute("source-dir", getInceptionDir() + "/")
                .attribute("include-dir",
                        outputDir.resolve("asciidoc").resolve(type).toString() + "/")
                .attribute("imagesdir",
                        outputDir.resolve("asciidoc").resolve(type).toString() + "/")
                .docType("book") //
                .attribute("toclevels", "8") //
                .setAnchors(true) //
                .attribute("docinfo1", "true") //
                .attribute("project-version", "DEVELOPER BUILD") //
                .attribute("revnumber", "DEVELOPER BUILD") //
                .attribute("product-name", "INCEpTION") //
                .attribute("product-formal-name", "Eclipse INCEpTION") //
                .attribute("min-java-version", getMinJavaVersion()) //
                .attribute("product-website-url", "https://eclipse-inception.github.io") //
                .attribute("copyright-year", Integer.toString(Year.now().getValue())) //
                .icons(Attributes.FONT_ICONS) //
                .tableOfContents(Placement.LEFT) //
                .experimental(true) //
                .build();
        var options = Options.builder() //
                .toDir(outputDir.toFile()) //
                .safe(SafeMode.UNSAFE) //
                .attributes(attributes);

        var asciidoctor = Asciidoctor.Factory.create();
        asciidoctor.requireLibrary("asciidoctor-diagram");

        var f = new File(outputDir.resolve("asciidoc").resolve(type).toString() + ".adoc");
        createDirectories(f.getParentFile().toPath());
        asciidoctor.convertFile(f, options.build());
    }

    public static void main(String... args) throws Exception
    {
        var inceptionDir = getInceptionDir();
        var outputDir = Paths.get(System.getProperty("user.dir")).resolve("target")
                .resolve("doc-out");

        deleteQuietly(outputDir.toFile());

        linkDocFiles(inceptionDir, outputDir);

        buildDoc("user-guide", outputDir);
        buildDoc("developer-guide", outputDir);
        buildDoc("admin-guide", outputDir);

        System.out.printf("Documentation written to: %s\n", outputDir);
    }

    private static void copyDocFiles(Path inceptionDir, Path outputDir) throws IOException
    {
        createDirectory(outputDir);
        var modules = new ArrayList<>(getAsciiDocs(inceptionDir));
        for (var module : modules) {
            System.out.printf("Including module: %s\n", module);

            for (var f : listFiles(module.toFile(), TrueFileFilter.INSTANCE,
                    TrueFileFilter.INSTANCE)) {
                var p = f.toPath();
                var targetPath = f.toPath().subpath(module.toAbsolutePath().getNameCount(),
                        p.toAbsolutePath().getNameCount());
                copyFile(f, outputDir.resolve("asciidoc").resolve(targetPath).toFile());
            }
        }
    }

    private static void linkDocFiles(Path inceptionDir, Path outputDir) throws IOException
    {
        // Create the output directory if it doesn't exist
        createDirectory(outputDir);

        // Get the list of module directories that contain AsciiDoc files
        var modules = new ArrayList<>(getAsciiDocs(inceptionDir));

        for (var module : modules) {
            System.out.printf("Including module: %s\n", module);

            // List all files in the current module
            for (var f : listFiles(module.toFile(), TrueFileFilter.INSTANCE,
                    TrueFileFilter.INSTANCE)) {
                if (Set.of(".DS_Store", ".asciidoctorconfig.adoc").contains(f.getName())) {
                    continue;
                }

                var p = f.toPath();
                var targetPath = f.toPath().subpath(module.toAbsolutePath().getNameCount(),
                        p.toAbsolutePath().getNameCount());
                var linkPath = outputDir.resolve("asciidoc").resolve(targetPath);

                // Create the parent directories for the linkPath if they don't exist
                createDirectories(linkPath.getParent());

                // Create a symbolic link pointing to the original file
                createSymbolicLink(linkPath, p.toAbsolutePath());
            }
        }
    }

    private static final Pattern MIN_JAVA_VERSION_PATTERN = Pattern
            .compile("<maven\\.compiler\\.release>\\s*(\\d+)\\s*</maven\\.compiler\\.release>");

    /**
     * Reads the minimum required Java version from the {@code maven.compiler.release} property in
     * the {@code inception/pom.xml}. This keeps the documentation in sync with the actual build
     * configuration without manual updates. The Maven-driven documentation build wires the same
     * property into the {@code min-java-version} AsciiDoc attribute via the asciidoctor plugin
     * configuration in {@code inception-app-webapp/pom.xml}.
     */
    private static String getMinJavaVersion() throws IOException
    {
        var pom = getInceptionDir().resolve("pom.xml");
        var matcher = MIN_JAVA_VERSION_PATTERN.matcher(Files.readString(pom));
        if (!matcher.find()) {
            throw new IllegalStateException(
                    "Could not find maven.compiler.release property in [" + pom + "]");
        }
        return matcher.group(1);
    }

    private static Path getInceptionDir()
    {
        var userDir = Paths.get(System.getProperty("user.dir"));
        return runningFromIntelliJ() ? userDir.resolve("inception") : userDir.getParent();
    }

    private static boolean runningFromIntelliJ()
    {
        return System.getenv().containsKey("INTELLIJ");
    }
}
