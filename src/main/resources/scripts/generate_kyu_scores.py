import pandas as pd
import numpy as np
from sklearn.model_selection import train_test_split
from sklearn.ensemble import RandomForestClassifier
from sklearn.preprocessing import OneHotEncoder
from sklearn.compose import ColumnTransformer
from sklearn.pipeline import Pipeline

def derive_email_type(df):
    """Derives Email_Type from Email column."""
    conditions = [
        df['Email'].str.contains('@gmail', case=False, na=False),
        df['Email'].str.contains('@outlook', case=False, na=False),
        df['Email'].str.contains('@yahoo', case=False, na=False)
    ]
    choices = ['Gmail', 'Outlook', 'Yahoo']
    df['Email_Type'] = np.select(conditions, choices, default='Other')
    return df

def encode_kyu_score(df):
    """Maps KYU Score text to KYU_Score_Encoded."""
    mapping = {'Low': 0, 'Moderate': 1, 'High': 2}
    df['KYU_Score_Encoded'] = df['KYU Score'].map(mapping)
    return df

def main():
    # Define file paths
    # Assuming input files are co-located or paths are adjusted during execution
    input_file_path = 'KYU_Score_Final.xlsx' # Adjusted path
    output_file_path = 'KYU Score.xlsx'

    # Load the dataset
    try:
        # Attempt to load from current dir, then from ../FILES, then from ./FILES
        try:
            kyu_df = pd.read_excel(input_file_path)
        except FileNotFoundError:
            try:
                kyu_df = pd.read_excel(f"../FILES/{input_file_path}")
            except FileNotFoundError:
                kyu_df = pd.read_excel(f"FILES/{input_file_path}")

    except FileNotFoundError:
        print(f"Error: Input file {input_file_path} not found in standard locations.")
        return
    except Exception as e:
        print(f"Error reading Excel file {input_file_path}: {e}")
        return

    # Feature Engineering
    kyu_df = derive_email_type(kyu_df)
    kyu_df = encode_kyu_score(kyu_df)

    kyu_df.dropna(subset=['KYU_Score_Encoded'], inplace=True)
    kyu_df['KYU_Score_Encoded'] = kyu_df['KYU_Score_Encoded'].astype(int)

    categorical_features = ['Email_Type', 'Domain', 'Purpose']

    for col in categorical_features:
        if col not in kyu_df.columns:
            print(f"Error: Expected column '{col}' not found in the input file.")
            # Create missing columns with a placeholder value if necessary
            kyu_df[col] = 'Missing' # Or some other appropriate placeholder
            # return # Or decide if execution can continue

    X = kyu_df[categorical_features]
    y = kyu_df['KYU_Score_Encoded']

    preprocessor = ColumnTransformer(
        transformers=[
            ('onehot', OneHotEncoder(handle_unknown='ignore', sparse_output=False), categorical_features)
        ],
        remainder='passthrough'
    )

    model = RandomForestClassifier(random_state=42, n_estimators=100)
    pipeline = Pipeline(steps=[('preprocessor', preprocessor), ('classifier', model)])

    try:
        pipeline.fit(X, y)
    except Exception as e:
        print(f"Error during model training: {e}")
        return

    all_predictions_encoded = pipeline.predict(X)
    reverse_mapping = {0: 'Low', 1: 'Moderate', 2: 'High'}
    all_predictions_textual = [reverse_mapping[pred] for pred in all_predictions_encoded]

    output_df = pd.DataFrame({
        'ID': kyu_df.index,
        'KYU_Score': all_predictions_textual
    })

    try:
        output_df.to_excel(output_file_path, index=False)
        print(f"Successfully generated '{output_file_path}'")
    except Exception as e:
        print(f"Error saving output file {output_file_path}: {e}")

if __name__ == '__main__':
    main()
