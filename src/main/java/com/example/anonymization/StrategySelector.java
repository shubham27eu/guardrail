package com.example.anonymization;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class StrategySelector {

    private static final Map<String, Map<String, Map<String, List<String>>>> strategiesMap = new HashMap<>();

    static {
        // Cell Data Level
        Map<String, Map<String, List<String>>> cellStrategies = new HashMap<>();
        
        // Cell - High Sensitivity
        Map<String, List<String>> cellHighSensitivity = new HashMap<>();
        cellHighSensitivity.put("low", Arrays.asList("cell_suppression", "differential_privacy_column", "full_masking"));
        cellHighSensitivity.put("moderate", Arrays.asList("top_bottom_coding", "microaggregation", "partial_masking"));
        cellHighSensitivity.put("high", Arrays.asList("noise_injection"));
        cellStrategies.put("high", cellHighSensitivity);

        // Cell - Moderate Sensitivity
        Map<String, List<String>> cellModerateSensitivity = new HashMap<>();
        cellModerateSensitivity.put("low", Arrays.asList("microaggregation"));
        cellModerateSensitivity.put("moderate", Arrays.asList("generalization", "partial_masking"));
        cellModerateSensitivity.put("high", Arrays.asList("no_transformation"));
        cellStrategies.put("moderate", cellModerateSensitivity);

        // Cell - Low Sensitivity
        Map<String, List<String>> cellLowSensitivity = new HashMap<>();
        cellLowSensitivity.put("low", Arrays.asList("noise_injection"));
        cellLowSensitivity.put("moderate", Arrays.asList("no_transformation"));
        cellLowSensitivity.put("high", Arrays.asList("no_transformation"));
        cellStrategies.put("low", cellLowSensitivity);
        
        strategiesMap.put("cell", cellStrategies);

        // Column Data Level
        Map<String, Map<String, List<String>>> columnStrategies = new HashMap<>();
        
        // Column - High Sensitivity
        Map<String, List<String>> columnHighSensitivity = new HashMap<>();
        columnHighSensitivity.put("low", Arrays.asList("generalization", "top_bottom_coding", "full_masking"));
        columnHighSensitivity.put("moderate", Arrays.asList("cell_suppression", "partial_masking"));
        columnHighSensitivity.put("high", Arrays.asList("noise_injection"));
        columnStrategies.put("high", columnHighSensitivity);

        // Column - Moderate Sensitivity
        Map<String, List<String>> columnModerateSensitivity = new HashMap<>();
        columnModerateSensitivity.put("low", Arrays.asList("binning"));
        columnModerateSensitivity.put("moderate", Arrays.asList("binning", "partial_masking"));
        columnModerateSensitivity.put("high", Arrays.asList("no_transformation"));
        columnStrategies.put("moderate", columnModerateSensitivity);

        // Column - Low Sensitivity
        Map<String, List<String>> columnLowSensitivity = new HashMap<>();
        columnLowSensitivity.put("low", Arrays.asList("generalization"));
        columnLowSensitivity.put("moderate", Arrays.asList("no_transformation"));
        columnLowSensitivity.put("high", Arrays.asList("no_transformation"));
        columnStrategies.put("low", columnLowSensitivity);

        strategiesMap.put("column", columnStrategies);

        // Row Data Level
        Map<String, Map<String, List<String>>> rowStrategies = new HashMap<>();

        // Row - High Sensitivity
        Map<String, List<String>> rowHighSensitivity = new HashMap<>();
        rowHighSensitivity.put("low", Arrays.asList("full_masking"));
        rowHighSensitivity.put("moderate", Arrays.asList("microaggregation", "partial_masking"));
        rowHighSensitivity.put("high", Arrays.asList("microaggregation"));
        rowStrategies.put("high", rowHighSensitivity);
        
        // Row - Moderate Sensitivity
        Map<String, List<String>> rowModerateSensitivity = new HashMap<>();
        rowModerateSensitivity.put("low", Arrays.asList("generalization"));
        rowModerateSensitivity.put("moderate", Arrays.asList("microaggregation", "partial_masking"));
        rowModerateSensitivity.put("high", Arrays.asList("no_transformation"));
        rowStrategies.put("moderate", rowModerateSensitivity);

        // Row - Low Sensitivity
        Map<String, List<String>> rowLowSensitivity = new HashMap<>();
        rowLowSensitivity.put("low", Arrays.asList("microaggregation"));
        rowLowSensitivity.put("moderate", Arrays.asList("no_transformation"));
        rowLowSensitivity.put("high", Arrays.asList("no_transformation"));
        rowStrategies.put("low", rowLowSensitivity);

        strategiesMap.put("row", rowStrategies);

        // Table Data Level
        Map<String, Map<String, List<String>>> tableStrategies = new HashMap<>();

        // Table - High Sensitivity
        Map<String, List<String>> tableHighSensitivity = new HashMap<>();
        tableHighSensitivity.put("low", Arrays.asList("cell_suppression", "differential_privacy", "full_masking"));
        tableHighSensitivity.put("moderate", Arrays.asList("microaggregation", "partial_masking"));
        tableHighSensitivity.put("high", Arrays.asList("microaggregation"));
        tableStrategies.put("high", tableHighSensitivity);

        // Table - Moderate Sensitivity
        Map<String, List<String>> tableModerateSensitivity = new HashMap<>();
        tableModerateSensitivity.put("low", Arrays.asList("generalization"));
        tableModerateSensitivity.put("moderate", Arrays.asList("microaggregation", "partial_masking"));
        tableModerateSensitivity.put("high", Arrays.asList("no_transformation"));
        tableStrategies.put("moderate", tableModerateSensitivity);
        
        // Table - Low Sensitivity
        Map<String, List<String>> tableLowSensitivity = new HashMap<>();
        tableLowSensitivity.put("low", Arrays.asList("generalization"));
        tableLowSensitivity.put("moderate", Arrays.asList("no_transformation"));
        tableLowSensitivity.put("high", Arrays.asList("no_transformation"));
        tableStrategies.put("low", tableLowSensitivity);

        strategiesMap.put("table", tableStrategies);
    }

    /**
     * Selects a list of anonymization strategies based on data level, sensitivity, and user trust.
     *
     * @param dataLevel The data granularity level ("cell", "column", "row", "table").
     * @param sensitivity The sensitivity of the data ("high", "moderate", "low").
     * @param userTrust The user trust level ("high", "moderate", "low").
     * @return A list of strategy names. Returns an empty list if no matching strategies are found.
     */
    public static List<String> getStrategies(String dataLevel, String sensitivity, String userTrust) {
        return strategiesMap
                .getOrDefault(dataLevel.toLowerCase(), Collections.emptyMap())
                .getOrDefault(sensitivity.toLowerCase(), Collections.emptyMap())
                .getOrDefault(userTrust.toLowerCase(), Collections.emptyList());
    }
}
