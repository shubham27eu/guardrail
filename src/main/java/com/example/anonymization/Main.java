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
            // List<String> appliedStrategiesForOutput = new ArrayList<>(); // Optional

            for (AttributeValueEntry entry : entries) {
                String attributeName = entry.getAttributeName();
                String originalValue = entry.getAttributeValue();

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

                // Output format: original_value::anonymized_value (strategy_applied)
                // This can be adjusted later if a different format is needed by app.py
                // String outputLine = originalValue + "::" + anonymizationResult.getAnonymizedValue() +
                //                     " (Strategy: " + anonymizationResult.getAppliedStrategy() + ")";
                anonymizedValuesForOutput.add(anonymizationResult.getAnonymizedValue());

                // appliedStrategiesForOutput.add(anonymizationResult.getAppliedStrategy()); // Optional
                System.out.println("Original: " + originalValue +
                                   " -> Anonymized: " + anonymizationResult.getAnonymizedValue() +
                                   " (Strategy: " + anonymizationResult.getAppliedStrategy() + ")");
            }

            // Write only the anonymized values to the output file, one per line.
            // If entries list was empty, anonymizedValuesForOutput will be empty, resulting in an empty file.
            Files.write(Paths.get(outputPath), anonymizedValuesForOutput, StandardCharsets.UTF_8);
            System.out.println(anonymizedValuesForOutput.size() + " anonymized values written to: " + outputPath);

        } catch (Exception e) {
            System.err.println("Error during anonymization process: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nAnonymization Process Completed (New Flow).");
    }
}
