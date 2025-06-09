package com.example.anonymization;

import java.util.List;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.Objects;

public class StrategyVerifier {

    record TestCase(String dataLevel, String sensitivity, String kyuScore, List<String> expectedStrategies) {}

    public static void main(String[] args) {
        List<TestCase> testCases = new ArrayList<>();

        // Cell Level
        testCases.add(new TestCase("cell", "high", "high", Arrays.asList("cell_suppression")));
        testCases.add(new TestCase("cell", "high", "moderate", Arrays.asList("cell_suppression")));
        testCases.add(new TestCase("cell", "high", "low", Arrays.asList("cell_suppression")));
        testCases.add(new TestCase("cell", "moderate", "high", Arrays.asList("cell_suppression")));
        testCases.add(new TestCase("cell", "moderate", "moderate", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("cell", "moderate", "low", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("cell", "low", "high", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("cell", "low", "moderate", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("cell", "low", "low", Arrays.asList("no_transformation")));

        // Row Level
        testCases.add(new TestCase("row", "high", "high", Arrays.asList("record_suppression", "pseudonymization")));
        testCases.add(new TestCase("row", "high", "moderate", Arrays.asList("record_suppression", "generalization")));
        testCases.add(new TestCase("row", "high", "low", Arrays.asList("record_suppression", "swapping")));
        testCases.add(new TestCase("row", "moderate", "high", Arrays.asList("pseudonymization", "generalization")));
        testCases.add(new TestCase("row", "moderate", "moderate", Arrays.asList("generalization", "swapping")));
        testCases.add(new TestCase("row", "moderate", "low", Arrays.asList("swapping")));
        testCases.add(new TestCase("row", "low", "high", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("row", "low", "moderate", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("row", "low", "low", Arrays.asList("no_transformation")));

        // Column Level
        testCases.add(new TestCase("column", "high", "high", Arrays.asList("pseudonymization", "generalization")));
        testCases.add(new TestCase("column", "high", "moderate", Arrays.asList("generalization", "swapping")));
        testCases.add(new TestCase("column", "high", "low", Arrays.asList("swapping")));
        testCases.add(new TestCase("column", "moderate", "high", Arrays.asList("generalization")));
        testCases.add(new TestCase("column", "moderate", "moderate", Arrays.asList("swapping")));
        testCases.add(new TestCase("column", "moderate", "low", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("column", "low", "high", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("column", "low", "moderate", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("column", "low", "low", Arrays.asList("no_transformation")));

        // Table Level
        testCases.add(new TestCase("table", "high", "high", Arrays.asList("aggregation", "microaggregation")));
        testCases.add(new TestCase("table", "high", "moderate", Arrays.asList("microaggregation", "partial_masking")));
        testCases.add(new TestCase("table", "high", "low", Arrays.asList("partial_masking", "full_masking")));
        testCases.add(new TestCase("table", "moderate", "high", Arrays.asList("microaggregation")));
        // Special case from image: (Table, Moderate, Moderate KYU) -> ["microaggregation", "partial_masking"]
        testCases.add(new TestCase("table", "moderate", "moderate", Arrays.asList("microaggregation", "partial_masking")));
        testCases.add(new TestCase("table", "moderate", "low", Arrays.asList("full_masking")));
        testCases.add(new TestCase("table", "low", "high", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("table", "low", "moderate", Arrays.asList("no_transformation")));
        testCases.add(new TestCase("table", "low", "low", Arrays.asList("no_transformation")));

        int passedTests = 0;
        for (TestCase tc : testCases) {
            List<String> actualStrategies = StrategySelector.getStrategies(tc.dataLevel(), tc.sensitivity(), tc.kyuScore());

            System.out.println("Test Case:");
            System.out.println("  Data Level: " + tc.dataLevel());
            System.out.println("  Sensitivity: " + tc.sensitivity());
            System.out.println("  KYU Score: " + tc.kyuScore());
            System.out.println("  Expected Strategies: " + tc.expectedStrategies());
            System.out.println("  Actual Strategies: " + actualStrategies);

            if (Objects.equals(actualStrategies, tc.expectedStrategies())) {
                System.out.println("  Result: PASS");
                passedTests++;
            } else {
                System.out.println("  Result: FAIL");
            }
            System.out.println("--------------------------------------");
        }

        System.out.println("\nSummary: " + passedTests + "/" + testCases.size() + " tests passed.");
    }
}
