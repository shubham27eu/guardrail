import pandas as pd
import numpy as np
import faiss
from sentence_transformers import SentenceTransformer
from collections import Counter

def get_sensitivity_level(domain, domain_sensitivity_mapping_rules):
    """
    Determines the sensitivity level for a given domain based on the predefined mapping.
    Handles multiple sensitivities by taking the highest.
    Defaults to 'Low' if domain is not found.
    """
    level_order = {'High': 3, 'Moderate': 2, 'Low': 1}

    # Get the sensitivity from the map, default to 'Low'
    sensitivity = domain_sensitivity_mapping_rules.get(domain, 'Low')

    # The mapping already incorporates the "highest sensitivity" rule.
    # If a domain had multiple potential sensitivities listed,
    # it should be resolved to the highest one in the initial mapping definition.
    # For example, if "Education" could be "Moderate" or "High",
    # domain_sensitivity_mapping_rules["Education"] should be "High".

    return sensitivity

def main():
    # 1. Define Domain-to-Sensitivity Mapping (highest sensitivity pre-selected for duplicates)
    # This mapping should be derived from the output of the first subtask,
    # applying the rule "use the highest: High > Moderate > Low".
    domain_sensitivity_mapping = {
        'Healthcare': 'High',
        'Finance & Banking': 'High',
        'E-commerce & Retail': 'High',
        'Telecommunications': 'High',
        'Government Services': 'High', # Was Low/Moderate, now High as per instructions
        'Education': 'High',           # Was Moderate/High, now High
        'Travel & Hospitality': 'High',  # Was Moderate/High, now High
        'Social Media & Entertainment': 'High',
        'Employment & HR Tech': 'Moderate',
        'Startups and IT Services': 'High', # Was Moderate/High, now High
        # Default for unmapped domains will be 'Low' (handled by get_sensitivity_level)
    }

    # File paths
    attributes_file = 'FILES/Attributes_2019-20.csv'
    domain_data_file = 'FILES/domain_data_points.xlsx'
    output_file = 'Sensitivity_Results.xlsx'

    # Model name
    model_name = 'all-MiniLM-L6-v2'

    # 2. Load data
    try:
        attributes_df = pd.read_csv(attributes_file)
        domain_df = pd.read_excel(domain_data_file)
    except FileNotFoundError as e:
        print(f"Error: Input file not found. {e}")
        return
    except Exception as e:
        print(f"Error reading input files: {e}")
        return

    # Validate required columns
    if 'Description' not in attributes_df.columns or 'Attr_id' not in attributes_df.columns:
        print("Error: 'Description' or 'Attr_id' column missing in attributes file.")
        return
    if 'data_point' not in domain_df.columns or 'domain' not in domain_df.columns:
        print("Error: 'data_point' or 'domain' column missing in domain data file.")
        return

    # Drop rows with NaN in critical columns to prevent errors during processing
    attributes_df.dropna(subset=['Description', 'Attr_id'], inplace=True)
    domain_df.dropna(subset=['data_point', 'domain'], inplace=True)

    if domain_df.empty:
        print("Error: Domain data is empty after dropping NaN values. Cannot build FAISS index.")
        return
    if attributes_df.empty:
        print("Error: Attributes data is empty after dropping NaN values. No descriptions to process.")
        return


    # 3. Load sentence transformer model and create FAISS index
    try:
        model = SentenceTransformer(model_name)
    except Exception as e:
        print(f"Error loading sentence transformer model '{model_name}': {e}")
        # This could be due to network issues if the model needs to be downloaded,
        # or if the model name is incorrect.
        return

    try:
        # Encode domain data points
        # Ensure data_point is string
        domain_embeddings = model.encode(domain_df['data_point'].astype(str).tolist(), show_progress_bar=False)

        # Create FAISS index
        dimension = domain_embeddings.shape[1]
        index = faiss.IndexFlatL2(dimension)
        index.add(np.array(domain_embeddings, dtype=np.float32))
    except Exception as e:
        print(f"Error during FAISS index creation or domain embedding: {e}")
        return

    results = []
    k = 5 # Number of nearest neighbors to retrieve

    # 4. For each attribute Description, find matching domain and assign sensitivity
    # Ensure Description is string
    attribute_descriptions = attributes_df['Description'].astype(str).tolist()
    attribute_ids = attributes_df['Attr_id'].tolist()

    try:
        description_embeddings = model.encode(attribute_descriptions, show_progress_bar=False)
    except Exception as e:
        print(f"Error encoding attribute descriptions: {e}")
        return

    # Search in FAISS index
    try:
        distances, indices = index.search(np.array(description_embeddings, dtype=np.float32), k)
    except Exception as e:
        print(f"Error during FAISS search: {e}")
        return

    for i in range(len(attribute_ids)):
        attr_id = attribute_ids[i]

        # Get the domains of the top-k matches
        matched_domains = [domain_df['domain'].iloc[idx] for idx in indices[i]]

        # Determine the most frequent domain
        if not matched_domains:
            most_frequent_domain = None # Should not happen if k > 0 and index is not empty
        else:
            most_frequent_domain = Counter(matched_domains).most_common(1)[0][0]

        # Assign sensitivity
        sensitivity = get_sensitivity_level(most_frequent_domain, domain_sensitivity_mapping)
        results.append({'Attr_id': attr_id, 'Sensitivity_Level': sensitivity})

    # 5. Output `Sensitivity_Results.xlsx`
    output_df = pd.DataFrame(results)

    try:
        output_df.to_excel(output_file, index=False)
        print(f"Successfully generated '{output_file}'")
    except Exception as e:
        print(f"Error saving output file {output_file}: {e}")

if __name__ == '__main__':
    main()
