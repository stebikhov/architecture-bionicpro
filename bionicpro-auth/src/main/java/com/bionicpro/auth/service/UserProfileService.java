package com.bionicpro.auth.service;

import com.bionicpro.auth.model.UserProfile;
import com.bionicpro.auth.repository.UserProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Map;
import java.util.Optional;

/**
 * Service for fetching user profile from Yandex ID API and persisting profile data in DB.
 */
@Service
public class UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileService.class);

    private final RestTemplate restTemplate;
    private final UserProfileRepository userProfileRepository;

    @Value("${yandex.userinfo.url:https://login.yandex.ru/info}")
    private String yandexUserInfoUrl;

    public UserProfileService(RestTemplateBuilder restTemplateBuilder,
                              UserProfileRepository userProfileRepository) {
        this.restTemplate = restTemplateBuilder.build();
        this.userProfileRepository = userProfileRepository;
    }

    /**
     * Fetch user profile from Yandex ID using access token or Keycloak Identity Broker token.
     */
    public UserProfile fetchYandexProfile(String yandexOrIdpToken) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setBearerAuth(yandexOrIdpToken);
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                    yandexUserInfoUrl + "?format=json",
                    HttpMethod.GET,
                    request,
                    new ParameterizedTypeReference<>() {}
            );

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<String, Object> data = response.getBody();
                String id = String.valueOf(data.get("id"));
                String login = (String) data.get("login");
                String email = (String) data.get("default_email");
                String firstName = (String) data.get("first_name");
                String lastName = (String) data.get("last_name");

                UserProfile profile = new UserProfile(id, login != null ? login : id, email, firstName, lastName, "yandex", false);
                profile.setRawAttributes(data);
                log.info("Successfully fetched Yandex profile for user {} ({})", login, email);
                return profile;
            }
        } catch (Exception e) {
            log.warn("Could not fetch profile directly from Yandex API (mocking/extracting from claims): {}", e.getMessage());
        }
        return null;
    }

    /**
     * Persist user profile and user consent in the Database.
     */
    public UserProfile saveProfile(UserProfile profile) {
        if (profile == null || profile.getUsername() == null) {
            return null;
        }
        UserProfile saved = userProfileRepository.save(profile);
        log.info("Persisted user profile for '{}' (consentGiven: {}) in Database. Total profiles in DB: {}",
                saved.getUsername(), saved.isConsentGiven(), userProfileRepository.count());
        return saved;
    }

    /**
     * Retrieve profile from DB by username.
     */
    public Optional<UserProfile> getProfile(String username) {
        if (username == null) return Optional.empty();
        return userProfileRepository.findByUsername(username);
    }

    /**
     * Update consent status and persist in Database.
     */
    public UserProfile updateConsent(String username, boolean consentGiven) {
        UserProfile profile = userProfileRepository.findByUsername(username).orElseGet(() -> {
            UserProfile p = new UserProfile();
            p.setUsername(username);
            p.setProvider("yandex");
            return p;
        });
        profile.setConsentGiven(consentGiven);
        return saveProfile(profile);
    }
}
