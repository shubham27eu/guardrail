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
        print(f"Error in predict_kyu_score: {e}") # Added print for debugging
        return "Error" # Return a generic error string

# ------------------ Routes ------------------
@app.route('/')
def index():
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT DISTINCT domain FROM ingests')
        domains = [row[0] for row in c.fetchall() if row[0] is not None] # Added None check
        c.execute('SELECT DISTINCT filename FROM ingests')
        data_sources = [row[0] for row in c.fetchall() if row[0] is not None] # Added None check
    return render_template('index.html', domains=domains, data_sources=data_sources)

@app.route('/ingest', methods=['POST'])
def ingest():
    name = request.form.get('name')
    email = request.form.get('email')
    owner = request.form.get('owner')
    domain = request.form.get('domain')
    file = request.files.get('csv_file')

    if not all([name, email, owner, domain, file]): # Check all fields
        return 'Missing form fields.', 400

    if not file.filename.endswith('.csv'):
        return 'Invalid file format. Only CSV allowed.', 400

    filename = secure_filename(f"{domain}_{file.filename}")
    save_path = os.path.join(app.config['UPLOAD_FOLDER'], filename)

    try:
        file.save(save_path)
    except Exception as e:
        print(f"Error saving uploaded file: {e}")
        return "Error saving file.", 500

    try:
        with sqlite3.connect(EXTERNAL_DB) as conn:
            conn.execute('''
                INSERT INTO ingests (name, email, owner, domain, filename)
                VALUES (?, ?, ?, ?, ?)
            ''', (name, email, owner, domain, filename))
    except sqlite3.Error as e:
        print(f"Database error during ingest: {e}")
        # Potentially remove saved file if DB insert fails
        if os.path.exists(save_path):
            os.remove(save_path)
        return "Database error during ingestion.", 500
    except Exception as e: # Catch any other unexpected errors
        print(f"Unexpected error during ingest: {e}")
        if os.path.exists(save_path):
            os.remove(save_path)
        return "An unexpected error occurred during ingestion.", 500


    return '''
        <h3>Data ingested successfully!</h3>
        <a href="/">Back to Home</a>
    '''

@app.route('/login', methods=['POST'])
def login():
    data = request.get_json()
    if not data:
        return jsonify({'success': False, 'error': 'No data provided'}), 400
    email = data.get('email')
    password = data.get('password')

    if not email or not password:
        return jsonify({'success': False, 'error': 'Email or password missing'}), 400

    try:
        with sqlite3.connect(INTERNAL_DB) as conn:
            c = conn.cursor()
            c.execute('SELECT 1 FROM users WHERE email=? AND password=?', (email, password))
            user = c.fetchone()
        return jsonify({'success': bool(user)})
    except sqlite3.Error as e:
        print(f"Database error during login: {e}")
        return jsonify({'success': False, 'error': 'Database error'}), 500
    except Exception as e: # Catch any other unexpected errors
        print(f"Unexpected error during login: {e}")
        return jsonify({'success': False, 'error': 'An unexpected error occurred'}), 500


@app.route('/get_sources', methods=['GET'])
def get_sources():
    domain = request.args.get('domain')
    if not domain:
        return jsonify([]) # Return empty if no domain
    try:
        with sqlite3.connect(EXTERNAL_DB) as conn:
            c = conn.cursor()
            c.execute('SELECT filename FROM ingests WHERE domain=?', (domain,))
            files = [row[0] for row in c.fetchall() if row[0] is not None] # Added None check
        return jsonify(files)
    except sqlite3.Error as e:
        print(f"Database error in get_sources: {e}")
        return jsonify({'error': 'Database error'}), 500
    except Exception as e: # Catch any other unexpected errors
        print(f"Unexpected error in get_sources: {e}")
        return jsonify({'error': 'An unexpected error occurred'}), 500


@app.route('/get_attributes/<filename>', methods=['GET'])
def get_attributes(filename):
    if not filename or "undefined" in filename or "null" in filename : # Basic check for invalid filename
        return jsonify([])

    filepath = os.path.join(app.config['UPLOAD_FOLDER'], secure_filename(filename)) # Secure filename again
    if os.path.exists(filepath):
        try:
            df = pd.read_csv(filepath, nrows=1) # Only read headers
            return jsonify(list(df.columns))
        except Exception as e:
            print(f"Error reading attributes from {filepath}: {e}")
            return jsonify([]) # Return empty on error
    return jsonify([])


