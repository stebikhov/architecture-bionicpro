package com.bionicpro.auth.model;

import java.time.Instant;

public class UserSession {
    private String sessionId;
    private String accessToken; // Held in server memory
    private Instant accessTokenExpiresAt;
    private String encryptedRefreshToken; // Encrypted in-memory
    private Instant sessionExpiresAt;
    private String username;
    private String provider; // e.g. "yandex" or "keycloak"
    private boolean consentGiven;
    private UserProfile userProfile;

    public UserSession() {}

    public UserSession(String sessionId, String accessToken, Instant accessTokenExpiresAt,
                       String encryptedRefreshToken, Instant sessionExpiresAt, String username) {
        this.sessionId = sessionId;
        this.accessToken = accessToken;
        this.accessTokenExpiresAt = accessTokenExpiresAt;
        this.encryptedRefreshToken = encryptedRefreshToken;
        this.sessionExpiresAt = sessionExpiresAt;
        this.username = username;
        this.provider = "keycloak";
        this.consentGiven = false;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }

    public boolean isConsentGiven() {
        return consentGiven;
    }

    public void setConsentGiven(boolean consentGiven) {
        this.consentGiven = consentGiven;
    }

    public UserProfile getUserProfile() {
        return userProfile;
    }

    public void setUserProfile(UserProfile userProfile) {
        this.userProfile = userProfile;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public Instant getAccessTokenExpiresAt() {
        return accessTokenExpiresAt;
    }

    public void setAccessTokenExpiresAt(Instant accessTokenExpiresAt) {
        this.accessTokenExpiresAt = accessTokenExpiresAt;
    }

    public String getEncryptedRefreshToken() {
        return encryptedRefreshToken;
    }

    public void setEncryptedRefreshToken(String encryptedRefreshToken) {
        this.encryptedRefreshToken = encryptedRefreshToken;
    }

    public Instant getSessionExpiresAt() {
        return sessionExpiresAt;
    }

    public void setSessionExpiresAt(Instant sessionExpiresAt) {
        this.sessionExpiresAt = sessionExpiresAt;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public boolean isSessionExpired() {
        return Instant.now().isAfter(sessionExpiresAt);
    }

    public boolean isAccessTokenExpired() {
        // Consider expired 5 seconds before actual expiration to prevent race conditions
        return Instant.now().plusSeconds(5).isAfter(accessTokenExpiresAt);
    }
}
