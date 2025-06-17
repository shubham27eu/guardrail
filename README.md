# Data Anonymization Web Application

## Description

This project provides a data anonymization solution orchestrated through a Python Flask web interface. Users can ingest CSV data, and request anonymization for specific attributes. The Flask application (`app.py`) handles user interaction, data preparation, KYU (Know Your User/Data) score calculation, and sensitivity level determination. It then invokes a Java-based backend engine to perform the anonymization based on these parameters. The anonymized data and relevant scores are then presented back to the user.

## Architecture Overview

The system comprises two main parts:

1.  **Python Flask Web Application (`app.py`):**
    *   Located in `src/main/resources/scripts/GUARDRAIL-3/`.
    *   Provides the user interface for data ingestion and anonymization requests.
    *   Calculates KYU scores and determines sensitivity levels for data attributes using local Python modules and models.
    *   Calls the Java anonymization engine as a subprocess.
    *   Exchanges data with the Java engine via command-line arguments and temporary files (for data lists).
    *   Displays original data insights, anonymized data, and anonymization scores.

2.  **Java Anonymization Engine:**
    *   The core anonymization logic resides in a Java application, built as an executable JAR.
    *   Receives data and parameters (user ID, KYU score, sensitivity level, input/output file paths) from `app.py` via command-line arguments.
    *   Performs anonymization based on the provided parameters and configured strategies.
    *   Writes anonymized data to a CSV file and anonymization scores to standard output, which `app.py` then captures.

## Prerequisites

*   **Java Development Kit (JDK):** Version 17 or higher (as per `pom.xml`).
*   **Apache Maven:** To build the Java project (latest version recommended).
*   **Python:** Version 3.8 or higher.
*   **Python Virtual Environment Tool:** `venv` (usually included with Python).
*   **Python Dependencies:** Listed in `src/main/resources/scripts/GUARDRAIL-3/requirements.txt`. These include Flask, Pandas, Joblib, Scikit-learn, and Faiss (specifically `faiss-cpu` for broad compatibility).

## Setup and Build

1.  **Clone the Repository:**
    ```bash
    git clone https://github.com/shubham27eu/guardrail.git
   cd guardrail
   git checkout feat/python-java-integration-revised
    ```

2.  **Build the Java Anonymization Engine:**
    From the project root directory:
    ```bash
    mvn clean package
    ```
    This will generate `data-processor-main.jar` in the `target/` directory.

3.  **Set up Python Environment for `app.py`:**
    Navigate to the Python application directory:
    ```bash
    cd src/main/resources/scripts/GUARDRAIL-3/
    ```
    Create and activate a virtual environment:
    ```bash
    python -m venv venv
    # On Linux/macOS:
    source venv/bin/activate
    # On Windows:
    # venv\Scripts\activate
    ```
    Install Python dependencies:
    ```bash
    pip install -r requirements.txt
    ```
    *(Note: `requirements.txt` will be created in the next step. Ensure it's present before running this command.)*

## Execution Instructions

1.  **Ensure Python Virtual Environment is Active:**
    If you haven't already, navigate to `src/main/resources/scripts/GUARDRAIL-3/` and activate the virtual environment:
    ```bash
    # On Linux/macOS:
    source venv/bin/activate
    # On Windows:
    # venv\Scripts\activate
    ```

2.  **Run the Flask Application:**
    From the `src/main/resources/scripts/GUARDRAIL-3/` directory:
    ```bash
    python app.py
    ```

3.  **Access the Application:**
    Open your web browser and go to:
    ```
    http://127.0.0.1:5000/
    ```

4.  **Using the Web UI:**
    *   **Ingest Data:** Use the form to provide details (Name, Email, Owner, Domain) and upload a CSV file.
    *   **Request Anonymization:** Use the "Request Data" form to select the domain, purpose, data source (ingested CSV), and a specific attribute from that CSV. The application will process this, call the Java backend, and display original data insights along with the anonymized results and scores.

## Project Structure (Key Files/Directories)

-   `pom.xml`: Maven build configuration for the Java project.
-   `src/main/java/`: Java source code for the anonymization engine.
    -   `com/example/anonymization/Main.java`: Main entry point for the Java CLI application.
-   `src/main/resources/scripts/GUARDRAIL-3/`: Python Flask application.
    -   `app.py`: Main Flask application file.
    -   `requirements.txt`: Python dependencies for `app.py`. (To be created)
    -   `kyu_module.py`, `faiss_domain_helper.py`, `compliance_lookup.py`: Helper Python modules for `app.py`.
    -   `templates/`: HTML templates for the web UI.
    -   `static/`: Static files (CSS, JS, uploaded data).
    -   `kyu_score_model.joblib`, `label_encoders.joblib`: Machine learning model files for KYU scoring.
    -   `domain_index.faiss`, `id_to_domain.pkl`: Files for FAISS domain matching.
-   `target/`: Directory where the compiled Java JAR (`data-processor-main.jar`) is placed after build.
-   `README.md`: This file.

## Configuration

*   **Java JAR Path:** The path to the `data-processor-main.jar` is configured within `app.py` via the `JAVA_JAR_PATH` variable. The current relative path (`../../../../../target/data-processor-main.jar`) is set up to work correctly when `app.py` is run from its directory after a successful Maven build.
