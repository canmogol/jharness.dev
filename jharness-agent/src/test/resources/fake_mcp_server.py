# A tiny stdio MCP server for tests (spec 037): echo, add, and whoami (reads an env variable).
import json
import os
import sys

TOOLS = [
    {"name": "echo", "description": "Echoes the text.", "inputSchema": {"type": "object", "properties": {"text": {"type": "string"}}}},
    {"name": "add", "description": "Adds two numbers.", "inputSchema": {"type": "object", "properties": {"a": {"type": "number"}, "b": {"type": "number"}}}},
    {"name": "env", "description": "Shows FAKE_TOKEN's length.", "inputSchema": {"type": "object"}},
]

for line in sys.stdin:
    try:
        m = json.loads(line)
    except ValueError:
        continue
    if "id" not in m:
        continue
    method = m.get("method")
    if method == "initialize":
        result = {"protocolVersion": "2025-06-18", "capabilities": {"tools": {}}, "serverInfo": {"name": "fake", "version": "1"}}
    elif method == "tools/list":
        result = {"tools": TOOLS}
    elif method == "tools/call":
        name = m["params"]["name"]
        args = m["params"].get("arguments", {})
        if name == "echo":
            text = args.get("text", "")
        elif name == "add":
            text = str(args.get("a", 0) + args.get("b", 0))
        else:
            text = "token length " + str(len(os.environ.get("FAKE_TOKEN", "")))
        result = {"content": [{"type": "text", "text": text}], "isError": False}
    else:
        sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "error": {"code": -32601, "message": "unknown"}}) + "\n")
        sys.stdout.flush()
        continue
    sys.stdout.write(json.dumps({"jsonrpc": "2.0", "id": m["id"], "result": result}) + "\n")
    sys.stdout.flush()
