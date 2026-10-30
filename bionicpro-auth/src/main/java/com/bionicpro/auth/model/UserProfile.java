package com.bionicpro.auth.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Map;

@Entity
@Table(name = "user_profiles")
public class UserProfile {
    @Id
    @Column(name = "username", length = 100, nullable = false, unique = true)
    private String username;

    @Column(name = "external_id")
    private String id;

    @Column(name = "email")
    private String email;

    @Column(name = "first_name")
    private String firstName;

    @Column(name = "last_name")
    private String lastName;

    @Column(name = "provider")
    private String provider; // "yandex", "keycloak", "ldap"

    @Column(name = "consent_given")
    private boolean consentGiven;

    @Column(name = "consent_timestamp")
    private Instant consentTimestamp;

    @Transient
    private Map<String, Object> rawAttributes;

    public UserProfile() {}

    public UserProfile(String id, String username, String email, String firstName, String lastName, String provider, boolean consentGiven) {
        this.id = id;
        this.username = username;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.provider = provider;
        this.consentGiven = consentGiven;
        this.consentTimestamp = consentGiven ? Instant.now() : null;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getFirstName() {
        return firstName;
    }

    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    public String getLastName() {
        return lastName;
    }

    public void setLastName(String lastName) {
        this.lastName = lastName;
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
        if (consentGiven && this.consentTimestamp == null) {
            this.consentTimestamp = Instant.now();
        }
    }

    public Instant getConsentTimestamp() {
        return consentTimestamp;
    }

    public void setConsentTimestamp(Instant consentTimestamp) {
        this.consentTimestamp = consentTimestamp;
    }

    public Map<String, Object> getRawAttributes() {
        return rawAttributes;
    }

    public void setRawAttributes(Map<String, Object> rawAttributes) {
        this.rawAttributes = rawAttributes;
    }
}
