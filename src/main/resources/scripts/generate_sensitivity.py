import pandas as pd
import numpy as np
import faiss
from sentence_transformers import SentenceTransformer
from collections import Counter
import os

def get_sensitivity_level(domain, domain_sensitivity_mapping_rules):
    """
    Determines the sensitivity level for a given domain based on the predefined mapping.
    Defaults to 'Low' if domain is not found.
    """
    return domain_sensitivity_mapping_rules.get(domain, 'Low')

def main():
    domain_sensitivity_mapping = {
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
    }

    # Adjusted base path for input files, assuming script is in src/main/resources/scripts
    # and FILES directory is at src/main/resources/FILES
    # The Java code will set the working directory, so paths might need to be relative to that.
    # For now, let's assume the Java process will make these files available in a way
    # that these paths (or just filenames) work.
    # Simplification: Assume files are in a 'FILES' subdirectory relative to script execution dir,
    # or directly in a path that Java makes accessible.

    attributes_file_name = 'Attributes_2019-20.csv'
    domain_data_file_name = 'domain_data_points.xlsx'

    # Try to locate files in common possible locations relative to script execution
    # (e.g. if script is run from /app, and files are in /app/FILES or /app/src/main/resources/FILES)
    possible_base_paths = ["./", "FILES/", "../FILES/", "src/main/resources/FILES/"]

    attributes_file = None
    domain_data_file = None

    for base_path in possible_base_paths:
        current_attributes_file = os.path.join(base_path, attributes_file_name)
        if os.path.exists(current_attributes_file):
            attributes_file = current_attributes_file
            break

    for base_path in possible_base_paths:
        current_domain_data_file = os.path.join(base_path, domain_data_file_name)
        if os.path.exists(current_domain_data_file):
            domain_data_file = current_domain_data_file
            break

    if not attributes_file:
        print(f"Error: Attributes file '{attributes_file_name}' not found in expected locations.")
        return
    if not domain_data_file:
        print(f"Error: Domain data file '{domain_data_file_name}' not found in expected locations.")
        return

    output_file = 'Sensitivity_Results.xlsx'
    model_name = 'all-MiniLM-L6-v2'

    try:
        attributes_df = pd.read_csv(attributes_file)
        domain_df = pd.read_excel(domain_data_file)
    except Exception as e:
        print(f"Error reading input files (Paths tried: {attributes_file}, {domain_data_file}): {e}")
        return

    if 'Description' not in attributes_df.columns or 'Attr_id' not in attributes_df.columns:
        print("Error: 'Description' or 'Attr_id' column missing in attributes file.")
        return
    if 'data_point' not in domain_df.columns or 'domain' not in domain_df.columns:
        print("Error: 'data_point' or 'domain' column missing in domain data file.")
        return

    attributes_df.dropna(subset=['Description', 'Attr_id'], inplace=True)
    domain_df.dropna(subset=['data_point', 'domain'], inplace=True)

    if domain_df.empty:
        print("Error: Domain data is empty. Cannot build FAISS index.")
        return
    if attributes_df.empty:
        print("Error: Attributes data is empty. No descriptions to process.")
        return

    try:
        model = SentenceTransformer(model_name)
    except Exception as e:
        print(f"Error loading sentence transformer model '{model_name}': {e}")
        return

    try:
        domain_embeddings = model.encode(domain_df['data_point'].astype(str).tolist(), show_progress_bar=False)
        dimension = domain_embeddings.shape[1]
        index = faiss.IndexFlatL2(dimension)
        index.add(np.array(domain_embeddings, dtype=np.float32))
    except Exception as e:
        print(f"Error during FAISS index creation or domain embedding: {e}")
        return

    results = []
    k = 5
    attribute_descriptions = attributes_df['Description'].astype(str).tolist()
    attribute_ids = attributes_df['Attr_id'].tolist()

    try:
        description_embeddings = model.encode(attribute_descriptions, show_progress_bar=False)
    except Exception as e:
        print(f"Error encoding attribute descriptions: {e}")
        return

    try:
        distances, indices = index.search(np.array(description_embeddings, dtype=np.float32), k)
    except Exception as e:
        print(f"Error during FAISS search: {e}")
        return

    for i in range(len(attribute_ids)):
        attr_id = attribute_ids[i]
        matched_domains = [domain_df['domain'].iloc[idx] for idx in indices[i]]
        if not matched_domains:
            most_frequent_domain = None
        else:
            most_frequent_domain = Counter(matched_domains).most_common(1)[0][0]
        sensitivity = get_sensitivity_level(most_frequent_domain, domain_sensitivity_mapping)
        results.append({'Attr_id': attr_id, 'Sensitivity_Level': sensitivity})

    output_df = pd.DataFrame(results)

    try:
        output_df.to_excel(output_file, index=False)
        print(f"Successfully generated '{output_file}'")
    except Exception as e:
        print(f"Error saving output file {output_file}: {e}")

if __name__ == '__main__':
    main()
