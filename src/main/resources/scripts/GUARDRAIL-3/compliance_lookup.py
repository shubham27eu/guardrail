import pandas as pd

# Load compliance repository only once
compliance_df = pd.read_excel("compliance_repository.xlsx")

def get_sensitivity_for_about_domain(about: str, domain: str) -> dict:
    match = compliance_df[
        (compliance_df["About"].str.lower().str.strip() == about.lower().strip()) &
        (compliance_df["Domain"].str.lower().str.strip() == domain.lower().strip())
    ]

    if not match.empty:
        row = match.iloc[0]
        return {
            "Sensitivity Level": row["Sensitivity Level"],
            "Explainability": row.get("Explainability", "N/A"),
            "Sharing Entity": row.get("Sharing Entity", "N/A")
        }
    else:
        return {
            "Sensitivity Level": "Unknown",
            "Explainability": "N/A",
            "Sharing Entity": "N/A"
        }
