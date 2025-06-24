from flask import Flask, render_template, request, jsonify
import sqlite3
import os
import pandas as pd
import numpy as np # Ensure numpy is imported (though calculate_alpha_score is removed, other pandas operations might use it implicitly)
from werkzeug.utils import secure_filename
import joblib
from collections import Counter
import subprocess
import tempfile
from compliance_lookup import get_sensitivity_for_about_domain

# REMOVED: calculate_alpha_score function definition

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
        df_peek = pd.read_csv(filepath, nrows=1)
        return jsonify(list(df_peek.columns))
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

    original_df = pd.read_csv(filepath)
    
    tagged_values = []
    compliance_results = []
    for attr in attributes:
        if attr not in original_df.columns:
            continue
        values = original_df[attr].dropna().unique().astype(str)
        for val_str in values:
            tagged_values.append(f"{attr}::{val_str}")

        compliance = get_sensitivity_for_about_domain(about_entity, file_domain)
        compliance_results.append({
            'attribute': attr,
            'domain': file_domain,
            'sensitivity': compliance['Sensitivity Level'],
            'explaination': compliance['Explaination'],
            'receiving entity': compliance['Receiving Entity']
        })

    with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt', encoding='utf-8') as temp_input:
        temp_input.write('\n'.join(tagged_values))
        input_path = temp_input.name

    with tempfile.NamedTemporaryFile(delete=False, suffix='.csv') as temp_output:
        output_path = temp_output.name

    sensitivities = {entry['sensitivity'].lower() for entry in compliance_results}
    if 'high' in sensitivities:
        overall_sensitivity = 'High'
    elif 'moderate' in sensitivities or 'medium' in sensitivities:
        overall_sensitivity = 'Medium'
    else:
        overall_sensitivity = 'Low'

    anonymized_data_with_strategy = [{'value': "Processing error before Java call.", 'strategy': 'N/A'}]
    # MODIFICATION 1a: Initialize java_alpha_score_display
    java_alpha_score_display = "N/A"

    try:
        java_command = [
            "java", "-jar",
            JAVA_JAR_PATH,
            email, kyu_score, overall_sensitivity,
            input_path, output_path
        ]
        print(f"Executing Java command: {' '.join(java_command)}")
        # MODIFICATION 1b: Ensure capture_output=True and text=True
        completed_process = subprocess.run(java_command, check=True, capture_output=True, text=True, timeout=120)
        java_stdout = completed_process.stdout
        
        # MODIFICATION 1c: Parse stdout for AlphaScore
        for line in java_stdout.splitlines():
            if line.startswith("AlphaScore:"):
                try:
                    score_value_str = line.split(":")[1].strip()
                    java_alpha_score_display = score_value_str
                    break
                except IndexError:
                    print(f"Warning: Could not parse AlphaScore line: {line}")
                    java_alpha_score_display = "Error parsing score"
        
        # Existing logic for reading the output file for anonymized_data_with_strategy
        anonymized_data_with_strategy = []
        if os.path.exists(output_path):
            with open(output_path, 'r', encoding='utf-8') as f:
                for line in f:
                    parts = line.strip().split('::', 1)
                    if len(parts) == 2:
                        anonymized_data_with_strategy.append({'value': parts[0], 'strategy': parts[1]})
                    elif len(parts) == 1:
                        anonymized_data_with_strategy.append({'value': parts[0], 'strategy': 'Unknown'})
                    else:
                        anonymized_data_with_strategy.append({'value': 'Error parsing Java output', 'strategy': 'Error'})
            if not anonymized_data_with_strategy:
                 anonymized_data_with_strategy = [{'value': "Java output file was empty.", 'strategy': 'N/A'}]
        else:
            anonymized_data_with_strategy = [{'value': "Java output not found.", 'strategy': 'N/A'}]

    except subprocess.CalledProcessError as e:
        error_message = f"Error during Java call (CalledProcessError): {e}\n"
        error_message += f"Return code: {e.returncode}\n"
        error_message += f"Command: {' '.join(e.cmd)}\n"
        # Capture stdout/stderr from the exception object
        java_stdout_on_error = e.stdout if e.stdout else ""
        java_stderr_on_error = e.stderr if e.stderr else ""
        error_message += f"Stdout: {java_stdout_on_error}\n"
        error_message += f"Stderr: {java_stderr_on_error}"
        
        anonymized_data_with_strategy = [{'value': error_message, 'strategy': 'Java Execution Error'}]
        # MODIFICATION 1d: Set alpha score display on error
        java_alpha_score_display = "Java error (CPE)"
        print(f"Java stdout on error: {java_stdout_on_error}") # Already done by adding to error_message
        print(f"Java stderr on error: {java_stderr_on_error}") # Already done by adding to error_message

    except Exception as e:
        error_message = f"General error during Java call: {type(e).__name__} - {e}"
        anonymized_data_with_strategy = [{'value': error_message, 'strategy': 'Python Execution Error'}]
        # MODIFICATION 1d: Set alpha score display on error
        java_alpha_score_display = "Python error during Java call"
        print(f"General error calling Java: {e}")
    finally:
        os.remove(input_path)
        if os.path.exists(output_path):
            os.remove(output_path)

    unique_strategies = set()
    if anonymized_data_with_strategy:
        for item in anonymized_data_with_strategy:
            if 'strategy' in item and item['strategy'] not in ['N/A', 'Error', 'Unknown', 'Java Execution Error', 'Python Execution Error']:
                unique_strategies.add(item['strategy'])
    if not unique_strategies:
        if any(item.get('strategy') in ['Java Execution Error', 'Python Execution Error', 'Error'] for item in anonymized_data_with_strategy):
            unique_strategies.add("An error occurred during processing.")
        elif any(item.get('strategy') == 'N/A' for item in anonymized_data_with_strategy):
             unique_strategies.add("No specific strategy applicable or output not found.")
        else:
            unique_strategies.add("No specific strategy applied.")

    strategies_html = "<ul>" + "".join(f"<li>{s}</li>" for s in unique_strategies) + "</ul>"
    
    output_html = ""
    for i, tag in enumerate(tagged_values):
        attr, orig_val_str = tag.split("::", 1)
        if i < len(anonymized_data_with_strategy):
            anon_entry = anonymized_data_with_strategy[i]
            anon_val_str = anon_entry['value']
        else:
            anon_val_str = "?"
        output_html += f"<li><strong>{attr}</strong>: {orig_val_str} → <strong>{anon_val_str}</strong></li>"

    metadata_html = ''.join(
        f"<li><strong>{entry['attribute']}</strong>: "
        f"<br>Domain: <em>{entry['domain']}</em>, "
        f"Sensitivity: <em>{entry['sensitivity']}</em>, "
        f"Explaination: {entry['explaination']}, "
        f"Receiving Entity: {entry['receiving entity']}</li>"
        for entry in compliance_results
    )

    # MODIFICATION 2: Display Java-calculated Alpha Score in HTML
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
        <p><strong>Data Filtering Score (Alpha - from Java):</strong> {java_alpha_score_display}</p>
        <hr>
        <p><strong>Attribute Metadata:</strong></p>
        <ul>{metadata_html}</ul>
        <hr>
        <p><strong>Applied Anonymization Strategies:</strong></p> {strategies_html}
        <hr>
        <p><strong>Anonymization Results (unique values processed):</strong></p> <ul>{output_html}</ul>
        <a href="/">Back to Home</a>
    '''

# ------------------ Score Interpretation Function ------------------
def get_score_interpretation(score_str):
    if score_str in ["N/A", "Error parsing score", "Java error (CPE)", "Python error during Java call", "Error"]:
        return "Score could not be calculated due to an error."
    try:
        score = float(score_str)
        if score == 0.0:
            return "No changes were made to the queried data values."
        elif score == 1.0:
            return "All queried data values were changed from their original state (or represent maximum possible change for numeric values)."
        elif 0.0 < score <= 0.1:
            return "Minimal changes: Very few values were altered, or numeric changes were very small."
        elif 0.1 < score <= 0.5:
            return "Minor changes: Some values were altered, or numeric changes were small to moderate."
        elif 0.5 < score < 0.9: # Corrected from <= 0.9 to < 0.9 to avoid overlap with next
            return "Moderate changes: Many values were altered, or numeric changes were significant."
        elif 0.9 <= score < 1.0:
            return "Significant changes: Most values were altered, or numeric changes were large."
        else:
            return "Score is out of the expected range (0.0-1.0)."
    except ValueError:
        return "Score value is not a valid number."

# ------------------ Routes ------------------
@app.route('/')
def index_2():
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT DISTINCT domain FROM ingests')
        domains = [row[0] for row in c.fetchall()]
        c.execute('SELECT DISTINCT filename FROM ingests')
        data_sources = [row[0] for row in c.fetchall()]
    return render_template('index.html', domains=domains, data_sources=data_sources)

@app.route('/ingest', methods=['POST'])
def ingest_2():
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
def get_sources_2():
    domain = request.args.get('domain')
    with sqlite3.connect(EXTERNAL_DB) as conn:
        c = conn.cursor()
        c.execute('SELECT filename FROM ingests WHERE domain=?', (domain,))
        files = [row[0] for row in c.fetchall()]
    return jsonify(files)

@app.route('/get_attributes/<filename>', methods=['GET'])
def get_attributes_2(filename):
    filepath = os.path.join(app.config['UPLOAD_FOLDER'], filename)
    if os.path.exists(filepath):
        df_peek = pd.read_csv(filepath, nrows=1)
        return jsonify(list(df_peek.columns))
    return jsonify([])

@app.route('/request', methods=['POST'])
def request_data_2():
    email = request.form.get('emailID')
    domain = request.form.get('domain')
    purpose = request.form.get('Purpose')
    source = request.form.get('dataSource')
    attributes_raw = request.form.get('attributes', '')
    attributes = [attr.strip() for attr in attributes_raw.split(',') if attr.strip()]

    kyu_score = predict_kyu_score(email, domain, purpose)

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

    original_df = pd.read_csv(filepath)
    
    tagged_values = []
    compliance_results = []
    for attr in attributes:
        if attr not in original_df.columns:
            continue
        values = original_df[attr].dropna().unique().astype(str)
        for val_str in values:
            tagged_values.append(f"{attr}::{val_str}")

        compliance = get_sensitivity_for_about_domain(about_entity, file_domain)
        compliance_results.append({
            'attribute': attr,
            'domain': file_domain,
            'sensitivity': compliance['Sensitivity Level'],
            'explaination': compliance['Explaination'],
            'receiving entity': compliance['Receiving Entity']
        })

    with tempfile.NamedTemporaryFile(mode='w', delete=False, suffix='.txt', encoding='utf-8') as temp_input:
        temp_input.write('\n'.join(tagged_values))
        input_path = temp_input.name

    with tempfile.NamedTemporaryFile(delete=False, suffix='.csv') as temp_output:
        output_path = temp_output.name

    sensitivities = {entry['sensitivity'].lower() for entry in compliance_results}
    if 'high' in sensitivities:
        overall_sensitivity = 'High'
    elif 'moderate' in sensitivities or 'medium' in sensitivities: # Added 'medium'
        overall_sensitivity = 'Medium'
    else:
        overall_sensitivity = 'Low'

    anonymized_data_with_strategy = [{'value': "Processing error before Java call.", 'strategy': 'N/A'}]
    java_alpha_score_display = "N/A"

    try:
        java_command = [
            "java", "-jar",
            JAVA_JAR_PATH,
            email, kyu_score, overall_sensitivity,
            input_path, output_path
        ]
        print(f"Executing Java command: {' '.join(java_command)}")
        completed_process = subprocess.run(java_command, check=True, capture_output=True, text=True, timeout=120)
        java_stdout = completed_process.stdout
        
        for line in java_stdout.splitlines():
            if line.startswith("AlphaScore:"):
                try:
                    score_value_str = line.split(":")[1].strip()
                    java_alpha_score_display = score_value_str
                    break
                except IndexError:
                    print(f"Warning: Could not parse AlphaScore line: {line}")
                    java_alpha_score_display = "Error parsing score"
        
        anonymized_data_with_strategy = []
        if os.path.exists(output_path):
            with open(output_path, 'r', encoding='utf-8') as f:
                for line in f:
                    parts = line.strip().split('::', 1)
                    if len(parts) == 2:
                        anonymized_data_with_strategy.append({'value': parts[0], 'strategy': parts[1]})
                    elif len(parts) == 1:
                        anonymized_data_with_strategy.append({'value': parts[0], 'strategy': 'Unknown'})
                    else:
                        anonymized_data_with_strategy.append({'value': 'Error parsing Java output', 'strategy': 'Error'})
            if not anonymized_data_with_strategy:
                 anonymized_data_with_strategy = [{'value': "Java output file was empty.", 'strategy': 'N/A'}]
        else:
            anonymized_data_with_strategy = [{'value': "Java output not found.", 'strategy': 'N/A'}]

    except subprocess.CalledProcessError as e:
        error_message = f"Error during Java call (CalledProcessError): {e}\n"
        error_message += f"Return code: {e.returncode}\n"
        error_message += f"Command: {' '.join(e.cmd)}\n"
        java_stdout_on_error = e.stdout if e.stdout else ""
        java_stderr_on_error = e.stderr if e.stderr else ""
        error_message += f"Stdout: {java_stdout_on_error}\n"
        error_message += f"Stderr: {java_stderr_on_error}"
        
        anonymized_data_with_strategy = [{'value': error_message, 'strategy': 'Java Execution Error'}]
        java_alpha_score_display = "Java error (CPE)"
        print(f"Java stdout on error: {java_stdout_on_error}")
        print(f"Java stderr on error: {java_stderr_on_error}")

    except Exception as e:
        error_message = f"General error during Java call: {type(e).__name__} - {e}"
        anonymized_data_with_strategy = [{'value': error_message, 'strategy': 'Python Execution Error'}]
        java_alpha_score_display = "Python error during Java call"
        print(f"General error calling Java: {e}")
    finally:
        os.remove(input_path)
        if os.path.exists(output_path):
            os.remove(output_path)
    
    score_interpretation_text = get_score_interpretation(java_alpha_score_display) # Get interpretation

    unique_strategies = set()
    if anonymized_data_with_strategy:
        for item in anonymized_data_with_strategy:
            if 'strategy' in item and item['strategy'] not in ['N/A', 'Error', 'Unknown', 'Java Execution Error', 'Python Execution Error']:
                unique_strategies.add(item['strategy'])
    if not unique_strategies:
        if any(item.get('strategy') in ['Java Execution Error', 'Python Execution Error', 'Error'] for item in anonymized_data_with_strategy):
            unique_strategies.add("An error occurred during processing.")
        elif any(item.get('strategy') == 'N/A' for item in anonymized_data_with_strategy):
             unique_strategies.add("No specific strategy applicable or output not found.")
        else:
            unique_strategies.add("No specific strategy applied.")

    strategies_html = "<ul>" + "".join(f"<li>{s}</li>" for s in unique_strategies) + "</ul>"
    
    output_html = ""
    for i, tag in enumerate(tagged_values):
        attr, orig_val_str = tag.split("::", 1)
        if i < len(anonymized_data_with_strategy):
            anon_entry = anonymized_data_with_strategy[i]
            anon_val_str = anon_entry['value']
        else:
            anon_val_str = "?" # Should not happen if lists are same length
        output_html += f"<li><strong>{attr}</strong>: {orig_val_str} → <strong>{anon_val_str}</strong></li>"

    metadata_html = ''.join(
        f"<li><strong>{entry['attribute']}</strong>: "
        f"<br>Domain: <em>{entry['domain']}</em>, "
        f"Sensitivity: <em>{entry['sensitivity']}</em>, "
        f"Explaination: {entry['explaination']}, "
        f"Receiving Entity: {entry['receiving entity']}</li>"
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
        <p><strong>Data Filtering Score (Alpha - from Java):</strong> {java_alpha_score_display}</p>
        <p><em>Interpretation: {score_interpretation_text}</em></p>
        <hr>
        <p><strong>Attribute Metadata:</strong></p>
        <ul>{metadata_html}</ul>
        <hr>
        <p><strong>Applied Anonymization Strategies:</strong></p> {strategies_html}
        <hr>
        <p><strong>Anonymization Results (unique values processed):</strong></p> <ul>{output_html}</ul>
        <a href="/">Back to Home</a>
    '''

# ------------------ Run App ------------------
if __name__ == '__main__':
    app.run(debug=True)
