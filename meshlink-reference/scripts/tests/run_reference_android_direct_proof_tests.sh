#!/usr/bin/env bash
set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$script_dir"
python3 -W error::ResourceWarning -m unittest discover -s "$script_dir" -p 'test_reference_android_direct_proof.py'
