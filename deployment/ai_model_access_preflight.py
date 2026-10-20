"""Read-only model metadata preflight, at most two GETs, zero inference POSTs.

Only named OPENAI_API_KEY from an explicitly supplied local file is read.
Never prints the key, response body, headers, request IDs, or error messages.
"""
import argparse
import http.client
import json
from pathlib import Path
import re
import time

MODELS = ("gpt-5.6-terra", "text-embedding-3-small")


def read_key(path):
    found = []
    for line in Path(path).read_text(encoding="utf-8-sig").splitlines():
        match = re.fullmatch(r"\s*(?:export\s+)?OPENAI_API_KEY\s*=\s*(.*?)\s*", line)
        if match:
            value = match.group(1)
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            found.append(value)
    if len(found) != 1 or not found[0] or any(c.isspace() for c in found[0]):
        raise ValueError("KEY_MISSING_OR_AMBIGUOUS")
    return found[0]


def run(key, connection_factory=http.client.HTTPSConnection):
    result = {"scope": "MODEL_METADATA_ONLY", "llmCalls": 0, "embeddingCalls": 0,
              "metadataGets": 0, "automaticRetries": 0, "models": []}
    for model in MODELS:
        connection = connection_factory("api.openai.com", 443, timeout=15)
        item = {"model": model, "status": "INCONCLUSIVE"}
        start = time.monotonic()
        try:
            result["metadataGets"] += 1
            connection.request("GET", "/v1/models/" + model, headers={"Authorization": "Bearer " + key})
            response = connection.getresponse()
            item["httpStatus"] = response.status
            raw = response.read(131073)
            if len(raw) > 131072:
                raise ValueError()
            data = json.loads(raw)
            if response.status == 200 and isinstance(data, dict) and data.get("id") == model:
                item["status"] = "PASS"
            else:
                item["status"] = "BLOCKED"
                # Fixed public classifications only; never provider's raw message.
                item["reason"] = {401: "AUTHENTICATION_REJECTED", 403: "MODEL_ACCESS_DENIED",
                                  404: "MODEL_UNAVAILABLE", 429: "RATE_OR_QUOTA_BLOCKED"}.get(response.status, "METADATA_REJECTED")
        except Exception:
            item["status"] = "INCONCLUSIVE"
            item["reason"] = "METADATA_TRANSPORT_OR_FORMAT_UNCERTAIN"
        finally:
            connection.close()
            item["elapsedMs"] = round((time.monotonic() - start) * 1000)
        result["models"].append(item)
        if item["status"] != "PASS":
            result["status"] = "BLOCKED"
            return result
    result["status"] = "PASS"
    result["limitation"] = "METADATA_ACCESS_IS_NOT_INFERENCE_SUCCESS"
    return result


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--credentials-file", required=True)
    p.add_argument("--evidence-dir", required=True)
    p.add_argument("--execute-approved-metadata-check", action="store_true")
    args = p.parse_args()
    if not args.execute_approved_metadata_check:
        raise SystemExit("EXPLICIT_METADATA_APPROVAL_REQUIRED")
    folder = Path(args.evidence_dir)
    folder.mkdir(exist_ok=False)
    try:
        key = read_key(args.credentials_file)
    except Exception:
        result = {"status": "BLOCKED", "reason": "KEY_MISSING_OR_AMBIGUOUS", "metadataGets": 0,
                  "llmCalls": 0, "embeddingCalls": 0}
    else:
        result = run(key)
    (folder / "model-access.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps(result))


if __name__ == "__main__":
    main()
