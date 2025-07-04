package com.example.anonymization;

import java.util.List;
// Assuming AnonymizationTechniques is in the same package

public class AnonymizationService {

    /**
     * Applies the first successful anonymization strategy from a list to a DataFrame.
     *
     * @param originalDf The DataFrame to anonymize.
     * @param strategies A list of strategy names to attempt.
     * @param granularity The granularity of the data ("cell", "column", "row", "table").
     * @param kyuScore The user's KYU score/trust level (e.g., "low", "moderate", "high") - needed for some strategies.
     * @return An AnonymizationResult containing the anonymized DataFrame and the applied strategy name.
     *         Returns the original DataFrame and null strategy if no strategy is applied or successful.
     */
    public static AnonymizationResult anonymizeBySensitivity(
            SimpleDataFrame originalDf, 
            List<String> strategies, 
            String granularity,
            String kyuScore // Added kyuScore as it might be needed for some strategy parameterization
                           // e.g. epsilon for noise_injection if not hardcoded
    ) {
        if (originalDf == null || strategies == null || strategies.isEmpty()) {
            return new AnonymizationResult(originalDf, null); // Or throw exception
        }

        SimpleDataFrame dfCopy = originalDf.copy(); // Work on a deep copy
        String appliedStrategyName = null;

        for (String strategy : strategies) {
            SimpleDataFrame tempDf = dfCopy.copy(); // Use a copy for each attempt from the current state of dfCopy
            boolean strategySuccessfullyApplied = true;

            try {
                // CELL and COLUMN granularities often iterate over columns
                if ("cell".equals(granularity) || "column".equals(granularity)) {
                    for (String columnName : tempDf.getColumnHeaders()) {
                        // Note: Some strategies from Python are column-specific by name,
                        // others apply to all columns if listed under cell/column granularity.
                        // The Python code applies the chosen strategy to *all* columns if granularity is cell/column.
                        applyStrategyToColumn(tempDf, columnName, strategy, kyuScore);
                    }
                } else if ("row".equals(granularity)) {
                    applyStrategyToRow(tempDf, strategy, kyuScore);
                } else if ("table".equals(granularity)) {
                    applyStrategyToTable(tempDf, strategy, kyuScore);
                } else {
                    // Unknown granularity, maybe log a warning
                    System.err.println("Warning: Unknown granularity: " + granularity);
                    strategySuccessfullyApplied = false;
                }
                
                // If no exception, commit changes from tempDf to dfCopy
                if (strategySuccessfullyApplied) {
                    dfCopy = tempDf; 
                    appliedStrategyName = strategy;
                    break; // Apply only the FIRST successful strategy
                }

            } catch (UnsupportedOperationException uoe) {
                System.err.println("Strategy '" + strategy + "' is not yet implemented. Trying next. " + uoe.getMessage());
                strategySuccessfullyApplied = false; // Mark as not applied
            } catch (Exception e) {
                System.err.println("Strategy '" + strategy + "' failed: " + e.getMessage() + ". Trying next.");
                // e.printStackTrace(); // for debugging
                strategySuccessfullyApplied = false; // Mark as not applied
            }
        }
        return new AnonymizationResult(dfCopy, appliedStrategyName);
    }

    private static void applyStrategyToColumn(SimpleDataFrame df, String columnName, String strategy, String kyuScore) {
        // Default parameters for strategies
        double defaultEpsilon = 0.1; 
        int defaultBinSize = 10; 
        int defaultK = 2; // For microaggregation_column

        switch (strategy.toLowerCase()) {
            case "full_masking": 
                AnonymizationTechniques.full_masking_cell(df, columnName);
                break;
            case "partial_masking": 
                AnonymizationTechniques.partial_masking_cell(df, columnName);
                break;
            case "noise_injection": 
                AnonymizationTechniques.noise_injection(df, columnName, defaultEpsilon);
                break;
            case "cell_suppression":
                AnonymizationTechniques.cell_suppression(df, columnName, 2); // Default threshold 2
                break;
            case "differential_privacy_column":
                AnonymizationTechniques.differential_privacy_column(df, columnName, defaultEpsilon);
                break;
            case "top_bottom_coding":
                AnonymizationTechniques.top_bottom_coding(df, columnName, 10.0, 90.0); // Default 10th and 90th percentiles
                break;
            case "microaggregation": 
                AnonymizationTechniques.microaggregation_column(df, columnName, defaultK); // defaultK is 2
                break;
            case "generalization":
                AnonymizationTechniques.generalization_column(df, columnName, 3); // Default 3 bins
                break;
            case "binning":
                AnonymizationTechniques.binning(df, columnName, defaultBinSize); // defaultBinSize is 10
                break;
            case "no_transformation":
                // Do nothing
                break;
            default:
                throw new IllegalArgumentException("Unknown strategy for column/cell granularity: " + strategy);
        }
    }

    private static void applyStrategyToRow(SimpleDataFrame df, String strategy, String kyuScore) {
        int defaultK = 2; // For microaggregation

        switch (strategy.toLowerCase()) {
            case "full_masking": 
                AnonymizationTechniques.full_masking_row(df);
                break;
            case "partial_masking": 
                AnonymizationTechniques.partial_masking_row(df);
                break;
            case "microaggregation": 
                SimpleDataFrame aggregated = AnonymizationTechniques.microaggregation_row(df, defaultK);
                // Replace df's content with aggregated's content
                // Basic check: if aggregated is null (e.g. due to error in microaggregation_row), don't proceed
                if (aggregated != null && aggregated.getColumnHeaders() != null && df.getColumnHeaders().equals(aggregated.getColumnHeaders())) { 
                    df.getRows().clear();
                    df.getRows().addAll(aggregated.getRows());
                } else if (aggregated != null && aggregated.getRowCount() == 0 && df.getRowCount() > 0) {
                    // If microaggregation results in an empty dataframe (e.g. k > number of rows)
                    // Clear original rows as well to reflect the aggregation.
                    df.getRows().clear();
                } else if (aggregated == null) {
                    System.err.println("Warning: microaggregation_row returned null. Original DataFrame unchanged.");
                } else {
                    System.err.println("Warning: Header mismatch or other issue after microaggregation_row. Original DataFrame unchanged.");
                }
                break;
            case "no_transformation":
                // Do nothing
                break;
            default:
                throw new IllegalArgumentException("Unknown strategy for row granularity: " + strategy);
        }
    }

    private static void applyStrategyToTable(SimpleDataFrame df, String strategy, String kyuScore) {
        double defaultEpsilon = 0.1; 
        int defaultK = 2; 
        int defaultThreshold = 2; 
        int defaultBins = 3;      

        switch (strategy.toLowerCase()) {
            case "full_masking":
                AnonymizationTechniques.full_masking_table(df);
                break;
            case "partial_masking":
                AnonymizationTechniques.partial_masking_table(df); 
                break;
            case "microaggregation":
                AnonymizationTechniques.microaggregation_table(df, defaultK);
                break;
            case "differential_privacy":
                AnonymizationTechniques.differential_privacy_table(df, defaultEpsilon);
                break;
            case "cell_suppression": 
                AnonymizationTechniques.cell_suppression_table(df, defaultThreshold);
                break;
            case "generalization": 
                AnonymizationTechniques.generalization_table(df, defaultBins);
                break;
            case "no_transformation":
                // Do nothing
                break;
            default:
                throw new IllegalArgumentException("Unknown strategy for table granularity: " + strategy);
        }
    }
}
