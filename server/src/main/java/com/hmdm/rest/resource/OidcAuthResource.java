/*
 *
 * ALL Tecnologias MDM (based on Headwind MDM)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdm.persistence.CustomerDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.Settings;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.view.user.UserView;
import com.hmdm.util.BackgroundTaskRunnerService;
import com.hmdm.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;
import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * <p>ALL Tecnologias: single sign-on through Authentik (or any OpenID Connect provider).</p>
 *
 * <p>Flow: <code>/start</code> redirects to the provider, <code>/callback</code> exchanges the code, reads the
 * user info and opens the same session the password login opens, then sends the browser back to the web app,
 * which reads the user with <code>/user</code>. The user must already exist in the MDM (matched by e-mail, then
 * by login = <code>preferred_username</code>).</p>
 */
@Singleton
@Path( "/public/auth/oidc" )
public class OidcAuthResource {

    private static final Logger log = LoggerFactory.getLogger(OidcAuthResource.class);

    private static final String sessionState = "oidc.state";
    private static final String sessionUser = "oidc.user";

    private final ObjectMapper mapper = new ObjectMapper();

    private UnsecureDAO userDAO;
    private CustomerDAO customerDAO;
    private BackgroundTaskRunnerService taskRunner;
    private String baseUrl;
    private String issuer;
    private String clientId;
    private String clientSecret;
    private String buttonLabel;
    private JsonNode discovery;

    /**
     * <p>A constructor required by Swagger.</p>
     */
    public OidcAuthResource() {
    }

    @Inject
    public OidcAuthResource(UnsecureDAO userDAO,
                            CustomerDAO customerDAO,
                            BackgroundTaskRunnerService taskRunner,
                            @Named("base.url") String baseUrl,
                            @Named("oidc.issuer") String issuer,
                            @Named("oidc.client.id") String clientId,
                            @Named("oidc.client.secret") String clientSecret,
                            @Named("oidc.button.label") String buttonLabel) {
        this.userDAO = userDAO;
        this.customerDAO = customerDAO;
        this.taskRunner = taskRunner;
        this.baseUrl = baseUrl;
        this.issuer = issuer.endsWith("/") || issuer.isEmpty() ? issuer : issuer + "/";
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.buttonLabel = buttonLabel;
    }

    private boolean isEnabled() {
        return !issuer.isEmpty() && !clientId.isEmpty() && !clientSecret.isEmpty();
    }

    private String redirectUri() {
        return baseUrl + "/rest/public/auth/oidc/callback";
    }

    /**
     * <p>Login page options: whether the SSO button is shown and its label.</p>
     */
    @GET
    @Path( "/options" )
    @Produces( MediaType.APPLICATION_JSON )
    public Response options() {
        Map<String, Object> result = new HashMap<>();
        result.put("enabled", isEnabled());
        result.put("label", buttonLabel);
        return Response.OK(result);
    }

    /**
     * <p>Starts the login: redirects to the provider. Also the launch URL of the application in Authentik.</p>
     */
    @GET
    @Path( "/start" )
    public javax.ws.rs.core.Response start(@Context HttpServletRequest req) throws IOException {
        if (!isEnabled()) {
            return javax.ws.rs.core.Response.status(404).build();
        }
        String state = UUID.randomUUID().toString();
        req.getSession().setAttribute(sessionState, state);
        String url = discovery().get("authorization_endpoint").asText()
                + "?response_type=code"
                + "&client_id=" + encode(clientId)
                + "&redirect_uri=" + encode(redirectUri())
                + "&scope=" + encode("openid email profile")
                + "&state=" + encode(state);
        return javax.ws.rs.core.Response.seeOther(URI.create(url)).build();
    }

