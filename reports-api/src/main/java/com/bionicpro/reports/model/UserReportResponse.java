package com.bionicpro.reports.model;

import java.util.List;
import java.util.Map;

public class UserReportResponse {
    private String username;
    private String clientName;
    private String clientEmail;
    private String prostheticId;
    private String prostheticModel;
    private String requestedPeriodStart;
    private String requestedPeriodEnd;
    private String processedBoundaryDate;
    private boolean containsUnprocessedPeriod;
    private String notice;
    private int daysCovered;
    private long totalActiveSeconds;
    private long totalGesturesPerformed;
    private double avgMuscleSignalAmplitude;
    private double maxMuscleSignalAmplitude;
    private double avgBatteryLevel;
    private int batteryChargeCycles;
    private double avgLatencyMs;
    private int totalWarnings;
    private String cdnUrl;
    private String s3Key;
    private String cacheStatus; // "CACHE_HIT_S3_CDN", "CACHE_MISS_GENERATED"
    private List<Map<String, Object>> dailyDetails;

    public UserReportResponse() {}

    public String getCdnUrl() {
        return cdnUrl;
    }

    public void setCdnUrl(String cdnUrl) {
        this.cdnUrl = cdnUrl;
    }

    public String getS3Key() {
        return s3Key;
    }

    public void setS3Key(String s3Key) {
        this.s3Key = s3Key;
    }

    public String getCacheStatus() {
        return cacheStatus;
    }

    public void setCacheStatus(String cacheStatus) {
        this.cacheStatus = cacheStatus;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getClientName() {
        return clientName;
    }

    public void setClientName(String clientName) {
        this.clientName = clientName;
    }

    public String getClientEmail() {
        return clientEmail;
    }

    public void setClientEmail(String clientEmail) {
        this.clientEmail = clientEmail;
    }

    public String getProstheticId() {
        return prostheticId;
    }

    public void setProstheticId(String prostheticId) {
        this.prostheticId = prostheticId;
    }

    public String getProstheticModel() {
        return prostheticModel;
    }

    public void setProstheticModel(String prostheticModel) {
        this.prostheticModel = prostheticModel;
    }

    public String getRequestedPeriodStart() {
        return requestedPeriodStart;
    }

    public void setRequestedPeriodStart(String requestedPeriodStart) {
        this.requestedPeriodStart = requestedPeriodStart;
    }

    public String getRequestedPeriodEnd() {
        return requestedPeriodEnd;
    }

    public void setRequestedPeriodEnd(String requestedPeriodEnd) {
        this.requestedPeriodEnd = requestedPeriodEnd;
    }

    public String getProcessedBoundaryDate() {
        return processedBoundaryDate;
    }

    public void setProcessedBoundaryDate(String processedBoundaryDate) {
        this.processedBoundaryDate = processedBoundaryDate;
    }

    public boolean isContainsUnprocessedPeriod() {
        return containsUnprocessedPeriod;
    }

    public void setContainsUnprocessedPeriod(boolean containsUnprocessedPeriod) {
        this.containsUnprocessedPeriod = containsUnprocessedPeriod;
    }

    public String getNotice() {
        return notice;
    }

    public void setNotice(String notice) {
        this.notice = notice;
    }

    public int getDaysCovered() {
        return daysCovered;
    }

    public void setDaysCovered(int daysCovered) {
        this.daysCovered = daysCovered;
    }

    public long getTotalActiveSeconds() {
        return totalActiveSeconds;
    }

    public void setTotalActiveSeconds(long totalActiveSeconds) {
        this.totalActiveSeconds = totalActiveSeconds;
    }

    public long getTotalGesturesPerformed() {
        return totalGesturesPerformed;
    }

    public void setTotalGesturesPerformed(long totalGesturesPerformed) {
        this.totalGesturesPerformed = totalGesturesPerformed;
    }

    public double getAvgMuscleSignalAmplitude() {
        return avgMuscleSignalAmplitude;
    }

    public void setAvgMuscleSignalAmplitude(double avgMuscleSignalAmplitude) {
        this.avgMuscleSignalAmplitude = avgMuscleSignalAmplitude;
    }

    public double getMaxMuscleSignalAmplitude() {
        return maxMuscleSignalAmplitude;
    }

    public void setMaxMuscleSignalAmplitude(double maxMuscleSignalAmplitude) {
        this.maxMuscleSignalAmplitude = maxMuscleSignalAmplitude;
    }

    public double getAvgBatteryLevel() {
        return avgBatteryLevel;
    }

    public void setAvgBatteryLevel(double avgBatteryLevel) {
        this.avgBatteryLevel = avgBatteryLevel;
    }

    public int getBatteryChargeCycles() {
        return batteryChargeCycles;
    }

    public void setBatteryChargeCycles(int batteryChargeCycles) {
        this.batteryChargeCycles = batteryChargeCycles;
    }

    public double getAvgResponseLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgResponseLatencyMs(double avgResponseLatencyMs) {
        this.avgLatencyMs = avgResponseLatencyMs;
    }

    public double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgLatencyMs(double avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    public int getTotalWarnings() {
        return totalWarnings;
    }

    public void setTotalWarnings(int totalWarnings) {
        this.totalWarnings = totalWarnings;
    }

    public List<Map<String, Object>> getDailyDetails() {
        return dailyDetails;
    }

    public void setDailyDetails(List<Map<String, Object>> dailyDetails) {
        this.dailyDetails = dailyDetails;
    }
}
