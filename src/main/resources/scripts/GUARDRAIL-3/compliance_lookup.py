import pandas as pd

# Load compliance repository only once
compliance_df = pd.read_excel("compliance_repository.xlsx")

def get_sensitivity_for_owner_domain(owner: str, domain: str) -> dict:
    match = compliance_df[
        (compliance_df["Owner"].str.lower().str.strip() == owner.lower().strip()) &
        (compliance_df["Domain"].str.lower().str.strip() == domain.lower().strip())
    ]

    if not match.empty:
        row = match.iloc[0]
        return {
            "Sensitivity Level": row["Sensitivity Level"]
            # "Explainability": row["Explainability"],
            # "Sharing Entity": row["Sharing Entity"]
        }
    else:
        return {
            "Sensitivity Level": "Unknown"
            # "Explainability": "N/A",
            # "Sharing Entity": "N/A"
        }
