package com.example.anonymization;

public class AttributeValueEntry {
    private final String attributeName;
    private final String attributeValue;

    public AttributeValueEntry(String attributeName, String attributeValue) {
        this.attributeName = attributeName;
        this.attributeValue = attributeValue;
    }

    public String getAttributeName() {
        return attributeName;
    }

    public String getAttributeValue() {
        return attributeValue;
    }

    @Override
    public String toString() {
        return "AttributeValueEntry{" +
               "attributeName='" + attributeName + '\'' +
               ", attributeValue='" + attributeValue + '\'' +
               '}';
    }
}
