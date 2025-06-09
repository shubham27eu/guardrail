# Data Processor Project

This project provides tools for data anonymization and processing. It generates two primary executable JARs:
- `data-processor-main.jar`: The main application. It uses compile-time embedded paths for the primary data and attributes files. It also bundles and executes Python scripts at runtime to generate intermediate data files (`Sensitivity_Results.xlsx`, `KYU Score.xlsx`) which are then consumed. Runtime arguments are needed for the user ID (for KYU score lookup) and the specific SQL query to execute.
- `data-processor-mock-runner.jar`: A test runner that takes all its required file paths as command-line arguments.

## Prerequisites

- **Java Development Kit (JDK)**: Version 17 or higher.
- **Maven**: For building the project (version 3.x).
- **Python 3**: Must be installed and accessible. The Java application will attempt to run Python scripts using `python3` (by default) or a Python interpreter found in a local `.venv` virtual environment.

### Python Environment Setup (Recommended)

It is **strongly recommended** to use a Python virtual environment to manage dependencies for this project and avoid conflicts with system-wide packages.

1.  **Create a virtual environment**:
    Navigate to your project's root directory (where you will run the Java JAR from) and run:
    for me:
    cd /Users/shubham/Desktop/java-anonymization

    ```bash
    python3 -m venv .venv
    ```

2.  **Activate the virtual environment**:
    -   On macOS and Linux:
        ```bash
        source .venv/bin/activate
        ```
    -   On Windows (Git Bash or similar):
        ```bash
        source .venv/Scripts/activate
        ```
    -   On Windows (Command Prompt):
        ```bash
        .venv\Scripts\activate.bat
        ```
    -   On Windows (PowerShell):
        ```bash
        .venv\Scripts\Activate.ps1
        ```
    You should see the name of the virtual environment (e.g., `(.venv)`) in your shell prompt after activation.

3.  **Install required Python packages**:
    Once the virtual environment is activated, install the necessary libraries:
    ```bash
    pip install pandas openpyxl
    ```
    (These are for the dummy scripts provided. Your actual Python ML scripts might have other dependencies.)

