# crew_agents/kyu_agent.py
from kyu_module import predict_kyu_score

class KYUAgent:
    def __init__(self, name="KYU Agent"):
        self.name = name

    def run(self, email, domain, purpose):
        return predict_kyu_score(email, domain, purpose)
