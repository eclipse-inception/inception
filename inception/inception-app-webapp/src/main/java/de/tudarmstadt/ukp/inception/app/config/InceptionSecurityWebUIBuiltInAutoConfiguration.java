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

import static de.tudarmstadt.ukp.clarin.webanno.ui.core.page.ProjectPageBase.NS_PROJECT;
import static de.tudarmstadt.ukp.inception.app.config.InceptionSecurityWebUIShared.accessToApplication;
import static de.tudarmstadt.ukp.inception.app.config.InceptionSecurityWebUIShared.accessToRemoteApiAndSwagger;
import static de.tudarmstadt.ukp.inception.app.config.InceptionSecurityWebUIShared.accessToStaticResources;
import static de.tudarmstadt.ukp.inception.app.config.InceptionSecurityWebUIShared.requestCache;
import static de.tudarmstadt.ukp.inception.support.logging.BaseLoggers.BOOT_LOG;

import java.util.Optional;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.saml2.provider.service.authentication.OpenSaml5AuthenticationProvider;
import org.springframework.security.saml2.provider.service.metadata.OpenSaml5MetadataResolver;
import org.springframework.security.saml2.provider.service.registration.RelyingPartyRegistrationRepository;
import org.springframework.security.saml2.provider.service.web.DefaultRelyingPartyRegistrationResolver;
import org.springframework.security.saml2.provider.service.web.Saml2MetadataFilter;
import org.springframework.security.saml2.provider.service.web.authentication.Saml2WebSsoAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.LoginUrlAuthenticationEntryPoint;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;
import org.springframework.security.web.util.matcher.NegatedRequestMatcher;

import de.tudarmstadt.ukp.inception.security.oauth.OAuth2Adapter;
import de.tudarmstadt.ukp.inception.security.saml.Saml2Adapter;

@ConditionalOnWebApplication
@ConditionalOnProperty(name = "auth.mode", havingValue = "database", matchIfMissing = true)
@EnableWebSecurity
public class InceptionSecurityWebUIBuiltInAutoConfiguration
{
    static {
        BOOT_LOG.info("Authentication: database");
    }

    @Bean
    public SecurityFilterChain webUiFilterChain(HttpSecurity aHttp,
            SessionRegistry aSessionRegistry, OAuth2Adapter aOAuth2Handling,
            Saml2Adapter aSaml2Handling,
            Optional<ClientRegistrationRepository> aClientRegistrationRepository,
            Optional<RelyingPartyRegistrationRepository> aRelyingPartyRegistrationRepository)
        throws Exception
    {
        aHttp.csrf(csrf -> {
            // Instead of disabling the Spring CSRF filter, we just disable the CSRF validation for
            // the Wicket UI (Wicket has its own CSRF mechanism). This way, Spring will still
            // populate the CSRF token attribute in the request which we will need later when we
            // need to provide the token to the JavaScript code in the browser to make callbacks to
            // Spring MVC controllers.
            csrf.requireCsrfProtectionMatcher(
                    new NegatedRequestMatcher(AnyRequestMatcher.INSTANCE));
        });
        aHttp.headers(headers -> {
            headers.frameOptions(frameOptions -> {
                frameOptions.sameOrigin();
            });
        });

        aHttp.authorizeHttpRequests(authz -> {
            authz.requestMatchers("/login.html*").permitAll();
            accessToStaticResources(authz);
            accessToRemoteApiAndSwagger(authz);
            authz.requestMatchers(NS_PROJECT + "/*/join-project/**").permitAll();
            accessToApplication(authz);
            authz.anyRequest().denyAll();
        });

        // Must use "defaultAuthenticationEntryPointFor" instead of "formLogin" because
        // if we use formLogin, Spring will handle the form submit and we want the Wicket
        // login page to handle the form submit instead!
        // .formLogin(form -> form.loginPage("/login.html").permitAll())
        aHttp.exceptionHandling(exceptions -> exceptions.defaultAuthenticationEntryPointFor(
                new LoginUrlAuthenticationEntryPoint("/login.html"), request -> true));

        requestCache(aHttp);

        if (aClientRegistrationRepository.isPresent()) {
            aHttp.oauth2Login(oauth2 -> oauth2 //
                    .loginPage("/login.html") //
                    .userInfoEndpoint(userInfo -> userInfo //
                            .oidcUserService(aOAuth2Handling::loadOidcUser) //
                            .userService(aOAuth2Handling::loadUserOAuth2User)));
        }

        if (aRelyingPartyRegistrationRepository.isPresent()) {
            var relyingPartyRegistrationResolver = //
                    new DefaultRelyingPartyRegistrationResolver(
                            aRelyingPartyRegistrationRepository.get());
            var filter = new Saml2MetadataFilter(relyingPartyRegistrationResolver,
                    new OpenSaml5MetadataResolver());
            aHttp.addFilterBefore(filter, Saml2WebSsoAuthenticationFilter.class);

            aHttp.saml2Login(saml2 -> {
                saml2.loginPage("/login.html");

                // Configure the authentication provider
                var authProvider = new OpenSaml5AuthenticationProvider();
                var converter = OpenSaml5AuthenticationProvider
                        .createDefaultResponseAuthenticationConverter();
                authProvider.setResponseAuthenticationConverter(token -> {
                    var authentication = converter.convert(token);
                    authentication = aSaml2Handling.process(token, authentication);
                    return authentication;
                });

                saml2.authenticationManager(authProvider::authenticate);
            });
        }

        aHttp.sessionManagement(session -> session
                // Configuring an unlimited session per-user maximum as a side-effect registers
                // the ConcurrentSessionFilter which checks for valid sessions in the session
                // registry. This allows us to indirectly invalidate a server session by marking
                // its Spring-security registration as invalid and have Spring Security in turn
                // mark the server session as invalid on the next request. This is used e.g. to
                // force-sign-out users that are being deleted.
                .maximumSessions(-1) //
                .sessionRegistry(aSessionRegistry));

        return aHttp.build();
    }
}
