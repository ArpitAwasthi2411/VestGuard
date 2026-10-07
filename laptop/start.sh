#!/usr/bin/env bash
# VestGuard: one-command start on Linux.
cd "$(dirname "$0")"

# 1) System Python already has aiohttp? Just use it.
if python3 -c "import aiohttp" 2>/dev/null; then
  exec python3 vestguard_server.py "$@"
fi

# 2) A working virtual environment from an earlier run?
if [ -x .venv/bin/python ] && .venv/bin/python -c "import aiohttp" 2>/dev/null; then
  exec .venv/bin/python vestguard_server.py "$@"
fi

# 3) Otherwise install aiohttp for this user and run.
rm -rf .venv
echo "Installing aiohttp..."
python3 -m pip install --user aiohttp 2>/dev/null \
  || python3 -m pip install --user --break-system-packages aiohttp \
  || { echo "Could not install aiohttp. Try:  sudo apt install python3-pip   then run ./start.sh again"; exit 1; }
exec python3 vestguard_server.py "$@"
