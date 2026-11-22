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

import static de.tudarmstadt.ukp.inception.support.about.KnownLicenses.toDisplayName;
import static de.tudarmstadt.ukp.inception.support.about.KnownLicenses.toSpdxId;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KnownLicensesTest
{
    @Test
    void thatDisplayNamesAreKeptForRecognisedAndRawTextForOtherDeclarations()
    {
        assertThat(toDisplayName(
                "Apache License, Version 2.0 (https://www.apache.org/licenses/LICENSE-2.0.txt)")) //
                        .isEqualTo(
                                "Apache License 2.0 (http://www.apache.org/licenses/LICENSE-2.0)");
        assertThat(toDisplayName("MIT")) //
                .isEqualTo("MIT (https://spdx.org/licenses/MIT#licenseText)");
        assertThat(toDisplayName("(MIT AND Apache-2.0)")).isEqualTo("(MIT AND Apache-2.0)");
        assertThat(toDisplayName("Public Domain")).isEqualTo("Public Domain");
        assertThat(toDisplayName(null)).isNull();
    }

    @Test
    void thatSpdxExpressionsAreKeptWithoutUrlAndOuterParentheses()
    {
        assertThat(toSpdxId("0BSD")).contains("0BSD");
        assertThat(toSpdxId("(MPL-2.0 OR Apache-2.0)")).contains("MPL-2.0 OR Apache-2.0");
        assertThat(
                toSpdxId("(CC-BY-4.0 AND OFL-1.1 AND MIT) (https://fontawesome.com/license/free)")) //
                        .contains("CC-BY-4.0 AND OFL-1.1 AND MIT");
    }

    @Test
    void thatFreeTextIsMappedByPatterns()
    {
        assertThat(toSpdxId("The Apache Software License, Version 2.0 "
                + "(http://www.apache.org/licenses/LICENSE-2.0.txt)")) //
                        .contains("Apache-2.0");
        assertThat(toSpdxId("GPL2 w/ CPE (https://www.gnu.org/software/classpath/license.html)")) //
                .contains("GPL-2.0-only WITH Classpath-exception-2.0");
    }

    @Test
    void thatUnknownDeclarationsAreNotGuessed()
    {
        // The pattern "MIT .*" must not reduce a compound expression to its known part
        assertThat(toSpdxId("MIT AND Some-Unknown-License")).isEmpty();
        assertThat(toSpdxId("Public Domain")).isEmpty();
        assertThat(toSpdxId(" ")).isEmpty();
        assertThat(toSpdxId(null)).isEmpty();
    }

    @Test
    void thatLicensesKnownForDualLicensesNeedAReviewOtherwise()
    {
        // Accepted: as an alternative, or GPL with the Classpath exception
        assertThat(toSpdxId("CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0"))
                .contains("CDDL-1.1 OR GPL-2.0-only WITH Classpath-exception-2.0");
        assertThat(toSpdxId("EPL-2.0 OR GPL-2.0-only WITH Classpath-exception-2.0")).isPresent();
        assertThat(toSpdxId("GPL-2.0-only WITH Classpath-exception-2.0")).isPresent();
        // Not accepted: on their own or combined otherwise
        assertThat(toSpdxId("GPL-2.0-only")).isEmpty();
        assertThat(toSpdxId("MIT OR GPL-2.0-only")).isEmpty();
        assertThat(toSpdxId("GPL-2.0-only AND Apache-2.0")).isEmpty();
        assertThat(toSpdxId("CDDL-1.1")).isEmpty();
        assertThat(toSpdxId("CDDL-1.0 AND MIT")).isEmpty();
    }
}
