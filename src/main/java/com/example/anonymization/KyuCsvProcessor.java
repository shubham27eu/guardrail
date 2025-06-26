package com.example.anonymization;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;
import com.opencsv.*;
import com.opencsv.exceptions.CsvValidationException;
import org.apache.commons.text.similarity.LevenshteinDistance;

public class KyuCsvProcessor {

    private static final String SAMPLE_DATA_DIR = "src/main/resources/scripts/GUARDRAIL-3/static/uploads/";

    public static void main(String[] args) throws IOException, CsvValidationException {
        if (args.length != 2) {
            System.err.println("Usage: java com.example.anonymization.KyuCsvProcessor <input_csv> <output_csv>");
            return;
        }

        String inputCsv = args[0];
        String outputCsv = args[1];

        System.out.println("=== KYU AlphaScore Processor (Per-Sample File) ===");
        System.out.println("Reading input: " + inputCsv);
        System.out.println("Writing output: " + outputCsv);

        try (
            Reader reader = Files.newBufferedReader(Paths.get(inputCsv), StandardCharsets.UTF_8);
            CSVReader csvReader = new CSVReader(reader);
            Writer writer = Files.newBufferedWriter(Paths.get(outputCsv), StandardCharsets.UTF_8);
            CSVWriter csvWriter = new CSVWriter(writer)
        ) {
            String[] headers = csvReader.readNext();
            if (headers == null) {
                System.err.println("CSV file is empty.");
                return;
            }

            List<String> newHeaders = new ArrayList<>(Arrays.asList(headers));
            newHeaders.add("Alpha Score");
            csvWriter.writeNext(newHeaders.toArray(new String[0]));

            String[] row;
            while ((row = csvReader.readNext()) != null) {
                Map<String, String> rowMap = new LinkedHashMap<>();
                for (int i = 0; i < headers.length; i++) {
                    rowMap.put(headers[i], row[i]);
                }

                String filenameRaw = rowMap.get("File Name");
                if (filenameRaw == null || filenameRaw.isEmpty()) {
                    System.err.println("Warning: No filename found in row.");
                    continue;
                }

                double alphaScore = 0.0;
                try {
                    Path matchedPath = findClosestMatchingFile(SAMPLE_DATA_DIR, filenameRaw);
                    if (matchedPath == null) {
                        System.err.println("Warning: No matching file found for " + filenameRaw);
                    } else {
                        alphaScore = computeAlphaScoreForSample(matchedPath.toString(), rowMap);
                    }
                } catch (Exception e) {
                    System.err.println("Warning: Could not process file for " + filenameRaw + ": " + e.getMessage());
                }

                List<String> fullRow = new ArrayList<>(Arrays.asList(row));
                fullRow.add(String.format("%.4f", alphaScore));
                csvWriter.writeNext(fullRow.toArray(new String[0]));
            }

            System.out.println("✅ Alpha Score written to: " + outputCsv);
        } catch (Exception e) {
            System.err.println("❌ Error processing CSV: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static double computeAlphaScoreForSample(String sampleCsvPath, Map<String, String> rowMeta)
            throws IOException, CsvValidationException {

        Path path = Paths.get(sampleCsvPath);
        System.out.println("Processing file: " + sampleCsvPath);
        if (!Files.exists(path)) {
            throw new FileNotFoundException("Sample file not found: " + sampleCsvPath);
        }

        List<Double> distances = new ArrayList<>();

        try (
            Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8);
            CSVReader csvReader = new CSVReader(reader);
        ) {
            String[] headers = csvReader.readNext();
            if (headers == null) return 0.0;

            String kyuScore = rowMeta.get("KYU Trust Score");
            String sensitivity = rowMeta.get("Sensitivity Level");

            List<String> strategies = StrategySelector.getStrategies(
                "cell",
                sensitivity.toLowerCase(),
                kyuScore.toLowerCase()
            );

            String[] row;
            while ((row = csvReader.readNext()) != null) {
                double rowTotalDistance = 0.0;
                int numColumnsToProcess = Math.min(3, row.length);

                for (int i = 0; i < numColumnsToProcess; i++) {
                    String original = row[i];
                    String attributeName = headers[i];

                    SingleAnonymizationResult result = AnonymizationService.anonymizeSingleValue(
                        original,
                        attributeName,
                        kyuScore,
                        sensitivity,
                        strategies
                    );

                    String anonymized = result.getAnonymizedValue();
                    double distance = Main.calculateDistance(original, anonymized);
                    rowTotalDistance += distance;
                }

                double rowAverage = rowTotalDistance / numColumnsToProcess;
                distances.add(rowAverage);
            }
        }

        return distances.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
    }

    

public static Path findClosestMatchingFile(String uploadsDir, String targetBaseName) throws IOException {
    Path dir = Paths.get(uploadsDir);
    LevenshteinDistance distanceCalculator = new LevenshteinDistance();

    try (Stream<Path> files = Files.list(dir)) {
        List<Path> csvFiles = files
                .filter(f -> f.getFileName().toString().endsWith(".csv"))
                .collect(Collectors.toList());

        Path bestMatch = null;
        int bestDistance = Integer.MAX_VALUE;

        for (Path file : csvFiles) {
            String fileName = file.getFileName().toString().toLowerCase().replace(".csv", "");
            int distance = distanceCalculator.apply(fileName, targetBaseName.toLowerCase());

            if (distance < bestDistance) {
                bestDistance = distance;
                bestMatch = file;
            }
        }

        if (bestMatch != null && bestDistance <= 15) {
            System.out.println("✔ Closest match for \"" + targetBaseName + "\" is \"" +
                               bestMatch.getFileName() + "\" (distance: " + bestDistance + ")");
            return bestMatch;
        } else {
            System.err.println("✖ No close match found for \"" + targetBaseName + "\" (closest distance: " + bestDistance + ")");
            return null;
        }
    }
}

}