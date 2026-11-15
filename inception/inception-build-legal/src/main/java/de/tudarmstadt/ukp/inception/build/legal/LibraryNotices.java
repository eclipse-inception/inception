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
import static java.util.Comparator.comparing;
import static java.util.regex.Pattern.compile;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Pattern;

/**
 * Collects the NOTICE files of the libraries in a distribution and renders each distinct text once,
 * preceded by the libraries that carry it. Texts that differ only in whitespace count as the same.
 * Libraries of one project often carry the same notice, e.g. all Spring Boot or OpenSearch jars.
 */
class LibraryNotices
{
    private static final Pattern WHITESPACE = compile("\\s+");
    private static final Pattern BACKTICKS = compile("`+");

    private final Map<String, Notice> notices = new HashMap<>();

    void add(String aLibrary, String aText)
    {
        var text = aText.replace("\uFEFF", "") //
                .replace("\r\n", "\n") //
                .replace('\r', '\n') //
                .strip();
        if (text.isEmpty()) {
            return;
        }

        var key = WHITESPACE.matcher(text).replaceAll(" ");
        notices.computeIfAbsent(key, k -> new Notice(text, new TreeSet<>())).libraries()
                .add(aLibrary);
    }

    boolean isEmpty()
    {
        return notices.isEmpty();
    }

    /**
     * Each text goes into a code block: many notices are Markdown themselves, and their headings
     * and comments would otherwise become part of the structure of the NOTICE file.
     */
    String toMarkdown()
    {
        var sorted = notices.values().stream() //
                .sorted(comparing((Notice n) -> n.libraries().first())) //
                .toList();

        var md = new StringBuilder();
        for (var notice : sorted) {
            for (var library : notice.libraries()) {
                md.append("* ").append(library).append("\n");
            }
            var fence = fence(notice.text());
            md.append("\n") //
                    .append(fence).append("text\n") //
                    .append(notice.text()).append("\n") //
                    .append(fence).append("\n\n");
        }
        return md.toString().strip();
    }

    /**
     * @return a code fence longer than any run of backticks in the text, so the text cannot close
     *         it.
     */
    private static String fence(String aText)
    {
        var longest = BACKTICKS.matcher(aText).results() //
                .mapToInt(r -> r.group().length()) //
                .max() //
                .orElse(0);
        return "`".repeat(Math.max(3, longest + 1));
    }

    /**
     * Decodes a NOTICE file as UTF-8, or as ISO-8859-1 if it is not valid UTF-8, which older files
     * with names like "Universität" sometimes are.
     */
    static String decode(byte[] aBytes)
    {
        try {
            // A fresh decoder reports malformed input instead of replacing it
            return UTF_8.newDecoder().decode(ByteBuffer.wrap(aBytes)).toString();
        }
        catch (CharacterCodingException e) {
            return new String(aBytes, ISO_8859_1);
        }
    }

    private record Notice(String text, TreeSet<String> libraries) {}
}
