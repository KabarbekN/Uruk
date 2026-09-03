import os
from pathlib import Path
import stat
import sys
import tempfile
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from container_smoke import prepare_output_directory


class SmokePermissionsTests(unittest.TestCase):
    def test_only_fresh_output_permissions_are_changed(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            workspace = root / "workspace"
            workspace.mkdir()
            source = workspace / "source.sql"
            source.write_text("SELECT 1;", encoding="utf-8")
            before = {path: path.stat().st_mode for path in (root, workspace, source)}
            output = prepare_output_directory(root, 1)
            self.assertEqual(output.parent, root)
            if os.name == "posix":
                self.assertEqual(stat.S_IMODE(output.stat().st_mode), 0o777)
            self.assertEqual(before, {path: path.stat().st_mode for path in before})
            self.assertEqual(source.read_text(encoding="utf-8"), "SELECT 1;")

    def test_existing_output_is_never_chmodded(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            output = root / "output-1"
            output.mkdir()
            before = output.stat().st_mode
            with self.assertRaises(FileExistsError):
                prepare_output_directory(root, 1)
            self.assertEqual(output.stat().st_mode, before)


if __name__ == "__main__":
    unittest.main()
