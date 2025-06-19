# kyu_module.py
import joblib
import pandas as pd

# Load model globally
RF_MODEL = joblib.load('RF_model.joblib')

def predict_kyu_score(email, domain, purpose):
    # In production, load pre-fitted LabelEncoders instead of fitting here
    from sklearn.preprocessing import LabelEncoder

    le_email = LabelEncoder()
    le_domain = LabelEncoder()
    le_purpose = LabelEncoder()

    df = pd.DataFrame([{
        'Email': email,
        'Domain': domain,
        'Purpose': purpose
    }])

    df['Email'] = le_email.fit_transform(df['Email'])
    df['Domain'] = le_domain.fit_transform(df['Domain'])
    df['Purpose'] = le_purpose.fit_transform(df['Purpose'])

    return RF_MODEL.predict(df)[0]
