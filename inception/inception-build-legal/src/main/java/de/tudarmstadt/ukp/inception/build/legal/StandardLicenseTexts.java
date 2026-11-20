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

import static de.tudarmstadt.ukp.inception.build.legal.LegalDocumentationGenerator.LICENSE_TEXTS_RESOURCE;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.regex.Pattern.compile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Tells whether a license file of a library is just the standard text of one of its licenses, so
 * that the deduplicated text in {@code LICENSES/} stands in for it. Like the SPDX matching
 * guidelines, the comparison ignores case, punctuation, whitespace and the URL scheme, so that e.g.
 * "[yyyy]" and "{yyyy}", or http and https links, do not matter. After the terms of a license ("END
 * OF TERMS AND CONDITIONS", as in the Apache License), only the standard appendix on how to apply
 * the license may follow, or nothing. Anything else is kept: the appended licenses of bundled code,
 * or an appendix whose placeholder was filled in with a real copyright line.
 */
class StandardLicenseTexts
{
    private static final Pattern NON_WORD = compile("[^a-z0-9]+");
    private static final String END_OF_TERMS = "end of terms and conditions";

    private final Map<String, String> canonical = new HashMap<>();

    /**
     * @return whether the text is the standard text of one of the given SPDX license ids.
     */
    boolean isStandard(byte[] aText, Iterable<String> aLicenseIds) throws IOException
    {
        var text = normalize(new String(aText, UTF_8));
        for (var id : aLicenseIds) {
            var standard = canonical(id);
            if (standard != null && matches(standard, text)) {
                return true;
            }
        }
        return false;
    }

    static boolean matches(String aStandard, String aText)
    {
        if (aStandard.equals(aText)) {
            return true;
        }

        var standardEnd = aStandard.indexOf(END_OF_TERMS);
        var textEnd = aText.indexOf(END_OF_TERMS);
        if (standardEnd < 0 || textEnd < 0) {
            return false;
        }

        if (!aStandard.substring(0, standardEnd).equals(aText.substring(0, textEnd))) {
            return false;
        }

        // Compared by content, not by length: a short license appended instead of the appendix
        // must not pass as a variant of it
        var textTail = aText.substring(textEnd);
        return textTail.equals(END_OF_TERMS) || textTail.equals(aStandard.substring(standardEnd));
    }

    static String normalize(String aText)
    {
        // As in the SPDX matching guidelines, "©" and "(c)" are the same
        var text = aText.toLowerCase(Locale.ROOT).replace("https://", "http://").replace("©",
                "(c)");
        return NON_WORD.matcher(text).replaceAll(" ").strip();
    }

    private String canonical(String aId) throws IOException
    {
        if (!canonical.containsKey(aId)) {
            try (var is = StandardLicenseTexts.class
                    .getResourceAsStream(LICENSE_TEXTS_RESOURCE + aId + ".txt")) {
                canonical.put(aId,
                        is == null ? null : normalize(new String(is.readAllBytes(), UTF_8)));
            }
        }
        return canonical.get(aId);
    }
}