@app.route('/request', methods=['POST'])
def request_data():
    # Initialize all variables that will be used in the final HTML response string
    # to ensure they have a default value in case of errors.
    anonymization_score_from_java = "N/A"
    utility_retained_from_java = "N/A"
    anonymized_values = ["Anonymization process did not complete successfully or was not attempted."]
    kyu_score = "N/A"
    owner_name = "N/A"
    overall_most_common = "N/A"
    overall_sensitivity_level_for_java = "Low" # Default
    domain_results = [] # For attribute_list_html
    attribute_values_for_display = [] # For original attribute values shown

    # Temporary file paths should also be initialized to None
    input_file_path_for_java = None
    output_file_path_for_java = None

    # Get form data
    email = request.form.get('emailID')
    # password = request.form.get('password') # Password not used in this function's core logic beyond form
    domain = request.form.get('domain')
    purpose = request.form.get('Purpose')
    source = request.form.get('dataSource')
    attribute = request.form.get('attributes')

    if not all([email, domain, purpose, source, attribute]):
        # This case should ideally be handled with a proper error message to the user,
        # but for now, the default "N/A" values will be shown.
        # Or, return an error page: return "Missing required form fields", 400
        print("Error: Missing form fields in /request")
        # Fall through to display default "N/A" values, or handle error explicitly:
        # For now, let's build a minimal response if critical info is missing
        error_message = "Missing required form fields for the request."
        return f"<h3>Error</h3><p>{error_message}</p><a href='/'>Back to Home</a>"


    # --- KYU Score ---
    kyu_score = predict_kyu_score(email, domain, purpose)

    # --- Read CSV and extract attribute values ---
    # attribute_values list will store values for Java, attribute_values_for_display for HTML
    attribute_values_for_java_processing = []
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], secure_filename(source)) # Secure filename

    if os.path.exists(filepath):
        try:
            df = pd.read_csv(filepath)
            if attribute in df.columns:
                # Get unique, non-null values as strings for processing and display
                unique_vals = df[attribute].dropna().unique()
                attribute_values_for_java_processing = [str(val) for val in unique_vals]
                attribute_values_for_display = list(attribute_values_for_java_processing) # Keep a copy for display
            else:
                # If attribute not found, this is a significant error for the request.
                error_message = f"Attribute '{attribute}' not found in '{source}'."
                print(error_message)
                return f"<h3>Error</h3><p>{error_message}</p><a href='/'>Back to Home</a>"

        except Exception as e:
            error_message = f"Error reading file '{source}': {e}"
            print(error_message)
            return f"<h3>Error</h3><p>{error_message}</p><a href='/'>Back to Home</a>"
    else:
        error_message = f"Source file '{source}' not found."
        print(error_message)
        return f"<h3>Error</h3><p>{error_message}</p><a href='/'>Back to Home</a>"

    if not attribute_values_for_java_processing: # If list is empty (e.g. column was all NaN)
        # This is also a case where anonymization might not make sense or will fail.
        # Proceeding will send an empty list to Java.
        print(f"Warning: Attribute '{attribute}' in '{source}' has no processable values (empty or all NaN).")
        # Let anonymized_values remain its default error message.


    # --- Metadata Owner ---
    try:
        with sqlite3.connect(EXTERNAL_DB) as conn:
            c = conn.cursor()
            c.execute('SELECT owner FROM ingests WHERE filename = ?', (secure_filename(source),))
            row = c.fetchone()
            if row:
                owner_name = row[0]
            else:
                owner_name = "Owner not found for this source" # More specific than "N/A"
    except sqlite3.Error as e:
        print(f"Database error fetching owner: {e}")
        owner_name = "Error fetching owner"


    # --- FAISS Domain Matching & Compliance Sensitivity ---
    # domain_results already initialized
    domain_votes = []

    for val_str in attribute_values_for_java_processing: # Already list of strings
        try:
            matched_domain, top_domains = predict_domain_for_value(val_str)
            compliance = get_sensitivity_for_owner_domain(owner_name, matched_domain)
            domain_results.append({
                'value': val_str,
                'matched_domain': matched_domain,
                'sensitivity': compliance.get('Sensitivity Level', 'N/A'), # Use .get for safety
            })
            domain_votes.append(matched_domain)
        except Exception as e:
            print(f"Error during FAISS/compliance for value '{val_str}': {e}")
            domain_results.append({
                'value': val_str,
                'matched_domain': 'Error',
                'sensitivity': 'Error processing value',
            })


    if domain_votes: # Check if domain_votes is not empty
        overall_most_common = Counter(domain_votes).most_common(1)[0][0]
    # overall_most_common is already "N/A" by default

    # --- Determine Overall Sensitivity for Java ---
    # overall_sensitivity_level_for_java already "Low" by default
    sensitivities_found = set(dr['sensitivity'].lower() for dr in domain_results if dr.get('sensitivity') and dr['sensitivity'] not in ['N/A', 'Error processing value'])
    if "high" in sensitivities_found:
        overall_sensitivity_level_for_java = "High"
    elif "medium" in sensitivities_found or "moderate" in sensitivities_found:
        overall_sensitivity_level_for_java = "Medium" # Standardize to Medium for Java if either found

    # --- Call Java Anonymization Application ---
    # Check if there are values to process before calling Java
    if attribute_values_for_java_processing:
        try:
            with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt', encoding='utf-8') as tmp_input_file:
                tmp_input_file.write('\n'.join(attribute_values_for_java_processing))
                input_file_path_for_java = tmp_input_file.name

            with tempfile.NamedTemporaryFile(delete=False, suffix='.csv', encoding='utf-8') as tmp_output_file_obj:
                output_file_path_for_java = tmp_output_file_obj.name

            # Ensure JAVA_JAR_PATH is correctly defined above
            java_command = [
                "java", "-jar",
                JAVA_JAR_PATH,
                email if email else "unknown_user", # userId, provide default if None
                kyu_score if kyu_score else "Low",  # kyuScore, provide default
                overall_sensitivity_level_for_java,  # sensitivityLevel
                input_file_path_for_java,
                output_file_path_for_java
            ]

            print(f"Executing Java command: {' '.join(java_command)}")

            process_result = subprocess.run(java_command, capture_output=True, text=True, check=True, timeout=120)

            java_stdout = process_result.stdout
            java_stderr = process_result.stderr
            print(f"Java stdout:
{java_stdout}")
            if java_stderr:
                print(f"Java stderr:
{java_stderr}")

            # Parse scores from Java stdout - defaults remain "N/A" if not found
            if java_stdout:
                for line in java_stdout.splitlines():
                    if line.startswith("ANONYMIZATION_SCORE:"):
                        anonymization_score_from_java = line.split(":", 1)[1].strip()
                    if line.startswith("UTILITY_RETAINED:"):
                        utility_retained_from_java = line.split(":", 1)[1].strip()

            # Read anonymized data
            if os.path.exists(output_file_path_for_java):
                try:
                    df_anonymized = pd.read_csv(output_file_path_for_java)
                    if not df_anonymized.empty:
                        anonymized_values = df_anonymized.iloc[:, 0].astype(str).tolist()
                    else:
                        anonymized_values = ["Java output file was empty."]
                except pd.errors.EmptyDataError:
                    anonymized_values = ["Java output file was empty (pandas error)."]
                except Exception as e:
                    anonymized_values = [f"Error reading Java output CSV: {e}"]
            else:
                anonymized_values = ["Java output file not found at path: " + str(output_file_path_for_java)] # Ensure path is str

        except subprocess.CalledProcessError as e:
            anonymized_values = [f"Java process error (Code {e.returncode}): {e.stderr if e.stderr else 'No stderr'}. Stdout: {e.stdout if e.stdout else 'No stdout'}"]
            print(f"Java CProcessError stdout:
{e.stdout}")
            print(f"Java CProcessError stderr:
{e.stderr}")
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
                # Keep output file for debugging if there was an issue with anonymized_values
                is_error_or_empty = not anonymized_values or                                     any(err_key in val.lower() for val in anonymized_values for err_key in ["error", "empty", "not found", "failed"])
                if is_error_or_empty:
                     print(f"Output file {output_file_path_for_java} kept for debugging.")
                else:
                     os.remove(output_file_path_for_java)
    else: # No attribute_values_for_java_processing
        anonymized_values = ["No data to anonymize (attribute column might be empty or all invalid)."]


    # --- Build HTML Output ---
    # Use attribute_values_for_display for the "Original" list
    attribute_list_html = ''.join(
        f"<li><strong>{entry['value']}</strong>: "
        f"Domain=<em><strong>{entry.get('matched_domain', 'N/A')}</strong></em>, " # Use .get for safety
        f"Sensitivity=<em><strong>{entry.get('sensitivity', 'N/A')}</strong></em></li>"
        for entry in domain_results # domain_results now uses original string values
    )

    anonymized_list_html = ''.join(f"<li>{str(val)}</li>" for val in anonymized_values)

    # Ensure all parts of the f-string are defined
    html_response_header = f'''
        <h3>Request Processed</h3>
        <p><strong>Domain (Selected):</strong> {domain if domain else "N/A"}</p>
        <p><strong>Purpose:</strong> {purpose if purpose else "N/A"}</p>
        <p><strong>Data Source:</strong> {source if source else "N/A"}</p>
        <p><strong>Attribute:</strong> {attribute if attribute else "N/A"}</p>
        <p><strong>Owner (From Metadata):</strong> {owner_name}</p>
        <p><strong>KYU Score (Python):</strong> {kyu_score}</p>
        <p><strong>Overall Sensitivity for Java:</strong> {overall_sensitivity_level_for_java}</p>
    '''
    # These are now guaranteed to be defined due to initialization at function start
    html_java_results = f'''
        <p><strong>Anonymization Score (from Java):</strong> {anonymization_score_from_java}</p>
        <p><strong>Utility Retained (from Java):</strong> {utility_retained_from_java}</p>
    '''
    html_attribute_analysis = f'''
        <p><strong>Most Relevant Domain (from FAISS):</strong> {overall_most_common}</p>
        <p><strong>Attribute Value Analysis (Original Values & FAISS/Compliance):</strong></p>
        <ul>{attribute_list_html}</ul>
    '''
    html_anonymized_data = f'''
        <p><strong>Anonymized Attribute Values (from Java):</strong></p>
        <ul>{anonymized_list_html}</ul>
        <a href="/">Back to Home</a>
    '''
    return html_response_header + html_java_results + html_attribute_analysis + html_anonymized_data

# ------------------ Run App ------------------
if __name__ == '__main__':
    app.run(debug=True)
