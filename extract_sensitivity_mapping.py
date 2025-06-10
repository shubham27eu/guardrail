import pandas as pd

def get_sensitivity_mapping(file_path):
    possible_sheet_names = ['Sheet1', 'Sheet 1', 'SensitivityMapping', 'DomainSensitivity']
    possible_domain_cols = ['Domain', 'Category', 'Data Type', 'Attribute_Type']
    possible_sensitivity_cols = ['SensitivityLevel', 'Sensitivity Level', 'Sensitivity', 'Classification']

    try:
        xls = pd.ExcelFile(file_path)
    except FileNotFoundError:
        print(f"Error: File not found at {file_path}")
        return
    except Exception as e:
        print(f"Error reading Excel file: {e}")
        return

    found_sheet_name = None
    for sheet_name in xls.sheet_names:
        if sheet_name in possible_sheet_names:
            found_sheet_name = sheet_name
            break
    if not found_sheet_name: # If no exact match, try the first sheet
        if xls.sheet_names:
            found_sheet_name = xls.sheet_names[0]
        else:
            print("Error: No sheets found in the Excel file.")
            return

    try:
        df = pd.read_excel(xls, sheet_name=found_sheet_name)
    except Exception as e:
        print(f"Error reading sheet '{found_sheet_name}': {e}")
        return

    domain_col = None
    sensitivity_col = None

    for col in df.columns:
        if col in possible_domain_cols:
            domain_col = col
            break

    for col in df.columns:
        if col in possible_sensitivity_cols:
            sensitivity_col = col
            break

    if not domain_col or not sensitivity_col:
        print("Could not find a sheet with expected columns (Domain/Sensitivity).")
        print(f"Sheet '{found_sheet_name}' columns: {df.columns.tolist()}")
        return

    # Drop rows where either domain_col or sensitivity_col is NaN
    df.dropna(subset=[domain_col, sensitivity_col], inplace=True)

    # Ensure the columns are treated as strings before finding unique pairs
    unique_pairs = df[[domain_col, sensitivity_col]].astype(str).drop_duplicates()

    if unique_pairs.empty:
        print(f"No data found in columns '{domain_col}' and '{sensitivity_col}'.")
        return

    for _, row in unique_pairs.iterrows():
        print(f"Domain: {row[domain_col]}, Sensitivity: {row[sensitivity_col]}")

if __name__ == "__main__":
    # Assuming the file is in the same directory as the script, or a known path.
    # For the subtask, the path is 'FILES/compliance_repository.xlsx'
    file_path = 'FILES/compliance_repository.xlsx'
    get_sensitivity_mapping(file_path)