4.  **Running the Java Application with Virtual Environment**:
    - If a virtual environment named `.venv` is present in the directory where `data-processor-main.jar` is run, and it contains a Python interpreter at `.venv/bin/python3` (for Linux/macOS) or `.venv/Scripts/python.exe` (for Windows), the Java application will automatically attempt to use that interpreter to run the bundled Python scripts.
    - Otherwise, it will fall back to using the system-wide `python3` command (or `python` if `Main.java`'s `ProcessBuilder` configuration is changed).
    - **Important**: Ensure you run the `java -jar ...` command from the same directory where the `.venv` folder is located (typically your project root).

## Building and Running the Project

### Building the JARs

To build the executable JAR files, navigate to the project's root directory (where `pom.xml` is located).

The build process for `data-processor-main.jar`:
- Embeds paths for the main data CSV and attributes CSV file into the JAR from Maven properties provided at build time.
- Bundles Python scripts located in `src/main/resources/scripts/` into the JAR. These scripts are executed at runtime by the Java application.

Run the following Maven command, replacing placeholder paths with actual paths to your files:
```bash
mvn clean package -Ddata.df.path=./path/to/your/Data_2019-20.csv \
                  -Dattributes.path=./path/to/your/Attributes.csv
```example 
mvn clean package -Ddata.df.path=Data_2019-20.csv \
                  -Dattributes.path=Attributes.csv
```

**Explanation of Build-Time Properties for `data-processor-main.jar`**:
- `-Ddata.df.path`: Specifies the path to the main data CSV file (e.g., `Data_2019-20.csv`). This path is embedded in the JAR.
- `-Dattributes.path`: Specifies the path to the attributes definition CSV file (e.g., `Attributes.csv`). This path is embedded in the JAR.

If these properties are not provided during the build, they will default to `"path-not-set"`, which will likely cause runtime errors when `data-processor-main.jar` is executed.

This command will compile the code and package the application. The primary output for the main application will be `target/data-processor-main.jar`.
The `data-processor-mock-runner.jar` might also be built if its execution is enabled in the `pom.xml`.

### Running `data-processor-main.jar`

This JAR orchestrates the data processing:
1.  At runtime, it first extracts and executes the bundled Python scripts (`generate_sensitivity.py` and `generate_kyu_scores.py`). It will attempt to use Python from a local `.venv` directory if detected (see "Python Environment Setup" above), otherwise falling back to the system `python3`.
2.  These Python scripts are responsible for generating `Sensitivity_Results.xlsx` and `KYU Score.xlsx` in the current working directory (where the JAR is launched).
3.  The Java application then loads these generated Excel files, along with the main data CSV and attributes CSV (paths for these two were embedded at build time).
4.  Finally, it performs the anonymization based on the provided runtime arguments and loaded data.

**Prerequisites for Running**:
- Review the main "Prerequisites" section, especially the Python environment setup.

**Usage:**
```bash
java -jar target/data-processor-main.jar <user_id> "<sqlite_query>"
```

**Arguments:**
- `<user_id>`: The ID of the user. This ID is used to look up their KYU score from the `KYU Score.xlsx` file generated by the Python script.
- `<sqlite_query>`: The full SQLite query string to execute on the data loaded from the CSV file (specified by `data.df.path` at build time). **Important**: Enclose the query in double quotes if it contains spaces or special shell characters. The table name within the query should typically be `data_df`.

**Example:**
```bash
java -jar target/data-processor-main.jar "2" "SELECT * FROM data_df WHERE "2" = 'Gadag'"```
Ensure you replace `"2"` with your desired user ID. The example query selects all columns for the row where column named "2" has the value 'Gadag'.

For more complex queries, especially those involving SQL `IN` clauses or other special characters, you might need more careful shell quoting. Here's an example demonstrating how to pass a query with an `IN` clause and quoted string literals, ensuring the column names (like `"2"`) are also correctly quoted for SQL if they are numeric or contain special characters:

```bash
java -jar target/data-processor-main.jar "2" 'SELECT "2", "18", "198" FROM data_df WHERE "2" IN ('"'"'Ramanagara'"'"', '"'"'Kolara'"'"')'
```
This example uses outer single quotes for the shell to treat the entire SQL query as one argument, and a common technique `'"'"'` to embed single quotes within the single-quoted string for the SQL string literals 'Ramanagara' and 'Kolara'. Alternatively, you can use outer double quotes and escape all inner double quotes and special characters as needed by your shell.

### Running `data-processor-mock-runner.jar`

This JAR runs a predefined mock data test scenario and requires all its necessary file paths as command-line arguments. It does *not* execute any Python scripts.
(Note: The build configuration for this JAR might be commented out in the current `pom.xml`. If needed, ensure its execution block in the `maven-shade-plugin` is active and correctly configured).

**Usage:**
```bash
java -jar target/data-processor-mock-runner.jar <data_df_path> <sensitivity_results_path> <kyu_score_path>
```
**Arguments:**
- `<data_df_path>`: Path to the main data CSV file.
- `<sensitivity_results_path>`: Path to the sensitivity results Excel file.
- `<kyu_score_path>`: Path to the KYU score Excel file.

**Example:**
```bash
java -jar target/data-processor-mock-runner.jar path/to/Data_2019-20.csv path/to/Sensitivity_Results.xlsx path/to/KYU_Score.xlsx
```

## Using as a Library / Dependency

The `data-processor-main.jar`, once built with its embedded paths for `data.df.path` and `attributes.path`, can potentially be included as a dependency in other Maven projects.

If the JAR is available locally, you might consider installing it to your local Maven repository (`mvn install`) or using a system-scoped dependency.

**Example of System-Scoped Dependency (for local JAR):**
```xml
<dependency>
    <groupId>com.example</groupId>
    <artifactId>data-processor-main</artifactId> <!-- This is the finalName from shade plugin -->
    <version>1.0-SNAPSHOT</version> <!-- Or the version built -->
    <scope>system</scope>
    <systemPath>${project.basedir}/path/to/data-processor-main.jar</systemPath>
</dependency>
```
**Note:** Using system scope has limitations and is generally not recommended. Installing the JAR to a local or remote Maven repository is a more robust approach.

When used as a library:
- The paths for `Data_2019-20.csv` and `Attributes.csv` would be used as configured during its build (embedded in `config.properties`).
- If you call `com.example.anonymization.Main.main()`, it will attempt to execute the bundled Python scripts. This means the consuming application's environment must also meet the Python 3 and library prerequisites (including having a `.venv` or system `python3` available, and necessary packages like `pandas` and `openpyxl` installed for that Python interpreter). The Python scripts must be able to write to the current working directory.
- Alternatively, you might refactor the core Java anonymization logic into separate, more easily callable public methods that do not directly invoke Python scripts, if you intend to manage the generation of `Sensitivity_Results.xlsx` and `KYU Score.xlsx` externally.
```
