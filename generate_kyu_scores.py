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
    input_file_path = 'FILES/KYU_Score_Final.xlsx'
    output_file_path = 'KYU Score.xlsx'

    # Load the dataset
    try:
        kyu_df = pd.read_excel(input_file_path)
    except FileNotFoundError:
        print(f"Error: Input file not found at {input_file_path}")
        return
    except Exception as e:
        print(f"Error reading Excel file {input_file_path}: {e}")
        return

    # Feature Engineering
    kyu_df = derive_email_type(kyu_df)
    kyu_df = encode_kyu_score(kyu_df)

    # Drop rows where 'KYU_Score_Encoded' is NaN (if 'KYU Score' was not in mapping)
    kyu_df.dropna(subset=['KYU_Score_Encoded'], inplace=True)
    # Ensure target variable is integer
    kyu_df['KYU_Score_Encoded'] = kyu_df['KYU_Score_Encoded'].astype(int)


    # Define features (X) and target (y)
    # Assuming 'Name' and 'Email' are not features for the model itself,
    # but 'Domain', 'Purpose', and 'Email_Type' are.
    # Also, 'KYU Score' is the original text target, and 'KYU_Score_Encoded' is the numerical target.
    # Other columns like 'Sensitivity Level', 'Sharing Entity', 'Review Date' might be present
    # but not used as features based on typical ML model training.

    # Columns to be one-hot encoded
    categorical_features = ['Email_Type', 'Domain', 'Purpose']

    # Ensure all categorical features are present in the DataFrame
    for col in categorical_features:
        if col not in kyu_df.columns:
            print(f"Error: Expected column '{col}' not found in the input file.")
            # Potentially, fill with a default value or handle as per notebook's logic
            # For now, we will error out if essential columns for preprocessing are missing.
            # Example: kyu_df[col] = 'Missing' # or some other placeholder
            return

    X = kyu_df[categorical_features]
    y = kyu_df['KYU_Score_Encoded']

    # Preprocessing: OneHotEncoder for categorical features
    preprocessor = ColumnTransformer(
        transformers=[
            ('onehot', OneHotEncoder(handle_unknown='ignore', sparse_output=False), categorical_features)
        ],
        remainder='passthrough' # Keep other columns (if any) not specified in transformers
    )

    # Define the model
    model = RandomForestClassifier(random_state=42, n_estimators=100)

    # Create a pipeline for preprocessing and modeling
    pipeline = Pipeline(steps=[('preprocessor', preprocessor),
                               ('classifier', model)])

    # Train the model (using the entire dataset as per instructions for prediction)
    # In a real scenario, you'd split into train and test sets for evaluation.
    # Here, the goal is to generate scores for all users in the input file.
    try:
        pipeline.fit(X, y)
    except Exception as e:
        print(f"Error during model training: {e}")
        print("Make sure all categorical columns have sufficient data and are of string type.")
        # Example: print(kyu_df[categorical_features].info())
        # Example: print(kyu_df[categorical_features].head())
        return

    # Prediction Logic
    # Predict KYU_Score_Encoded for all users in the loaded data
    all_predictions_encoded = pipeline.predict(X)

    # Convert numerical predictions back to textual KYU scores
    reverse_mapping = {0: 'Low', 1: 'Moderate', 2: 'High'}
    all_predictions_textual = [reverse_mapping[pred] for pred in all_predictions_encoded]

    # Output `KYU Score.xlsx`
    # Create a DataFrame with 'ID' (0-indexed row number) and 'KYU_Score'

    # Using the original index of kyu_df as the ID
    output_df = pd.DataFrame({
        'ID': kyu_df.index, # 0-indexed row number
        'KYU_Score': all_predictions_textual
    })

    # Save the DataFrame to 'KYU Score.xlsx'
    try:
        output_df.to_excel(output_file_path, index=False)
        print(f"Successfully generated '{output_file_path}'")
    except Exception as e:
        print(f"Error saving output file {output_file_path}: {e}")

if __name__ == '__main__':
    main()
