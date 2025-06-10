import pandas as pd
import numpy as np
import faiss
from sentence_transformers import SentenceTransformer
import os
from collections import Counter # Though top-1 match might not need it, good to have if k>1 later

# Sensitivity level order for comparison
SENSITIVITY_ORDER = {'High': 3, 'Moderate': 2, 'Low': 1}
DEFAULT_SENSITIVITY = 'Low'

def get_sensitivity_from_category(category, mapping):
    """Gets sensitivity for a single category, defaulting to DEFAULT_SENSITIVITY."""
    return mapping.get(category, DEFAULT_SENSITIVITY)

def get_final_sensitivity(domain_label, owner_label, mapping):
    """
    Determines the final sensitivity by comparing domain and owner sensitivities.
    Returns the higher of the two.
    """
    domain_sensitivity = get_sensitivity_from_category(domain_label, mapping)
    owner_sensitivity = get_sensitivity_from_category(owner_label, mapping)

    if SENSITIVITY_ORDER[domain_sensitivity] >= SENSITIVITY_ORDER[owner_sensitivity]:
        return domain_sensitivity
    else:
        return owner_sensitivity

def find_best_match(query_embedding, index, labels_df, k=1):
    """Searches FAISS index and returns the label of the top match."""
    if query_embedding.ndim == 1:
        query_embedding = np.expand_dims(query_embedding, axis=0)

    distances, indices = index.search(query_embedding.astype(np.float32), k)

    if k == 1:
        if indices[0][0] < 0 or indices[0][0] >= len(labels_df): # Check for invalid index
             return None
        return labels_df.iloc[indices[0][0]]
    else:
        # For k > 1, one might want to implement logic like most frequent label
        matched_labels = [labels_df.iloc[idx] for idx in indices[0] if 0 <= idx < len(labels_df)]
        if not matched_labels:
            return None
        return Counter(matched_labels).most_common(1)[0][0]


def load_data_with_fallback(filename_options, read_func, **kwargs):
    """Tries to load a file from a list of possible names/paths."""
    last_exception = None
    for filename in filename_options:
        try:
            df = read_func(filename, **kwargs)
            print(f"Successfully loaded {filename}")
            return df
        except FileNotFoundError:
            last_exception = FileNotFoundError(f"File not found: {filename}")
            print(f"Attempted to load {filename}, but not found.")
        except Exception as e:
            last_exception = e
            print(f"Error loading {filename}: {e}")
    if last_exception:
        # Re-raise the last exception if all options failed
        # raise last_exception
        # For this task, we'll print an error and return None to allow script to report missing file
        print(f"Failed to load data after trying all options for base name: {filename_options[0]}. Last error: {last_exception}")
    return None


