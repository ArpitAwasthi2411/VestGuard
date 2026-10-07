"""Turn the important lines of a failed build log into GitHub annotations,
so the error is visible on the run page (and through the public API) without opening logs."""
import re
import sys

path, title = sys.argv[1], sys.argv[2]
try:
    lines = open(path, errors="replace").read().splitlines()
except OSError:
    sys.exit(0)
pat = re.compile(r"(error:|\berror\b.*:|^e: |What went wrong|FAILURE|Exception|> Task .*FAILED|cannot find symbol|not found)", re.I)
picked, seen = [], set()
for i, l in enumerate(lines):
    if pat.search(l):
        for j in range(max(0, i - 1), min(len(lines), i + 6)):
            if j not in seen:
                seen.add(j)
                picked.append(lines[j])
if not picked:
    picked = lines[-60:]
chunks = [picked[i:i + 35] for i in range(0, min(len(picked), 35 * 9), 35)]
for n, c in enumerate(chunks, 1):
    msg = "\n".join(c).replace("%", "%25").replace("\r", "").replace("\n", "%0A")
    print(f"::error title={title} ({n}/{len(chunks)})::{msg}")
