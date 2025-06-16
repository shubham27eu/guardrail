package com.example.anonymization;

import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

public class MockDataTestRunner {

    // private static final String DATA_DF_PATH = "Data_2019-20.xlsx";
    // private static final String SENSITIVITY_RESULTS_PATH = "Sensitivity_Results.xlsx";
    // private static final String KYU_SCORE_PATH = "KYU Score.xlsx";
    private static final String USER_ID_TO_QUERY = "2"; // This remains hardcoded as per current task scope

    public static void main(String[] args) {
        System.out.println("Starting Mock Data Anonymization Process (using real data files)...");

        if (args.length != 3) {
            System.err.println("Usage: java com.example.anonymization.MockDataTestRunner <data_df_path> <sensitivity_results_path> <kyu_score_path>");
            return;
        }

        String dataDfPath = args[0];
        String sensitivityResultsPath = args[1];
        String kyuScorePath = args[2];

        try {
            SimpleDataFrame dataDf = DataLoader.loadDataDf(dataDfPath, ';');
            List<SensitivityResult> sensitivityResultsList = DataLoader.loadSensitivityResults(sensitivityResultsPath, null);
            List<KyuScore> kyuScoresList = DataLoader.loadKyuScores(kyuScorePath, null);

            System.out.println("Data initialized. dataDf rows: " + dataDf.getRowCount());

            try (Connection conn = DriverManager.getConnection("jdbc:sqlite::memory:")) {
                Class.forName("org.sqlite.JDBC");
                System.out.println("In-memory SQLite DB connected.");

                Main.createTableFromSimpleDataFrame(conn, dataDf, "data_df");
                System.out.println("'data_df' table created and populated in SQLite.");

                String query = "SELECT \"1455\", \"1198\", \"924\", \"21\", \"1524\", \"333\", \"351\" FROM data_df WHERE \"2\" = 'Gadag'";
                System.out.println("Executing query: " + query);
                SimpleDataFrame resultSDF = Main.executeSqlQueryToSimpleDataFrame(conn, query);
                System.out.println("Query resultSDF rows: " + resultSDF.getRowCount());

                if (resultSDF.getRowCount() == 0) {
                    System.err.println("Query returned no results.");
                    return;
                }

                String resultType = DataProcessor.determineQueryResultType(resultSDF);
                System.out.println("Result Type ------ " + resultType);

                String kyuScoreString = kyuScoresList.stream()
                        .filter(ks -> USER_ID_TO_QUERY.equals(ks.getUserId()))
                        .map(ks -> ks.getKyuScore().toLowerCase())
                        .findFirst().orElse("low");

                System.out.println("KYU Score ------ " + kyuScoreString);

                // String sensitivityLevelString = resultSDF.getColumnCount() == 0 ? "Low" :
                //         ("cell".equals(resultType)
                //                 ? sensitivityResultsList.stream()
                //                         .filter(sr -> sr.getAttributeId().equals(resultSDF.getColumnHeaders().get(0)))
                //                         .map(SensitivityResult::getSensitivityLevel)
                //                         .findFirst().orElse("Low")
                //                 : DataProcessor.getMaxSensitivityLevel(resultSDF, sensitivityResultsList));
                // TODO: MockDataTestRunner needs refactoring to work without DataProcessor.getMaxSensitivityLevel
                // For now, using a placeholder value to allow compilation.
                String sensitivityLevelString = "low";

                System.out.println("Sensitivity Level (mocked in TestRunner) ------ " + sensitivityLevelString);

                List<String> selectedStrategies = StrategySelector.getStrategies(resultType, sensitivityLevelString.toLowerCase(), kyuScoreString.toLowerCase());
                System.out.println("Selected Strategies ----- " + selectedStrategies);

                SimpleDataFrame originalResultSdf = resultSDF.copy();
                AnonymizationResult anonymizationOutput = AnonymizationService.anonymizeBySensitivity(resultSDF, selectedStrategies, resultType, kyuScoreString);
                SimpleDataFrame anonymizedSdf = anonymizationOutput.getAnonymizedDataFrame();

                String appliedStrategy = anonymizationOutput.getAppliedStrategy();
                System.out.println("Applied Strategy --- " + appliedStrategy);

                AnonymizationScore scores = ScoreCalculator.calculateScore(originalResultSdf, anonymizedSdf);
                System.out.println("Anonymization Score (Composite) ------ " + scores.getScore());
                System.out.println("Utility Retained ------ " + scores.getUtilityRetained());

                System.out.println("\nOriginal Result DataFrame (first 5 rows):");
                Main.printSimpleDataFrame(originalResultSdf, 5);

                System.out.println("\nAnonymized Result DataFrame (first 5 rows):");
                Main.printSimpleDataFrame(anonymizedSdf, 5);

            } catch (SQLException | ClassNotFoundException e) {
                System.err.println("Database error: " + e.getMessage());
                e.printStackTrace();
            }
        } catch (IOException e) {
            System.err.println("Error loading input files: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("Mock Data Anonymization Process Completed.");
    }
}