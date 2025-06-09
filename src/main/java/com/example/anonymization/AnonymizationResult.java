package com.example.anonymization;

public class AnonymizationResult {
    private final SimpleDataFrame anonymizedDataFrame;
    private final String appliedStrategy;

    public AnonymizationResult(SimpleDataFrame anonymizedDataFrame, String appliedStrategy) {
        this.anonymizedDataFrame = anonymizedDataFrame;
        this.appliedStrategy = appliedStrategy;
    }

    public SimpleDataFrame getAnonymizedDataFrame() {
        return anonymizedDataFrame;
    }

    public String getAppliedStrategy() {
        return appliedStrategy;
    }
}
