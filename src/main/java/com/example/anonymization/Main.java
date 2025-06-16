package com.example.anonymization;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.Paths; // Added for Paths.get
import java.sql.*;
import java.util.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.EnumSet;
import java.util.Set;


public class Main {

    // private static final String LOADED_DATA_DF_PATH; // Commented out, data comes from inputFilePath
    private static final String LOADED_ATTRIBUTES_PATH;
    // private static final String LOADED_SENSITIVITY_RESULTS_PATH; // Removed
    // private static final String LOADED_KYU_SCORE_PATH; // Removed

    // private static final String SENSITIVITY_RESULTS_FILE_PATH = "Sensitivity_Results.xlsx"; // No longer loaded directly by Main
    // private static final String KYU_SCORE_FILE_PATH = "KYU Score.xlsx"; // No longer loaded directly by Main

    static {
        Properties props = new Properties();
        try (InputStream input = Main.class.getResourceAsStream("/config.properties")) {
            if (input == null) {
                System.err.println("Sorry, unable to find config.properties. Make sure it's in src/main/resources and included in the JAR.");
                throw new RuntimeException("config.properties not found");
            }
            props.load(input);

            // LOADED_DATA_DF_PATH = props.getProperty("data.df.path"); // Commented out
            LOADED_ATTRIBUTES_PATH = props.getProperty("attributes.path");
            // LOADED_SENSITIVITY_RESULTS_PATH = props.getProperty("sensitivity.results.path"); // Removed
            // LOADED_KYU_SCORE_PATH = props.getProperty("kyu.score.path"); // Removed

            // if (LOADED_DATA_DF_PATH == null || LOADED_DATA_DF_PATH.equals("path-not-set") || LOADED_DATA_DF_PATH.isEmpty()) {
            //     throw new RuntimeException("data.df.path not set in config.properties");
            // }
            if (LOADED_ATTRIBUTES_PATH == null || LOADED_ATTRIBUTES_PATH.equals("path-not-set") || LOADED_ATTRIBUTES_PATH.isEmpty()) {
                // This path is not directly used in Main's logic for now, but we load it for completeness
                System.out.println("Warning: attributes.path is not set or default in config.properties. This may be an issue if needed later.");
            }
            // Removed error checks for LOADED_SENSITIVITY_RESULTS_PATH and LOADED_KYU_SCORE_PATH

        } catch (IOException ex) {
            ex.printStackTrace();
            throw new RuntimeException("Failed to load config.properties", ex);
        }
    }

    public static void createTableFromSimpleDataFrame(Connection conn, SimpleDataFrame sdf, String tableName) throws SQLException {
        if (sdf == null || sdf.getColumnCount() == 0) {
            System.err.println("Skipping table creation for empty SimpleDataFrame: " + tableName);
            return;
        }

        List<String> originalHeaders = sdf.getColumnHeaders();
        List<String> sanitizedHeaders = new ArrayList<>();

        for (int i = 0; i < originalHeaders.size(); i++) {
            String header = originalHeaders.get(i);
            if (header == null || header.trim().isEmpty()) {
                header = "unnamed_col_" + i;
                System.out.println("⚠️ Found blank column name. Renamed to: " + header);
            }
            sanitizedHeaders.add(header);
        }

        // Update headers in SimpleDataFrame (optional, depends on how you're handling column lookups elsewhere)
        sdf.setColumnHeaders(sanitizedHeaders);

        String columnsWithType = sanitizedHeaders.stream()
                .map(header -> "\"" + header + "\" TEXT")
                .collect(Collectors.joining(", "));

        try (Statement stmt = conn.createStatement()) {
            stmt.execute("DROP TABLE IF EXISTS \"" + tableName + "\"");
            stmt.execute("CREATE TABLE \"" + tableName + "\" (" + columnsWithType + ")");

            String insertSQL = "INSERT INTO \"" + tableName + "\" (" +
                    sanitizedHeaders.stream().map(h -> "\"" + h + "\"").collect(Collectors.joining(", ")) +
                    ") VALUES (" +
                    sanitizedHeaders.stream().map(h -> "?").collect(Collectors.joining(", ")) + ")";

            try (PreparedStatement pstmt = conn.prepareStatement(insertSQL)) {
                for (Map<String, Object> row : sdf.getRows()) {
                    for (int i = 0; i < sanitizedHeaders.size(); i++) {
                        Object value = row.get(originalHeaders.get(i)); // use original header for lookup
                        pstmt.setString(i + 1, value != null ? String.valueOf(value) : null);
                    }
                    pstmt.addBatch();
                }
                pstmt.executeBatch();
            }
        }
    }

