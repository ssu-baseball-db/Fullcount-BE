"""Local Ollama adapter for the Fullcount summary API."""
import json
import os
import threading
import time
from http.server import BaseHTTPRequestHandler, HTTPServer
from urllib.request import Request, urlopen


OLLAMA_URL = os.getenv("OLLAMA_CHAT_URL", "http://127.0.0.1:11434/api/chat")
MODEL = "qwen2.5:1.5b"
# CPU 서버에서 모델 로딩과 생성에 10초 이상 걸릴 수 있다. 백엔드 대기(55초)와 Nginx(60초)보다 짧게 둔다.
OLLAMA_TIMEOUT = float(os.getenv("OLLAMA_TIMEOUT_SECONDS", "45"))


class SummaryHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        if self.path != "/summary":
            self.send_error(404)
            return
        try:
            if self.headers.get("Transfer-Encoding") == "chunked":
                chunks = []
                while size := int(self.rfile.readline().split(b";", 1)[0], 16):
                    chunks.append(self.rfile.read(size))
                    self.rfile.read(2)
                data = b"".join(chunks)
            else:
                data = self.rfile.read(int(self.headers["Content-Length"]))
            game = json.loads(data)["game"]
            away, home = game["awayTeam"]["teamName"], game["homeTeam"]["teamName"]
            facts = [f"최종 점수: {away} {game['awayScore']}-{game['homeScore']} {home}"]
            decisive = [event for event in game["events"] if event["description"].startswith("결승타")]
            highlights = decisive or [event for event in game["events"] if event["description"].startswith("홈런")]
            for event in highlights[:2]:
                if event["description"]:
                    half = "초" if event["half"] == "T" else "말"
                    facts.append(f"{event['inning']}회{half} {event['description']}")
            for key, metric, unit in (("battingStats", "runsBattedIn", "타점"),
                                      ("pitchingStats", "strikeouts", "탈삼진")):
                line = max(game[key], key=lambda row: row.get(metric) or 0)
                if line.get(metric) is not None:
                    facts.append(f"{line['playerName']} {line[metric]}{unit}")
            prompt = "다음 기록을 순서대로 한국어로 정리하세요. 이름, 이닝, 숫자를 바꾸거나 새로운 사실을 추가하지 마세요.\n" + "\n".join(facts)
            body = json.dumps({"model": MODEL, "stream": False, "keep_alive": -1,
                               "messages": [{"role": "user", "content": prompt}],
                               "options": {"temperature": 0, "num_predict": 180}}).encode()
            request = Request(OLLAMA_URL, data=body,
                              headers={"Content-Type": "application/json"})
            with urlopen(request, timeout=OLLAMA_TIMEOUT) as response:
                summary = json.load(response)["message"]["content"].strip()
            if not summary:
                raise ValueError("Ollama returned an empty summary")
        except (KeyError, OSError, TypeError, ValueError) as error:
            print(error, flush=True)
            self.send_error(502)
            return
        response_body = json.dumps({"summary": summary}, ensure_ascii=False).encode()
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(response_body)))
        self.end_headers()
        self.wfile.write(response_body)


def warm_up():
    """Load the model once at startup so the first summary request does not pay the loading time."""
    body = json.dumps({"model": MODEL, "messages": [], "keep_alive": -1}).encode()
    for _ in range(30):
        try:
            request = Request(OLLAMA_URL, data=body, headers={"Content-Type": "application/json"})
            with urlopen(request, timeout=180):
                print("Ollama model loaded", flush=True)
                return
        except OSError as error:
            print(f"Ollama warm-up retry: {error}", flush=True)
            time.sleep(10)


if __name__ == "__main__":
    threading.Thread(target=warm_up, daemon=True).start()
    HTTPServer((os.getenv("SUMMARY_BIND", "127.0.0.1"), 18086), SummaryHandler).serve_forever()
