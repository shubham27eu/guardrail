package com.example.anonymization;

public class SensitivityResult {
    private String attributeId; // maps to 'Attr_id'
    private String sensitivityLevel;

    public SensitivityResult(String attributeId, String sensitivityLevel) {
        this.attributeId = attributeId;
        this.sensitivityLevel = sensitivityLevel;
    }

    public String getAttributeId() {
        return attributeId;
    }

    public String getSensitivityLevel() {
        return sensitivityLevel;
    }

    @Override
    public String toString() {
        return "SensitivityResult{" +
               "attributeId='" + attributeId + '\'' +
               ", sensitivityLevel='" + sensitivityLevel + '\'' +
               '}';
    }
}
