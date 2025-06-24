package com.example.anonymization;

public class SingleAnonymizationResult {
    private final String anonymizedValue;
    private final String appliedStrategy;

    public SingleAnonymizationResult(String anonymizedValue, String appliedStrategy) {
        this.anonymizedValue = anonymizedValue;
        this.appliedStrategy = appliedStrategy;
    }

    public String getAnonymizedValue() {
        return anonymizedValue;
    }

    public String getAppliedStrategy() {
        return appliedStrategy;
    }
}
