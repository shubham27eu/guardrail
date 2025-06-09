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

    /**
     * Determines the type of a query result based on the dimensions of the SimpleDataFrame.
     *
     * @param resultDataFrame The input SimpleDataFrame representing the query result.
     * @return A string indicating the result type: "table", "column", "row", or "cell".
     */
    public static String determineQueryResultType(SimpleDataFrame resultDataFrame) {
        if (resultDataFrame == null) {
            // Or throw an IllegalArgumentException, though "cell" might be a safe default for empty/null.
            // The original Python code would likely error out if result_df was None before .shape
            // For robustness, let's treat null as "cell" or handle as an error case.
            // Given the python code, if shape could be accessed, it would fall to the else case.
            // So, if resultDataFrame is null, number of rows/cols can be considered 0 or 1.
            return "cell"; 
        }

        int rowCount = resultDataFrame.getRowCount();
        int colCount = resultDataFrame.getColumnCount();

        if (rowCount > 1 && colCount > 1) {
            return "table";
        } else if (rowCount > 1 && colCount == 1) {
            return "column";
        } else if (rowCount == 1 && colCount > 1) {
            return "row";
        } else { // Includes (1,1), (0,N), (N,0)
            return "cell";
        }
    }

    private static final Map<String, Integer> sensitivityRank = new HashMap<>();
    private static final Map<Integer, String> reverseSensitivityRank = new HashMap<>();

    static {
        sensitivityRank.put("Low", 1);
        sensitivityRank.put("Moderate", 2);
        sensitivityRank.put("High", 3);

        for (Map.Entry<String, Integer> entry : sensitivityRank.entrySet()) {
            reverseSensitivityRank.put(entry.getValue(), entry.getKey());
        }
    }

    /**
     * Determines the maximum sensitivity level from a list of columns in a DataFrame.
     *
     * @param resultDf The DataFrame whose columns' sensitivities are to be checked.
     * @param allSensitivities A list of all available SensitivityResult objects.
     * @return The maximum sensitivity level ("Low", "Moderate", or "High") found among the DataFrame's columns.
     * @throws IllegalArgumentException if no matching columns are found in the sensitivity mapping,
     *         or if the input DataFrame is null/empty.
     */
    public static String getMaxSensitivityLevel(SimpleDataFrame resultDf, List<SensitivityResult> allSensitivities) {
        if (resultDf == null || resultDf.getColumnCount() == 0) {
            throw new IllegalArgumentException("Input DataFrame cannot be null or empty.");
        }
        if (allSensitivities == null) {
            throw new IllegalArgumentException("Sensitivity mapping list cannot be null.");
        }

        Set<String> dfColumnNames = resultDf.getColumnHeaders().stream()
                                        .map(String::valueOf) // Ensure they are strings
                                        .collect(Collectors.toSet());

        List<SensitivityResult> matchedSensitivities = allSensitivities.stream()
                .filter(sr -> dfColumnNames.contains(String.valueOf(sr.getAttributeId())))
                .collect(Collectors.toList());

        if (matchedSensitivities.isEmpty()) {
            // Python code raises ValueError: "No matching columns found between DataFrame and sensitivity mapping."
            // The warning in python: "A value is trying to be set on a copy of a slice from a DataFrame" is for pandas, not relevant here.
            throw new IllegalArgumentException("No matching columns found between DataFrame and sensitivity mapping for columns: " + dfColumnNames);
        }

        int maxRank = 0;
        for (SensitivityResult sr : matchedSensitivities) {
            // Use toLowerCase for sensitivity level matching to be robust, though ranks are defined with specific casing
            String level = sr.getSensitivityLevel();
            // Find the rank, being case-insensitive for the key lookup in sensitivityRank if needed,
            // or ensure data consistency. For now, assume exact match or pre-cleaned data.
            // A more robust way:
            int currentRank = sensitivityRank.entrySet().stream()
                                .filter(entry -> entry.getKey().equalsIgnoreCase(level))
                                .map(Map.Entry::getValue)
                                .findFirst()
                                .orElse(0); // Default to 0 if level string is not recognized

            if (currentRank > maxRank) {
                maxRank = currentRank;
            }
        }
        
        if (maxRank == 0) {
            // This implies that columns were matched, but their sensitivity levels were not "Low", "Moderate", or "High"
            // or were not found in the sensitivityRank map.
            // Python code would raise KeyError from reverse_rank[0].
            // Let's return "Low" as a default as per earlier reasoning for safety, or throw an error.
            // Given the Python behavior, an error or specific handling for "unknown" might be better.
            // For now, let's align with a safer default if ranks are all zero for matched columns.
             System.err.println("Warning: Matched columns found, but no recognized sensitivity levels ('Low', 'Moderate', 'High'). Defaulting to Low.");
            return "Low";
        }

        return reverseSensitivityRank.get(maxRank);
    }
}