def main():
    # 1. Define Domain/Owner-to-Sensitivity Mapping
    sensitivity_mapping = {
        'Healthcare': 'High',
        'Finance & Banking': 'High',
        'E-commerce & Retail': 'High',
        'Telecommunications': 'High',
        'Government Services': 'High',
        'Education': 'High',
        'Travel & Hospitality': 'High',
        'Social Media & Entertainment': 'High',
        'Employment & HR Tech': 'Moderate',
        'Startups and IT Services': 'High',
        'Adult Individual': 'High',  # Example owner type
        'Company': 'Moderate',      # Example owner type
        # DEFAULT_SENSITIVITY ('Low') will be used for unmapped categories
    }

    # --- File Definitions ---
    # Base names, actual paths will be searched
    attributes_base_file = 'Attributes_2019-20.csv'
    domain_data_base_file = 'domain_data_points.xlsx'
    metadata_base_file = 'metadata_repo.csv'
    output_file = 'Sensitivity_Results.xlsx'
    model_name = 'all-MiniLM-L6-v2'

    # --- Path Probing Logic ---
    # List of base directories where 'FILES' might be, or where files might be directly
    # Assumes script is in /app/src/main/resources/scripts
    # and FILES could be /app/FILES or /app/src/main/resources/FILES
    possible_locations = [
        f"FILES/{attributes_base_file}",
        f"../FILES/{attributes_base_file}", # e.g. if cwd is scripts/, FILES is in resources/
        f"../../FILES/{attributes_base_file}", # e.g. if cwd is scripts/, FILES is in /app/
        attributes_base_file, # Current directory
    ]
    domain_file_options = [p.replace(attributes_base_file, domain_data_base_file) for p in possible_locations]
    metadata_file_options = [p.replace(attributes_base_file, metadata_base_file) for p in possible_locations]

    # 2. Load data
    attributes_df = load_data_with_fallback(possible_locations, pd.read_csv)
    domain_points_df = load_data_with_fallback(domain_file_options, pd.read_excel)
    meta_df = load_data_with_fallback(metadata_file_options, pd.read_csv)

    if attributes_df is None or domain_points_df is None or meta_df is None:
        print("Error: One or more essential data files could not be loaded. Exiting.")
        return

    # Validate required columns
    required_cols = {
        'attributes_df': ['Attr_id', 'Description'],
        'domain_points_df': ['data_point', 'domain'],
        'meta_df': ['Data', 'Owner']
    }
    for df_name, cols in required_cols.items():
        current_df = locals()[df_name]
        for col in cols:
            if col not in current_df.columns:
                print(f"Error: Column '{col}' missing in {df_name}. Exiting.")
                return

    # Rename columns for clarity
    domain_points_df.rename(columns={'data_point': 'domain_text', 'domain': 'domain_label'}, inplace=True)
    meta_df.rename(columns={'Data': 'owner_data_text', 'Owner': 'owner_label'}, inplace=True)

    attributes_df.dropna(subset=['Description', 'Attr_id'], inplace=True)
    domain_points_df.dropna(subset=['domain_text', 'domain_label'], inplace=True)
    meta_df.dropna(subset=['owner_data_text', 'owner_label'], inplace=True)

    if domain_points_df.empty or meta_df.empty or attributes_df.empty:
        print("Error: Dataframes are empty after NaNs removal or initial load. Exiting.")
        return

    # 3. Load sentence transformer model
    try:
        model = SentenceTransformer(model_name)
    except Exception as e:
        print(f"Error loading SentenceTransformer model '{model_name}': {e}")
        return

    # 4. Create FAISS Indices
    try:
        domain_embeddings = model.encode(domain_points_df['domain_text'].astype(str).tolist(), show_progress_bar=False)
        owner_embeddings = model.encode(meta_df['owner_data_text'].astype(str).tolist(), show_progress_bar=False)

        domain_faiss_index = faiss.IndexFlatL2(domain_embeddings.shape[1])
        domain_faiss_index.add(domain_embeddings.astype(np.float32))

        owner_faiss_index = faiss.IndexFlatL2(owner_embeddings.shape[1])
        owner_faiss_index.add(owner_embeddings.astype(np.float32))
    except Exception as e:
        print(f"Error during FAISS index creation or data embedding: {e}")
        return

    results = []
    attribute_descriptions = attributes_df['Description'].astype(str).tolist()
    attribute_ids = attributes_df['Attr_id'].tolist()

    try:
        description_embeddings = model.encode(attribute_descriptions, show_progress_bar=False)
    except Exception as e:
        print(f"Error encoding attribute descriptions: {e}")
        return

    # 5. Process each attribute
    for i in range(len(attribute_ids)):
        attr_id = attribute_ids[i]
        current_desc_embedding = description_embeddings[i]

        found_domain_label = find_best_match(current_desc_embedding, domain_faiss_index, domain_points_df['domain_label'])
        found_owner_label = find_best_match(current_desc_embedding, owner_faiss_index, meta_df['owner_label'])

        if found_domain_label is None:
            print(f"Warning: Could not determine domain for Attr_id {attr_id}. Defaulting.")
            found_domain_label = "Unknown" # Or some other placeholder
        if found_owner_label is None:
            print(f"Warning: Could not determine owner for Attr_id {attr_id}. Defaulting.")
            found_owner_label = "Unknown" # Or some other placeholder

        final_sensitivity = get_final_sensitivity(found_domain_label, found_owner_label, sensitivity_mapping)
        results.append({'Attr_id': attr_id, 'Sensitivity_Level': final_sensitivity})

    # 6. Output `Sensitivity_Results.xlsx`
    output_df = pd.DataFrame(results)
    try:
        output_df.to_excel(output_file, index=False)
        print(f"Successfully generated '{output_file}' with {len(output_df)} records.")
    except Exception as e:
        print(f"Error saving output file '{output_file}': {e}")

if __name__ == '__main__':
    main()
