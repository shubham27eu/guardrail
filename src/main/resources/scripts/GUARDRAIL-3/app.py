from flask import Flask, render_template, request, jsonify
import sqlite3
import os
import pandas as pd
from werkzeug.utils import secure_filename
import joblib
from faiss_domain_helper import predict_domain_for_value
from collections import Counter
from compliance_lookup import get_sensitivity_for_owner_domain

app = Flask(__name__)

# ------------------ Config ------------------
UPLOAD_FOLDER = 'static/uploads'
DB_DIR = 'db'
INTERNAL_DB = os.path.join(DB_DIR, 'internal.db')
EXTERNAL_DB = os.path.join(DB_DIR, 'external.db')
app.config['UPLOAD_FOLDER'] = UPLOAD_FOLDER

os.makedirs(UPLOAD_FOLDER, exist_ok=True)
os.makedirs(DB_DIR, exist_ok=True)

# ------------------ Load KYU Model and Encoders ------------------
model = joblib.load('kyu_score_model.joblib')
label_encoders = joblib.load('label_encoders.joblib')

# ------------------ DB Setup ------------------
def init_internal_db():
    with sqlite3.connect(INTERNAL_DB) as conn:
        conn.execute('''
            CREATE TABLE IF NOT EXISTS users (
                email TEXT PRIMARY KEY,
                password TEXT
            )
        ''')

def init_external_db():
    with sqlite3.connect(EXTERNAL_DB) as conn:
        conn.execute('''
            CREATE TABLE IF NOT EXISTS ingests (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT,
                email TEXT,
                owner TEXT,
                domain TEXT,
                filename TEXT
            )
        ''')

init_internal_db()
init_external_db()

# ------------------ KYU Score Function ------------------
def predict_kyu_score(email, domain, purpose):
    personal_domains = ['gmail.com', 'yahoo.com', 'hotmail.com']
    domain_part = email.split('@')[-1].lower()
    email_type = 'Personal' if any(p in domain_part for p in personal_domains) else 'Organisational'

    try:
        email_type_encoded = label_encoders['Email_Type'].transform([email_type])[0]
        domain_encoded = label_encoders['Domain'].transform([domain])[0]
        purpose_encoded = label_encoders['Purpose'].transform([purpose])[0]
        X = [[email_type_encoded, domain_encoded, purpose_encoded]]
        prediction = model.predict(X)[0]
        reverse_map = {0: 'Low', 1: 'Moderate', 2: 'High'}
        return reverse_map[prediction]
    except Exception as e:
        return f"Error predicting KYU Score: {e}"

# ------------------ Routes ------------------
@app.route('/')
def index():
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT DISTINCT domain FROM ingests')
        domains = [row[0] for row in c.fetchall()]
        c.execute('SELECT DISTINCT filename FROM ingests')
        data_sources = [row[0] for row in c.fetchall()]
    return render_template('index.html', domains=domains, data_sources=data_sources)

@app.route('/ingest', methods=['POST'])
def ingest():
    name = request.form.get('name')
    email = request.form.get('email')
    owner = request.form.get('owner')
    domain = request.form.get('domain')
    file = request.files.get('csv_file')

    if not file or not file.filename.endswith('.csv'):
        return 'Invalid file format. Only CSV allowed.', 400

    filename = secure_filename(f"{domain}_{file.filename}")
    save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(save_path)

    with sqlite3.connect(EXTERNAL_DB) as conn:
        conn.execute('''
            INSERT INTO ingests (name, email, owner, domain, filename)
            VALUES (?, ?, ?, ?, ?)
        ''', (name, email, owner, domain, filename))

    return '''
        <h3>Data ingested successfully!</h3>
        <a href="/">Back to Home</a>
    '''

@app.route('/login', methods=['POST'])
def login():
    data = request.get_json()
    email = data.get('email')
    password = data.get('password')

    with sqlite3.connect(INTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT 1 FROM users WHERE email=? AND password=?', (email, password))
        user = c.fetchone()

    return jsonify({'success': bool(user)})

@app.route('/get_sources', methods=['GET'])
def get_sources():
    domain = request.args.get('domain')
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT filename FROM ingests WHERE domain=?', (domain,))
        files = [row[0] for row in c.fetchall()]
    return jsonify(files)

@app.route('/get_attributes/<filename>', methods=['GET'])
def get_attributes(filename):
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    if os.path.exists(filepath):
        df = pd.read_csv(filepath, nrows=1)
        return jsonify(list(df.columns))
    return jsonify([])

@app.route('/request', methods=['POST'])
def request_data():
    email = request.form.get('emailID')
    password = request.form.get('password')
    domain = request.form.get('domain')
    purpose = request.form.get('Purpose')
    source = request.form.get('dataSource')
    attribute = request.form.get('attributes')

    # --- KYU Score ---
    kyu_score = predict_kyu_score(email, domain, purpose)

    # --- Read CSV and extract attribute values ---
    attribute_values = []
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], source)
    if os.path.exists(filepath):
        try:
            df = pd.read_csv(filepath)
            if attribute in df.columns:
                attribute_values = df[attribute].dropna().unique().tolist()
            else:
                attribute_values = ["Attribute not found."]
        except Exception as e:
            attribute_values = [f"Error reading file: {e}"]
    else:
        attribute_values = ["Source file not found."]

    # --- Metadata Owner ---
    owner_name = "Not found"
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT owner FROM ingests WHERE filename = ?', (source,))
        row = c.fetchone()
        if row:
            owner_name = row[0]

    # --- FAISS Domain Matching & Compliance Sensitivity ---
    from compliance_lookup import get_sensitivity_for_owner_domain
    domain_results = []
    domain_votes = []

    for val in attribute_values:
        matched_domain, top_domains = predict_domain_for_value(str(val))
        compliance = get_sensitivity_for_owner_domain(owner_name, matched_domain)
        domain_results.append({
            'value': val,
            'matched_domain': matched_domain,
            'sensitivity': compliance['Sensitivity Level'],
            # 'explainability': compliance['Explainability'],
            # 'sharing_entity': compliance['Sharing Entity']
        })
        domain_votes.append(matched_domain)

    # --- Most common domain (majority vote) ---
    overall_most_common = Counter(domain_votes).most_common(1)[0][0]

    # --- Build HTML Output ---
    attribute_list_html = ''.join(
        f"<li><strong>{entry['value']}</strong>: "
        f"Domain=<em><strong>{entry['matched_domain']}</strong></em>, "
        f"Sensitivity=<em><strong>{entry['sensitivity']}</strong></em>, "
        # f"Explainability=<em>{entry['explainability']}</em>, "
        # f"Sharing Entity=<em>{entry['sharing_entity']}</em></li>"
        for entry in domain_results
    )

    return f'''
        <h3>Request Processed</h3>
        <p><strong>Domain (Selected):</strong> {domain}</p>
        <p><strong>Purpose:</strong> {purpose}</p>
        <p><strong>Data Source:</strong> {source}</p>
        <p><strong>Attribute:</strong> {attribute}</p>
        <p><strong>Owner (From Metadata):</strong> {owner_name}</p>
        <p><strong>KYU Score:</strong> {kyu_score}</p>
        <p><strong>Most Relevant Domain (from FAISS):</strong> {overall_most_common}</p>
        <p><strong>Attribute Value Analysis:</strong></p>
        <ul>{attribute_list_html}</ul>
        <a href="/">Back to Home</a>
    '''

# ------------------ Run App ------------------
if __name__ == '__main__':
    app.run(debug=True)