    /**
     * <p>Provider callback: validates the state, identifies the user and opens the session.</p>
     */
    @GET
    @Path( "/callback" )
    public javax.ws.rs.core.Response callback(@QueryParam("code") String code,
                                              @QueryParam("state") String state,
                                              @Context HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        String expectedState = session != null ? (String) session.getAttribute(sessionState) : null;
        if (!isEnabled() || code == null || state == null || expectedState == null
                || !MessageDigest.isEqual(expectedState.getBytes(StandardCharsets.UTF_8), state.getBytes(StandardCharsets.UTF_8))) {
            return backToLogin("failed");
        }
        session.removeAttribute(sessionState);

        try {
            JsonNode userInfo = fetchUserInfo(code);
            User user = findUser(userInfo);
            if (user == null) {
                log.warn("OIDC login: no MDM user for e-mail {} / login {}",
                        userInfo.path("email").asText(""), userInfo.path("preferred_username").asText(""));
                return backToLogin("nouser");
            }

            // Same steps as the password login (AuthResource.login), without the second factor:
            // the identity provider is responsible for it.
            req.changeSessionId();
            this.taskRunner.submitTask(() -> {
                this.customerDAO.recordLastLoginTime(user.getCustomerId(), System.currentTimeMillis());
            });
            Settings settings = userDAO.getSettings(user.getCustomerId());
            if (settings != null) {
                user.setIdleLogout(settings.getIdleLogout());
            }
            if (user.getAuthToken() == null || user.getAuthToken().length() == 0) {
                user.setAuthToken(PasswordUtil.generateToken());
                user.setNewPassword(user.getPassword());        // copy value for setUserNewPasswordUnsecure
                userDAO.setUserNewPasswordUnsecure(user);
            }
            user.setPassword(null);
            user.setSingleCustomer(userDAO.isSingleCustomer());

            session.setAttribute(AuthFilter.sessionCredentials, user);
            session.setAttribute(sessionUser, user);
            return backToLogin("ok");
        } catch (Exception e) {
            log.error("OIDC login failed", e);
            return backToLogin("failed");
        }
    }

    /**
     * <p>Returns the user that has just signed in through the provider (read once by the web app).</p>
     */
    @GET
    @Path( "/user" )
    @Produces( MediaType.APPLICATION_JSON )
    public Response user(@Context HttpServletRequest req) {
        HttpSession session = req.getSession(false);
        User user = session != null ? (User) session.getAttribute(sessionUser) : null;
        if (user == null) {
            return Response.ERROR();
        }
        session.removeAttribute(sessionUser);
        return Response.OK(new UserView(user));
    }

    private User findUser(JsonNode userInfo) {
        String email = userInfo.path("email").asText("");
        if (!email.isEmpty()) {
            User user = userDAO.findByEmail(email);
            if (user != null) {
                return user;
            }
        }
        String login = userInfo.path("preferred_username").asText("");
        return login.isEmpty() ? null : userDAO.findByLogin(login);
    }

    private JsonNode fetchUserInfo(String code) throws IOException {
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(redirectUri())
                + "&client_id=" + encode(clientId)
                + "&client_secret=" + encode(clientSecret);
        HttpURLConnection token = open(discovery().get("token_endpoint").asText());
        token.setRequestMethod("POST");
        token.setDoOutput(true);
        token.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
        try (OutputStream out = token.getOutputStream()) {
            out.write(form.getBytes(StandardCharsets.UTF_8));
        }
        String accessToken = read(token).get("access_token").asText();

        HttpURLConnection info = open(discovery().get("userinfo_endpoint").asText());
        info.setRequestProperty("Authorization", "Bearer " + accessToken);
        return read(info);
    }

    private synchronized JsonNode discovery() throws IOException {
        if (discovery == null) {
            discovery = read(open(issuer + ".well-known/openid-configuration"));
        }
        return discovery;
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);
        return connection;
    }

    private JsonNode read(HttpURLConnection connection) throws IOException {
        int status = connection.getResponseCode();
        if (status != 200) {
            throw new IOException("HTTP " + status + " from " + connection.getURL());
        }
        try (InputStream in = connection.getInputStream()) {
            return mapper.readTree(in);
        }
    }

    private javax.ws.rs.core.Response backToLogin(String result) {
        return javax.ws.rs.core.Response.seeOther(URI.create(baseUrl + "/#/login?sso=" + result)).build();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }
}
