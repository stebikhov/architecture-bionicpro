package com.bionicpro.reports.service;

import com.bionicpro.reports.model.UserReportResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.*;

/**
 * Service for checking and storing user reports in S3-compatible storage (MinIO / Ceph)
 * and building CDN URLs for edge caching distribution.
 *
 * Structure of storage in S3:
 * bucket: bionicpro-reports
 * key: reports/{username}/report_{startDate}_{endDate}.json
 *
 * This key structure provides fast, isolated partitioning per user and date range.
 */
@Service
public class S3ReportStorageService {

    private static final Logger log = LoggerFactory.getLogger(S3ReportStorageService.class);

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Value("${s3.endpoint:http://minio:9000}")
    private String s3Endpoint;

    @Value("${s3.access-key:minioadmin}")
    private String accessKey;

    @Value("${s3.secret-key:minioadminpassword}")
    private String secretKey;

    @Value("${s3.bucket:bionicpro-reports}")
    private String bucket;

    @Value("${cdn.base-url:http://localhost:8082/bionicpro-reports}")
    private String cdnBaseUrl;

    public S3ReportStorageService(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder.build();
    }

    /**
     * Build standard hierarchical S3 key for user report.
     * reports/{username}/report_{startDate}_{endDate}.json
     */
    public String buildReportKey(String username, LocalDate startDate, LocalDate endDate) {
        return String.format("reports/%s/report_%s_%s.json", username, startDate, endDate);
    }

    /**
     * Build public CDN URL for the cached report.
     */
    public String buildCdnUrl(String s3Key) {
        return String.format("%s/%s", cdnBaseUrl, s3Key);
    }

    /**
     * Check if report already exists in S3 and return cached response if found.
     * Prevents redundant OLAP ClickHouse queries.
     */
    public Optional<UserReportResponse> findReportInS3(String username, LocalDate startDate, LocalDate endDate) {
        String s3Key = buildReportKey(username, startDate, endDate);
        String s3Url = String.format("%s/%s/%s", s3Endpoint, bucket, s3Key);

        try {
            HttpHeaders headers = buildS3AuthHeaders("GET", "/" + bucket + "/" + s3Key, "");
            HttpEntity<Void> request = new HttpEntity<>(headers);

            ResponseEntity<String> response = restTemplate.exchange(s3Url, HttpMethod.GET, request, String.class);
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                UserReportResponse cachedReport = objectMapper.readValue(response.getBody(), UserReportResponse.class);
                cachedReport.setS3Key(s3Key);
                cachedReport.setCdnUrl(buildCdnUrl(s3Key));
                cachedReport.setCacheStatus("CACHE_HIT_S3_CDN");
                log.info("Report CACHE HIT in S3/CDN for user '{}' ({})", username, s3Key);
                return Optional.of(cachedReport);
            }
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.debug("Report CACHE MISS in S3 for user '{}' ({})", username, s3Key);
            } else {
                log.warn("S3 check returned status {}: {}", e.getStatusCode(), e.getMessage());
            }
        } catch (Exception e) {
            log.warn("Error checking report in S3 (will fallback to OLAP generation): {}", e.getMessage());
        }

        return Optional.empty();
    }

    /**
     * Store freshly generated report in S3 object storage.
     */
    public void saveReportToS3(UserReportResponse report) {
        String s3Key = buildReportKey(
                report.getUsername(),
                LocalDate.parse(report.getRequestedPeriodStart()),
                LocalDate.parse(report.getRequestedPeriodEnd())
        );

        report.setS3Key(s3Key);
        report.setCdnUrl(buildCdnUrl(s3Key));
        report.setCacheStatus("CACHE_MISS_GENERATED");

        String s3Url = String.format("%s/%s/%s", s3Endpoint, bucket, s3Key);

        try {
            String jsonPayload = objectMapper.writeValueAsString(report);
            byte[] contentBytes = jsonPayload.getBytes(StandardCharsets.UTF_8);

            HttpHeaders headers = buildS3AuthHeaders("PUT", "/" + bucket + "/" + s3Key, jsonPayload);
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setContentLength(contentBytes.length);

            HttpEntity<byte[]> request = new HttpEntity<>(contentBytes, headers);
            ResponseEntity<String> response = restTemplate.exchange(s3Url, HttpMethod.PUT, request, String.class);

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully persisted report to S3 for user '{}' at {}", report.getUsername(), s3Key);
            } else {
                log.warn("Failed to persist report to S3, status: {}", response.getStatusCode());
            }
        } catch (Exception e) {
            log.error("Error persisting report to S3: {}", e.getMessage());
        }
    }

    /**
     * Generates standard AWS S3 v4 authorization headers for S3 REST API calls.
     */
    private HttpHeaders buildS3AuthHeaders(String method, String canonicalUri, String payload) {
        HttpHeaders headers = new HttpHeaders();
        try {
            SimpleDateFormat amzFormat = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'");
            amzFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
            SimpleDateFormat dateStampFormat = new SimpleDateFormat("yyyyMMdd");
            dateStampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));

            Date now = new Date();
            String amzDate = amzFormat.format(now);
            String dateStamp = dateStampFormat.format(now);

            byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
            String payloadHash = hexHash(sha256(payloadBytes));

            headers.set("x-amz-date", amzDate);
            headers.set("x-amz-content-sha256", payloadHash);

            String canonicalHeaders = "host:" + getHost(s3Endpoint) + "\n" +
                                     "x-amz-content-sha256:" + payloadHash + "\n" +
                                     "x-amz-date:" + amzDate + "\n";
            String signedHeaders = "host;x-amz-content-sha256;x-amz-date";

            String canonicalRequest = method + "\n" +
                                     canonicalUri + "\n" +
                                     "\n" +
                                     canonicalHeaders + "\n" +
                                     signedHeaders + "\n" +
                                     payloadHash;

            String algorithm = "AWS4-HMAC-SHA256";
            String credentialScope = dateStamp + "/us-east-1/s3/aws4_request";
            String stringToSign = algorithm + "\n" +
                                  amzDate + "\n" +
                                  credentialScope + "\n" +
                                  hexHash(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));

            byte[] kSecret = ("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8);
            byte[] kDate = hmacSha256(kSecret, dateStamp);
            byte[] kRegion = hmacSha256(kDate, "us-east-1");
            byte[] kService = hmacSha256(kRegion, "s3");
            byte[] kSigning = hmacSha256(kService, "aws4_request");

            String signature = hexHash(hmacSha256(kSigning, stringToSign));

            String authorizationHeader = algorithm + " " +
                    "Credential=" + accessKey + "/" + credentialScope + ", " +
                    "SignedHeaders=" + signedHeaders + ", " +
                    "Signature=" + signature;

            headers.set(HttpHeaders.AUTHORIZATION, authorizationHeader);
        } catch (Exception e) {
            log.warn("Failed to generate S3 v4 auth header: {}", e.getMessage());
        }
        return headers;
    }

    private String getHost(String url) {
        String host = url.replace("http://", "").replace("https://", "");
        if (host.contains("/")) {
            host = host.substring(0, host.indexOf("/"));
        }
        return host;
    }

    private byte[] sha256(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return md.digest(data);
    }

    private byte[] hmacSha256(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private String hexHash(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
