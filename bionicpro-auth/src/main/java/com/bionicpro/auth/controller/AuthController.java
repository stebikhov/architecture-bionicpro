package com.bionicpro.auth.controller;

import com.bionicpro.auth.model.UserProfile;
import com.bionicpro.auth.model.UserSession;
import com.bionicpro.auth.service.SessionService;
import com.bionicpro.auth.service.UserProfileService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

@RestController
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final String SESSION_COOKIE_NAME = "BIONIC_SESSION";

    private final SessionService sessionService;
    private final UserProfileService userProfileService;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${keycloak.auth-server-url}")
    private String keycloakUrl;

    @Value("${keycloak.public-url:${keycloak.auth-server-url}}")
    private String keycloakPublicUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${reports.api.url}")
    private String reportsApiUrl;

    @Value("${bionicpro.session.cookie-secure:true}")
    private boolean cookieSecure;

    public AuthController(SessionService sessionService,
                          UserProfileService userProfileService,
                          RestTemplateBuilder restTemplateBuilder) {
        this.sessionService = sessionService;
        this.userProfileService = userProfileService;
        this.restTemplate = restTemplateBuilder.build();
    }

    /**
     * Step 1: Initiate OAuth2 PKCE login.
     * Optional param: kc_idp_hint=yandex to route directly to Yandex ID.
     */
    @GetMapping("/auth/login")
    public void login(@RequestParam(value = "idp", required = false) String idp,
                      @RequestParam(value = "prompt", required = false) String prompt,
                      HttpServletRequest request,
                      HttpServletResponse response) throws IOException {
        String codeVerifier = sessionService.generateCodeVerifier();
        String codeChallenge = sessionService.generateCodeChallenge(codeVerifier);
        String state = UUID.randomUUID().toString();

        sessionService.storePkceState(state, codeVerifier);

        String redirectUri = getCallbackUri(request);

        UriComponentsBuilder uriBuilder = UriComponentsBuilder.fromHttpUrl(
                String.format("%s/realms/%s/protocol/openid-connect/auth", keycloakPublicUrl, realm))
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "openid profile email")
                .queryParam("state", state)
                .queryParam("code_challenge", codeChallenge)
                .queryParam("code_challenge_method", "S256");

        if (prompt != null && !prompt.isBlank()) {
            uriBuilder.queryParam("prompt", prompt);
        } else {
            // By default force login prompt on re-login after logout
            uriBuilder.queryParam("prompt", "login");
        }

        if (idp != null && !idp.isBlank()) {
            uriBuilder.queryParam("kc_idp_hint", idp);
        }

        String authorizationUrl = uriBuilder.build().toUriString();
        log.info("Redirecting to Keycloak authorization endpoint with PKCE (idp={}, prompt={}): {}", idp, prompt, authorizationUrl);
        response.sendRedirect(authorizationUrl);
    }

    /**
     * Step 2: Keycloak redirects back with authorization code.
     */
    @GetMapping({"/auth/callback", "/login/oauth2/code/keycloak"})
    public void callback(@RequestParam("code") String code,
                         @RequestParam("state") String state,
                         HttpServletRequest request,
                         HttpServletResponse response) throws IOException {
        String codeVerifier = sessionService.retrieveAndRemoveCodeVerifier(state);
        if (codeVerifier == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Invalid or expired state");
            return;
        }

        String redirectUri = getCallbackUri(request);
        String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl, realm);

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "authorization_code");
            body.add("client_id", clientId);
            if (clientSecret != null && !clientSecret.isBlank()) {
                body.add("client_secret", clientSecret);
            }
            body.add("code", code);
            body.add("redirect_uri", redirectUri);
            body.add("code_verifier", codeVerifier);

            HttpEntity<MultiValueMap<String, String>> tokenRequest = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> tokenResponse = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, tokenRequest, new ParameterizedTypeReference<>() {}
            );

            if (tokenResponse.getStatusCode().is2xxSuccessful() && tokenResponse.getBody() != null) {
                Map<String, Object> data = tokenResponse.getBody();
                String accessToken = (String) data.get("access_token");
                Number expiresIn = (Number) data.get("expires_in");
                String refreshToken = (String) data.get("refresh_token");
                String idToken = (String) data.get("id_token");

                // Parse claims from JWT token
                ParsedClaims claims = parseJwtClaims(accessToken != null ? accessToken : idToken);
                String username = claims.username;
                String provider = claims.provider;

                UserSession userSession = sessionService.createSession(
                        accessToken,
                        expiresIn != null ? expiresIn.longValue() : 120,
                        refreshToken,
                        username
                );
                userSession.setProvider(provider);

                // Fetch & build initial user profile
                UserProfile profile = null;
                if ("yandex".equalsIgnoreCase(provider)) {
                    // Fetch Yandex profile
                    profile = userProfileService.fetchYandexProfile(accessToken);
                }
                if (profile == null) {
                    profile = new UserProfile(claims.sub, username, claims.email, claims.firstName, claims.lastName, provider, false);
                }
                userSession.setUserProfile(profile);

                setSessionCookie(response, userSession.getSessionId(), request);
                log.info("User {} (provider={}) authenticated successfully. Session: {}", username, provider, userSession.getSessionId());

                response.sendRedirect("http://localhost:3000/");
            } else {
                response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Failed to exchange token with Keycloak");
            }
        } catch (Exception e) {
            log.error("Error exchanging authorization code: {}", e.getMessage(), e);
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "Authentication error: " + e.getMessage());
        }
    }

    /**
     * Check current authentication & consent status.
     */
    @GetMapping("/auth/status")
    public ResponseEntity<?> getAuthStatus(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = extractSessionId(request);
        UserSession session = sessionService.getSession(sessionId);

        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("authenticated", false));
        }

        // Check if profile exists in DB
        UserProfile storedProfile = userProfileService.getProfile(session.getUsername()).orElse(session.getUserProfile());

        // Rotate session
        UserSession rotatedSession = sessionService.rotateSession(session);
        setSessionCookie(response, rotatedSession.getSessionId(), request);

        boolean isYandex = "yandex".equalsIgnoreCase(rotatedSession.getProvider());
        boolean consentRequired = isYandex && (storedProfile == null || !storedProfile.isConsentGiven());

        return ResponseEntity.ok(Map.of(
                "authenticated", true,
                "sessionId", rotatedSession.getSessionId(),
                "username", rotatedSession.getUsername(),
                "provider", rotatedSession.getProvider() != null ? rotatedSession.getProvider() : "keycloak",
                "consentRequired", consentRequired,
                "consentGiven", storedProfile != null && storedProfile.isConsentGiven(),
                "profile", storedProfile != null ? storedProfile : Map.of()
        ));
    }

    /**
     * User grants or denies consent to use Yandex profile data.
     * When consent is granted, the profile is saved/updated in the Database.
     */
    @PostMapping("/auth/consent")
    public ResponseEntity<?> handleConsent(@RequestBody Map<String, Boolean> requestBody,
                                           HttpServletRequest request,
                                           HttpServletResponse response) {
        String sessionId = extractSessionId(request);
        UserSession session = sessionService.getSession(sessionId);

        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized"));
        }

        boolean consent = Boolean.TRUE.equals(requestBody.get("consent"));
        session.setConsentGiven(consent);

        UserProfile profile = session.getUserProfile();
        if (profile == null) {
            profile = new UserProfile();
            profile.setUsername(session.getUsername());
            profile.setProvider(session.getProvider());
        }
        profile.setConsentGiven(consent);

        if (consent) {
            // Save to DB
            userProfileService.saveProfile(profile);
            log.info("User {} granted consent. Profile saved to DB.", session.getUsername());
        } else {
            log.warn("User {} denied consent.", session.getUsername());
        }

        // Rotate session
        UserSession rotatedSession = sessionService.rotateSession(session);
        setSessionCookie(response, rotatedSession.getSessionId(), request);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "consentGiven", consent,
                "message", consent ? "Consent recorded and profile stored in DB." : "Consent declined."
        ));
    }

    /**
     * Logout and destroy session.
     */
    @PostMapping("/auth/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        String sessionId = extractSessionId(request);
        if (sessionId != null) {
            UserSession session = sessionService.getSession(sessionId);
            if (session != null) {
                // Terminate session in Keycloak via backchannel refresh_token revocation
                sessionService.logoutFromKeycloak(session);
            }
            sessionService.removeSession(sessionId);
        }

        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE_NAME, "")
                .httpOnly(true)
                .secure(false)
                .path("/")
                .maxAge(0)
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());

        String keycloakLogoutRedirect = String.format(
                "%s/realms/%s/protocol/openid-connect/logout?client_id=%s&post_logout_redirect_uri=http://localhost:3000/",
                keycloakPublicUrl, realm, clientId
        );

        return ResponseEntity.ok(Map.of(
                "message", "Logged out successfully",
                "logoutUrl", keycloakLogoutRedirect
        ));
    }

    /**
     * Protected resource gateway: forwards requests to reports API with Authorization: Bearer <access_token>.
     * Rotates session ID on each successful request to prevent session fixation attack.
     */
    @RequestMapping(value = "/api/**", method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
    public ResponseEntity<?> proxyToApi(HttpServletRequest request, HttpServletResponse response, @RequestBody(required = false) byte[] body) {
        String sessionId = extractSessionId(request);
        UserSession session = sessionService.getSession(sessionId);

        if (session == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized: No valid session"));
        }

        // Check if Yandex consent is required before accessing protected resources
        boolean isYandex = "yandex".equalsIgnoreCase(session.getProvider());
        UserProfile profile = userProfileService.getProfile(session.getUsername()).orElse(session.getUserProfile());
        if (isYandex && (profile == null || !profile.isConsentGiven())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "error", "User consent is required before accessing reports service",
                    "consentRequired", true
            ));
        }

        // Get active access token or refresh via refresh_token if expired
        String validAccessToken = sessionService.getOrRefreshAccessToken(session);
        if (validAccessToken == null) {
            sessionService.removeSession(sessionId);
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Session expired, refresh failed"));
        }

        // ROTATE SESSION ID: Prevent Session Fixation Attack
        UserSession rotatedSession = sessionService.rotateSession(session);
        setSessionCookie(response, rotatedSession.getSessionId(), request);
        response.setHeader("X-Session-ID", rotatedSession.getSessionId());

        // Forward request to actual Resource API
        String requestUri = request.getRequestURI().substring(4);
        String queryString = request.getQueryString() != null ? "?" + request.getQueryString() : "";
        String targetUrl = reportsApiUrl + requestUri + queryString;

        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(validAccessToken);
            if (request.getContentType() != null) {
                headers.setContentType(MediaType.parseMediaType(request.getContentType()));
            }
            headers.setAccept(MediaType.parseMediaTypes(request.getHeader(HttpHeaders.ACCEPT) != null ? request.getHeader(HttpHeaders.ACCEPT) : "*/*"));

            HttpMethod method = HttpMethod.valueOf(request.getMethod());
            HttpEntity<byte[]> entity = new HttpEntity<>(body, headers);

            ResponseEntity<byte[]> apiResponse = restTemplate.exchange(URI.create(targetUrl), method, entity, byte[].class);

            HttpHeaders returnHeaders = new HttpHeaders();
            apiResponse.getHeaders().forEach((k, v) -> {
                if (!k.equalsIgnoreCase(HttpHeaders.TRANSFER_ENCODING) && !k.equalsIgnoreCase(HttpHeaders.SET_COOKIE)) {
                    returnHeaders.put(k, v);
                }
            });

            return new ResponseEntity<>(apiResponse.getBody(), returnHeaders, apiResponse.getStatusCode());
        } catch (HttpStatusCodeException e) {
            return ResponseEntity.status(e.getStatusCode()).body(e.getResponseBodyAsString());
        } catch (Exception e) {
            log.error("Proxy error to {}: {}", targetUrl, e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(Map.of("error", "Failed to proxy request: " + e.getMessage()));
        }
    }

    private String extractSessionId(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie cookie : request.getCookies()) {
                if (SESSION_COOKIE_NAME.equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        String headerSession = request.getHeader("X-Session-ID");
        if (headerSession != null && !headerSession.isBlank()) {
            return headerSession;
        }
        return null;
    }

    private void setSessionCookie(HttpServletResponse response, String sessionId, HttpServletRequest request) {
        boolean secureFlag = cookieSecure || request.isSecure();
        ResponseCookie cookie = ResponseCookie.from(SESSION_COOKIE_NAME, sessionId)
                .httpOnly(true)
                .secure(secureFlag)
                .path("/")
                .maxAge(sessionService.getSession(sessionId) != null ? 1800 : 0)
                .sameSite("Lax")
                .build();

        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private String getCallbackUri(HttpServletRequest request) {
        String host = request.getHeader("X-Forwarded-Host");
        if (host == null || host.isBlank()) {
            host = request.getHeader("Host");
        }
        if (host == null || host.isBlank()) {
            host = "localhost:8081";
        }
        String proto = request.getHeader("X-Forwarded-Proto");
        if (proto == null || proto.isBlank()) {
            proto = request.getScheme();
        }
        return String.format("%s://%s/auth/callback", proto, host);
    }

    private ParsedClaims parseJwtClaims(String jwtToken) {
        ParsedClaims claims = new ParsedClaims();
        if (jwtToken == null || !jwtToken.contains(".")) {
            claims.username = "user";
            claims.provider = "keycloak";
            return claims;
        }

        try {
            String[] parts = jwtToken.split("\\.");
            if (parts.length >= 2) {
                byte[] decoded = Base64.getUrlDecoder().decode(parts[1]);
                JsonNode json = objectMapper.readTree(decoded);

                claims.sub = json.has("sub") ? json.get("sub").asText() : UUID.randomUUID().toString();
                claims.username = json.has("preferred_username") ? json.get("preferred_username").asText() :
                                  json.has("email") ? json.get("email").asText() : "user";
                claims.email = json.has("email") ? json.get("email").asText() : null;
                claims.firstName = json.has("given_name") ? json.get("given_name").asText() : null;
                claims.lastName = json.has("family_name") ? json.get("family_name").asText() : null;

                if (json.has("identity_provider")) {
                    claims.provider = json.get("identity_provider").asText();
                } else if (claims.email != null && claims.email.endsWith("@yandex.ru")) {
                    claims.provider = "yandex";
                } else {
                    claims.provider = "keycloak";
                }
            }
        } catch (Exception e) {
            log.warn("Failed to parse JWT claims: {}", e.getMessage());
            claims.username = "user";
            claims.provider = "keycloak";
        }
        return claims;
    }

    private static class ParsedClaims {
        String sub;
        String username;
        String email;
        String firstName;
        String lastName;
        String provider = "keycloak";
    }
}
