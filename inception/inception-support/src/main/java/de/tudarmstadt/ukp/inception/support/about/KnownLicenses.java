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
package de.tudarmstadt.ukp.inception.support.about;

import static java.util.Arrays.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Maps the free-text license declarations found in Maven POMs, npm packages and our embedded SPDX
 * declarations to SPDX license identifiers, and to the display names shown on the about page.
 */
public final class KnownLicenses
{
    private static final Map<String, KnownLicense> LICENSES = new LinkedHashMap<>();

    private static final Set<String> EXCEPTIONS = Set.of("Classpath-exception-2.0");

    private static final Set<String> OPERATORS = Set.of("AND", "OR", "WITH");

    private static final Pattern TRAILING_URL = Pattern.compile("^(.+?) \\((https?://[^)]*)\\)$");

    private static final Pattern EXPRESSION_TOKEN = Pattern.compile("[()]|[^\\s()]+");

    private static final Pattern COMPOUND = Pattern.compile(" (AND|OR|WITH) ");

    static {
        add("Apache-2.0", "Apache License 2.0 (http://www.apache.org/licenses/LICENSE-2.0)", //
                "ASF 2\\.0.*", //
                "Apache-2\\.0.*", //
                "Apache 2 .*", //
                "Apache 2\\.0.*", //
                ".*Apache License 2\\.0.*", //
                ".*Apache License.*Version 2\\.0.*", //
                ".* Apache Software License.*Version 2\\.0.*", //
                ".*://www\\.apache\\.org/licenses/LICENSE-2\\.0.*");
        add("ISC", "ISC License (https://spdx.org/licenses/ISC#licenseText)", //
                "ISC");
        add("EPL-1.0",
                "Eclipse Public License 1.0 (https://www.eclipse.org/org/documents/epl-1.0/EPL-1.0.txt)",
                "Eclipse Public License.*1\\.0.*");
        add("EPL-2.0",
                "Eclipse Public License 2.0 (https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt)",
                "Eclipse Public License.*2\\.0.*", //
                "EPL 2\\.0.*", //
                "EPL-2\\.0.*");
        // The Bouncy Castle licence is the MIT license text with the Bouncy Castle copyright;
        // ClearlyDefined and the Eclipse Dash license tool record it as MIT.
        add("MIT", "MIT (https://spdx.org/licenses/MIT#licenseText)", //
                "MIT", //
                "MIT .*", //
                ".*MIT License.*", //
                ".*://www\\.bouncycastle\\.org/licen[cs]e.*");
        add("MIT-0", "MIT No Attribution (https://spdx.org/licenses/MIT-0.html)");
        add("0BSD", "Zero-Clause BSD (https://spdx.org/licenses/0BSD.html)");
        // According to the SPDX folks EDL 1.0 is essentially BSD-3-Clause, so there is no SPDX for
        // EDL 1.0. So we fold it into BSD-3-Clause as well.
        // https://lists.spdx.org/g/Spdx-legal/topic/request_for_adding_eclipse/67981884
        // The Go license (re2j), the HSQLDB license, and the BSD licenses that jzlib and
        // TwelveMonkeys point to are BSD-3-Clause texts.
        add("BSD-3-Clause", "BSD 3-Clause (https://spdx.org/licenses/BSD 3-Clause#licenseText)", //
                "(?i).*BSD.3.Clause.*", //
                ".*://raw.github.com/jsonld-java/jsonld-java/master/LICENCE.*", //
                ".*://www.antlr.org/license.html.*", //
                ".*://raw.githubusercontent.com/jaxen-xpath/jaxen/master/LICENSE.txt.*", //
                "Eclipse Distribution License.*", //
                "EDL 1\\.0.*", //
                ".*://golang\\.org/LICENSE.*", //
                "HSQLDB License.*", //
                ".*://www\\.jcraft\\.com/jzlib/LICENSE\\.txt.*", //
                ".*://github\\.com/haraldk/TwelveMonkeys#license.*");
        add("BSD-2-Clause", "BSD 2-Clause (https://spdx.org/licenses/BSD-2-Clause#licenseText)", //
                ".*BSD.2.Clause.*", //
                ".*://www.opensource.org/licenses/bsd-license.*");
        add("LGPL-2.1-only", "LGPL 2.1 (http://www.gnu.org/licenses/lgpl-2.1.html)", //
                "LGPL-2\\.1", //
                "LGPL 2\\.1 .*");
        add("LGPL-2.1-or-later", "LGPL 2.1 or later (http://www.gnu.org/licenses/lgpl-2.1.html)", //
                "GNU Library General Public License v2.1 or later.*");
        // Not the bare http://www.gnu.org/licenses/lgpl.html: it always points to the current
        // version, so it says nothing about the version a component is licensed under
        add("LGPL-3.0-only", "LGPL 3.0 (https://www.gnu.org/licenses/lgpl-3.0.html)", //
                "GNU Lesser General Public License, Version 3 .*", //
                "GNU LGPL Version 3\\.0.*", //
                ".*://www\\.gnu\\.org/licenses/lgpl-3\\.0\\.txt.*");
        add("MPL-1.1", "Mozilla Public License 1.1 (http://www.mozilla.org/MPL/MPL-1.1.html)", //
                "MPL 1.1.*");
        add("MPL-2.0", "Mozilla Public License 2.0 (http://www.mozilla.org/MPL/2.0/index.txt)", //
                "MPL 2.0.*", //
                "Mozilla Public License.*2\\.0.*");
        add("CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0",
                "CDDL + GPLv2 with classpath exception (https://oss.oracle.com/licenses/CDDL+GPL-1.1)",
                "CDDL \\+ GPLv2 with classpath exception.*", //
                "CDDL/GPLv2\\+CE.*", //
                ".*://oss.oracle.com/licenses/CDDL\\+GPL-1.1.*", //
                "CDDL\\+GPL License.*", //
                "Dual license consisting of the CDDL v1\\.1 and GPL v2.*");
        add("GPL-2.0-only WITH Classpath-exception-2.0",
                "GPL 2.0 with Classpath Exception (https://www.gnu.org/software/classpath/license.html)",
                "GNU General Public License, version 2 with the GNU Classpath Exception.*", //
                "GPL-2\\.0-with-classpath-exception.*", //
                "GPL2 w/ CPE.*");
        add("CC0-1.0", "CC0 1.0 (https://creativecommons.org/publicdomain/zero/1.0/)", //
                ".*://creativecommons\\.org/publicdomain/zero/1\\.0.*");
        add("CC-BY-2.5", "CC BY 2.5 (https://creativecommons.org/licenses/by/2.5/)");
        add("CC-BY-3.0", "CC BY 3.0 (https://creativecommons.org/licenses/by/3.0/)");
        add("CC-BY-4.0", "CC BY 4.0 (https://creativecommons.org/licenses/by/4.0/)");
        add("OFL-1.1", "SIL Open Font License 1.1 (https://openfontlicense.org)");
        add("Unicode-3.0", "Unicode License v3 (https://spdx.org/licenses/Unicode-3.0.html)");
    }

