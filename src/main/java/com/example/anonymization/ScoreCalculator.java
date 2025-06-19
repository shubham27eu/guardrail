package com.example.anonymization;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List; // Manually adding based on SimpleDataFrame usage, prompt was missing this

public class ScoreCalculator {

    private static final Set<String> SUPPRESSION_MARKERS = new HashSet<>(Arrays.asList(
        "*", "****", "REDACTED", "SUPPRESSED" 
        // Add more markers if needed, ensure they are lowercase if comparing lowercase anon strings
    ));

    private static double delta(Object orig, Object anon) {
        String anonStr = (anon == null) ? null : String.valueOf(anon).trim(); // Trim for robust marker check

        if (anon == null || (anonStr != null && SUPPRESSION_MARKERS.contains(anonStr))) {
            return 1.0;
        }
        
        // Handle cases where orig and anon might be numerically equal but different types (e.g., Integer 1 vs Double 1.0)
        if (orig instanceof Number && anon instanceof Number) {
            if (((Number) orig).doubleValue() == ((Number) anon).doubleValue()) {
                return 0.0;
            }
        } else if (Objects.equals(orig, anon)) { // General equality for non-numeric or same-type objects
            return 0.0;
        }

        // Check for generalized range string in anon, e.g., "30-40"
        if (anonStr != null && anonStr.contains("-") && !anonStr.startsWith("-")) { // !startsWith("-") to avoid negative numbers
            // Simple rule from Python: 0.5 if it's a range.
            // A more complex rule might try to see if orig is within the range.
            // For now, stick to the 0.5 fixed penalty.
             boolean looksLikeRange = false;
             try {
                String[] parts = anonStr.split("-");
                if (parts.length == 2) {
                    Double.parseDouble(parts[0].trim());
                    Double.parseDouble(parts[1].trim());
                    looksLikeRange = true;
                }
             } catch (NumberFormatException e) { /* Not a numeric range */ }
             if (looksLikeRange) return 0.5;
        }

        // Try numeric comparison for relative difference
        try {
            double origVal = Double.parseDouble(String.valueOf(orig)); // orig can be null here, String.valueOf(null) is "null"
            double anonVal = Double.parseDouble(String.valueOf(anon)); // anon is confirmed not null here

            if (origVal == 0.0) {
                return (anonVal == 0.0) ? 0.0 : 1.0; // If orig is 0, distance is 1 unless anon is also 0.
            }
            // The formula Math.abs(origVal - anonVal) / Math.abs(origVal) can exceed 1.0.
            // Capped at 1.0 as per problem description (delta usually <= 1.0).
            return Math.min(1.0, Math.abs(origVal - anonVal) / Math.abs(origVal));
        } catch (NumberFormatException | NullPointerException e) { // Catch if orig was null and String.valueOf(orig) was parsed
            // Fallback for non-numeric types that are different, or one is null and other isn't (and not a suppression marker)
            return 1.0;
        }
    }

    public static AnonymizationScore calculateScore(SimpleDataFrame originalDf, SimpleDataFrame anonymizedDf) {
        if (originalDf == null || anonymizedDf == null) {
            throw new IllegalArgumentException("Input DataFrames cannot be null.");
        }
        if (originalDf.getRowCount() != anonymizedDf.getRowCount() ||
            originalDf.getColumnCount() != anonymizedDf.getColumnCount()) {
            // Note: Prompt's version doesn't check column headers, previous version did. Adhering to prompt.
            throw new IllegalArgumentException("DataFrames must have the same shape.");
        }

        int nRows = originalDf.getRowCount();
        int nCols = originalDf.getColumnCount();
        long totalCells = (long) nRows * nCols;

        if (totalCells == 0) {
            return new AnonymizationScore(0.0, 1.0); // No cells to compare, perfect utility or undefined score
        }

        double totalDistance = 0.0;

        // These lines require "import java.util.List;"
        List<String> columnHeaders = originalDf.getColumnHeaders(); 
        List<Map<String, Object>> originalRows = originalDf.getRows();
        List<Map<String, Object>> anonymizedRows = anonymizedDf.getRows();

        for (int i = 0; i < nRows; i++) {
            Map<String, Object> origRow = originalRows.get(i);
            Map<String, Object> anonRow = anonymizedRows.get(i);
            for (String colName : columnHeaders) { // Iterate in defined header order
                totalDistance += delta(origRow.get(colName), anonRow.get(colName));
            }
        }

        double anonymizationScoreValue = totalDistance / totalCells;
        double utilityRetained = 1.0 - anonymizationScoreValue;
        
        // Ensure scores are within [0,1] due to potential floating point inaccuracies if many cells
        anonymizationScoreValue = Math.max(0.0, Math.min(1.0, anonymizationScoreValue));
        utilityRetained = Math.max(0.0, Math.min(1.0, utilityRetained));

        return new AnonymizationScore(anonymizationScoreValue, utilityRetained);
    }
}
