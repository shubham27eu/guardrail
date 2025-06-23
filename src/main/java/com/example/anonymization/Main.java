package com.example.anonymization;

// Keep necessary imports
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
// Removed: File, FileOutputStream, InputStream, OutputStream, Path, StandardCopyOption, PosixFilePermission,
// sql.*, Properties, TimeUnit, Collectors, EnumSet, Set, Map, HashMap, Collections (if not used elsewhere)

public class Main {

    // Removed static final String declarations for LOADED_DATA_DF_PATH, LOADED_ATTRIBUTES_PATH, SENSITIVITY_RESULTS_FILE_PATH, KYU_SCORE_FILE_PATH
    // Removed static initializer block for loading properties from config.properties

    // Removed createTableFromSimpleDataFrame method
    // Removed executeSqlQueryToSimpleDataFrame method
    // Removed printSimpleDataFrame method
    // Removed executePythonScript method

    public static void main(String[] args) {
        System.out.println("Starting Anonymization Process (New Flow)...");

        if (args.length != 5) {
            System.err.println("Usage: java com.example.anonymization.Main <email> <kyu_score> <overall_sensitivity> <input_path> <output_path>");
            return;
        }

        String email = args[0];
        String kyuScoreCli = args[1]; // Renamed to avoid conflict
        String overallSensitivityCli = args[2]; // Renamed
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
            // For debugging, print a few entries:
            for (int i = 0; i < Math.min(entries.size(), 5); i++) {
                System.out.println("Entry " + i + ": " + entries.get(i));
            }

            List<String> anonymizedValuesForOutput = new ArrayList<>();
            // MODIFICATION 1: Initialize Score Variables
            double totalDistance = 0.0;
            int processedValueCount = 0;

            for (AttributeValueEntry entry : entries) {
                String attributeName = entry.getAttributeName();
                String originalValue = entry.getAttributeValue(); // This is currentOriginalValue

                // Using overallSensitivityCli for all attributes as per current CLI args
                // resultType is assumed "cell" for individual values
                List<String> selectedStrategies = StrategySelector.getStrategies(
                    "cell", // resultType for single value
                    overallSensitivityCli.toLowerCase(), // sensitivity level
                    kyuScoreCli.toLowerCase() // kyu score
                );

                System.out.println("For value '" + originalValue + "' (attr: " + attributeName + "), selected strategies: " + selectedStrategies);

                SingleAnonymizationResult anonymizationResult = AnonymizationService.anonymizeSingleValue(
                    originalValue,
                    attributeName,
                    kyuScoreCli,
                    overallSensitivityCli, // sensitivity for this attribute
                    selectedStrategies
                );

                String outputLine = anonymizationResult.getAnonymizedValue() + "::" + anonymizationResult.getAppliedStrategy();
                anonymizedValuesForOutput.add(outputLine);

                System.out.println("Original: " + originalValue +
                                   " -> Anonymized: " + anonymizationResult.getAnonymizedValue() +
                                   " (Strategy: " + anonymizationResult.getAppliedStrategy() + ")");

                // MODIFICATION 2: Calculate Distance per Value and Update Score Variables
                String currentOriginalValue = entry.getAttributeValue(); // Same as originalValue above
                String currentAnonymizedValue = anonymizationResult.getAnonymizedValue();
                double distance = 0.0;

                if (currentOriginalValue == null && currentAnonymizedValue == null) {
                    distance = 0.0;
                } else if (currentOriginalValue == null || currentAnonymizedValue == null) {
                    distance = 1.0; // One is null, the other isn't
                } else if (!currentOriginalValue.equals(currentAnonymizedValue)) {
                    distance = 1.0; // They are different
                }
                // No numeric comparison for now, just string equality.
                // Can be enhanced later if needed, but Java received strings.

                totalDistance += distance;
                processedValueCount++;
                // End of new score calculation code for this item
            }

            Files.write(Paths.get(outputPath), anonymizedValuesForOutput, StandardCharsets.UTF_8);
            System.out.println(anonymizedValuesForOutput.size() + " anonymized values written to: " + outputPath);

            // MODIFICATION 3: Calculate and Print Final Score
            if (processedValueCount > 0) {
                double alphaScore = totalDistance / processedValueCount;
                System.out.println(String.format("AlphaScore:%.4f", alphaScore));
            } else {
                System.out.println("AlphaScore:0.0000"); // Or handle as N/A, or don't print
            }
            // End of new alpha score printing code

        } catch (Exception e) {
            System.err.println("Error during anonymization process: " + e.getMessage());
            e.printStackTrace();
            // It might be good to also print a default AlphaScore in case of exception
            // after loading entries but before finishing processing all of them.
            // For now, only printing if loop completes or doesn't run.
             System.out.println("AlphaScore:Error"); 
        }

        System.out.println("\nAnonymization Process Completed (New Flow).");
    }
}
