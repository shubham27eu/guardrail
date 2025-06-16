from flask import Flask, render_template, request, jsonify
import sqlite3
import os
import pandas as pd
from werkzeug.utils import secure_filename
import subprocess
import tempfile
import joblib
from faiss_domain_helper import predict_domain_for_value
from collections import Counter
from compliance_lookup import get_sensitivity_for_owner_domain

app = Flask(__name__)

# ------------------ Config ------------------
UPLOAD_FOLDER = 'static/uploads'
# IMPORTANT: This path needs to be correct for your deployment structure.
# It assumes app.py is at src/main/resources/scripts/GUARDRAIL-3/app.py
# and the JAR is in the standard Maven target directory of the root Java project.
JAVA_JAR_PATH = "../../../../../target/data-processor-main.jar" # Corrected path
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

    # Ensure attribute_values are strings for FAISS and later Java input
    attribute_values = [str(val) for val in attribute_values]

    for val in attribute_values:
        matched_domain, top_domains = predict_domain_for_value(val) # val is already str
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
    overall_most_common = "N/A"
    if domain_votes:
        overall_most_common = Counter(domain_votes).most_common(1)[0][0]

    # --- Determine Overall Sensitivity for Java ---
    overall_sensitivity_level_for_java = "Low" # Default
    sensitivities_found = set(dr['sensitivity'].lower() for dr in domain_results if 'sensitivity' in dr)
    if "high" in sensitivities_found:
        overall_sensitivity_level_for_java = "High"
    elif "medium" in sensitivities_found: # "medium" is used by compliance_lookup, Java expects "moderate" or "medium"
        overall_sensitivity_level_for_java = "Medium" # Or "Moderate" depending on Java's StrategySelector
    elif "moderate" in sensitivities_found: # some parts of Java might use moderate
        overall_sensitivity_level_for_java = "Moderate"


    # --- Call Java Anonymization Application ---
    anonymized_values = []
    input_file_path_for_java = None
    output_file_path_for_java = None

    try:
        with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt', encoding='utf-8') as tmp_input_file:
            tmp_input_file.write('\n'.join(attribute_values)) # attribute_values are already strings
            input_file_path_for_java = tmp_input_file.name

        # Create a temporary file path for Java to write its output
        # We create it, close it, so Java can open and write to it.
        with tempfile.NamedTemporaryFile(delete=False, suffix='.csv', encoding='utf-8') as tmp_output_file_obj:
            output_file_path_for_java = tmp_output_file_obj.name

        # Ensure JAVA_JAR_PATH is correctly defined above
        java_command = [
            "java", "-jar",
            JAVA_JAR_PATH,
            email,  # userId
            kyu_score,  # kyuScore (e.g., "Low", "Moderate", "High")
            overall_sensitivity_level_for_java,  # sensitivityLevel (e.g., "Low", "Medium", "High")
            input_file_path_for_java,
            output_file_path_for_java
        ]

        print(f"Executing Java command: {' '.join(java_command)}") # For debugging

        process_result = subprocess.run(java_command, capture_output=True, text=True, check=True, timeout=120)

        java_stdout = process_result.stdout
        java_stderr = process_result.stderr
        print(f"Java stdout:\n{java_stdout}") # For debugging
        if java_stderr:
            print(f"Java stderr:\n{java_stderr}") # For debugging

        # Parse scores from Java stdout
        anonymization_score_from_java = "N/A"
        utility_retained_from_java = "N/A"
        if java_stdout: # Ensure stdout is not None
            for line in java_stdout.splitlines():
                if line.startswith("ANONYMIZATION_SCORE:"):
                    anonymization_score_from_java = line.split(":", 1)[1]
                if line.startswith("UTILITY_RETAINED:"):
                    utility_retained_from_java = line.split(":", 1)[1]

        if os.path.exists(output_file_path_for_java):
            try:
                df_anonymized = pd.read_csv(output_file_path_for_java)
                if not df_anonymized.empty:
                    # Assuming the anonymized values are in the first column,
                    # and Java might output a header like "anonymized_value" or "attribute_value"
                    anonymized_values = df_anonymized.iloc[:, 0].astype(str).tolist()
                else:
                    anonymized_values = ["Java output file was empty."]
            except pd.errors.EmptyDataError:
                anonymized_values = ["Java output file was empty (pandas error)."]
            except Exception as e:
                anonymized_values = [f"Error reading Java output CSV: {e}"]
        else:
            anonymized_values = ["Java output file not found at path: " + output_file_path_for_java]

    except subprocess.CalledProcessError as e:
        anonymized_values = [f"Java process error (exit code {e.returncode}): {e.stderr}"]
        print(f"Java CProcessError stdout:\n{e.stdout}")
        print(f"Java CProcessError stderr:\n{e.stderr}")
    except subprocess.TimeoutExpired:
        anonymized_values = ["Java process timed out after 120 seconds."]
        print("Java process timed out.")
    except FileNotFoundError:
        anonymized_values = [f"Java executable not found. Check JAVA_JAR_PATH: {JAVA_JAR_PATH}"]
        print(f"Java executable or JAR not found. Ensure JAVA_JAR_PATH is correct: {JAVA_JAR_PATH}")
    except Exception as e:
        anonymized_values = [f"An unexpected error occurred when calling Java: {e}"]
        print(f"General error calling Java: {e}")
    finally:
        if input_file_path_for_java and os.path.exists(input_file_path_for_java):
            os.remove(input_file_path_for_java)
        if output_file_path_for_java and os.path.exists(output_file_path_for_java):
            # Consider keeping output_file_path_for_java for debugging if anonymized_values is empty/error
            if not anonymized_values or "error" in anonymized_values[0].lower() or "empty" in anonymized_values[0].lower() :
                 print(f"Output file {output_file_path_for_java} kept for debugging due to error or empty result.")
            else:
                 os.remove(output_file_path_for_java)


    # --- Build HTML Output ---
    attribute_list_html = ''.join(
        f"<li><strong>{entry['value']}</strong>: "
        f"Domain=<em><strong>{entry['matched_domain']}</strong></em>, "
        f"Sensitivity=<em><strong>{entry['sensitivity']}</strong></em></li>"
        for entry in domain_results
    )

    anonymized_list_html = ''.join(f"<li>{str(val)}</li>" for val in anonymized_values)


    html_response = f'''
        <h3>Request Processed</h3>
        <p><strong>Domain (Selected):</strong> {domain}</p>
        <p><strong>Purpose:</strong> {purpose}</p>
        <p><strong>Data Source:</strong> {source}</p>
        <p><strong>Attribute:</strong> {attribute}</p>
        <p><strong>Owner (From Metadata):</strong> {owner_name}</p>
        <p><strong>KYU Score (Python):</strong> {kyu_score}</p>
        <p><strong>Overall Sensitivity for Java:</strong> {overall_sensitivity_level_for_java}</p>
        <p><strong>Anonymization Score (from Java):</strong> {anonymization_score_from_java}</p>
        <p><strong>Utility Retained (from Java):</strong> {utility_retained_from_java}</p>
        <p><strong>Most Relevant Domain (from FAISS):</strong> {overall_most_common}</p>
        <p><strong>Attribute Value Analysis (Original):</strong></p>
        <ul>{attribute_list_html}</ul>
        <p><strong>Anonymized Attribute Values (from Java):</strong></p>
        <ul>{anonymized_list_html}</ul>
        <a href="/">Back to Home</a>
    '''
    return html_response

# ------------------ Run App ------------------
if __name__ == '__main__':
    app.run(debug=True)