    public static SimpleDataFrame loadSimpleDataFrameFromFile(String filePath) throws IOException {
        List<String> headers = Arrays.asList("attribute_value"); // Single column
        SimpleDataFrame sdf = new SimpleDataFrame(headers);
        try {
            List<String> lines = Files.readAllLines(Paths.get(filePath));
            for (String line : lines) {
                Map<String, Object> row = new HashMap<>();
                // Assuming each line is a value for the single column.
                // If the line could be empty or needs trimming, add that logic here.
                row.put("attribute_value", line);
                sdf.addRow(row);
            }
        } catch (IOException e) {
            System.err.println("Error reading input file: " + filePath);
            throw e;
        }
        return sdf;
    }

    public static SimpleDataFrame executeSqlQueryToSimpleDataFrame(Connection conn, String query) throws SQLException {
        List<String> headers = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();

        try (Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(query)) {
            ResultSetMetaData metaData = rs.getMetaData();
            int columnCount = metaData.getColumnCount();
            for (int i = 1; i <= columnCount; i++) {
                headers.add(metaData.getColumnLabel(i));
            }
            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (String header : headers) {
                    row.put(header, rs.getObject(header));
                }
                rows.add(row);
            }
        }

        SimpleDataFrame sdf = new SimpleDataFrame(headers);
        for (Map<String, Object> row : rows) {
            sdf.addRow(row);
        }
        return sdf;
    }

    public static void printSimpleDataFrame(SimpleDataFrame sdf, int maxRows) {
        if (sdf == null) {
            System.out.println("DataFrame is null.");
            return;
        }
        System.out.println("Columns: " + sdf.getColumnHeaders());
        System.out.println("Total Rows: " + sdf.getRowCount());
        List<Map<String, Object>> rowsToPrint = sdf.getRows().subList(0, Math.min(maxRows, sdf.getRowCount()));
        for (int i = 0; i < rowsToPrint.size(); i++) {
            System.out.println("Row " + i + ": " + rowsToPrint.get(i));
        }
        if (sdf.getRowCount() > maxRows) {
            System.out.println("... and " + (sdf.getRowCount() - maxRows) + " more rows.");
        }
        System.out.println("--------------------");
    }

    // private static void executePythonScript(String scriptResourcePath) {
    //     try {
    //         Path tempScript = Files.createTempFile("anonymization_script_", ".py");
    //         try (InputStream scriptStream = Main.class.getResourceAsStream(scriptResourcePath)) {
    //             if (scriptStream == null) {
    //                 throw new RuntimeException("Cannot find Python script in JAR: " + scriptResourcePath);
    //             }
    //             Files.copy(scriptStream, tempScript, StandardCopyOption.REPLACE_EXISTING);
    //         }

    //         // Make executable (especially for Linux/macOS)
    //         File scriptFile = tempScript.toFile();
    //         boolean executableSet = false;
    //         try {
    //             // For POSIX compliant systems
    //             Set<PosixFilePermission> perms = EnumSet.of(
    //                     PosixFilePermission.OWNER_READ,
    //                     PosixFilePermission.OWNER_WRITE,
    //                     PosixFilePermission.OWNER_EXECUTE,
    //                     PosixFilePermission.GROUP_READ,
    //                     PosixFilePermission.OTHERS_READ
    //             );
    //             Files.setPosixFilePermissions(tempScript, perms);
    //             executableSet = true;
    //         } catch (UnsupportedOperationException | IOException e) {
    //             // This means system is likely not POSIX compliant (e.g. Windows)
    //             // Fallback to Java's setExecutable, which might work or not depending on OS
    //             executableSet = scriptFile.setExecutable(true);
    //         }

    //         if (!executableSet && !scriptFile.canExecute()) {
    //              System.out.println("Warning: Could not make Python script executable: " + scriptFile.getAbsolutePath() + ". Execution might fail if direct execution is required.");
    //         }

    //         String pythonExecutableName = "python3"; // Default
    //         // Potentially make "python3" a constant or configurable if it differs from "python" often for users.

    //         String venvPythonPath;
    //         String osName = System.getProperty("os.name").toLowerCase();
    //         if (osName.contains("win")) {
    //             venvPythonPath = ".venv" + File.separator + "Scripts" + File.separator + "python.exe";
    //             // On Windows, often just "python" is used for venv, and "python3" might not exist
    //             // Also, the system python might just be "python". This logic might need refinement
    //             // based on common Windows Python setups if "python3" fails.
    //         } else { // macOS, Linux, other POSIX-like
    //             venvPythonPath = ".venv" + File.separator + "bin" + File.separator + "python3";
    //         }

    //         File venvPythonFile = new File(venvPythonPath);
    //         String effectivePythonCommand;

    //         if (venvPythonFile.exists() && venvPythonFile.canExecute()) {
    //             effectivePythonCommand = venvPythonFile.getAbsolutePath();
    //             System.out.println("Using Python from virtual environment: " + effectivePythonCommand);
    //         } else {
    //             effectivePythonCommand = pythonExecutableName; // Fallback to system python3 (or python)
    //             System.out.println("Virtual environment Python not found at '" + venvPythonPath + "'. Falling back to system Python: " + effectivePythonCommand);
    //         }

    //         System.out.println("Executing Python script: " + scriptFile.getAbsolutePath() + " using " + effectivePythonCommand);
    //         ProcessBuilder pb = new ProcessBuilder(effectivePythonCommand, scriptFile.getAbsolutePath());
    //         // User might need to change "python3" to "python" if that's their system default for Python 3
    //         pb.inheritIO(); // Stream Python's stdout/stderr to Java's stdout/stderr

    //         Process p = pb.start();
    //         // Wait for a reasonable time, e.g., 1 minute per script
    //         boolean finished = p.waitFor(60, TimeUnit.SECONDS);
    //         if (!finished) {
    //             p.destroyForcibly();
    //             throw new RuntimeException("Python script execution timed out: " + scriptResourcePath);
    //         }

    //         int exitCode = p.exitValue();
    //         if (exitCode != 0) {
    //             throw new RuntimeException("Python script " + scriptResourcePath + " exited with error code: " + exitCode);
    //         }
    //         System.out.println("Python script " + scriptResourcePath + " executed successfully.");

    //         // Schedule temp file for deletion on JVM exit
    //         scriptFile.deleteOnExit();

    //     } catch (IOException | InterruptedException e) {
    //         throw new RuntimeException("Error executing Python script " + scriptResourcePath, e);
    //     }
    // }

    public static void main(String[] args) {
        // System.out.println("Attempting to execute Python scripts...");
        // try {
        //     executePythonScript("/scripts/generate_sensitivity.py");
        //     executePythonScript("/scripts/generate_kyu_scores.py");
        //     System.out.println("Python scripts execution phase completed.");
        // } catch (RuntimeException e) {
        //     System.err.println("Failed to execute Python scripts: " + e.getMessage());
        //     e.printStackTrace();
        //     // Decide if to exit or try to continue if files might exist from a previous run
        //     System.err.println("Exiting due to Python script execution failure.");
        //     return;
        // }
        // System.out.println("--------------------------------------------------");

        System.out.println("Starting Anonymization Process...");
        if (args.length != 5) {
            System.err.println("Usage: java com.example.anonymization.Main <userId> <kyuScore> <sensitivityLevel> <inputFilePath> <outputFilePath>");
            return;
        }

        String userId = args[0];
        String kyuScoreArg = args[1].toLowerCase(); // kyuScore from arg
        String sensitivityLevelArg = args[2].toLowerCase(); // sensitivityLevel from arg
        String inputFilePath = args[3];
        String outputFilePath = args[4]; // To be used later for writing output

        System.out.println("--- Configuration ---");
        // System.out.println("Data DF path (from config): " + LOADED_DATA_DF_PATH); // Commented out
        System.out.println("Attributes path (from config): " + LOADED_ATTRIBUTES_PATH); // May become less relevant
        // System.out.println("Sensitivity Results path (generated): " + SENSITIVITY_RESULTS_FILE_PATH); // No longer loaded
        // System.out.println("KYU Score Path (generated): " + KYU_SCORE_FILE_PATH); // No longer loaded
        System.out.println("User ID (from CLI): " + userId);
        System.out.println("KYU Score (from CLI): " + kyuScoreArg);
        System.out.println("Sensitivity Level (from CLI): " + sensitivityLevelArg);
        System.out.println("Input File Path (from CLI): " + inputFilePath);
        System.out.println("Output File Path (from CLI): " + outputFilePath);
        System.out.println("--- End Configuration ---");

        try {
            // Load data directly from the input file path provided
            SimpleDataFrame resultSDF = loadSimpleDataFrameFromFile(inputFilePath);
            System.out.println("Data loaded from input file. Rows: " + resultSDF.getRowCount());

            if (resultSDF.getRowCount() == 0) {
                System.err.println("Input file '" + inputFilePath + "' is empty or resulted in no data. Exiting.");
                return;
            }
             if (resultSDF.getColumnCount() == 0 || !resultSDF.getColumnHeaders().contains("attribute_value")) {
                System.err.println("Input file did not load correctly into SimpleDataFrame with 'attribute_value' column. Exiting.");
                return;
            }


            // Sensitivity results and KYU scores are no longer loaded from files by Main.java
            // List<SensitivityResult> sensitivityResultsList = DataLoader.loadSensitivityResults(SENSITIVITY_RESULTS_FILE_PATH, null); // Commented out
            // List<KyuScore> kyuScoresList = DataLoader.loadKyuScores(KYU_SCORE_FILE_PATH, null); // Already commented out / kyuScore now comes from arg

            // The main data_df is no longer loaded from a large Excel file into SQLite for querying.
            // The `resultSDF` is the direct data to be anonymized.
            // No SQLite connection needed for this part if resultSDF is directly processed.
            // try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            //     System.out.println("In-memory SQLite DB connected.");
            //     createTableFromSimpleDataFrame(conn, dataDf, "data_df"); // dataDf is no longer loaded
            //     System.out.println("'data_df' table created and populated in SQLite.");
            //     System.out.println("Executing query (from arg): " + sqliteQuery); // sqliteQuery removed
            //     SimpleDataFrame resultSDF = executeSqlQueryToSimpleDataFrame(conn, sqliteQuery); // resultSDF now loaded from file

                // Determine result type - for a single column from file, it's likely "column" or "cell"
                String resultType = DataProcessor.determineQueryResultType(resultSDF);
                System.out.println("Result Type (determined from input data) ------ " + resultType);

                // KYU score is taken directly from command line argument
                String kyuScoreString = kyuScoreArg;
                System.out.println("KYU Score (from CLI arg) ------ " + kyuScoreString);

                // Sensitivity level is taken directly from command line argument
                String sensitivityLevelString = sensitivityLevelArg;
                // The old logic for deriving sensitivityLevelString:
                // if (resultSDF.getColumnCount() == 0) {
                //     sensitivityLevelString = "Low";
                //     System.err.println("Query result has no columns. Defaulting sensitivity to Low.");
                // } else if ("cell".equals(resultType)) {
                //     // If it's a single cell, we might still want to look up its original attribute's sensitivity.
                //     // Assuming the single column in resultSDF ("attribute_value") needs to be mapped to an "attributeId"
                //     // This part needs clarification on how to map "attribute_value" column to an attribute in Sensitivity_Results.xlsx
                //     // For now, we use the CLI argument. If the input file represents a specific known attribute,
                //     // its name could be passed or inferred to look up in sensitivityResultsList.
                //     // String cellColumnName = resultSDF.getColumnHeaders().get(0); // This is "attribute_value"
                //     // sensitivityLevelString = sensitivityResultsList.stream()
                //     //         .filter(sr -> cellColumnName.equals(String.valueOf(sr.getAttributeId()))) // This comparison is problematic
                //     //         .map(SensitivityResult::getSensitivityLevel)
                //     //         .findFirst().orElse(sensitivityLevelArg); // Fallback to arg
                // } else { // "column" or other types
                //     // If it's a column, getMaxSensitivityLevel iterates over column headers.
                //     // resultSDF only has "attribute_value". This also needs mapping.
                //     // sensitivityLevelString = DataProcessor.getMaxSensitivityLevel(resultSDF, sensitivityResultsList);
                // }
                System.out.println("Sensitivity Level (from CLI arg) ------ " + sensitivityLevelString);


                List<String> selectedStrategies = StrategySelector.getStrategies(resultType, sensitivityLevelString.toLowerCase(), kyuScoreString.toLowerCase());
                System.out.println("Selected Strategies ----- " + selectedStrategies);

                SimpleDataFrame originalResultSdf = resultSDF.copy();
                AnonymizationResult anonymizationOutput = AnonymizationService.anonymizeBySensitivity(resultSDF, selectedStrategies, resultType, kyuScoreString);
                SimpleDataFrame anonymizedSdf = anonymizationOutput.getAnonymizedDataFrame();
                String appliedStrategy = anonymizationOutput.getAppliedStrategy();
                System.out.println("Applied Strategy --- " + appliedStrategy);

                if (appliedStrategy == null && !selectedStrategies.isEmpty() &&
                        !(selectedStrategies.size() == 1 && "no_transformation".equalsIgnoreCase(selectedStrategies.get(0)))) {
                    System.err.println("Warning: Strategies were selected but none was applied.");
                }

                AnonymizationScore scores = ScoreCalculator.calculateScore(originalResultSdf, anonymizedSdf);
                System.out.println("Anonymization Score (Composite) ------ " + scores.getScore());
                System.out.println("Utility Retained ------ " + scores.getUtilityRetained());

                System.out.println("\nOriginal Result DataFrame (first 5 rows):");
                printSimpleDataFrame(originalResultSdf, 5);
                System.out.println("\nAnonymized Result DataFrame (first 5 rows):");
                printSimpleDataFrame(anonymizedSdf, 5);

                // Write anonymizedSdf to outputFilePath
                try {
                    System.out.println("Writing anonymized data to: " + outputFilePath);
                    if (anonymizedSdf != null) {
                        anonymizedSdf.writeToCsv(outputFilePath);
                        System.out.println("Anonymized data written successfully to " + outputFilePath);
                    } else {
                        System.err.println("Anonymized SimpleDataFrame is null. Cannot write to CSV.");
                        // Create an empty file to signify completion but no data, if app.py expects a file.
                        Files.write(Paths.get(outputFilePath), "".getBytes());
                    }
                } catch (IOException e_csv) {
                    System.err.println("Error writing anonymized data to CSV at " + outputFilePath + ": " + e_csv.getMessage());
                    e_csv.printStackTrace();
                    // If writing fails, app.py might not find the file or find an incomplete one.
                }

                // Print scores to STDOUT for app.py to capture
                if (scores != null) {
                    System.out.println("ANONYMIZATION_SCORE:" + scores.getScore());
                    System.out.println("UTILITY_RETAINED:" + scores.getUtilityRetained());
                } else {
                    System.out.println("ANONYMIZATION_SCORE:ERROR_CALCULATING");
                    System.out.println("UTILITY_RETAINED:ERROR_CALCULATING");
                }


            // } // End of commented-out SQLite block
        } catch (IOException e) { // Catch IOException from loadSimpleDataFrameFromFile or writeToCsv
            System.err.println("Critical file error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) { // General exception handler
            System.err.println("Unexpected error during anonymization process: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nAnonymization Process Completed in Java.");
    }
}
