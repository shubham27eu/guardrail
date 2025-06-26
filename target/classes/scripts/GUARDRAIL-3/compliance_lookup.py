import pandas as pd

# Load compliance repository only once
compliance_df = pd.read_excel("compliance_repository[Merged].xlsx")

def get_sensitivity_for_about_domain(about: str, domain: str) -> dict:
    match = compliance_df[
        (compliance_df["About"].str.lower().str.strip() == about.lower().strip()) &
        (compliance_df["Domain"].str.lower().str.strip() == domain.lower().strip())
    ]

    if not match.empty:
        row = match.iloc[0]
        return {
            "Sensitivity Level": row["Sensitivity Level"],
            "Explaination": row.get("Explaination", "N/A"),
            "Receiving Entity": row.get("Receiving Entity", "N/A")
        }
    else:
        return {
            "Sensitivity Level": "Low",
            "Explaination": "As Sensitivity is default, there is no explanation ",
            "Recieving Entity": "N/A"
        }
