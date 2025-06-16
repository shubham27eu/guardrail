package com.example.anonymization;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Utility class that loads the various input files used by the anonymization pipeline.
 *  • CSV files are read with Apache Commons CSV
 *  • XLS/XLSX files are read with Apache POI
 *
 * The loader decides which parser to use by the file-extension.
 */
public class DataLoader {

    /* ──────────────────────────────  COMMON HELPERS  ───────────────────────────── */

    private static String ext(String filePath) {
        int dot = filePath.lastIndexOf('.');
        return dot < 0 ? "" : filePath.substring(dot + 1).toLowerCase();
    }

    /* ──────────────────────────────  CSV → SimpleDataFrame  ────────────────────── */

    private static SimpleDataFrame loadCsvToSimpleDataFrame(String filePath, char delimiter) throws IOException {
        try (Reader reader = Files.newBufferedReader(Paths.get(filePath));
             CSVParser parser = CSVFormat.Builder.create()
                                                .setDelimiter(delimiter)
                                                .setHeader()
                                                .setSkipHeaderRecord(true)
                                                .build()
                                                .parse(reader)) {

            List<String> headers = parser.getHeaderNames();
            SimpleDataFrame sdf = new SimpleDataFrame(headers);

            for (CSVRecord rec : parser) {
                Map<String, Object> row = new HashMap<>();
                for (String h : headers) row.put(h, rec.get(h));
                sdf.addRow(row);
            }
            return sdf;
        }
    }

    /* ──────────────────────────────  XLSX → SimpleDataFrame  ───────────────────── */

    private static SimpleDataFrame loadExcelToSimpleDataFrame(String filePath, String sheetName) throws IOException {
        List<String> headers   = new ArrayList<>();
        List<Map<String, Object>> rows = new ArrayList<>();
        DataFormatter fmt = new DataFormatter();

        try (InputStream is = Files.newInputStream(Paths.get(filePath));
             Workbook wb   = new XSSFWorkbook(is)) {

            Sheet sheet = sheetName == null ? wb.getSheetAt(0) : wb.getSheet(sheetName);
            if (sheet == null) throw new IOException("Sheet '" + sheetName + "' not found in " + filePath);

            Row headerRow = sheet.getRow(0);
            headerRow.forEach(c -> headers.add(fmt.formatCellValue(c)));

            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row r = sheet.getRow(i);
                if (r == null) continue;
                Map<String,Object> row = new LinkedHashMap<>();
                for (int j = 0; j < headers.size(); j++) {
                    Cell c = r.getCell(j, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    row.put(headers.get(j), c == null ? null : fmt.formatCellValue(c));
                }
                rows.add(row);
            }
        }

        SimpleDataFrame sdf = new SimpleDataFrame(headers);
        rows.forEach(sdf::addRow);
        return sdf;
    }

    /* ──────────────────────────────  Public loaders  ───────────────────────────── */

    /** Loads the main data-frame (Data_2019-20.*). */
    public static SimpleDataFrame loadDataDf(String filePath, char delimiter) throws IOException {
        return switch (ext(filePath)) {
            case "csv"  -> loadCsvToSimpleDataFrame(filePath, delimiter);
            case "xls", "xlsx" -> loadExcelToSimpleDataFrame(filePath, null);
            default -> throw new IOException("Unsupported file type for data_df: " + filePath);
        };
    }

    /** Loads Attributes.* and returns a list of Attribute POJOs. */
    public static List<Attribute> loadAttributes(String filePath) throws IOException {
        List<Attribute> attrs = new ArrayList<>();

        if ("csv".equals(ext(filePath))) {
            try (Reader r = Files.newBufferedReader(Paths.get(filePath));
                 CSVParser p = CSVFormat.DEFAULT.withFirstRecordAsHeader().withTrim().parse(r)) {

                String idH  = p.getHeaderNames().contains("Attr_id")     ? "Attr_id"     : p.getHeaderNames().get(0);
                String dsH  = p.getHeaderNames().contains("Description") ? "Description" : p.getHeaderNames().get(1);

                for (CSVRecord rec : p)
                    attrs.add(new Attribute(rec.get(idH), rec.get(dsH)));
            }
        } else if ("xls".equals(ext(filePath)) || "xlsx".equals(ext(filePath))) {
            SimpleDataFrame sdf = loadExcelToSimpleDataFrame(filePath, null);
            String idH = sdf.getColumnHeaders().stream()
                            .filter(h -> h.equalsIgnoreCase("Attr_id")).findFirst()
                            .orElse(sdf.getColumnHeaders().get(0));
            String dsH = sdf.getColumnHeaders().stream()
                            .filter(h -> h.equalsIgnoreCase("Description")).findFirst()
                            .orElse(sdf.getColumnHeaders().get(1));

            for (Map<String,Object> row : sdf.getRows())
                attrs.add(new Attribute(String.valueOf(row.get(idH)), String.valueOf(row.get(dsH))));
        } else {
            throw new IOException("Unsupported file type for Attributes: " + filePath);
        }
        return attrs;
    }

    /** Loads Sensitivity_Results.xlsx (or .csv) → list of POJOs */
    public static List<SensitivityResult> loadSensitivityResults(String filePath, String sheetName) throws IOException {
        List<SensitivityResult> res = new ArrayList<>();

        SimpleDataFrame sdf = switch (ext(filePath)) {
            case "csv"        -> loadCsvToSimpleDataFrame(filePath, ',');
            case "xls", "xlsx"-> loadExcelToSimpleDataFrame(filePath, sheetName);
            default           -> throw new IOException("Unsupported file for sensitivity results: " + filePath);
        };

        for (Map<String,Object> row : sdf.getRows()) {
            String attr = Optional.ofNullable(row.get("Attr_id"))
                                  .orElse(row.get("column_name"))      // fallback names
                                  .toString();
            String lvl  = Optional.ofNullable(row.get("Sensitivity_Level"))
                                  .orElse(row.get("sensitivity_level"))
                                  .toString();
            res.add(new SensitivityResult(attr, lvl));
        }
        return res;
    }

    /** Loads KYU Score.* → list of POJOs */
    public static List<KyuScore> loadKyuScores(String filePath, String sheetName) throws IOException {
        List<KyuScore> res = new ArrayList<>();

        SimpleDataFrame sdf = switch (ext(filePath)) {
            case "csv"        -> loadCsvToSimpleDataFrame(filePath, ',');
            case "xls", "xlsx"-> loadExcelToSimpleDataFrame(filePath, sheetName);
            default           -> throw new IOException("Unsupported file for KYU scores: " + filePath);
        };

        for (Map<String,Object> row : sdf.getRows()) {
            String id  = Optional.ofNullable(row.get("ID"))
                                 .orElse(row.get("user_id")).toString();
            String kyu = Optional.ofNullable(row.get("KYU_Score"))
                                 .orElse(row.get("kyu_score")).toString();
            res.add(new KyuScore(id, kyu));
        }
        return res;
    }
}