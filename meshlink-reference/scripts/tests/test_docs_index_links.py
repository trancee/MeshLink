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

    def test_docs_index_labels_the_m011_fallback_proof_plan(self) -> None:
        # Arrange
        docs_index = Path("docs/README.md")
        proof_plan = Path("docs/rfcs/crypto/m011-android-crypto-fallback-proof.md")

        # Act
        docs_index_text = docs_index.read_text(encoding="utf-8")
        proof_plan_text = proof_plan.read_text(encoding="utf-8")

        # Assert
        self.assertIn("M011 Android crypto fallback proof plan", docs_index_text)
        self.assertIn("shipped fallback plus retained validation posture", docs_index_text)
        self.assertIn("implementation shipped; retained validation follow-up still open", proof_plan_text)
        self.assertIn("Android 9 / SDK 28", proof_plan_text)


if __name__ == "__main__":
    unittest.main()
