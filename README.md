# Project Compliance Guardian: Langchain Hybrid Application

This project demonstrates a hybrid application leveraging Langchain for intelligent document processing, data analysis, and compliance-related scoring. It combines Python scripts for machine learning tasks with a Java backend for orchestration and API endpoints.

## Overview

Project Compliance Guardian assists in determining sensitivity levels of data attributes and assessing "Know Your User" (KYU) scores. It uses NLP techniques for matching descriptions to predefined domains and owner data types, and a machine learning model for KYU score prediction.

## Features

*   **Sensitivity Analysis:** Determines the sensitivity level (Low, Moderate, High) of data attributes based on their description. This involves:
    *   Matching attribute descriptions to known data domains (e.g., Healthcare, Finance).
    *   Matching attribute descriptions to owner data types (e.g., Company, Adult Individual).
    *   Combining domain and owner sensitivity to determine a final sensitivity score.
*   **KYU Score Prediction:** Predicts a KYU (Know Your User) score (Low, Moderate, High) based on user attributes like email type, domain, and purpose.
*   **Data-driven Insights:** Utilizes FAISS for efficient similarity search on text embeddings.
*   **Hybrid Architecture:** Python scripts for ML and NLP, Java for backend services.

## Architecture

The system is composed of:

1.  **Java Backend (`src/main/java`):**
    *   Provides API endpoints to trigger analysis.
    *   Orchestrates the execution of Python scripts.
    *   Manages data flow between components.
    *   Includes services for:
        *   `ComplianceService.java`: Core logic for calling Python scripts and processing results.
        *   `DataLoadService.java`: Handles loading of necessary data files for the scripts.
        *   `FileStorageService.java`: Manages uploaded files (though not fully utilized by current Python scripts which expect files in specific paths).
        *   `Main.java`: Example entry point for demonstrating service usage.

2.  **Python Scripts (`src/main/resources/scripts`):**
    *   `generate_sensitivity.py`:
        *   Takes attribute descriptions as input (implicitly from `Attributes_2019-20.csv`).
        *   Uses Sentence Transformers and FAISS to map descriptions to domains (from `domain_data_points.xlsx`) and owner types (from `metadata_repo.csv`).
        *   Applies a predefined sensitivity mapping to determine the final sensitivity for each attribute.
        *   Outputs `Sensitivity_Results.xlsx`.
    *   `generate_kyu_scores.py`:
        *   Takes user data as input (from `KYU_Score_Final.xlsx`).
        *   Performs feature engineering (e.g., deriving email type).
        *   Trains a RandomForestClassifier model.
        *   Predicts KYU scores for the input users.
        *   Outputs `KYU Score.xlsx`.

3.  **Data Files (`src/main/resources/FILES` or expected in script execution path):**
    *   `compliance_repository.xlsx`: Contains mappings for domain-to-sensitivity (used conceptually for `generate_sensitivity.py`'s hardcoded map).
    *   `Attributes_2019-20.csv`: Input for `generate_sensitivity.py` containing attribute IDs and descriptions.
    *   `domain_data_points.xlsx`: Used by `generate_sensitivity.py` to build a FAISS index for domain matching.
    *   `metadata_repo.csv`: Used by `generate_sensitivity.py` to build a FAISS index for owner type matching.
    *   `KYU_Score_Final.xlsx`: Input for `generate_kyu_scores.py` containing user data for KYU scoring.

## How to Run (Conceptual)

1.  **Prerequisites:**
    *   Java JDK (version specified in `pom.xml`)
    *   Maven
    *   Python environment with necessary packages (`pandas`, `numpy`, `scikit-learn`, `faiss-cpu`, `sentence-transformers`). These are installed by the Python scripts if `pip` is available and the environment is writable.
2.  **Build the Java Application:**
    ```bash
    mvn clean install
    ```
3.  **Prepare Data Files:**
    *   Ensure the required `.xlsx` and `.csv` data files are present in a location accessible by the Python scripts (e.g., a `FILES` directory relative to where the Java application will run the scripts, or in the script's execution directory). The Python scripts contain logic to search in a few common relative paths.
4.  **Run the Java Application:**
    *   The `Main.java` provides an example of how to invoke the services. The application would typically be run as a Spring Boot application.
    ```bash
    java -jar target/compliance-guardian-0.0.1-SNAPSHOT.jar
    ```
    (Assuming the JAR name and that it's executable with a main class).
5.  **Trigger Analysis:**
    *   Through API calls to the Java backend (if REST controllers were fully implemented and exposed).
    *   Or by directly running methods in `Main.java` for demonstration.

## Python Script Execution Details

The Java application executes the Python scripts using `ProcessBuilder`. The scripts are expected to:
*   Read input files from pre-defined paths or names (e.g., `KYU_Score_Final.xlsx`, `Attributes_2019-20.csv`).
*   Generate output files (e.g., `KYU Score.xlsx`, `Sensitivity_Results.xlsx`) in their working directory.
*   The Java service then reads these output files.

## Original Project Information (PyMath)

This repository also contains legacy implementations of various useful mathematical operators in Python, found in the `pymath` directory. This was the original content before the "Project Compliance Guardian" was developed.

**PyMath Functions:**
- factorial(n): Calculates the factorial of a non-negative integer.
- fibonacci(n): Calculates the nth Fibonacci number.
- is_prime(n): Checks if a number is a prime number.
- gcd(a, b): Calculates the Greatest Common Divisor (GCD) of two integers.
- lcm(a, b): Calculates the Least Common Multiple (LCM) of two integers.
- is_perfect_square(n): Checks if a number is a perfect square.

**To use PyMath functions:**
Copy `pymath/lib/math.py` into your project or install it as a module.

```python
from pymath.lib.math import factorial, is_prime
# Example: print(factorial(5))
```

## Contributing
We welcome contributions to improve and expand Project Compliance Guardian. Please fork the repository, create a new branch, make your changes (including tests if applicable), and submit a pull request.

## License
This project is released under the MIT License. See the LICENSE file for details.
