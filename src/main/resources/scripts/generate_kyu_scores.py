import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import OneHotEncoder
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline
import os # Added for path joining

def derive_email_type(df):
    """Derives Email_Type from Email column."""
    # Ensure 'Email' column exists
    if 'Email' not in df.columns:
        print("Warning: 'Email' column not found for Email_Type derivation. Returning DataFrame unchanged.")
        df['Email_Type'] = 'Other' # Default if no email column
        return df

    conditions = [
        df['Email'].astype(str).str.contains('@gmail', case=False, na=False),
        df['Email'].astype(str).str.contains('@outlook', case=False, na=False),
        df['Email'].astype(str).str.contains('@yahoo', case=False, na=False)
    ]
    choices = ['Gmail', 'Outlook', 'Yahoo']
    df['Email_Type'] = np.select(conditions, choices, default='Other')
    return df

def encode_kyu_score(df):
    """Maps KYU Score text to KYU_Score_Encoded."""
    mapping = {'Low': 0, 'Moderate': 1, 'High': 2}
    # Ensure 'KYU Score' column exists before mapping
    if 'KYU Score' in df.columns:
        df['KYU_Score_Encoded'] = df['KYU Score'].map(mapping)
    else:
        print("Error: 'KYU Score' column not found in input data. Cannot encode. Defaulting KYU_Score_Encoded to NaN.")
        df['KYU_Score_Encoded'] = np.nan # Or some default encoding
    return df

def load_data_with_fallback(filename_options, read_func, **kwargs):
    """Tries to load a file from a list of possible names/paths."""
    last_exception = None
    for filename in filename_options:
        try:
            # Ensure os.path.exists is checked before attempting to read for robustness
            if not os.path.exists(filename):
                print(f"File path {filename} does not exist. Skipping.")
                continue
            df = read_func(filename, **kwargs)
            print(f"Successfully loaded {filename}")
            return df
        except FileNotFoundError: # Should be caught by os.path.exists, but as a fallback
            last_exception = FileNotFoundError(f"File not found: {filename}")
            print(f"Attempted to load {filename}, but not found.")
        except Exception as e:
            last_exception = e
            print(f"Error loading {filename}: {e}")
    if last_exception:
        print(f"Failed to load data after trying all options for base name: {filename_options[0]}. Last error: {last_exception}")
    return None

def main():
    # Define file paths
    kyu_score_base_file = 'KYU_Score_Final.xlsx'
    output_file_path = 'KYU Score.xlsx' # Output will be in the script's CWD

    # Path Probing Logic
    possible_locations = [
        f"FILES/{kyu_score_base_file}",
        f"../FILES/{kyu_score_base_file}",
        f"../../FILES/{kyu_score_base_file}",
        kyu_score_base_file,
    ]

    kyu_df = load_data_with_fallback(possible_locations, pd.read_excel)

    if kyu_df is None:
        print(f"Error: Input file '{kyu_score_base_file}' not found in standard locations. Exiting.")
        return

    # Robust ID column selection: Prefer 'ID', then 'User_ID', then 'Email', then 'Name', then index
    id_column_options = ['ID', 'User_ID', 'Email', 'Name']
    id_col_name_for_output = 'ID_Original' # Name for the column in output, to avoid clash if 'ID' is generated index

    # Determine which ID column to use from the input kyu_df
    chosen_input_id_col = None
    if 'ID' in kyu_df.columns and not kyu_df['ID'].isnull().all():
        chosen_input_id_col = 'ID'
    else:
        for col_opt in id_column_options:
            if col_opt in kyu_df.columns and not kyu_df[col_opt].isnull().all():
                chosen_input_id_col = col_opt
                break

    # Store original IDs for the output file
    if chosen_input_id_col:
        original_ids = kyu_df[chosen_input_id_col]
    else:
        original_ids = kyu_df.index # Fallback to index
        chosen_input_id_col = "DataFrame_Index" # For logging purposes
        print(f"Warning: No standard ID column found or 'ID' column is all NaN. Using DataFrame index as '{id_col_name_for_output}'. Original column name for reference: {chosen_input_id_col}")


    # Feature Engineering
    kyu_df = derive_email_type(kyu_df)
    kyu_df = encode_kyu_score(kyu_df)

    # Critical for target variable: drop rows where KYU_Score_Encoded could not be determined
    kyu_df.dropna(subset=['KYU_Score_Encoded'], inplace=True)
    if kyu_df.empty:
        print("Error: DataFrame is empty after dropping rows with no valid KYU_Score_Encoded. Cannot proceed.")
        return
    kyu_df['KYU_Score_Encoded'] = kyu_df['KYU_Score_Encoded'].astype(int)

    categorical_features = ['Email_Type', 'Domain', 'Purpose']

    # Ensure all categorical features are present, fill with placeholder if not
    for col in categorical_features:
        if col not in kyu_df.columns:
            print(f"Warning: Feature column '{col}' not found. Filling with 'Unknown'.")
            kyu_df[col] = 'Unknown'

    X = kyu_df[categorical_features]
    y = kyu_df['KYU_Score_Encoded']

    preprocessor = ColumnTransformer(
        transformers=[
            ('onehot', OneHotEncoder(handle_unknown='ignore', sparse_output=False), categorical_features)
        ],
        remainder='passthrough' # Keep other columns not specified
    )

    model = RandomForestClassifier(random_state=42, n_estimators=100)
    pipeline = Pipeline(steps=[('preprocessor', preprocessor), ('classifier', model)])

    try:
        pipeline.fit(X, y)
    except ValueError as ve:
        print(f"ValueError during model training: {ve}. This might be due to all-NaN features after placeholder fill or empty X/y.")
        print("Sample of X after potential modifications:")
        print(X.head())
        print("Shape of X:", X.shape)
        print("Shape of y:", y.shape)
        return
    except Exception as e:
        print(f"Error during model training: {e}")
        return

    all_predictions_encoded = pipeline.predict(X)
    reverse_mapping = {0: 'Low', 1: 'Moderate', 2: 'High'}
    all_predictions_textual = [reverse_mapping[pred] for pred in all_predictions_encoded]

    # Use the determined original_ids for the output 'ID' column
    # Ensure the output DataFrame uses the correct set of IDs, matching the rows used for prediction.
    # Since rows might have been dropped by dropna, re-index original_ids if necessary,
    # or simply use the index from the (potentially filtered) kyu_df if original_ids isn't aligned.
    # The safest is to use the index of kyu_df (which was used for X and y) if original_ids might be stale.
    # However, 'original_ids' should correspond to kyu_df before dropna if we want to map back to true original state.
    # For this script, we'll output IDs corresponding to the rows *used* for prediction.

    # Create the output DataFrame
    # The 'ID' column should be the string representation of the 0-indexed row numbers
    # of the DataFrame *after all processing and filtering* (i.e., kyu_df.index).
    output_df = pd.DataFrame({
        'ID': kyu_df.index.astype(str),
        'KYU_Score': all_predictions_textual
    })

    try:
        output_df.to_excel(output_file_path, index=False)
        print(f"Successfully generated '{output_file_path}' with {len(output_df)} records.")
    except Exception as e:
        print(f"Error saving output file {output_file_path}: {e}")

if __name__ == '__main__':
    main()
