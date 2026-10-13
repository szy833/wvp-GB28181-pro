"""Local Git fixtures; never stage or commit in the project repository."""
import contextlib
import io
import json
from pathlib import Path
import subprocess
import tempfile
import unittest

import batch_local_commits as batch


ROOT = Path(__file__).resolve().parents[1]


class BatchCommitTest(unittest.TestCase):
    def setUp(self):
        fixture_root = ROOT / "target/batch-commit-tests"
        fixture_root.mkdir(parents=True, exist_ok=True)
        self.temp = tempfile.TemporaryDirectory(prefix="fixture-", dir=str(fixture_root))
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name)
        self.git("init", "-q")
        self.git("config", "user.name", "Batch Fixture")
        self.git("config", "user.email", "fixture@example.invalid")
        self.git("config", "commit.gpgsign", "false")
        self.write("src/existing.java", "original\n")
        self.write("src/deleted.java", "delete me\n")
        self.git("add", "--", "src/existing.java", "src/deleted.java")
        self.git("commit", "-qm", "测试基线")
        self.base = self.git("rev-parse", "HEAD").strip()
        self.write("src/existing.java", "changed\n")
        (self.repo / "src/deleted.java").unlink()
        self.write("tools/gb28181-load-test/example.go", "package example\n")
        self.write("docs/说明.md", "说明\n")
        self.manifest = self.repo / "target/manifest.json"
        self.data = {
            "head": self.base,
            "files": [
                {"path": "src/existing.java", "status": " M", "group": batch.GROUPS[0][0]},
                {"path": "src/deleted.java", "status": " D", "group": batch.GROUPS[0][0]},
                {"path": "tools/gb28181-load-test/example.go", "status": "??", "group": batch.GROUPS[1][0]},
                {"path": "docs/说明.md", "status": "??", "group": batch.GROUPS[2][0]},
                {"path": "tools/application-dev.yml", "status": "??", "group": "排除：本地配置与凭据"},
            ],
        }
        self.save_manifest()

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args], stderr=subprocess.PIPE).decode("utf-8")

    def write(self, path, content):
        target = self.repo / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")

    def save_manifest(self):
        self.write("target/manifest.json", json.dumps(self.data, ensure_ascii=False))

    def run_batch(self, **kwargs):
        with contextlib.redirect_stdout(io.StringIO()) as output:
            result = batch.run(self.repo, kwargs.pop("manifest", self.manifest), **kwargs)
        return result, output.getvalue()

    def assert_untouched(self):
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.base)
        self.assertEqual(self.git("diff", "--cached", "--name-only"), "")

    def test_preview_is_read_only_and_excludes_artifacts(self):
        self.write("tools/application-dev.yml", "local credential configuration\n")
        self.write("tools/gb28181-load-test/reports/local.json", "local report\n")
        _, output = self.run_batch()
        self.assertIn(batch.GROUPS[0][1], output)
        self.assertIn("src/deleted.java", output)
        self.assertNotIn("local credential configuration", output)
        self.assert_untouched()

    def test_execute_creates_three_exact_chinese_commits(self):
        self.run_batch(execute=True)
        commits = self.git("rev-list", "--reverse", self.base + "..HEAD").splitlines()
        self.assertEqual(len(commits), 3)
        for commit, (group, subject) in zip(commits, batch.GROUPS):
            self.assertEqual(self.git("show", "-s", "--format=%s", commit).strip(), subject)
            actual = set(filter(None, self.git("diff-tree", "--no-commit-id", "--name-only", "-r", "-z", commit).split("\0")))
            expected = {row["path"] for row in self.data["files"] if row["group"] == group}
            self.assertEqual(actual, expected)
        self.assertEqual(self.git("diff", "--cached", "--name-only"), "")
        _, output = self.run_batch(resume=True)
        self.assertIn("没有剩余", output)

    def test_existing_index_is_rejected(self):
        self.git("add", "--", "src/existing.java")
        with self.assertRaisesRegex(batch.PlanError, "暂存区"):
            self.run_batch(execute=True)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.base)
        self.assertEqual(self.git("diff", "--cached", "--name-only").strip(), "src/existing.java")

    def test_unlisted_source_is_rejected(self):
        self.write("src/unlisted.java", "extra\n")
        with self.assertRaisesRegex(batch.PlanError, "清单外"):
            self.run_batch(execute=True)
        self.assert_untouched()

    def test_protected_or_invalid_path_in_candidate_group_is_rejected(self):
        for path in ("t.cap", "../escape", "/absolute", "src/*.java", "src/(secret).private.yml",
                     "tools/application-dev.yml", "tools/gb28181-load-test/reports/a.json", "target/data.json"):
            with self.subTest(path=path):
                self.data["files"][0]["path"] = path
                self.save_manifest()
                with self.assertRaises(batch.PlanError):
                    self.run_batch(execute=True)
                self.assert_untouched()

    def test_symlink_is_rejected_without_following(self):
        (self.repo / "src/existing.java").unlink()
        (self.repo / "src/existing.java").symlink_to("deleted.java")
        with self.assertRaisesRegex(batch.PlanError, "符号链接"):
            self.run_batch(execute=True)
        self.assert_untouched()

    def test_status_drift_is_rejected(self):
        self.write("src/deleted.java", "changed instead of deleted\n")
        with self.assertRaisesRegex(batch.PlanError, "状态"):
            self.run_batch(execute=True)
        self.assert_untouched()

    def test_stale_head_is_rejected(self):
        self.git("commit", "--allow-empty", "-qm", "无关提交")
        with self.assertRaisesRegex(batch.PlanError, "HEAD"):
            self.run_batch(execute=True)
        with self.assertRaisesRegex(batch.PlanError, "续跑"):
            self.run_batch(execute=True, resume=True)

    def test_resume_after_first_group(self):
        paths = [row["path"] for row in self.data["files"] if row["group"] == batch.GROUPS[0][0]]
        self.git("add", "-A", "--", *paths)
        self.git("commit", "-qm", batch.GROUPS[0][1])
        self.run_batch(execute=True, resume=True)
        self.assertEqual(len(self.git("rev-list", self.base + "..HEAD").splitlines()), 3)

    def test_expect_plan_rejects_content_changes(self):
        digest, _ = self.run_batch()
        self.write("src/existing.java", "another change\n")
        with self.assertRaisesRegex(batch.PlanError, "指纹"):
            self.run_batch(execute=True, expect_plan=digest)
        self.assert_untouched()

    def test_duplicate_manifest_entry_is_rejected(self):
        self.data["files"].append(self.data["files"][0])
        self.save_manifest()
        with self.assertRaisesRegex(batch.PlanError, "重复"):
            self.run_batch(execute=True)
        self.assert_untouched()

    def test_wrong_group_is_rejected(self):
        self.data["files"][0]["group"] = batch.GROUPS[2][0]
        self.save_manifest()
        with self.assertRaisesRegex(batch.PlanError, "分组"):
            self.run_batch(execute=True)
        self.assert_untouched()

    def test_java_media_namespace_is_not_artifact_directory(self):
        batch.validate_path(self.repo, "src/main/java/media/Node.java", batch.GROUPS[0][0])
        self.assertFalse(batch.excluded("src/main/java/media/Node.java"))
        self.assertTrue(batch.excluded("tools/gb28181-load-test/media/sample.ps"))
        self.assert_untouched()

    def test_expect_plan_allows_unchanged_plan(self):
        digest, _ = self.run_batch()
        self.run_batch(execute=True, expect_plan=digest)
        self.assertEqual(len(self.git("rev-list", self.base + "..HEAD").splitlines()), 3)

    def test_resume_rejects_correct_subject_with_wrong_files(self):
        self.git("add", "--", "src/existing.java")
        self.git("commit", "-qm", batch.GROUPS[0][1])
        with self.assertRaisesRegex(batch.PlanError, "续跑"):
            self.run_batch(resume=True)

    def test_protected_manifest_is_rejected_before_read(self):
        with self.assertRaisesRegex(batch.PlanError, "JSON"):
            self.run_batch(manifest="t.cap")


    def test_whitespace_errors_are_rejected_before_staging(self):
        self.write("src/existing.java", "trailing whitespace \n")
        with self.assertRaisesRegex(batch.PlanError, "diff --check"):
            self.run_batch(execute=True)
        self.assert_untouched()

    def test_commit_failure_keeps_index_without_reset(self):
        hook = self.repo / ".git/hooks/pre-commit"
        hook.write_text("#!/bin/sh\nexit 1\n", encoding="utf-8")
        hook.chmod(0o755)
        with self.assertRaisesRegex(batch.PlanError, "保留"):
            self.run_batch(execute=True)
        self.assertEqual(self.git("rev-parse", "HEAD").strip(), self.base)
        self.assertEqual(set(self.git("diff", "--cached", "--name-only").splitlines()),
                         {"src/existing.java", "src/deleted.java"})


if __name__ == "__main__":
    unittest.main()
