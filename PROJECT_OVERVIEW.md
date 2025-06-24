# Data Anonymization Web Application: Project Overview

## 1. Overall Purpose and Architecture

This project provides a data anonymization solution orchestrated through a Python Flask web interface. Users can ingest CSV data and request anonymization for specific attributes. The system is designed to calculate data sensitivity (KYU - Know Your User/Data score) and apply appropriate anonymization techniques via a backend Java engine.

The architecture comprises two main parts:

1.  **Python Flask Web Application (`app.py`):**
    *   Handles user interaction, data ingestion, KYU score calculation, sensitivity level determination, and orchestration of the anonymization process.
    *   Located in `src/main/resources/scripts/GUARDRAIL-3/`.

2.  **Java Anonymization Engine:**
    *   A command-line application (built as `data-processor-main.jar`) that performs the actual anonymization.
    *   It is invoked by the Flask application and receives parameters and data (or paths to data) to guide its operation.

## 2. Frontend Components

The frontend is responsible for user interaction and preparing data for anonymization.

*   **`app.py` (Flask Application):**
    *   The central component of the frontend, providing the web UI.
    *   **User Interaction:**
        *   Allows users to ingest data by uploading CSV files along with metadata (Name, Email, Owner, Domain).
        *   Enables users to request anonymization by selecting the domain, purpose, data source (ingested CSV), and a specific attribute.
    *   **Data Preparation & Orchestration:**
        *   Calculates KYU scores using `kyu_module.py` and associated machine learning models (`kyu_score_model.joblib`, `label_encoders.joblib`).
        *   Determines sensitivity levels for data attributes.
        *   Utilizes `faiss_domain_helper.py` with FAISS files (`domain_index.faiss`, `id_to_domain.pkl`) for domain matching/lookup.
        *   May use `compliance_lookup.py` for compliance checks.
        *   Invokes the Java backend engine as a subprocess.
    *   **Results Display:** Presents original data insights, anonymized data, and anonymization scores (received from the Java engine) to the user.
    *   Serves HTML content from `templates/` and static files (CSS, JS) from `static/`.

*   **Supporting Python Modules (within `src/main/resources/scripts/GUARDRAIL-3/`):**
    *   `kyu_module.py`: Calculates KYU scores.
    *   `faiss_domain_helper.py`: Assists with FAISS domain matching.
    *   `compliance_lookup.py`: Potentially for compliance-related checks.

*   **Machine Learning Models & FAISS Files (within `src/main/resources/scripts/GUARDRAIL-3/`):**
    *   `kyu_score_model.joblib`: Pre-trained model for KYU scoring.
    *   `label_encoders.joblib`: Pre-fitted label encoders for the ML model.
    *   `domain_index.faiss`: FAISS index for efficient domain similarity searching.
    *   `id_to_domain.pkl`: Maps FAISS index IDs to domain names.

## 3. Backend Components

The backend consists of the Java anonymization engine.

*   **Java Anonymization Engine (`data-processor-main.jar`):**
    *   **Core Logic:** Contains the algorithms and strategies for data anonymization. Built as an executable JAR (`data-processor-main.jar`) located in the `target/` directory. The main entry point is `com/example/anonymization/Main.java`.
    *   **Invocation:** Called as a subprocess by the Python Flask application (`app.py`).
    *   **Parameter Reception:** Receives data and parameters from `app.py` via:
        *   **Command-line arguments:** User ID, KYU score, sensitivity level, input CSV file path, and output CSV file path.
        *   **Temporary files:** Potentially for other data lists or detailed instructions.
    *   **Anonymization Process:** Performs anonymization based on the received parameters (KYU score, sensitivity) and its internally configured strategies.
    *   **Output:**
        *   Writes the anonymized data to a CSV file at the specified output path.
        *   Writes anonymization scores (e.g., effectiveness metrics) to standard output, which `app.py` then captures.
    *   Configuration for the Java application may be present in `src/main/resources/config.properties`.

## 4. End-to-End Data Flow

