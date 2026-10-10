"""
End-to-end tests (tests/e2e) against the local stack with the test inventory, through a headless client.

  uv run e2e [--fresh] [pytest options, e.g. -k fpv]
"""

import sys

import pytest


def main():
    sys.exit(pytest.main(["tests/e2e", *sys.argv[1:]]))
