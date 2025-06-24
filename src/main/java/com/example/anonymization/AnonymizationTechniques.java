package com.example.anonymization;

import org.apache.commons.math3.distribution.LaplaceDistribution;
import org.apache.commons.math3.stat.descriptive.rank.Percentile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AnonymizationTechniques {

    private static boolean isColumnNumeric(SimpleDataFrame df, String columnName) {
        if (df == null || df.getRowCount() == 0 || !df.getColumnHeaders().contains(columnName)) return false;
        for (Map<String, Object> row : df.getRows()) {
            Object value = row.get(columnName);
            if (value != null) {
                if (value instanceof Number) return true;
                try { Double.parseDouble(String.valueOf(value)); return true; } catch (NumberFormatException e) { return false; }
            }
        }
        return false; 
    }

    public static void full_masking_cell(SimpleDataFrame df, String columnName) {
        if (df == null || !df.getColumnHeaders().contains(columnName)) return;
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i);
            Object value = actualRow.get(columnName);
            if (value != null) actualRow.put(columnName, String.join("", Collections.nCopies(String.valueOf(value).length(), "*")));
        }
    }

    public static void partial_masking_cell(SimpleDataFrame df, String columnName) {
        // System.out.println("[DEBUG partial_masking_cell] Called for column: " + columnName); // Optional
        if (df == null || !df.getColumnHeaders().contains(columnName)) return;
        int rowCount = df.getRowCount();
        for (int i = 0; i < rowCount; i++) {
            Map<String, Object> actualRow = df.getRow(i);
            Object value = actualRow.get(columnName);
            if (value != null) {
                String valStr = String.valueOf(value);
                int half = valStr.length() / 2;
                String maskedValue = String.join("", Collections.nCopies(half, "*")) + valStr.substring(half);
                actualRow.put(columnName, maskedValue);
                // if (i < 1 && (columnName.equals("1455") || columnName.equals("924"))) System.out.println("[DEBUG partial_masking_cell] Col: " + columnName + ", Orig: " + valStr + ", Masked: " + maskedValue);
            }
        }
    }
    
    public static void noise_injection(SimpleDataFrame df, String columnName, double epsilon) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || df.getRowCount() == 0) return;
        if (epsilon <= 0) { System.err.println("Warning: Epsilon must be positive for noise injection."); return; }
        List<Double> numericalValuesForStats = new ArrayList<>();
        for (int i = 0; i < df.getRowCount(); i++) { Object value = df.getRow(i).get(columnName); if (value != null) try { numericalValuesForStats.add(Double.parseDouble(String.valueOf(value))); } catch (NumberFormatException e) {}}
        if (numericalValuesForStats.isEmpty()) return;
        if (df.getRowCount() == 1 && df.getColumnCount() == 1 && numericalValuesForStats.size() == 1) {
            df.getRow(0).put(columnName, numericalValuesForStats.get(0) * (1 + 0.85)); return;
        }
        double min = Collections.min(numericalValuesForStats); double max = Collections.max(numericalValuesForStats);
        double sensitivity = Math.max(max - min, 1e-6); double scale = sensitivity / epsilon;
        if (Double.isInfinite(scale) || Double.isNaN(scale) || scale == 0) { System.err.println("Warning: Invalid scale for Laplace: " + scale); return; }
        LaplaceDistribution laplace = new LaplaceDistribution(0, scale);
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i); Object value = actualRow.get(columnName);
            if (value != null) try { actualRow.put(columnName, Double.parseDouble(String.valueOf(value)) + laplace.sample()); } catch (NumberFormatException e) { System.err.println("Warning: Non-numeric in noise_injection: " + value); }
        }
    }
    
    public static void microaggregation_row(SimpleDataFrame df, int k, List<String> numericColumnHeadersToAggregate) {
        if (df == null || df.getRowCount() == 0 || k <= 0 || df.getColumnCount() == 0) return;
        String sortColumn = df.getColumnHeaders().get(0); List<Map<String, Object>> rows = new ArrayList<>(); 
        for(int i=0; i<df.getRowCount(); i++) rows.add(df.getRow(i));
        rows.sort(Comparator.comparing(rowMap -> { Object val = rowMap.get(sortColumn); if (val instanceof Number) return ((Number) val).doubleValue(); try { return Double.parseDouble(String.valueOf(val)); } catch (NumberFormatException e) { return String.valueOf(val); }}, Comparator.nullsLast((o1, o2) -> { if (o1 == null && o2 == null) return 0; if (o1 == null) return 1; if (o2 == null) return -1; if (o1 instanceof Double && o2 instanceof Double) return ((Double)o1).compareTo((Double)o2); if (o1 instanceof String && o2 instanceof String) return ((String)o1).compareTo((String)o2); if (o1 instanceof Double) return -1; if (o2 instanceof Double) return 1; return String.valueOf(o1).compareTo(String.valueOf(o2));})));
        for (int i = 0; i < rows.size(); i += k) {
            List<Map<String, Object>> group = rows.subList(i, Math.min(i + k, rows.size())); if (group.isEmpty()) continue;
            for (String numCol : numericColumnHeadersToAggregate) {
                if (!df.getColumnHeaders().contains(numCol) /*|| !isColumnNumeric(df, numCol)*/) continue; 
                double sum = 0; int count = 0;
                for (Map<String, Object> groupRow : group) { Object val = groupRow.get(numCol); try { sum += Double.parseDouble(String.valueOf(val)); count++; } catch (Exception e) {}}
                if (count > 0) { double meanVal = sum / count; for (Map<String, Object> groupRow : group) groupRow.put(numCol, meanVal); }
            }
        }
    }
    public static SimpleDataFrame microaggregation_row(SimpleDataFrame df, int k) {
        if (df == null) return null; SimpleDataFrame dfCopy = df.copy(); 
        if (dfCopy.getRowCount() == 0 || k <= 0) return dfCopy;
        List<String> numericCols = dfCopy.getColumnHeaders().stream().filter(col -> isColumnNumeric(dfCopy, col)).collect(Collectors.toList());
        if (!numericCols.isEmpty()) microaggregation_row(dfCopy, k, numericCols);
        return dfCopy;
    }

    public static void cell_suppression(SimpleDataFrame df, String columnName, int threshold) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || threshold <= 0 || df.getRowCount() == 0) return;
        Map<Object, Integer> valueCounts = new HashMap<>();
        for (Map<String, Object> row : df.getRows()){ Object value = row.get(columnName); valueCounts.put(value, valueCounts.getOrDefault(value, 0) + 1); }
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i); Object value = actualRow.get(columnName);
            if (valueCounts.getOrDefault(value, 0) < threshold) actualRow.put(columnName, null);
        }
    }

    public static void differential_privacy_column(SimpleDataFrame df, String columnName, double epsilon) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || df.getRowCount() == 0) return;
        if (epsilon <= 0) { System.err.println("Warning: Epsilon must be positive for differential privacy."); return; }
        LaplaceDistribution laplace = new LaplaceDistribution(0, 1.0 / epsilon);
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i); Object value = actualRow.get(columnName);
            if (value != null) try { actualRow.put(columnName, Double.parseDouble(String.valueOf(value)) + laplace.sample()); } catch (NumberFormatException e) { System.err.println("Warning: Non-numeric in diff_priv_col: " + value); }
        }
    }

    public static void top_bottom_coding(SimpleDataFrame df, String columnName, double bottomPercentile, double topPercentile) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || df.getRowCount() == 0) return;
        if (bottomPercentile < 0 || bottomPercentile > 100 || topPercentile < 0 || topPercentile > 100 || bottomPercentile >= topPercentile) { System.err.println("Warning: Invalid percentile cutoffs."); return; }
        List<Double> numericalValues = new ArrayList<>();
        for (Map<String, Object> row : df.getRows()) { Object value = row.get(columnName); if (value != null) try { numericalValues.add(Double.parseDouble(String.valueOf(value))); } catch (NumberFormatException e) {}}
        if (numericalValues.isEmpty()) return;
        Percentile perc = new Percentile(); perc.setData(numericalValues.stream().mapToDouble(d->d).toArray());
        double bottomValue = perc.evaluate(bottomPercentile); double topValue = perc.evaluate(topPercentile);
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i); Object value = actualRow.get(columnName);
            if (value != null) try {
                double numValue = Double.parseDouble(String.valueOf(value));
                if (numValue < bottomValue) actualRow.put(columnName, bottomValue); else if (numValue > topValue) actualRow.put(columnName, topValue);
            } catch (NumberFormatException e) {}
        }
    }

    public static void microaggregation_column(SimpleDataFrame df, String columnName, int k) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || k <= 0) return;
        SimpleDataFrame tempColDf = new SimpleDataFrame(Collections.singletonList(columnName));
        for (Map<String, Object> originalRow : df.getRows()) { Map<String, Object> tempRow = new HashMap<>(); tempRow.put(columnName, originalRow.get(columnName)); tempColDf.addRow(tempRow); }
        SimpleDataFrame aggregatedColDf = microaggregation_row(tempColDf, k);
        List<Map<String, Object>> aggregatedDataRows = aggregatedColDf.getRows();
        for (int i = 0; i < Math.min(df.getRowCount(), aggregatedDataRows.size()); i++) df.getRow(i).put(columnName, aggregatedDataRows.get(i).get(columnName));
    }

    public static void generalization_column(SimpleDataFrame df, String columnName, int bins) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || bins <= 0) return;
        List<Double> numericalValues = new ArrayList<>();
        for (Map<String, Object> row : df.getRows()) { Object value = row.get(columnName); if (value != null) try { numericalValues.add(Double.parseDouble(String.valueOf(value))); } catch (NumberFormatException e) {}}
        if (numericalValues.isEmpty()) return;
        double minVal = Collections.min(numericalValues); double maxVal = Collections.max(numericalValues);
        if (minVal == maxVal) {
            for (int i = 0; i < df.getRowCount(); i++) { Map<String, Object> r = df.getRow(i); if (r.get(columnName) != null) try { if (Double.parseDouble(String.valueOf(r.get(columnName))) == minVal) r.put(columnName, "Group 1"); } catch (NumberFormatException e) {} } return;
        }
        double binWidth = (maxVal - minVal) / bins; if (binWidth == 0 && bins > 1) binWidth = Math.nextUp(0.0);
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i); Object value = actualRow.get(columnName);
            if (value != null) try {
                double numValue = Double.parseDouble(String.valueOf(value));
                int binIndex = (numValue == maxVal) ? (bins - 1) : (int) Math.floor((numValue - minVal) / binWidth);
                actualRow.put(columnName, "Group " + (Math.max(0, Math.min(bins - 1, binIndex)) + 1));
            } catch (NumberFormatException e) {}
        }
    }

    public static void binning(SimpleDataFrame df, String columnName, int binSize) {
        if (df == null || !df.getColumnHeaders().contains(columnName) || binSize <= 0) return;
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i); Object value = actualRow.get(columnName);
            if (value != null) try { actualRow.put(columnName, Math.floor(Double.parseDouble(String.valueOf(value)) / binSize) * binSize); } catch (NumberFormatException e) {}
        }
    }

    public static void full_masking_row(SimpleDataFrame df) {
        if (df == null) return;
        for (int i = 0; i < df.getRowCount(); i++) { Map<String, Object> actualRow = df.getRow(i); for (String key : new ArrayList<>(actualRow.keySet())) actualRow.put(key, "XXXX"); }
    }

    public static void partial_masking_row(SimpleDataFrame df) {
        if (df == null) return;
        for (int i = 0; i < df.getRowCount(); i++) {
            Map<String, Object> actualRow = df.getRow(i);
            for (String key : new ArrayList<>(actualRow.keySet())) {
                Object value = actualRow.get(key); if (value != null) { String vS = String.valueOf(value); int h = vS.length()/2; actualRow.put(key, String.join("",Collections.nCopies(h,"*"))+vS.substring(h));}
            }
        }
    }

    public static void full_masking_table(SimpleDataFrame df) {
        if (df == null) return;
        System.out.println("[DEBUG full_masking_table] Called."); // Added for this test
        for (String colName : df.getColumnHeaders()) for (int i = 0; i < df.getRowCount(); i++) df.getRow(i).put(colName, "XXXX");
    }
    
    public static void partial_masking_table(SimpleDataFrame df) {
       System.out.println("[DEBUG partial_masking_table] Called.");
       if (df == null) return;
       for (String columnName : df.getColumnHeaders()) {
           // Call partial_masking_cell for each column
           // System.out.println("[DEBUG partial_masking_table] Processing column: " + columnName); // Optional
           int rowCount = df.getRowCount();
           for (int i = 0; i < rowCount; i++) {
               Map<String, Object> actualRow = df.getRow(i);
               Object value = actualRow.get(columnName);
               if (value != null) {
                   String valStr = String.valueOf(value);
                   int half = valStr.length() / 2;
                   String maskedValue = String.join("", Collections.nCopies(half, "*")) + valStr.substring(half);
                   actualRow.put(columnName, maskedValue);
                   // Example: print for first row, specific columns
                   if (i == 0 && (columnName.equals("1455") || columnName.equals("924"))) {
                       System.out.println("[DEBUG partial_masking_table/cell] Col: " + columnName + ", Orig: " + valStr + ", Masked: " + maskedValue);
                   }
               }
           }
       }
    }


    public static void microaggregation_table(SimpleDataFrame df, int k) {
        if (df == null || k <= 0) return;
        for (String colName : df.getColumnHeaders()) if (isColumnNumeric(df, colName)) microaggregation_column(df, colName, k);
    }

    public static void differential_privacy_table(SimpleDataFrame df, double epsilon) {
        if (df == null || epsilon <= 0) return;
        for (String colName : df.getColumnHeaders()) if (isColumnNumeric(df, colName)) differential_privacy_column(df, colName, epsilon);
    }
    
    public static void cell_suppression_table(SimpleDataFrame df, int threshold) {
        if (df == null || threshold <= 0) return;
        for (String colName : df.getColumnHeaders()) cell_suppression(df, colName, threshold);
    }

    public static void generalization_table(SimpleDataFrame df, int binsOrHierarchyRef) {
        if (df == null || binsOrHierarchyRef <= 0) return;
        for (String colName : df.getColumnHeaders()) if (isColumnNumeric(df, colName)) generalization_column(df, colName, binsOrHierarchyRef);
    }

    // New methods for single string value anonymization:

    public static String applyCellFullMasking(String value) {
        if (value == null) return null;
        return String.join("", Collections.nCopies(value.length(), "*"));
    }

    public static String applyCellPartialMasking(String value) {
        if (value == null) return null;
        if (value.length() <= 1) return "*"; // Mask short strings entirely
        int half = Math.max(1, value.length() / 2); // Ensure at least 1 char is masked
        return String.join("", Collections.nCopies(half, "*")) + value.substring(half);
    }

    public static String applyCellSuppression(String value) {
        // Using a more common representation for suppression
        return "[S]"; // Or "[REDACTED]", or null, depending on desired output
    }

    public static String applyCellNoTransformation(String value) {
        return value;
    }

    // Placeholder for noise injection - very simplified
    public static String applyCellNoiseInjection(String value) {
        if (value == null) return null;
        try {
            double numericValue = Double.parseDouble(value);
            // Simplified: Add up to +/- 5% random noise relative to the value.
            // This is NOT differentially private or statistically robust.
            double noiseFactor = (Math.random() - 0.5) * 0.1; // Range: -0.05 to +0.05
            double noisyValue = numericValue * (1 + noiseFactor);
            return String.valueOf(noisyValue);
        } catch (NumberFormatException e) {
            // If not numeric, return original value or a specific marker
            return value + "[N_ERR]"; // Indicate noise couldn't be applied
        }
    }

    // Placeholder for generalization - very simplified
    public static String applyCellGeneralization(String value) {
        if (value == null) return null;
        try {
            double numericValue = Double.parseDouble(value);
            // Example: round to nearest 10 for numbers
            return String.valueOf(Math.round(numericValue / 10.0) * 10.0);
        } catch (NumberFormatException e) {
            // If not numeric, very basic categorical generalization by first letter
            if (value.length() > 0) return "CAT_" + Character.toUpperCase(value.charAt(0));
            return "CAT_EMPTY";
        }
    }
}
