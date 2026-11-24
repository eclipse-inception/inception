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
package de.tudarmstadt.ukp.inception.app.config;

import static de.tudarmstadt.ukp.inception.app.config.InceptionSecurityWebUIShared.savedRequestMatcher;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class InceptionSecurityWebUISharedTest
{
    private static final String NAVIGATION_ACCEPT = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8";

    @Test
    void thatNavigationOverHttpsIsSaved()
    {
        var request = new MockHttpServletRequest("GET", "/p/project/annotate");
        request.addHeader("Sec-Fetch-Dest", "document");
        request.addHeader("Sec-Fetch-Mode", "navigate");
        request.addHeader("Accept", NAVIGATION_ACCEPT);

        assertThat(savedRequestMatcher().matches(request)).isTrue();
    }

    @Test
    void thatNavigationOverPlainHttpIsSaved()
    {
        // No fetch metadata headers: browsers send them only to secure origins
        var request = new MockHttpServletRequest("GET", "/p/project/annotate");
        request.addHeader("Accept", NAVIGATION_ACCEPT);

        assertThat(savedRequestMatcher().matches(request)).isTrue();
    }

    @Test
    void thatBackgroundImageFetchOverHttpsIsNotSaved()
    {
        var request = new MockHttpServletRequest("GET", "/favicon.ico");
        request.addHeader("Sec-Fetch-Dest", "image");
        request.addHeader("Sec-Fetch-Mode", "no-cors");
        request.addHeader("Accept", "*/*");

        assertThat(savedRequestMatcher().matches(request)).isFalse();
    }

    @Test
    void thatSafariIconLoaderFetchIsNotSaved()
    {
        // The Safari icon loader sends neither fetch metadata headers nor an HTML Accept header
        var request = new MockHttpServletRequest("GET", "/apple-touch-icon.png");
        request.addHeader("Accept", "*/*");

        assertThat(savedRequestMatcher().matches(request)).isFalse();
    }

    @Test
    void thatRequestWithoutAcceptHeaderIsNotSaved()
    {
        var request = new MockHttpServletRequest("GET", "/p/project/annotate");

        assertThat(savedRequestMatcher().matches(request)).isFalse();
    }

    @Test
    void thatPostIsNotSaved()
    {
        var request = new MockHttpServletRequest("POST", "/p/project/annotate");
        request.addHeader("Sec-Fetch-Dest", "document");
        request.addHeader("Accept", NAVIGATION_ACCEPT);

        assertThat(savedRequestMatcher().matches(request)).isFalse();
    }

    @Test
    void thatAjaxRequestIsNotSaved()
    {
        var request = new MockHttpServletRequest("GET", "/p/project/annotate");
        request.addHeader("X-Requested-With", "XMLHttpRequest");
        request.addHeader("Accept", NAVIGATION_ACCEPT);

        assertThat(savedRequestMatcher().matches(request)).isFalse();
    }

    @Test
    void thatWebsocketUpgradeIsNotSaved()
    {
        var request = new MockHttpServletRequest("GET", "/ws");
        request.addHeader("Upgrade", "websocket");
        request.addHeader("Accept", NAVIGATION_ACCEPT);

        assertThat(savedRequestMatcher().matches(request)).isFalse();
    }
}
