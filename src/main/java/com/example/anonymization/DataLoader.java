package com.example.anonymization;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook; // For .xlsx
// Consider also: import org.apache.poi.hssf.usermodel.HSSFWorkbook; // For .xls if needed

import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class DataLoader {

    // Generic CSV Loader into SimpleDataFrame
    public static SimpleDataFrame loadCsvToSimpleDataFrame(String filePath, char delimiter) throws IOException {
        try (FileReader reader = new FileReader(filePath);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.Builder.create().setDelimiter(delimiter).setHeader().setSkipHeaderRecord(true).build())) {

            List<String> headers = csvParser.getHeaderNames();
            SimpleDataFrame sdf = new SimpleDataFrame(headers);

            for (CSVRecord csvRecord : csvParser) {
                Map<String, Object> row = new HashMap<>();
                for (String header : headers) {
                    row.put(header, csvRecord.get(header));
                }
                sdf.addRow(row);
            }
            return sdf;
        }
    }

    // Specific loader for Data_2019-20.csv
    public static SimpleDataFrame loadDataDf(String filePath, char delimiter) throws IOException {
        return loadCsvToSimpleDataFrame(filePath, delimiter);
    }

    // Specific loader for Attributes.csv
    public static List<Attribute> loadAttributes(String filePath) throws IOException {
        List<Attribute> attributes = new ArrayList<>();
        // Assuming Attr_id is the first column and Description is the second. Adjust if different.
        // Using a generic CSV reader approach here.
        try (FileReader reader = new FileReader(filePath);
             CSVParser csvParser = new CSVParser(reader, CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim())) {
            
            // Determine actual header names for safety, though we expect "Attr_id" and "Description"
            // For now, let's assume fixed positions or names if known, or be more robust by checking headers.
            // If "Attributes.csv" has headers "Attr_id", "Description", this will work.
            // Otherwise, need to adjust CSVFormat or manual parsing.
            // The notebook implies attribute_df = pd.read_csv("Attributes.csv"), so it likely has headers.

            String idHeader = csvParser.getHeaderNames().contains("Attr_id") ? "Attr_id" : csvParser.getHeaderNames().get(0);
            String descHeader = csvParser.getHeaderNames().contains("Description") ? "Description" : csvParser.getHeaderNames().get(1);


            for (CSVRecord csvRecord : csvParser) {
                String attrId = csvRecord.get(idHeader);
                String description = csvRecord.get(descHeader);
                attributes.add(new Attribute(attrId, description));
            }
        }
        return attributes;
    }

    // Generic Excel Loader (adapt as needed for specific POJO mapping)
    private static List<Map<String, Object>> loadExcelSheet(String filePath, String sheetName) throws IOException {
        List<Map<String, Object>> sheetData = new ArrayList<>();
        try (InputStream is = Files.newInputStream(Paths.get(filePath));
             Workbook workbook = WorkbookFactory.create(is)) { // WorkbookFactory handles both .xls and .xlsx

            Sheet sheet = sheetName != null ? workbook.getSheet(sheetName) : workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IOException("Sheet with name " + (sheetName != null ? sheetName : "[FIRST_SHEET]") + " not found in " + filePath);
            }

            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IOException("Header row not found in sheet " + (sheetName != null ? sheetName : "[FIRST_SHEET]"));
            }

            List<String> headers = new ArrayList<>();
            for (Cell cell : headerRow) {
                headers.add(cell.getStringCellValue());
            }

            DataFormatter dataFormatter = new DataFormatter();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row currentRow = sheet.getRow(i);
                if (currentRow == null) continue;
                Map<String, Object> rowMap = new HashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell cell = currentRow.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell != null) {
                        rowMap.put(headers.get(j), dataFormatter.formatCellValue(cell));
                    } else {
                        rowMap.put(headers.get(j), null);
                    }
                }
                sheetData.add(rowMap);
            }
        }
        return sheetData;
    }
    
    // Specific loader for Sensitivity_Results.xlsx
    // Expected columns: "Attr_id", "Sensitivity_Level" (as per notebook cell 3 & 9)
    public static List<SensitivityResult> loadSensitivityResults(String filePath, String sheetName) throws IOException {
        List<SensitivityResult> results = new ArrayList<>();
        List<Map<String, Object>> sheetData = loadExcelSheet(filePath, sheetName);
        for (Map<String, Object> row : sheetData) {
            // Ensure keys match exact column names in Excel file.
            // The notebook uses 'Attr_id' for sensitivity_df.
            String attrId = row.get("Attr_id") != null ? row.get("Attr_id").toString() : (row.get("column_name") != null ? row.get("column_name").toString() : null);
            String sensitivityLevel = row.get("Sensitivity_Level") != null ? row.get("Sensitivity_Level").toString() : (row.get("sensitivity_level") != null ? row.get("sensitivity_level").toString() : null);
            
            if (attrId == null || sensitivityLevel == null) {
                // Handle cases where expected columns might be missing or have different names
                // For now, skip rows with missing critical data or throw error
                System.err.println("Skipping row due to missing Attr_id or Sensitivity_Level: " + row);
                continue;
            }
            results.add(new SensitivityResult(attrId, sensitivityLevel));
        }
        return results;
    }

    // Specific loader for KYU Score.xlsx
    // Expected columns: "ID", "KYU_Score" (as per notebook cell 11)
    public static List<KyuScore> loadKyuScores(String filePath, String sheetName) throws IOException {
        List<KyuScore> scores = new ArrayList<>();
        List<Map<String, Object>> sheetData = loadExcelSheet(filePath, sheetName);
        for (Map<String, Object> row : sheetData) {
             // Ensure keys match exact column names in Excel file.
            String userId = row.get("ID") != null ? row.get("ID").toString() : (row.get("user_id") != null ? row.get("user_id").toString() : null);
            String kyuScore = row.get("KYU_Score") != null ? row.get("KYU_Score").toString() : (row.get("kyu_score") != null ? row.get("kyu_score").toString() : null);

            if (userId == null || kyuScore == null) {
                System.err.println("Skipping row due to missing ID or KYU_Score: " + row);
                continue;
            }
            scores.add(new KyuScore(userId, kyuScore));
        }
        return scores;
    }
}
