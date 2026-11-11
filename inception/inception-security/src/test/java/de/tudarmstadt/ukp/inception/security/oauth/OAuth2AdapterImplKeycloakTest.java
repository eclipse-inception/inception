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
package de.tudarmstadt.ukp.inception.security.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE;
import static org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType.BEARER;

import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import de.tudarmstadt.ukp.clarin.webanno.security.UserDao;
import de.tudarmstadt.ukp.clarin.webanno.security.config.InceptionSecurityAutoConfiguration;
import de.tudarmstadt.ukp.clarin.webanno.security.config.SecurityAutoConfiguration;
import de.tudarmstadt.ukp.clarin.webanno.security.model.Role;
import de.tudarmstadt.ukp.inception.support.deployment.DeploymentModeService;

/**
 * End-to-end integration tests for OAuth2 role mapping that use a real Keycloak instance running in
 * a Docker Testcontainer.
 * <p>
 * Unlike {@link OAuth2AdapterImplRoleMappingTest} (which stubs the OIDC token directly), these
 * tests obtain a genuine signed JWT from Keycloak via the Resource Owner Password Credentials
 * grant. This exercises the full token-decoding stack, including the Nimbus JOSE+JWT library that
 * produces {@code net.minidev.json.JSONArray} for non-standard array claims like {@code groups}.
 * <p>
 * The realm configuration is loaded from
 * {@code src/test/resources/keycloak/inception-security-test-realm.json}.
 */
@Transactional
@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles(DeploymentModeService.PROFILE_AUTH_MODE_DATABASE)
@DataJpaTest( //
        showSql = false, //
        properties = { //
                "spring.liquibase.enabled=false", //
                "spring.main.banner-mode=off", //
                "security.oauth2.roles.enabled=true", //
                "security.oauth2.roles.claim=groups", //
                "security.oauth2.roles.admin=/INCEPTION_ADMIN", //
                "security.oauth2.roles.user=/INCEPTION_USER", //
                "security.oauth2.roles.project-creator=/INCEPTION_PROJECT_CREATOR", //
                "security.oauth2.roles.remote=/INCEPTION_REMOTE" })
@ImportAutoConfiguration({ //
        SecurityAutoConfiguration.class, //
        InceptionSecurityAutoConfiguration.class })
@EntityScan(basePackages = { //
        "de.tudarmstadt.ukp.clarin.webanno.security.model" })
class OAuth2AdapterImplKeycloakTest
{
    private static final String REALM = "inception-security-test";
    private static final String CLIENT_ID = "inception-security-test-client";
    private static final String CLIENT_SECRET = "test-secret";

    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("keycloak/keycloak:26.8") //
            .withRealmImportFile("keycloak/inception-security-test-realm.json");

    @Autowired
    UserDao userService;

    @Autowired
    OAuth2Adapter sut;

    @BeforeAll
    static void waitForKeycloak()
    {
        // Container startup waits for Keycloak readiness automatically.
        // Verify the realm was imported successfully via the public OIDC discovery endpoint
        // (avoids relying on the admin-cli ROPC grant which is disabled by default in Keycloak
        // 26+).
        var discoveryUrl = keycloak.getAuthServerUrl() + "/realms/" + REALM
                + "/.well-known/openid-configuration";
        var discovery = RestClient.create().get() //
                .uri(discoveryUrl) //
                .retrieve() //
                .body(Map.class);
        assertThat(discovery) //
                .as("OIDC discovery for realm '%s' should be available after import", REALM) //
                .isNotNull() //
                .containsKey("issuer");
    }

    @Test
    void thatAdminRoleIsAssignedFromRealKeycloakGroupsClaim()
    {
        var oidcUser = loginAs("admin-user", "password");

        assertThat((Object) oidcUser.getAttribute("groups")) //
                .as("groups claim should be present in the Keycloak token") //
                .isNotNull();

        assertThat(userService.get("admin-user").getRoles()) //
                .containsExactlyInAnyOrder(Role.ROLE_ADMIN);
    }

    @Test
    void thatUserRoleIsAssignedFromRealKeycloakGroupsClaim()
    {
        var oidcUser = loginAs("regular-user", "password");

        assertThat(userService.get("regular-user").getRoles()) //
                .containsExactlyInAnyOrder(Role.ROLE_USER);
    }

    @Test
    void thatAccessIsDeniedWhenGroupsClaimHasNoMatchingRole()
    {
        assertThatExceptionOfType(AccessDeniedException.class) //
                .isThrownBy(() -> loginAs("unknown-groups-user", "password"));
    }

    // ---- helpers ----------------------------------------------------------------

    /**
     * Obtains a real OIDC token from Keycloak using the ROPC grant and then drives the adapter's
     * {@link OAuth2Adapter#loadOidcUser} method, just as Spring Security would after the
     * authorization-code callback.
     */
    private OidcUser loginAs(String aUsername, String aPassword)
    {
        var tokenResponse = fetchTokenFromKeycloak(aUsername, aPassword);

        var accessToken = new OAuth2AccessToken(BEARER, //
                (String) tokenResponse.get("access_token"), //
                Instant.now(), //
                Instant.now().plusSeconds(((Number) tokenResponse.get("expires_in")).longValue()));

        var idTokenValue = (String) tokenResponse.get("id_token");
        var jwksUri = keycloak.getAuthServerUrl() + "/realms/" + REALM
                + "/protocol/openid-connect/certs";
        var jwt = NimbusJwtDecoder //
                .withJwkSetUri(jwksUri) //
                .build() //
                .decode(idTokenValue);
        var idToken = new OidcIdToken(idTokenValue, jwt.getIssuedAt(), jwt.getExpiresAt(),
                jwt.getClaims());

        return sut.loadOidcUser(new OidcUserRequest(clientRegistration(), accessToken, idToken));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> fetchTokenFromKeycloak(String aUsername, String aPassword)
    {
        var tokenUrl = keycloak.getAuthServerUrl() + "/realms/" + REALM
                + "/protocol/openid-connect/token";

        var formParams = new LinkedMultiValueMap<String, String>();
        formParams.add("grant_type", "password");
        formParams.add("client_id", CLIENT_ID);
        formParams.add("client_secret", CLIENT_SECRET);
        formParams.add("username", aUsername);
        formParams.add("password", aPassword);
        formParams.add("scope", "openid");

        var restClient = RestClient.create();
        return restClient.post() //
                .uri(tokenUrl) //
                .contentType(MediaType.APPLICATION_FORM_URLENCODED) //
                .body(formParams) //
                .retrieve() //
                .body(Map.class);
    }

    private ClientRegistration clientRegistration()
    {
        return ClientRegistration.withRegistrationId("keycloak") //
                .authorizationGrantType(AUTHORIZATION_CODE) //
                .clientId(CLIENT_ID) //
                .clientSecret(CLIENT_SECRET) //
                .redirectUri("http://dummy") //
                .authorizationUri(keycloak.getAuthServerUrl() + "/realms/" + REALM
                        + "/protocol/openid-connect/auth") //
                .tokenUri(keycloak.getAuthServerUrl() + "/realms/" + REALM
                        + "/protocol/openid-connect/token") //
                .jwkSetUri(keycloak.getAuthServerUrl() + "/realms/" + REALM
                        + "/protocol/openid-connect/certs") //
                .userInfoUri(keycloak.getAuthServerUrl() + "/realms/" + REALM
                        + "/protocol/openid-connect/userinfo") //
                .userNameAttributeName("preferred_username") //
                .build();
    }
}
