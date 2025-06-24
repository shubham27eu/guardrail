package com.example.anonymization;

public class KyuScore {
    private String userId;
    private String kyuScore; // Storing as String e.g., "Low", "Moderate", "High"

    public KyuScore(String userId, String kyuScore) {
        this.userId = userId;
        this.kyuScore = kyuScore;
    }

    public String getUserId() {
        return userId;
    }

    public String getKyuScore() {
        return kyuScore;
    }

    @Override
    public String toString() {
        return "KyuScore{" +
               "userId='" + userId + '\'' +
               ", kyuScore='" + kyuScore + '\'' +
               '}';
    }
}
