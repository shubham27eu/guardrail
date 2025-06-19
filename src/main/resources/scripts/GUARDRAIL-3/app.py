from flask import Flask, render_template, request, jsonify
import sqlite3
import os
import pandas as pd
from werkzeug.utils import secure_filename
import joblib
from collections import Counter
import subprocess
import tempfile
from compliance_lookup import get_sensitivity_for_about_domain

app = Flask(__name__)

# ------------------ Config ------------------
UPLOAD_FOLDER = 'static/uploads'
JAVA_JAR_PATH = '../../../../../target/data-processor-main.jar'  # Adjust this path if needed
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
                about TEXT,
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
    email_type = 'Personal' if domain_part in personal_domains else 'Organisational'

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
    about = request.form.get('about')
    domain = request.form.get('domain')
    file = request.files.get('csv_file')

    if not file or not file.filename.endswith('.csv'):
        return 'Invalid file format. Only CSV allowed.', 400

    filename = secure_filename(f"{domain}_{file.filename}")
    save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    file.save(save_path)

    with sqlite3.connect(EXTERNAL_DB) as conn:
        conn.execute('''
            INSERT INTO ingests (name, email, about, domain, filename)
            VALUES (?, ?, ?, ?, ?)
        ''', (name, email, about, domain, filename))

    return '''
        <h3>Data ingested successfully!</h3>
        <a href="/">Back to Home</a>
    '''

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
    domain = request.form.get('domain')
    purpose = request.form.get('Purpose')
    source = request.form.get('dataSource')
    attributes_raw = request.form.get('attributes', '')
    attributes = [attr.strip() for attr in attributes_raw.split(',') if attr.strip()]

    kyu_score = predict_kyu_score(email, domain, purpose)

    # --- Metadata Lookup ---
    about_entity = "Not found"
    file_domain = "Unknown"
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT about, domain FROM ingests WHERE filename = ?', (source,))
        row = c.fetchone()
        if row:
            about_entity, file_domain = row

    filepath = os.path.join(app.config['UPLOAD_FOLDER'], source)
    if not os.path.exists(filepath):
        return '<p>Source file not found.</p>'

    df = pd.read_csv(filepath)

    # Extract values per attribute, tagged for Java
    tagged_values = []
    compliance_results = []
    for attr in attributes:
        if attr not in df.columns:
            continue
        values = df[attr].dropna().unique().astype(str)
        for val in values:
            tagged_values.append(f"{attr}::{val}")

        # Same compliance for all values (domain-level)
        compliance = get_sensitivity_for_about_domain(about_entity, file_domain)
        compliance_results.append({
            'attribute': attr,
            'domain': file_domain,
            'sensitivity': compliance['Sensitivity Level'],
            'explainability': compliance['Explainability'],
            'sharing_entity': compliance['Sharing Entity']
        })

    # Write tagged input to temp file for Java
    with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt', encoding='utf-8') as temp_input:
        temp_input.write('\n'.join(tagged_values))
        input_path = temp_input.name

    with tempfile.NamedTemporaryFile(delete=False, suffix='.csv') as temp_output:
        output_path = temp_output.name

    # Determine overall sensitivity
    sensitivities = {entry['sensitivity'].lower() for entry in compliance_results}
    if 'high' in sensitivities:
        overall_sensitivity = 'High'
    elif 'moderate' in sensitivities or 'medium' in sensitivities:
        overall_sensitivity = 'Medium'
    else:
        overall_sensitivity = 'Low'

    # --- Call Java ---
    anonymized_values = []
    try:
        java_command = [
            "java", "-jar",
            JAVA_JAR_PATH,
            email, kyu_score, overall_sensitivity,
            input_path, output_path
        ]
        print(f"Executing Java command: {' '.join(java_command)}") # Log the command
        subprocess.run(java_command, check=True, capture_output=True, timeout=120)

        if os.path.exists(output_path):
            df_out = pd.read_csv(output_path, header=None)
            anonymized_values = df_out[0].astype(str).tolist()
        else:
            anonymized_values = ["Java output not found."]

    except subprocess.CalledProcessError as e:
        error_message = f"Error during Java call (CalledProcessError): {e}\n"
        error_message += f"Return code: {e.returncode}\n"
        error_message += f"Command: {' '.join(e.cmd)}\n"
        error_message += f"Stdout: {e.stdout.decode('utf-8', errors='ignore') if e.stdout else 'N/A'}\n"
        error_message += f"Stderr: {e.stderr.decode('utf-8', errors='ignore') if e.stderr else 'N/A'}"
        anonymized_values = [error_message]
    except Exception as e: # General fallback for other errors like TimeoutExpired
        error_message = f"General error during Java call: {type(e).__name__} - {e}"
        anonymized_values = [error_message]

    finally:
        os.remove(input_path)
        if os.path.exists(output_path):
            os.remove(output_path)

    # Re-pair input and output
    output_html = ""
    for i, tag in enumerate(tagged_values):
        attr, val = tag.split("::")
        anon_val = anonymized_values[i] if i < len(anonymized_values) else "?"
        output_html += f"<li><strong>{attr}</strong>: {val} → <strong>{anon_val}</strong></li>"

    # Compliance metadata
    metadata_html = ''.join(
        f"<li><strong>{entry['attribute']}</strong>: "
        f"<br>Domain: <em>{entry['domain']}</em>, "
        f"Sensitivity: <em>{entry['sensitivity']}</em>, "
        f"Explainability: {entry['explainability']}, "
        f"Sharing Entity: {entry['sharing_entity']}</li>"
        for entry in compliance_results
    )

    return f'''
        <h3>Request Processed</h3>
        <p><strong>Domain (Selected):</strong> {domain}</p>
        <p><strong>Purpose:</strong> {purpose}</p>
        <p><strong>Data Source:</strong> {source}</p>
        <p><strong>Attributes:</strong> {', '.join(attributes)}</p>
        <p><strong>About (Metadata):</strong> {about_entity}</p>
        <p><strong>Domain (Metadata):</strong> {file_domain}</p>
        <p><strong>KYU Trust Score:</strong> {kyu_score}</p>
        <p><strong>Overall Sensitivity for Java:</strong> {overall_sensitivity}</p>
        <hr>
        <p><strong>Attribute Metadata:</strong></p>
        <ul>{metadata_html}</ul>
        <hr>
        <p><strong>Anonymization Results:</strong></p>
        <ul>{output_html}</ul>
        <a href="/">Back to Home</a>
    '''

# ------------------ Run App ------------------
if __name__ == '__main__':
    app.run(debug=True)
