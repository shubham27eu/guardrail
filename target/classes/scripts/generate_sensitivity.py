import pandas as pd
import numpy as np
import faiss
from sentence_transformers import SentenceTransformer
from collections import Counter

# Sensitivity level order
SENSITIVITY_ORDER = {'High': 3, 'Moderate': 2, 'Low': 1}
DEFAULT_SENSITIVITY = 'Low'

def get_sensitivity_from_category(category, mapping):
    return mapping.get(category, DEFAULT_SENSITIVITY)

def get_final_sensitivity(domain_label, owner_label, mapping):
    domain_sens = get_sensitivity_from_category(domain_label, mapping)
    owner_sens = get_sensitivity_from_category(owner_label, mapping)
    return domain_sens if SENSITIVITY_ORDER[domain_sens] >= SENSITIVITY_ORDER[owner_sens] else owner_sens

def find_best_match(query_embedding, index, labels_df, k=1):
    if query_embedding.ndim == 1:
        query_embedding = np.expand_dims(query_embedding, axis=0)
    distances, indices = index.search(query_embedding.astype(np.float32), k)
    if k == 1:
        idx = indices[0][0]
        if idx < 0 or idx >= len(labels_df):
            return None
        return labels_df.iloc[idx]
    else:
        matched_labels = [labels_df.iloc[idx] for idx in indices[0] if 0 <= idx < len(labels_df)]
        return Counter(matched_labels).most_common(1)[0][0] if matched_labels else None

def load_data_with_fallback(filename_options, read_func, **kwargs):
    last_exception = None
    for filename in filename_options:
        try:
            df = read_func(filename, **kwargs)
            print(f"Successfully loaded {filename}")
            return df
        except FileNotFoundError:
            print(f"Attempted to load {filename}, but not found.")
        except Exception as e:
            print(f"Error loading {filename}: {e}")
            last_exception = e
    if last_exception:
        print(f"Failed to load data after all options. Last error: {last_exception}")
    return None

def main():
    # Sensitivity mapping
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
        'Adult Individual': 'High',
        'Company': 'Moderate',
    }

    attributes_file = 'Attributes_2019-20.xlsx'
    domain_file = 'domain_data_points.xlsx'
    metadata_file = 'metadata_repo.xlsx'
    output_file = 'Sensitivity_Results.xlsx'
    model_name = 'all-MiniLM-L6-v2'

    # File path probing
    search_paths = [
        f"FILES/{attributes_file}",
        f"../FILES/{attributes_file}",
        f"../../FILES/{attributes_file}",
        attributes_file
    ]
    domain_paths = [p.replace(attributes_file, domain_file) for p in search_paths]
    metadata_paths = [p.replace(attributes_file, metadata_file) for p in search_paths]

    # Load data
    attributes_df = load_data_with_fallback(search_paths, pd.read_excel, header=1)
    domain_df = load_data_with_fallback(domain_paths, pd.read_excel, header=0)
    meta_df = load_data_with_fallback(metadata_paths, pd.read_excel, header=1)

    if attributes_df is None or domain_df is None or meta_df is None:
        print("❌ Error: Failed to load all required files.")
        return

    # Column validation
    required_cols = {
        'attributes_df': ['Attr_id', 'Description'],
        'domain_df': ['Domain', 'Owner'],
        'meta_df': ['Data', 'Owner']
    }
    for name, cols in required_cols.items():
        df = locals()[name]
        for col in cols:
            if col not in df.columns:
                print(f"❌ Error: Missing column '{col}' in {name}")
                return

    # Rename columns
    domain_df.rename(columns={'Domain': 'domain_label', 'Owner': 'domain_text'}, inplace=True)
    meta_df.rename(columns={'Data': 'owner_data_text', 'Owner': 'owner_label'}, inplace=True)

    # Clean data
    attributes_df.dropna(subset=['Description', 'Attr_id'], inplace=True)
    domain_df.dropna(subset=['domain_label', 'domain_text'], inplace=True)
    meta_df.dropna(subset=['owner_label', 'owner_data_text'], inplace=True)

    if attributes_df.empty or domain_df.empty or meta_df.empty:
        print("❌ Error: Empty dataframe after cleanup.")
        return

    # Load model
    try:
        model = SentenceTransformer(model_name)
    except Exception as e:
        print(f"❌ Error loading model '{model_name}': {e}")
        return

    # Create embeddings
    try:
        domain_embeddings = model.encode(domain_df['domain_text'].astype(str).tolist(), show_progress_bar=False)
        owner_embeddings = model.encode(meta_df['owner_data_text'].astype(str).tolist(), show_progress_bar=False)

        domain_index = faiss.IndexFlatL2(domain_embeddings.shape[1])
        domain_index.add(domain_embeddings.astype(np.float32))

        owner_index = faiss.IndexFlatL2(owner_embeddings.shape[1])
        owner_index.add(owner_embeddings.astype(np.float32))
    except Exception as e:
        print(f"❌ FAISS or embedding error: {e}")
        return

    # Encode descriptions
    try:
        descriptions = attributes_df['Description'].fillna("").astype(str).tolist()
        description_embeddings = model.encode(descriptions, show_progress_bar=False)
    except Exception as e:
        print(f"❌ Error encoding attribute descriptions: {e}")
        return

    # Sensitivity results
    results = []
    for i, attr_id in enumerate(attributes_df['Attr_id']):
        emb = description_embeddings[i]
        domain_label = find_best_match(emb, domain_index, domain_df['domain_label']) or "Unknown"
        owner_label = find_best_match(emb, owner_index, meta_df['owner_label']) or "Unknown"

        if domain_label == "Unknown":
            print(f"⚠️ Warning: Could not determine domain for Attr_id {attr_id}")
        if owner_label == "Unknown":
            print(f"⚠️ Warning: Could not determine owner for Attr_id {attr_id}")

        final_level = get_final_sensitivity(domain_label, owner_label, sensitivity_mapping)
        results.append({'Attr_id': attr_id, 'Sensitivity_Level': final_level})

    # Save results
    try:
        pd.DataFrame(results).to_excel(output_file, index=False)
        print(f"✅ Successfully generated '{output_file}' with {len(results)} records.")
    except Exception as e:
        print(f"❌ Failed to save output file: {e}")

if __name__ == '__main__':
    main()