    private KnownLicenses()
    {
        // No instances
    }

    private static void add(String aSpdxId, String aDisplayName, String... aPatterns)
    {
        var patterns = stream(aPatterns) //
                .map(Pattern::compile) //
                .toList();
        LICENSES.put(aSpdxId, new KnownLicense(aSpdxId, aDisplayName, patterns));
    }

    /**
     * @param aText
     *            a license declaration as found in a POM, an npm package or an SPDX declaration.
     * @return the SPDX license identifier or expression for the declaration, or nothing if it is
     *         not recognised.
     */
    public static Optional<String> toSpdxId(String aText)
    {
        if (aText == null || aText.isBlank()) {
            return Optional.empty();
        }

        var text = aText.trim();

        var expression = asKnownExpression(text);
        if (expression.isPresent()) {
            return expression;
        }

        // A compound expression with an unknown part must not be reduced to its known part by a
        // pattern such as "MIT .*"
        if (COMPOUND.matcher(text).find()) {
            return Optional.empty();
        }

        return matchPattern(text).map(KnownLicense::spdxId);
    }

    /**
     * @param aText
     *            a license declaration as found in a POM, an npm package or an SPDX declaration.
     * @return the display name of a recognised single license, otherwise the text unchanged.
     */
    public static String toDisplayName(String aText)
    {
        if (aText == null) {
            return null;
        }

        return toSpdxId(aText) //
                .map(LICENSES::get) //
                .map(KnownLicense::displayName) //
                .orElse(aText);
    }

    private static Optional<KnownLicense> matchPattern(String aText)
    {
        for (var license : LICENSES.values()) {
            if (license.patterns().stream().anyMatch(p -> p.matcher(aText).matches())) {
                return Optional.of(license);
            }
        }

        return Optional.empty();
    }

    /**
     * Accepts the text, or the text without a trailing URL in parentheses, if it is an SPDX license
     * expression whose license and exception identifiers are all known. Checking this before the
     * patterns keeps a compound expression such as {@code MIT AND Apache-2.0} from being collapsed
     * by a pattern like {@code MIT .*}.
     */
    private static Optional<String> asKnownExpression(String aText)
    {
        var candidates = new ArrayList<String>();
        candidates.add(aText);
        var withUrl = TRAILING_URL.matcher(aText);
        if (withUrl.matches()) {
            candidates.add(withUrl.group(1).trim());
        }

        for (var candidate : candidates) {
            if (isKnownExpression(candidate)) {
                return Optional.of(stripOuterParentheses(candidate));
            }
        }

        return Optional.empty();
    }

    private static boolean isKnownExpression(String aCandidate)
    {
        var tokens = new ArrayList<String>();
        var matcher = EXPRESSION_TOKEN.matcher(aCandidate);
        while (matcher.find()) {
            tokens.add(matcher.group());
        }

        if (tokens.isEmpty()) {
            return false;
        }

        var depth = 0;
        var expectOperand = true;
        String previous = null;
        for (var token : tokens) {
            if ("(".equals(token)) {
                if (!expectOperand) {
                    return false;
                }
                depth++;
            }
            else if (")".equals(token)) {
                if (expectOperand || depth == 0) {
                    return false;
                }
                depth--;
            }
            else if (OPERATORS.contains(token)) {
                if (expectOperand) {
                    return false;
                }
                expectOperand = true;
            }
            else {
                if (!expectOperand) {
                    return false;
                }
                var known = "WITH".equals(previous) ? EXCEPTIONS.contains(token)
                        : isSingleLicenseId(token);
                if (!known) {
                    return false;
                }
                expectOperand = false;
            }
            previous = token;
        }

        return depth == 0 && !expectOperand;
    }

    private static boolean isSingleLicenseId(String aToken)
    {
        // Entries keyed by a compound expression are not single identifiers
        return LICENSES.containsKey(aToken) && !aToken.contains(" ");
    }

    private static String stripOuterParentheses(String aExpression)
    {
        var expression = aExpression;
        while (expression.startsWith("(") && expression.endsWith(")")
                && isKnownExpression(expression.substring(1, expression.length() - 1))) {
            expression = expression.substring(1, expression.length() - 1).trim();
        }
        return expression;
    }

    private record KnownLicense(String spdxId, String displayName, List<Pattern> patterns) {}
}
