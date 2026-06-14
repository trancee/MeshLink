from __future__ import annotations

import unittest
from pathlib import Path


class ReleaseStatusDocsTests(unittest.TestCase):
    def test_landing_docs_describe_the_shipped_android_crypto_fallback_posture(self) -> None:
        # Arrange
        readme = Path("meshlink-reference/README.md")
        release_status = Path("docs/reference/release-status.md")
        proof_plan = Path("docs/rfcs/crypto/m011-android-crypto-fallback-proof.md")

        # Act
        readme_text = readme.read_text(encoding="utf-8")
        release_status_text = release_status.read_text(encoding="utf-8")
        proof_plan_text = proof_plan.read_text(encoding="utf-8")

        # Assert
        self.assertIn("the in-repo fallback is shipped", readme_text)
        self.assertIn("validation on the lowest Android tiers is tracked in the M011 proof plan", readme_text)
        self.assertNotIn("open validation item", readme_text)
        self.assertIn("shipped fallback plus the retained validation posture", release_status_text)
        self.assertIn("Android 9 / SDK 28 hardware pass", release_status_text)
        self.assertIn("API 26 emulator proof is still blocked", release_status_text)
        self.assertIn("implementation shipped; retained validation follow-up still open", proof_plan_text)
        self.assertIn("API 28", proof_plan_text)
        self.assertIn(
            "emulator proof remains blocked by local emulator stability",
            proof_plan_text,
        )


if __name__ == "__main__":
    unittest.main()
