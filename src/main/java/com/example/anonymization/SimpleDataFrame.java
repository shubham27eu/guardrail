package com.example.anonymization;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class SimpleDataFrame {
    private List<String> headers;
    private List<Map<String, Object>> data;

    public SimpleDataFrame(List<String> headers) {
        // Use a copy to prevent external modification
        this.headers = new ArrayList<>(headers);
        this.data = new ArrayList<>();
    }

    public void addRow(Map<String, Object> row) {
        Map<String, Object> newRow = new LinkedHashMap<>();
        for (String header : headers) {
            if (row.containsKey(header)) {
                newRow.put(header, row.get(header));
            } else {
                newRow.put(header, null); // Or some other placeholder for missing values
            }
        }
        data.add(newRow);
    }

    public List<String> getColumnHeaders() {
        return new ArrayList<>(headers); // Return a copy
    }

    public List<Map<String, Object>> getRows() {
        // Return a deep copy to prevent external modification of rows
        return data.stream()
                .map(LinkedHashMap::new)
                .collect(Collectors.toList());
    }

    public int getRowCount() {
        return data.size();
    }

    public int getColumnCount() {
        return headers.size();
    }

    public Map<String, Object> getRow(int rowIndex) {
        if (rowIndex < 0 || rowIndex >= data.size()) {
            throw new IndexOutOfBoundsException("Row index out of bounds: " + rowIndex);
        }
        // Return a DIRECT REFERENCE to the actual row map
        return data.get(rowIndex);
    }

    public List<Object> getColumnData(String columnName) {
        if (!headers.contains(columnName)) {
            // Or return empty list: return new ArrayList<>();
            throw new IllegalArgumentException("Column not found: " + columnName);
        }
        return data.stream()
                .map(row -> row.get(columnName))
                .collect(Collectors.toList());
    }

    public List<Object> getColumnData(int columnIndex) {
        if (columnIndex < 0 || columnIndex >= headers.size()) {
            throw new IndexOutOfBoundsException("Column index out of bounds: " + columnIndex);
        }
        String columnName = headers.get(columnIndex);
        return getColumnData(columnName);
    }

    public SimpleDataFrame subset(List<String> columnsToKeep) {
        List<String> newHeaders = new ArrayList<>();
        for (String col : columnsToKeep) {
            if (this.headers.contains(col)) {
                newHeaders.add(col);
            } else {
                // Optionally, warn or throw exception for columns not found
                System.err.println("Warning: Column '" + col + "' not found in original DataFrame.");
            }
        }

        SimpleDataFrame newDf = new SimpleDataFrame(newHeaders);
        for (Map<String, Object> oldRow : this.data) {
            Map<String, Object> newRow = new LinkedHashMap<>();
            for (String header : newHeaders) {
                newRow.put(header, oldRow.get(header));
            }
            newDf.addRow(newRow);
        }
        return newDf;
    }

    public SimpleDataFrame copy() {
        SimpleDataFrame copyDf = new SimpleDataFrame(this.headers); // Headers list is already copied in constructor
        // Deep copy data
        for (Map<String, Object> row : this.data) {
            copyDf.data.add(new LinkedHashMap<>(row)); // Add a copy of each row map
        }
        return copyDf;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        // Print headers
        sb.append(String.join("\t|\t", headers)).append("\n");
        sb.append(headers.stream().map(h -> "----").collect(Collectors.joining("\t|\t"))).append("\n");

        // Print first few rows (e.g., up to 5)
        int rowsToPrint = Math.min(5, data.size());
        for (int i = 0; i < rowsToPrint; i++) {
            Map<String, Object> row = data.get(i);
            List<String> rowValues = new ArrayList<>();
            for (String header : headers) {
                Object value = row.get(header);
                rowValues.add(value == null ? "NULL" : value.toString());
            }
            sb.append(String.join("\t|\t", rowValues)).append("\n");
        }
        if (data.size() > 5) {
            sb.append("... (").append(data.size() - 5).append(" more rows)\n");
        }
        return sb.toString();
    }
    public void setColumnHeaders(List<String> newHeaders) {
        if (newHeaders == null || newHeaders.size() != this.headers.size()) {
            throw new IllegalArgumentException("New headers list must match existing header count.");
        }
        this.headers = new ArrayList<>(newHeaders);
    }

    public void writeToCsv(String filePath) throws IOException {
        if (filePath == null || filePath.trim().isEmpty()) {
            throw new IllegalArgumentException("File path cannot be null or empty.");
        }

        StringBuilder sb = new StringBuilder();
        List<String> currentHeaders = this.getColumnHeaders(); // Use getter to ensure consistency if overridden

        // Write headers
        // Simple quoting for headers containing comma, quote, or newline
        sb.append(currentHeaders.stream()
                .map(this::escapeCsvValue)
                .collect(Collectors.joining(",")))
                .append("\n");

        // Write rows
        if (this.getRowCount() > 0) {
            for (Map<String, Object> row : this.getRows()) { // Use getter
                List<String> rowValues = new ArrayList<>();
                for (String header : currentHeaders) {
                    Object value = row.get(header);
                    rowValues.add(escapeCsvValue(value));
                }
                sb.append(String.join(",", rowValues)).append("\n");
            }
        } else if (currentHeaders.isEmpty()) {
             // No headers and no rows, write truly empty file if desired
             // For now, if headers existed, they are written. If no headers, this means empty file.
             // If currentHeaders is empty, the header line above is just a newline.
             // To ensure an absolutely empty file if no headers and no data:
             // if (currentHeaders.isEmpty()) {
             //     Files.write(Paths.get(filePath), "".getBytes("UTF-8"));
             //     return;
             // }
        }
        // If no rows but headers exist, only the header line will be written.

        java.nio.file.Files.write(java.nio.file.Paths.get(filePath), sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String escapeCsvValue(Object value) {
        if (value == null) {
            return ""; // Represent null as empty string in CSV
        }
        String stringValue = String.valueOf(value);
        // If value contains comma, quote, or newline, then enclose in double quotes
        if (stringValue.contains(",") || stringValue.contains("\"") || stringValue.contains("\n") || stringValue.contains("\r")) {
            // Replace any existing double quotes with two double quotes
            stringValue = stringValue.replace("\"", "\"\"");
            return "\"" + stringValue + "\"";
        }
        return stringValue;
    }
}
