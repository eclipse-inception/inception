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

import static java.util.Locale.ROOT;
import static org.springframework.http.HttpMethod.GET;

import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;
import org.springframework.security.web.savedrequest.HttpSessionRequestCache;
import org.springframework.security.web.util.matcher.AndRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;
import org.springframework.security.web.util.matcher.RequestHeaderRequestMatcher;
import org.springframework.security.web.util.matcher.RequestMatcher;

import jakarta.servlet.http.HttpServletRequest;

public class InceptionSecurityWebUIShared
{
    public static void requestCache(HttpSecurity aHttp) throws Exception
    {
        var requestCache = new HttpSessionRequestCache();
        requestCache.setRequestMatcher(savedRequestMatcher());
        aHttp.requestCache(cache -> cache.requestCache(requestCache));
    }

    static RequestMatcher savedRequestMatcher()
    {
        return new AndRequestMatcher( //
                request -> GET.matches(request.getMethod()), //
                new NegatedRequestMatcher(
                        new RequestHeaderRequestMatcher("X-Requested-With", "XMLHttpRequest")),
                new NegatedRequestMatcher(new RequestHeaderRequestMatcher("Upgrade", "websocket")),
                InceptionSecurityWebUIShared::isPageNavigation);
    }

    private static boolean isPageNavigation(HttpServletRequest aRequest)
    {
        // Browsers send the fetch metadata headers only to secure origins (HTTPS, localhost).
        var fetchDest = aRequest.getHeader("Sec-Fetch-Dest");
        if (fetchDest != null) {
            return "document".equals(fetchDest);
        }

        // Over plain HTTP, fall back to the Accept header. Browsers explicitly ask for HTML when
        // navigating, while background fetches like the Safari icon loader send "*/*".
        var accept = aRequest.getHeader("Accept");
        return accept != null && accept.toLowerCase(ROOT).contains("text/html");
    }

    public static void accessToStaticResources(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry aCfg)
    {
        // Resources need to be publicly accessible so they don't trigger the login
        // page. Otherwise it could happen that the user is redirected to a resource
        // upon login instead of being forwarded to a proper application page.
        aCfg //
                .requestMatchers("/favicon.ico").permitAll() //
                .requestMatchers("/favicon.png").permitAll() //
                .requestMatchers("/apple-touch-icon.png").permitAll() //
                .requestMatchers("/apple-touch-icon-precomposed.png").permitAll() //
                .requestMatchers("/assets/**").permitAll() //
                .requestMatchers("/images/**").permitAll() //
                .requestMatchers("/resources/**").permitAll() //
                .requestMatchers("/whoops").permitAll() //
                .requestMatchers("/nowhere").permitAll() //
                .requestMatchers("/about/**").permitAll() //
                .requestMatchers("/wicket/resource/**").permitAll();
    }

    public static void accessToRemoteApiAndSwagger(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry aCfg)
    {
        aCfg //
                .requestMatchers("/swagger-ui/**").hasAnyRole("REMOTE") //
                .requestMatchers("/swagger-ui.html").hasAnyRole("REMOTE") //
                .requestMatchers("/v3/**") //
                .hasAnyRole("REMOTE");
    }

    public static void accessToApplication(
            AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry aCfg)
    {
        aCfg //
                .requestMatchers("/admin/**").hasAnyRole("ADMIN") //
                .requestMatchers("/doc/**").hasAnyRole("ADMIN", "USER") //
                .requestMatchers("/**").hasAnyRole("ADMIN", "USER");
    }
}
