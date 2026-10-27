package com.bionicpro.auth.service;

import com.bionicpro.auth.model.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {

    private static final Logger log = LoggerFactory.getLogger(SessionService.class);

    private final TokenEncryptionService tokenEncryptionService;
    private final RestTemplate restTemplate;

    @Value("${keycloak.auth-server-url}")
    private String keycloakUrl;

    @Value("${keycloak.realm}")
    private String realm;

    @Value("${keycloak.client-id}")
    private String clientId;

    @Value("${keycloak.client-secret}")
    private String clientSecret;

    @Value("${bionicpro.session.timeout-seconds:1800}")
    private long sessionTimeoutSeconds;

    // In-memory thread-safe session store
    private final Map<String, UserSession> sessionStore = new ConcurrentHashMap<>();

    // Temporary storage for OAuth2 PKCE state & verifier: state -> codeVerifier
    private final Map<String, String> pkceStateStore = new ConcurrentHashMap<>();

    public SessionService(TokenEncryptionService tokenEncryptionService, RestTemplateBuilder restTemplateBuilder) {
        this.tokenEncryptionService = tokenEncryptionService;
        this.restTemplate = restTemplateBuilder.build();
    }

    public String generateCodeVerifier() {
        byte[] code = new byte[32];
        new SecureRandom().nextBytes(code);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(code);
    }

    public String generateCodeChallenge(String codeVerifier) {
        try {
            byte[] bytes = codeVerifier.getBytes(StandardCharsets.US_ASCII);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate code challenge", e);
        }
    }

    public void storePkceState(String state, String codeVerifier) {
        pkceStateStore.put(state, codeVerifier);
    }

    public String retrieveAndRemoveCodeVerifier(String state) {
        return pkceStateStore.remove(state);
    }

    public UserSession createSession(String accessToken, long expiresInSeconds, String refreshToken, String username) {
        String sessionId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        Instant accessExpiresAt = now.plusSeconds(expiresInSeconds > 0 ? expiresInSeconds : 120);
        Instant sessionExpiresAt = now.plusSeconds(sessionTimeoutSeconds);

        String encryptedRefreshToken = tokenEncryptionService.encrypt(refreshToken);

        UserSession session = new UserSession(sessionId, accessToken, accessExpiresAt,
                encryptedRefreshToken, sessionExpiresAt, username);

        sessionStore.put(sessionId, session);
        log.info("Created new session {} for user {}, expires at {}", sessionId, username, sessionExpiresAt);
        return session;
    }

    public UserSession getSession(String sessionId) {
        if (sessionId == null) return null;
        UserSession session = sessionStore.get(sessionId);
        if (session == null) return null;

        if (session.isSessionExpired()) {
            sessionStore.remove(sessionId);
            log.warn("Session {} expired and removed", sessionId);
            return null;
        }

        return session;
    }

    public String getOrRefreshAccessToken(UserSession session) {
        if (!session.isAccessTokenExpired()) {
            return session.getAccessToken();
        }

        log.info("Access token for session {} is expired. Refreshing with Keycloak...", session.getSessionId());
        String decryptedRefreshToken = tokenEncryptionService.decrypt(session.getEncryptedRefreshToken());
        if (decryptedRefreshToken == null) {
            log.error("No refresh token available in session {}", session.getSessionId());
            return null;
        }

        try {
            String tokenUrl = String.format("%s/realms/%s/protocol/openid-connect/token", keycloakUrl, realm);

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("grant_type", "refresh_token");
            body.add("client_id", clientId);
            if (clientSecret != null && !clientSecret.isBlank()) {
                body.add("client_secret", clientSecret);
            }
            body.add("refresh_token", decryptedRefreshToken);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    tokenUrl, HttpMethod.POST, request, new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> tokenData = response.getBody();
                String newAccessToken = (String) tokenData.get("access_token");
                Number expiresIn = (Number) tokenData.get("expires_in");
                String newRefreshToken = (String) tokenData.get("refresh_token");

                session.setAccessToken(newAccessToken);
                session.setAccessTokenExpiresAt(Instant.now().plusSeconds(expiresIn != null ? expiresIn.longValue() : 120));

                if (newRefreshToken != null) {
                    session.setEncryptedRefreshToken(tokenEncryptionService.encrypt(newRefreshToken));
                }

                log.info("Successfully refreshed access token for session {}", session.getSessionId());
                return newAccessToken;
            }
        } catch (Exception e) {
            log.error("Failed to refresh access token for session {}: {}", session.getSessionId(), e.getMessage());
        }

        return null;
    }

    /**
     * Session rotation: rotates existing session ID to a new random session ID
     * to protect against Session Fixation attacks.
     */
    public UserSession rotateSession(UserSession oldSession) {
        String oldSessionId = oldSession.getSessionId();
        String newSessionId = UUID.randomUUID().toString();

        sessionStore.remove(oldSessionId);

        oldSession.setSessionId(newSessionId);
        // Extend session lifetime on activity
        oldSession.setSessionExpiresAt(Instant.now().plusSeconds(sessionTimeoutSeconds));

        sessionStore.put(newSessionId, oldSession);
        log.debug("Session rotated from {} to {}", oldSessionId, newSessionId);
        return oldSession;
    }

    public void removeSession(String sessionId) {
        if (sessionId != null) {
            sessionStore.remove(sessionId);
        }
    }

    public void logoutFromKeycloak(UserSession session) {
        if (session == null) return;
        String decryptedRefreshToken = tokenEncryptionService.decrypt(session.getEncryptedRefreshToken());
        if (decryptedRefreshToken == null || decryptedRefreshToken.isBlank()) {
            return;
        }

        try {
            String logoutUrl = String.format("%s/realms/%s/protocol/openid-connect/logout", keycloakUrl, realm);
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_FORM_URLENCODED);

            MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
            body.add("client_id", clientId);
            if (clientSecret != null && !clientSecret.isBlank()) {
                body.add("client_secret", clientSecret);
            }
            body.add("refresh_token", decryptedRefreshToken);

            HttpEntity<MultiValueMap<String, String>> request = new HttpEntity<>(body, headers);
            ResponseEntity<String> response = restTemplate.exchange(logoutUrl, HttpMethod.POST, request, String.class);
            log.info("Successfully revoked Keycloak session for user {}. Status: {}", session.getUsername(), response.getStatusCode());
        } catch (Exception e) {
            log.warn("Failed to revoke session in Keycloak for user {}: {}", session.getUsername(), e.getMessage());
        }
    }

    @Scheduled(fixedRate = 60000)
    public void cleanupExpiredSessions() {
        Instant now = Instant.now();
        sessionStore.entrySet().removeIf(entry -> entry.getValue().getSessionExpiresAt().isBefore(now));
    }
}
