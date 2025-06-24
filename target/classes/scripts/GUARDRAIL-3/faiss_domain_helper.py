# faiss_domain_helper.py

import faiss
import numpy as np
import joblib
from sklearn.preprocessing import normalize
from sentence_transformers import SentenceTransformer
from collections import Counter

# Load SentenceTransformer model and FAISS index
model = SentenceTransformer("all-MiniLM-L6-v2")
index = faiss.read_index("domain_index.faiss")
id_to_domain = joblib.load("id_to_domain.pkl")

def predict_domain_for_value(value, k=5):
    query_embedding = model.encode([value])
    normalized_query = normalize(query_embedding, norm="l2")
    D, I = index.search(normalized_query.astype("float32"), k=k)

    top_domains = [id_to_domain[idx] for idx in I[0]]
    domain_counts = Counter(top_domains)
    most_common_domain, _ = domain_counts.most_common(1)[0]

    return most_common_domain, top_domains  # return both main and top-5 list
