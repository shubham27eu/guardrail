package com.example.anonymization;

// Removed: org.apache.commons.csv.*
// Removed: org.apache.poi.ss.usermodel.*
// Removed: org.apache.poi.xssf.usermodel.XSSFWorkbook
// Removed: java.io.InputStream, java.io.Reader
// Removed: java.nio.file.Files, java.nio.file.Paths (unless used by FileReader, but direct path is fine)
// Removed: java.util.Map, java.util.HashMap, java.util.LinkedHashMap, java.util.Optional

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Utility class that loads input files for the anonymization pipeline.
 * Currently, it only loads attribute-value pairs from a text file.
 */
public class DataLoader {

    // Removed ext method
    // Removed loadCsvToSimpleDataFrame method
    // Removed loadExcelToSimpleDataFrame method
    // Removed loadDataDf method
    // Removed loadAttributes method
    // Removed loadSensitivityResults method
    // Removed loadKyuScores method

    public static List<AttributeValueEntry> loadAttributeValues(String filePath) throws IOException {
        List<AttributeValueEntry> entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            int lineNumber = 0;
            while ((line = reader.readLine()) != null) {
                lineNumber++;
                String[] parts = line.split("::", 2); // Split into exactly two parts
                if (parts.length == 2) {
                    entries.add(new AttributeValueEntry(parts[0].trim(), parts[1].trim()));
                } else {
                    System.err.println("Warning: Malformed line at " + filePath + ":" + lineNumber + ". Line: '" + line + "'. Skipping.");
                }
            }
        }
        // IOException from BufferedReader or FileReader will propagate
        return entries;
    }
}