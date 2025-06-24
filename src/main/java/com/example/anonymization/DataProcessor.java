package com.example.anonymization;

import java.util.ArrayList;
import java.util.ArrayList;
import java.util.HashMap; // Added for sensitivityRank
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DataProcessor {

    // getMaxSensitivityLevel and determineQueryResultType are removed,
    // so sensitivityRank and reverseSensitivityRank maps and their static initializer are also removed.

    /**
     * Filters a list of SensitivityResult objects to include only those with "High" or "Moderate"
     * sensitivity levels, and then extracts their attribute IDs.
     *
     * @param sensitivityResults The full list of SensitivityResult objects.
     * @return A list of attribute IDs (Strings) corresponding to high or moderate sensitivity.
     */
    public static List<String> extractSensitiveAttributeIds(List<SensitivityResult> sensitivityResults) {
        if (sensitivityResults == null) {
            return List.of(); // Or throw IllegalArgumentException
        }

        return sensitivityResults.stream()
                .filter(sr -> "High".equalsIgnoreCase(sr.getSensitivityLevel()) ||
                               "Moderate".equalsIgnoreCase(sr.getSensitivityLevel()))
                .map(SensitivityResult::getAttributeId)
                .map(String::valueOf) // Ensure it's a string, though getter should already return String
                .collect(Collectors.toList());
    }

    // Add other data processing methods here in later steps

    /**
     * Identifies quasi-identifier columns from a SimpleDataFrame based on unique value ratios.
     *
     * @param dataFrame The input SimpleDataFrame.
     * @param minUniqueRatio The minimum ratio of unique values to total rows for a column to be considered a QID.
     * @param maxUniqueRatio The maximum ratio of unique values to total rows for a column to be considered a QID.
     * @return A list of column names identified as quasi-identifiers.
     */
    public static List<String> identifyQuasiIdentifiers(SimpleDataFrame dataFrame, double minUniqueRatio, double maxUniqueRatio) {
        if (dataFrame == null || dataFrame.getRowCount() == 0) {
            return List.of();
        }

        List<String> qids = new ArrayList<>();
        List<String> columnHeaders = dataFrame.getColumnHeaders();
        int n = dataFrame.getRowCount();

        for (String colName : columnHeaders) {
            List<Object> columnData = dataFrame.getColumnData(colName);
            
            Set<Object> uniqueValues = new HashSet<>();
            for (Object value : columnData) {
                if (value != null) { // Simulating dropna=True by only adding non-nulls
                    uniqueValues.add(value);
                }
            }
            int nunique = uniqueValues.size();
            
            double ratio = (n > 0) ? (double) nunique / n : 0.0;

            // Condition from notebook: 1 < nunique < n AND min_unique_ratio <= ratio <= max_unique_ratio
            // Ensure nunique < n means not all values are unique (which would make it a direct identifier)
            // Ensure 1 < nunique means it's not a constant value column.
            if (nunique > 1 && nunique < n && ratio >= minUniqueRatio && ratio <= maxUniqueRatio) {
                qids.add(colName);
            }
        }
        return qids;
    }

    // Removed determineQueryResultType method
    // Removed getMaxSensitivityLevel method
    // Removed sensitivityRank and reverseSensitivityRank maps and their static initializer
}
