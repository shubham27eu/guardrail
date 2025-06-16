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
import java.sql.*;
import java.util.*;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.EnumSet;
import java.util.Set;


public class Main {

    private static final String LOADED_DATA_DF_PATH;
    private static final String LOADED_ATTRIBUTES_PATH;
    // private static final String LOADED_SENSITIVITY_RESULTS_PATH; // Removed
    // private static final String LOADED_KYU_SCORE_PATH; // Removed

    private static final String SENSITIVITY_RESULTS_FILE_PATH = "Sensitivity_Results.xlsx"; // Expected output from Python script
    private static final String KYU_SCORE_FILE_PATH = "KYU Score.xlsx"; // Expected output from Python script

    static {
        Properties props = new Properties();
        try (InputStream input = Main.class.getResourceAsStream("/config.properties")) {
            if (input == null) {
                System.err.println("Sorry, unable to find config.properties. Make sure it's in src/main/resources and included in the JAR.");
                throw new RuntimeException("config.properties not found");
            }
            props.load(input);

            LOADED_DATA_DF_PATH = props.getProperty("data.df.path");
            LOADED_ATTRIBUTES_PATH = props.getProperty("attributes.path");
            // LOADED_SENSITIVITY_RESULTS_PATH = props.getProperty("sensitivity.results.path"); // Removed
            // LOADED_KYU_SCORE_PATH = props.getProperty("kyu.score.path"); // Removed

            if (LOADED_DATA_DF_PATH == null || LOADED_DATA_DF_PATH.equals("path-not-set") || LOADED_DATA_DF_PATH.isEmpty()) {
                throw new RuntimeException("data.df.path not set in config.properties");
            }
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

    private static void executePythonScript(String scriptResourcePath) {
        try {
            Path tempScript = Files.createTempFile("anonymization_script_", ".py");
            try (InputStream scriptStream = Main.class.getResourceAsStream(scriptResourcePath)) {
                if (scriptStream == null) {
                    throw new RuntimeException("Cannot find Python script in JAR: " + scriptResourcePath);
                }
                Files.copy(scriptStream, tempScript, StandardCopyOption.REPLACE_EXISTING);
            }

            // Make executable (especially for Linux/macOS)
            File scriptFile = tempScript.toFile();
            boolean executableSet = false;
            try {
                // For POSIX compliant systems
                Set<PosixFilePermission> perms = EnumSet.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.OWNER_EXECUTE,
                        PosixFilePermission.GROUP_READ,
                        PosixFilePermission.OTHERS_READ
                );
                Files.setPosixFilePermissions(tempScript, perms);
                executableSet = true;
            } catch (UnsupportedOperationException | IOException e) {
                // This means system is likely not POSIX compliant (e.g. Windows)
                // Fallback to Java's setExecutable, which might work or not depending on OS
                executableSet = scriptFile.setExecutable(true);
            }

            if (!executableSet && !scriptFile.canExecute()) {
                 System.out.println("Warning: Could not make Python script executable: " + scriptFile.getAbsolutePath() + ". Execution might fail if direct execution is required.");
            }

            String pythonExecutableName = "python3"; // Default
            // Potentially make "python3" a constant or configurable if it differs from "python" often for users.

            String venvPythonPath;
            String osName = System.getProperty("os.name").toLowerCase();
            if (osName.contains("win")) {
                venvPythonPath = ".venv" + File.separator + "Scripts" + File.separator + "python.exe";
                // On Windows, often just "python" is used for venv, and "python3" might not exist
                // Also, the system python might just be "python". This logic might need refinement
                // based on common Windows Python setups if "python3" fails.
            } else { // macOS, Linux, other POSIX-like
                venvPythonPath = ".venv" + File.separator + "bin" + File.separator + "python3";
            }

            File venvPythonFile = new File(venvPythonPath);
            String effectivePythonCommand;

            if (venvPythonFile.exists() && venvPythonFile.canExecute()) {
                effectivePythonCommand = venvPythonFile.getAbsolutePath();
                System.out.println("Using Python from virtual environment: " + effectivePythonCommand);
            } else {
                effectivePythonCommand = pythonExecutableName; // Fallback to system python3 (or python)
                System.out.println("Virtual environment Python not found at '" + venvPythonPath + "'. Falling back to system Python: " + effectivePythonCommand);
            }

            System.out.println("Executing Python script: " + scriptFile.getAbsolutePath() + " using " + effectivePythonCommand);
            ProcessBuilder pb = new ProcessBuilder(effectivePythonCommand, scriptFile.getAbsolutePath());
            // User might need to change "python3" to "python" if that's their system default for Python 3
            pb.inheritIO(); // Stream Python's stdout/stderr to Java's stdout/stderr

            Process p = pb.start();
            // Wait for a reasonable time, e.g., 1 minute per script
            boolean finished = p.waitFor(60, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                throw new RuntimeException("Python script execution timed out: " + scriptResourcePath);
            }

            int exitCode = p.exitValue();
            if (exitCode != 0) {
                throw new RuntimeException("Python script " + scriptResourcePath + " exited with error code: " + exitCode);
            }
            System.out.println("Python script " + scriptResourcePath + " executed successfully.");

            // Schedule temp file for deletion on JVM exit
            scriptFile.deleteOnExit();

        } catch (IOException | InterruptedException e) {
            throw new RuntimeException("Error executing Python script " + scriptResourcePath, e);
        }
    }

    public static void main(String[] args) {
        System.out.println("Attempting to execute Python scripts...");
        try {
            executePythonScript("/scripts/generate_sensitivity.py");
            executePythonScript("/scripts/generate_kyu_scores.py");
            System.out.println("Python scripts execution phase completed.");
        } catch (RuntimeException e) {
            System.err.println("Failed to execute Python scripts: " + e.getMessage());
            e.printStackTrace();
            // Decide if to exit or try to continue if files might exist from a previous run
            System.err.println("Exiting due to Python script execution failure.");
            return;
        }
        System.out.println("--------------------------------------------------");

        System.out.println("Starting Anonymization Process...");
        if (args.length != 2) { // Expect 2 arguments now
    System.err.println("Usage: java com.example.anonymization.Main <user_id> \"<sqlite_query>\"");
    return;
}

        String userId = args[0];
        String sqliteQuery = args[1]; // Second argument is now the full SQLite query

        System.out.println("--- Configuration ---");
        System.out.println("Data DF path (from config): " + LOADED_DATA_DF_PATH);
        System.out.println("Attributes path (from config): " + LOADED_ATTRIBUTES_PATH);
        System.out.println("Sensitivity Results path (fixed): " + SENSITIVITY_RESULTS_FILE_PATH);
        System.out.println("KYU Score Path (fixed): " + KYU_SCORE_FILE_PATH);
        System.out.println("User ID (from runtime arg): " + userId);
        System.out.println("SQLite Query (from runtime arg): " + sqliteQuery);
        System.out.println("--- End Configuration ---");

        try {
            SimpleDataFrame dataDf = DataLoader.loadDataDf(LOADED_DATA_DF_PATH, ';');
            List<SensitivityResult> sensitivityResultsList = DataLoader.loadSensitivityResults(SENSITIVITY_RESULTS_FILE_PATH, null);
            List<KyuScore> kyuScoresList = DataLoader.loadKyuScores(KYU_SCORE_FILE_PATH, null);

            System.out.println("Data initialized. dataDf rows: " + dataDf.getRowCount());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                System.out.println("In-memory SQLite DB connected.");
                createTableFromSimpleDataFrame(conn, dataDf, "data_df");
                System.out.println("'data_df' table created and populated in SQLite.");
                // String query = String.format("SELECT * FROM data_df WHERE \"%s\" = '%s'", columnId, filterValue); // Removed
                System.out.println("Executing query (from arg): " + sqliteQuery);
                SimpleDataFrame resultSDF = executeSqlQueryToSimpleDataFrame(conn, sqliteQuery);

System.out.println("Query resultSDF rows: " + resultSDF.getRowCount());

                if (resultSDF.getRowCount() == 0) {
                    System.err.println("Query returned no results. Check query, data, and file contents.");
                    return;
                }

                String resultType = DataProcessor.determineQueryResultType(resultSDF);
                System.out.println("Result Type ------ " + resultType);

                String kyuScoreString = kyuScoresList.stream()
                        .filter(ks -> userId.equals(ks.getUserId()))
                        .map(ks -> ks.getKyuScore().toLowerCase())
                        .findFirst()
                        .orElse("low"); // Default if user_id not found or KYU Score file doesn't contain the user
                System.out.println("KYU Score for User ID '" + userId + "' ------ " + kyuScoreString);

                String sensitivityLevelString;
                if (resultSDF.getColumnCount() == 0) {
                    sensitivityLevelString = "Low";
                    System.err.println("Query result has no columns. Defaulting sensitivity to Low.");
                } else if ("cell".equals(resultType)) {
                    String cellColumnName = resultSDF.getColumnHeaders().get(0);
                    sensitivityLevelString = sensitivityResultsList.stream()
                            .filter(sr -> cellColumnName.equals(String.valueOf(sr.getAttributeId())))
                            .map(SensitivityResult::getSensitivityLevel)
                            .findFirst().orElse("Low");
                } else {
                    sensitivityLevelString = DataProcessor.getMaxSensitivityLevel(resultSDF, sensitivityResultsList);
                }
                System.out.println("Sensitivity Level ------ " + sensitivityLevelString);

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

            }
        } catch (IOException | SQLException e) {
            System.err.println("Critical error: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("Unexpected error: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nAnonymization Process Completed.");
    }
}
