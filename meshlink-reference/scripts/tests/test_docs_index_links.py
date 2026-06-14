from __future__ import annotations

import unittest
from pathlib import Path


class DocsIndexLinksTests(unittest.TestCase):
    def test_docs_index_points_to_the_direct_proof_digest(self) -> None:
        # Arrange
        docs_index = Path("docs/README.md")
        digest = Path("docs/reference/android-direct-proof-matrix-result.md")

        # Act
        docs_index_text = docs_index.read_text(encoding="utf-8")
        digest_text = digest.read_text(encoding="utf-8")

        # Assert
        self.assertIn("Android direct-proof matrix result", docs_index_text)
        self.assertIn("canonical 45s rerun summary", docs_index_text)
        self.assertIn("Android direct-proof matrix result", digest_text)
        self.assertIn("45s fail-fast rerun", digest_text)


if __name__ == "__main__":
    unittest.main()