1.  **User Ingests Data:** User uploads a CSV file and metadata via the Flask web UI.
2.  **`app.py` Processes Request:** When anonymization is requested for a specific attribute, `app.py` calculates its KYU score and sensitivity level using its Python modules and ML models.
3.  **`app.py` Invokes Java Engine:** `app.py` calls `data-processor-main.jar` as a subprocess, passing the user ID, KYU score, sensitivity level, input/output file paths as command-line arguments.
4.  **Java Engine Anonymizes:** The Java engine reads the input CSV, anonymizes the data based on the provided parameters and its internal strategies.
5.  **Java Engine Outputs Results:** It writes the anonymized data to the specified output CSV file and prints anonymization scores to standard output.
6.  **`app.py` Presents Results:** `app.py` captures the scores from standard output and reads the anonymized CSV. It then displays insights, the anonymized data, and scores to the user via the web UI.

## 5. Key Technologies and Dependencies

*   **Python (v3.8+):**
    *   Flask: Web framework.
    *   Pandas: Data manipulation.
    *   Joblib: ML model persistence.
    *   Scikit-learn: Machine learning library.
    *   Faiss (`faiss-cpu`): Similarity search.
*   **Java (JDK 17+):**
    *   Maven: Build tool for the Java engine.
*   **Notable Java Libraries (managed by Maven, visible in `lib/`):**
    *   Apache Commons: `collections`, `compress`, `csv`, `io`, `math`.
    *   Apache POI: For working with Microsoft Office (Excel) files.
    *   Logging: Log4j, SLF4J.
    *   SQLite-JDBC: For SQLite database interaction (potential usage).
    *   XMLBeans: For XML processing (used with POI).
*   **Data Formats:** CSV (primary), XLSX, SQL (potential).

## 6. Setup and Execution Process

1.  **Clone Repository:**
    ```bash
    git clone https://github.com/shubham27eu/guardrail.git
    cd guardrail
    git checkout feat/python-java-integration-revised
    ```
2.  **Build Java Engine:** From the project root:
    ```bash
    mvn clean package
    ```
    (This creates `target/data-processor-main.jar`)
3.  **Setup Python Environment:**
    ```bash
    cd src/main/resources/scripts/GUARDRAIL-3/
    python3 -m venv venv
    # Activate: source venv/bin/activate (Linux/macOS) or venv\Scripts\activate (Windows)
    pip install -r requirements.txt
    ```
4.  **Run Flask Application:** From `src/main/resources/scripts/GUARDRAIL-3/` (with venv active):
    ```bash
    python3 app.py
    ```
5.  **Access Application:** Open a web browser to `http://127.0.0.1:5000/`.

## 7. Key Files and Directories

*   **Project Root:**
    *   `pom.xml`: Maven build configuration for Java.
    *   `README.md`: This documentation.
    *   `Data_2019-20.csv`, `Data_2019-20.xlsx`, `Data_2019_20.sql`: Example data files.
    *   `FILES/`: Contains various metadata and supporting data files (mostly `.xlsx`).
    *   `lib/`: Contains Java library JAR files.
*   **Java Source & Resources:**
    *   `src/main/java/com/example/anonymization/Main.java`: Entry point for the Java engine.
    *   `src/main/resources/config.properties`: Potential configuration for the Java app.
*   **Python Application (`src/main/resources/scripts/GUARDRAIL-3/`):**
    *   `app.py`: Main Flask application.
    *   `requirements.txt`: Python dependencies.
    *   `kyu_module.py`, `faiss_domain_helper.py`, `compliance_lookup.py`: Helper Python modules.
    *   `templates/`: HTML templates for the UI.
    *   `static/`: Static files (CSS, JS, uploaded data).
    *   `kyu_score_model.joblib`, `label_encoders.joblib`: ML models.
    *   `domain_index.faiss`, `id_to_domain.pkl`: FAISS files.
*   **Build Output:**
    *   `target/data-processor-main.jar`: The compiled Java anonymization engine.

This overview should provide a comprehensive understanding of the project's structure, functionality, and operation.
