package com.example.anonymization;

public class AnonymizationScore {
    private final double score; // The calculated anonymization score (0 to 1, higher means more anonymized/less utility)
    private final double utilityRetained; // 1 - score

    public AnonymizationScore(double score, double utilityRetained) {
        this.score = score;
        this.utilityRetained = utilityRetained;
    }

    public double getScore() {
        return score;
    }

    public double getUtilityRetained() {
        return utilityRetained;
    }

    @Override
    public String toString() {
        return "AnonymizationScore{score=" + score + ", utilityRetained=" + utilityRetained + "}";
    }
}
