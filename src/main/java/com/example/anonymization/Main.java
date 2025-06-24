package com.example.anonymization;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class Main {

    public static void main(String[] args) {
        System.out.println("Starting Anonymization Process (New Flow)...");

        if (args.length != 5) {
            System.err.println("Usage: java com.example.anonymization.Main <email> <kyu_score> <overall_sensitivity> <input_path> <output_path>");
            return;
        }

        String email = args[0];
        String kyuScoreCli = args[1];
        String overallSensitivityCli = args[2];
        String inputPath = args[3];
        String outputPath = args[4];

        System.out.println("--- Configuration (from CLI args) ---");
        System.out.println("Email: " + email);
        System.out.println("KYU Score: " + kyuScoreCli);
        System.out.println("Overall Sensitivity: " + overallSensitivityCli);
        System.out.println("Input File Path: " + inputPath);
        System.out.println("Output File Path: " + outputPath);
        System.out.println("--- End Configuration ---");

        try {
            List<AttributeValueEntry> entries = DataLoader.loadAttributeValues(inputPath);
            System.out.println("Loaded " + entries.size() + " attribute-value entries from " + inputPath);

            for (int i = 0; i < Math.min(entries.size(), 5); i++) {
                System.out.println("Entry " + i + ": " + entries.get(i));
            }

            List<String> anonymizedValuesForOutput = new ArrayList<>();
            double totalDistance = 0.0;
            int processedValueCount = 0;

            for (AttributeValueEntry entry : entries) {
                String attributeName = entry.getAttributeName();
                String originalValue = entry.getAttributeValue();

                List<String> selectedStrategies = StrategySelector.getStrategies(
                        "cell",
                        overallSensitivityCli.toLowerCase(),
                        kyuScoreCli.toLowerCase()
                );

                System.out.println("For value '" + originalValue + "' (attr: " + attributeName + "), selected strategies: " + selectedStrategies);

                SingleAnonymizationResult anonymizationResult = AnonymizationService.anonymizeSingleValue(
                        originalValue,
                        attributeName,
                        kyuScoreCli,
                        overallSensitivityCli,
                        selectedStrategies
                );

                String outputLine = anonymizationResult.getAnonymizedValue() + "::" + anonymizationResult.getAppliedStrategy();
                anonymizedValuesForOutput.add(outputLine);

                System.out.println("Original: " + originalValue +
                        " -> Anonymized: " + anonymizationResult.getAnonymizedValue() +
                        " (Strategy: " + anonymizationResult.getAppliedStrategy() + ")");

                double distance = calculateDistance(entry.getAttributeValue(), anonymizationResult.getAnonymizedValue());
                totalDistance += distance;
                processedValueCount++;
            }

            Files.write(Paths.get(outputPath), anonymizedValuesForOutput, StandardCharsets.UTF_8);
            System.out.println(anonymizedValuesForOutput.size() + " anonymized values written to: " + outputPath);

            if (processedValueCount > 0) {
                double anonymizationScore = totalDistance / processedValueCount;
                System.out.println(String.format("AlphaScore:%.4f", anonymizationScore));
            } else {
                System.out.println("AlphaScore:0.0000");
            }

        } catch (Exception e) {
            System.err.println("Error during anonymization process: " + e.getMessage());
            e.printStackTrace();
            System.out.println("AlphaScore:Error");
        }

        System.out.println("\nAnonymization Process Completed (New Flow).");
    }

    public static double calculateDistance(String originalValue, String anonymizedValue) {
        if (originalValue == null && anonymizedValue == null) {
            return 0.0;
        }
        if (anonymizedValue == null || originalValue == null) {
            return 1.0;
        }

        if (originalValue.equals(anonymizedValue)) {
            return 0.0;
        }

        if (anonymizedValue.equals("REDACTED") || anonymizedValue.equals("SUPPRESSED") || anonymizedValue.matches("^\\*+$")) {
            return 1.0;
        }

        try {
            double origNum = Double.parseDouble(originalValue);
            double anonNum = Double.parseDouble(anonymizedValue);

            if (origNum == 0.0) {
                return 1.0;
            }
            return Math.min(1.0, Math.abs(origNum - anonNum) / Math.abs(origNum));
        } catch (NumberFormatException e) {
            return 1.0 - levenshteinSimilarity(originalValue, anonymizedValue);
        }
    }

    public static double levenshteinSimilarity(String s1, String s2) {
        int distance = levenshteinDistance(s1, s2);
        int maxLen = Math.max(s1.length(), s2.length());
        if (maxLen == 0) return 1.0;
        return 1.0 - ((double) distance / maxLen);
    }

    public static int levenshteinDistance(String s1, String s2) {
        int[][] dp = new int[s1.length() + 1][s2.length() + 1];

        for (int i = 0; i <= s1.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= s2.length(); j++) dp[0][j] = j;

        for (int i = 1; i <= s1.length(); i++) {
            for (int j = 1; j <= s2.length(); j++) {
                if (s1.charAt(i - 1) == s2.charAt(j - 1)) {
                    dp[i][j] = dp[i - 1][j - 1];
                } else {
                    dp[i][j] = 1 + Math.min(
                            dp[i - 1][j - 1],
                            Math.min(dp[i - 1][j], dp[i][j - 1])
                    );
                }
            }
        }
        return dp[s1.length()][s2.length()];
    }
}