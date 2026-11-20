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

import static java.nio.file.Files.createDirectories;
import static java.nio.file.Files.write;
import static java.util.regex.Pattern.CASE_INSENSITIVE;
import static java.util.regex.Pattern.compile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;

/**
 * The license and notice files of libraries that cannot be replaced by a deduplicated license text:
 * NOTICE files, license files that differ from the standard text of their license (e.g. with a
 * copyright line, or with the licenses of bundled code appended), and the license and notice files
 * of code bundled into a library. They are passed on unchanged, each library in a folder of its
 * own.
 */
class LibraryFiles
{
    static final String LIBRARIES_DIR = "LIBRARIES";

    /**
     * Files named like license, notice, copying, copyright or third-party files, or with "lic" as a
     * word of their name (hsqldb's {@code hsqldb_lic.txt}).
     */
    private static final Pattern LEGAL_FILE_NAME = compile(
            "(^|[-_.])(licen[cs]e|notice|copying|copyright|third[-_]?party|lic([-_.]|$))",
            CASE_INSENSITIVE);
    private static final Pattern LICENSE_FILE_NAME = compile(
            "licen[cs]e|copying|(^|[-_.])lic([-_.]|$)", CASE_INSENSITIVE);

    /**
     * Folders whose files are all license texts, whatever their names, e.g. byte-buddy's
     * {@code META-INF/licenses/ASM}.
     */
    private static final Pattern LICENSE_FOLDER = compile("licen[cs]es?", CASE_INSENSITIVE);

    /**
     * The legal files of Eclipse projects: {@code about.html} with the license terms and the
     * notices of bundled code, and the license texts it links to in {@code about_files/}.
     */
    private static final String ECLIPSE_ABOUT = "about.html";
    private static final String ECLIPSE_ABOUT_FILES = "about_files";

    /**
     * Files with such names that are not legal texts, e.g. an icon named copyright.svg, an XML
     * schema or HTML license templates of a license checker.
     */
    private static final Pattern NOT_A_LEGAL_TEXT = compile(
            ".*\\.(class|svg|png|gif|jpe?g|xsd|xml|json|js|css|properties|java)$",
            CASE_INSENSITIVE);

    /**
     * Leading folders that only say where a file sits in the jar, which the library's own folder
     * makes redundant: the {@code META-INF/} of most jars, and the
     * {@code META-INF/resources/webjars/<name>/<version>/} of WebJars. Folders below them are kept,
     * as they tell the code bundled into a library apart (postgresql's
     * {@code META-INF/licenses/com.ongres.scram/scram-client-3.2/META-INF/LICENSE}).
     */
    private static final Pattern REDUNDANT_PREFIX = compile(
            "^META-INF/(?:resources/webjars/[^/]+/[^/]+/)?");

    private final Map<String, Library> libraries = new TreeMap<>();

    /**
     * @return whether an entry of a jar is a license or notice file to consider.
     */
    static boolean isLegalFile(String aPath)
    {
        if (aPath.endsWith("/") || aPath.startsWith("META-INF/services/")) {
            return false;
        }
        // Entries meant to escape the folder they are copied to are not copied anywhere. A
        // backslash is a separator on Windows, where the installer is built.
        if (aPath.startsWith("/") || aPath.contains("\\")
                || List.of(aPath.split("/")).contains("..")) {
            return false;
        }
        var name = aPath.substring(aPath.lastIndexOf('/') + 1);
        if (NOT_A_LEGAL_TEXT.matcher(name).matches()) {
            return false;
        }
        return LEGAL_FILE_NAME.matcher(name).find() || name.equalsIgnoreCase(ECLIPSE_ABOUT)
                || folders(aPath).stream().anyMatch(f -> LICENSE_FOLDER.matcher(f).matches()
                        || f.equalsIgnoreCase(ECLIPSE_ABOUT_FILES));
    }

    /**
     * @return whether a file is a license file, as opposed to a notice. Only a license file can be
     *         replaced by a standard license text.
     */
    static boolean isLicenseFile(String aPath)
    {
        return LICENSE_FILE_NAME.matcher(aPath.substring(aPath.lastIndexOf('/') + 1)).find()
                || folders(aPath).stream().anyMatch(f -> LICENSE_FOLDER.matcher(f).matches());
    }

    private static List<String> folders(String aPath)
    {
        var segments = List.of(aPath.split("/"));
        return segments.subList(0, segments.size() - 1);
    }

    /**
     * @param aPath
     *            the path of the file in the library's jar or package.
     */
    void add(String aLibrary, String aFolder, String aPath, byte[] aContent)
    {
        libraries.computeIfAbsent(aFolder, f -> new Library(aLibrary, f, new TreeMap<>())).files()
                .put(aPath, aContent);
    }

    /**
     * @return the files of a library by the path to write them to, relative to the library's
     *         folder. The leading folders that only say where a file sits in the jar are dropped,
     *         unless two files would then end up at the same path (a {@code LICENSE} at the jar's
     *         root next to {@code META-INF/LICENSE}): then the library keeps the paths it has in
     *         the jar.
     */
    private static Map<String, byte[]> outputFiles(Library aLibrary)
    {
        var files = aLibrary.files();
        var shortened = new TreeMap<String, byte[]>();
        for (var file : files.entrySet()) {
            var path = REDUNDANT_PREFIX.matcher(file.getKey()).replaceFirst("");
            if (clashes(shortened.keySet(), path)) {
                shortened = null;
                break;
            }
            shortened.put(path, file.getValue());
        }
        if (shortened != null) {
            return shortened;
        }

        // Paths that differ only in case (META-INF/LICENSE and META-INF/license, or a file and a
        // folder) would overwrite each other on a case-insensitive file system such as macOS';
        // such a file goes into a numbered folder of its own instead
        var result = new LinkedHashMap<String, byte[]>();
        for (var file : files.entrySet()) {
            var path = file.getKey();
            for (var n = 2; clashes(result.keySet(), path); n++) {
                path = "case-" + n + "/" + file.getKey();
            }
            result.put(path, file.getValue());
        }
        return result;
    }

    private static boolean clashes(Set<String> aExisting, String aPath)
    {
        var lower = aPath.toLowerCase(Locale.ROOT);
        for (var existing : aExisting) {
            var other = existing.toLowerCase(Locale.ROOT);
            if (other.equals(lower) || other.startsWith(lower + "/")
                    || lower.startsWith(other + "/")) {
                return true;
            }
        }
        return false;
    }

    boolean isEmpty()
    {
        return libraries.isEmpty();
    }

    String toMarkdown()
    {
        var md = new StringBuilder();
        for (var library : libraries.values()) {
            md.append("* ").append(library.name()).append(": ").append(LIBRARIES_DIR).append("/")
                    .append(library.folder()).append("\n");
        }
        return md.toString().strip();
    }

    /**
     * @return the paths of the written files, relative to the output directory.
     */
    List<String> writeTo(Path aOutput) throws IOException
    {
        var files = new ArrayList<String>();
        for (var library : libraries.values()) {
            for (var file : outputFiles(library).entrySet()) {
                var path = LIBRARIES_DIR + "/" + library.folder() + "/" + file.getKey();
                var target = aOutput.resolve(path);
                createDirectories(target.getParent());
                write(target, file.getValue());
                files.add(path);
            }
        }
        return files;
    }

    private record Library(String name, String folder, Map<String, byte[]> files) {}
}
