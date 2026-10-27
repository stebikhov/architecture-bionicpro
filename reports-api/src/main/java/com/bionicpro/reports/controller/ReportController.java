package com.bionicpro.reports.controller;

import com.bionicpro.reports.model.UserReportResponse;
import com.bionicpro.reports.service.ClickHouseReportService;
import com.bionicpro.reports.service.S3ReportStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

@RestController
public class ReportController {

    private static final Logger log = LoggerFactory.getLogger(ReportController.class);
    private final ClickHouseReportService clickHouseReportService;
    private final S3ReportStorageService s3ReportStorageService;

    public ReportController(ClickHouseReportService clickHouseReportService,
                            S3ReportStorageService s3ReportStorageService) {
        this.clickHouseReportService = clickHouseReportService;
        this.s3ReportStorageService = s3ReportStorageService;
    }

    /**
     * Endpoint /reports for generating and retrieving user reports.
     *
     * Optimization & Caching flow:
     * 1. Checks if report already exists in S3 object storage (MinIO/Ceph).
     *    If found -> returns cached report with CDN URL immediately (CACHE_HIT_S3_CDN),
     *    avoiding any queries to the ClickHouse OLAP database.
     * 2. If not found -> generates report from ClickHouse datamart, uploads it to S3,
     *    and returns the report with CDN URL (CACHE_MISS_GENERATED).
     * 3. Reverse-proxy CDN (Nginx) caches responses with edge expiration and ETag/Cache-Control.
     */
    @GetMapping("/reports")
    public ResponseEntity<?> getReports(
            @AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "startDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate startDate,
            @RequestParam(value = "endDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate endDate,
            @RequestParam(value = "targetUser", required = false) String targetUser,
            @RequestParam(value = "forceRefresh", required = false, defaultValue = "false") boolean forceRefresh) {

        if (jwt == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Unauthorized: Valid token required"));
        }

        // Extract authenticated username from JWT
        String currentUsername = jwt.getClaimAsString("preferred_username");
        if (currentUsername == null || currentUsername.isBlank()) {
            currentUsername = jwt.getClaimAsString("sub");
        }
        if (currentUsername == null || currentUsername.isBlank()) {
            currentUsername = "user";
        }

        // Restriction Check: Users can only generate/view reports in relation to themselves
        // Any attempt to request a different user is rejected with 403 Forbidden
        if (targetUser != null && !targetUser.isBlank() && !targetUser.trim().equalsIgnoreCase(currentUsername.trim())) {
            log.warn("Access denied: User '{}' attempted to access report of '{}'", currentUsername, targetUser);
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "Access denied: You can only view reports for your own profile and prosthetics"
            ));
        }

        if (startDate == null) {
            startDate = LocalDate.now().minusDays(7);
        }
        if (endDate == null) {
            endDate = LocalDate.now();
        }

        log.info("Report request for user '{}' (period: {} to {}, forceRefresh: {})", currentUsername, startDate, endDate, forceRefresh);

        // STEP 1: Check S3 Object Storage cache first (eliminates duplicate OLAP load)
        if (!forceRefresh) {
            Optional<UserReportResponse> cachedReport = s3ReportStorageService.findReportInS3(currentUsername, startDate, endDate);
            if (cachedReport.isPresent()) {
                return ResponseEntity.ok(cachedReport.get());
            }
        }

        // STEP 2: Cache miss - Query OLAP datamart (ClickHouse)
        log.info("Generating report from ClickHouse OLAP for user '{}'", currentUsername);
        UserReportResponse freshReport = clickHouseReportService.getReportForUser(currentUsername, startDate, endDate);

        // STEP 3: Store generated report in S3 and attach CDN URL
        s3ReportStorageService.saveReportToS3(freshReport);

        return ResponseEntity.ok(freshReport);
    }

    @GetMapping("/health")
    public ResponseEntity<?> health() {
        return ResponseEntity.ok(Map.of("status", "UP", "service", "reports-api"));
    }
}
