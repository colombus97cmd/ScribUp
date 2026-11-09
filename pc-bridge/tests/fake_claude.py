"""Faux CLI `claude` pour les tests : répond en streaming au format stream-json."""
import json
import sys

args = sys.argv[1:]
prompt = sys.stdin.read()
system = open(args[args.index("--system-prompt-file") + 1], encoding="utf-8").read()
model = args[args.index("--model") + 1] if "--model" in args else "-"
if "ERREUR" in prompt:
    print(json.dumps({"type": "result", "is_error": True, "result": "Invalid API key · Please run /login"}))
    sys.exit(1)
answer = f"[{model}|{system}] {prompt.splitlines()[-1]}"
print(json.dumps({"type": "system", "subtype": "init"}))
for i in range(0, len(answer), 5):
    print(json.dumps({"type": "stream_event", "event": {"type": "content_block_delta",
                      "delta": {"type": "text_delta", "text": answer[i:i + 5]}}}), flush=True)
print(json.dumps({"type": "result", "is_error": False, "result": answer}))
