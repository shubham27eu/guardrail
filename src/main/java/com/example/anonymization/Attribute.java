package com.example.anonymization;

public class Attribute {
    private String attributeId;
    private String description;

    public Attribute(String attributeId, String description) {
        this.attributeId = attributeId;
        this.description = description;
    }

    public String getAttributeId() {
        return attributeId;
    }

    public String getDescription() {
        return description;
    }

    @Override
    public String toString() {
        return "Attribute{" +
               "attributeId='" + attributeId + '\'' +
               ", description='" + description + '\'' +
               '}';
    }
}
