#!/usr/bin/env python3
"""Preview or create local Chinese commits from a reviewed path manifest."""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import subprocess
import sys


GROUPS = (
    ("01-WVP统一生命周期与迁移", "统一点播会话生命周期并完善代次隔离与有界清理"),
    ("02-压测工具累计修复", "完善压测媒体时间轴、协议终止及发送错误分类报告"),
    ("03-设计验收与提交记录", "补充生命周期部署验收、阶梯压测与批量提交说明"),
)
DEFAULT_MANIFEST = "docs/2026-10-12-未提交修改文件清单.json"
COMMIT_TOOLS = {"tools/batch_local_commits.py", "tools/test_batch_local_commits.py"}
BLOCKED_DIRS = {".git", "target", "__pycache__", "node_modules"}
ARTIFACT_DIRS = {"bin", "media", "reports"}
BLOCKED_SUFFIXES = {".cap", ".pcap", ".pcapng", ".dump", ".bak", ".pem", ".key", ".p12", ".pfx", ".jks"}
LOCAL_CONFIGS = {"tools/application-dev.yml", "tools/gb28181-load-test/configs/test-10.yaml"}


class PlanError(Exception):
    pass


def excluded(path):
    parts = PurePosixPath(path).parts
    return (path in LOCAL_CONFIGS or ".private." in path or
            bool(BLOCKED_DIRS.intersection(parts)) or
            (bool(parts) and parts[0] in ARTIFACT_DIRS) or
            (parts[:2] == ("tools", "gb28181-load-test") and len(parts) > 2 and parts[2] in ARTIFACT_DIRS) or
            PurePosixPath(path).suffix.lower() in BLOCKED_SUFFIXES)


def validate_path(repo, path, group):
    if (not isinstance(path, str) or not path or
            any(c in path for c in "\x00\r\n\\*?[]:") or
            path.startswith("/") or any(p in ("", ".", "..") for p in path.split("/")) or excluded(path)):
        raise PlanError("清单含禁止提交或不安全路径，未操作该文件。")
    prefixes = {GROUPS[0][0]: ("src/", "数据库/"),
                GROUPS[1][0]: ("tools/gb28181-load-test/",), GROUPS[2][0]: ("docs/",)}
    if not path.startswith(prefixes[group]) and not (group == GROUPS[2][0] and path in COMMIT_TOOLS):
        raise PlanError("文件分组不符合允许的目录边界：" + path)
    current = repo
    for component in path.split("/"):
        current = current / component
        if current.is_symlink():
            raise PlanError("拒绝候选文件或父目录中的符号链接：" + path)
    if current.exists() and not current.is_file():
        raise PlanError("候选路径必须是单个文件：" + path)


class Repository:
    def __init__(self, root):
        self.root = Path(root).resolve()
        overrides = {"GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR",
                     "GIT_OBJECT_DIRECTORY", "GIT_ALTERNATE_OBJECT_DIRECTORIES"}
        if any(os.environ.get(name) for name in overrides):
            raise PlanError("检测到 Git 路径覆盖环境变量，停止以防操作仓库外的索引或对象。")
        self.env = dict(os.environ, GIT_OPTIONAL_LOCKS="0")

    def git(self, *args):
        result = subprocess.run(["git", "-C", str(self.root), *args], env=self.env,
                                stdout=subprocess.PIPE, stderr=subprocess.PIPE)
        if result.returncode:
            # Git/hook output can contain file contents or deployment secrets.
            raise PlanError("Git 操作失败（退出码 {}）：git {}。未自动回滚；若有暂存则保留，请检查。".format(
                result.returncode, args[0]))
        return result.stdout.decode("utf-8")

    def names(self, *args):
        return set(filter(None, self.git(*args).split("\0")))

    def status(self):
        raw = self.git("status", "--porcelain=v1", "-z", "--untracked-files=all", "--",
                       ".", ":(exclude)t.cap", ":(glob,exclude)**/t.cap")
        rows = {}
        entries = iter(raw.split("\0"))
        for entry in entries:
            if not entry:
                continue
            status, path = entry[:2], entry[3:]
            rows[path] = status
            if "R" in status or "C" in status:
                previous = next(entries)
                rows[previous] = status
        return rows

    def require_empty_index(self):
        if self.names("diff", "--cached", "--name-only", "-z"):
            raise PlanError("暂存区已有内容，停止；请先人工检查、提交或取消暂存，不会自动处理。")

    def check_environment(self):
        if Path(self.git("rev-parse", "--show-toplevel").strip()).resolve() != self.root:
            raise PlanError("必须在独立的项目仓库根目录操作。")
        git_dir = Path(self.git("rev-parse", "--absolute-git-dir").strip()).resolve()
        if self.root not in git_dir.parents:
            raise PlanError("Git 元数据位于仓库外，不能在本项目目录限制下操作。")
        for name in ("MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "rebase-merge", "rebase-apply", "sequencer", "index.lock"):
            if (git_dir / name).exists():
                raise PlanError("存在未结束的 Git 操作或索引锁：" + name)
        if self.names("ls-files", "--unmerged", "-z"):
            raise PlanError("存在未解决的合并冲突。")
        self.require_empty_index()


def literal_paths(paths):
    return [":(literal)" + path for path in paths]


def load_manifest(repo, manifest):
    manifest = Path(manifest)
    if manifest.suffix != ".json" or ".private." in str(manifest):
        raise PlanError("只允许读取仓库内非私有的 JSON 清单。")
    if not manifest.is_absolute():
        manifest = repo.root / manifest
    if repo.root not in manifest.resolve().parents:
        raise PlanError("清单必须位于仓库内。")
    current = manifest
    while current != repo.root:
        if current.is_symlink():
            raise PlanError("清单路径不能包含符号链接。")
        current = current.parent
    data = json.loads(manifest.read_text(encoding="utf-8"))
    base = data.get("head", "")
    if not re.fullmatch(r"[0-9a-f]{40}|[0-9a-f]{64}", base):
        raise PlanError("清单 HEAD 无效。")
    groups = {key: [] for key, _ in GROUPS}
    statuses = {}
    for row in data["files"]:
        group = row.get("group", "")
        if group not in groups:
            if group.startswith("排除："):
                continue
            raise PlanError("清单存在待人工确认或未知分组。")
        path = row["path"]
        validate_path(repo.root, path, group)
        if path in statuses:
            raise PlanError("清单含重复候选路径：" + path)
        if row["status"] not in (" M", " D", "??"):
            raise PlanError("仅支持未暂存修改、删除和新增；重命名须人工处理：" + path)
        statuses[path] = row["status"]
        groups[group].append(path)
    if any(not members for members in groups.values()):
        raise PlanError("三组候选文件均不能为空。")
    return base, groups, statuses


def completed_groups(repo, base, groups, resume):
    head = repo.git("rev-parse", "HEAD").strip()
    if head == base:
        return 0
    if not resume:
        raise PlanError("当前 HEAD 与清单基线不同；仅脚本已完成的前序提交可用 --resume 校验续跑。")
    try:
        repo.git("merge-base", "--is-ancestor", base, head)
        commits = repo.git("rev-list", "--reverse", base + ".." + head).splitlines()
        if len(commits) > len(GROUPS):
            raise PlanError("提交数量超出计划。")
        parent = base
        for commit, (key, subject) in zip(commits, GROUPS):
            if repo.git("show", "-s", "--format=%P", commit).strip() != parent:
                raise PlanError("不是连续单父提交。")
            if repo.git("show", "-s", "--format=%s", commit).strip() != subject:
                raise PlanError("提交标题不同。")
            if repo.names("diff-tree", "--no-commit-id", "--name-only", "-r", "-z", commit) != set(groups[key]):
                raise PlanError("提交文件集合不同。")
            parent = commit
        return len(commits)
    except PlanError as error:
        raise PlanError("续跑校验失败；保留现有提交，请人工确认：" + str(error)) from error


def snapshot(repo, paths):
    result = {}
    for path in paths:
        target = repo.root / path
        for parent in (target, *target.parents):
            if parent == repo.root:
                break
            if parent.is_symlink():
                raise PlanError("执行期间出现符号链接，停止：" + path)
        if not target.exists():
            result[path] = None
        else:
            result[path] = (repo.git("hash-object", "--", path).strip(), target.stat().st_mode & 0o777)
    return result


def run(root, manifest, execute=False, resume=False, expect_plan=None):
    repo = Repository(root)
    repo.check_environment()
    base, groups, statuses = load_manifest(repo, manifest)
    done = completed_groups(repo, base, groups, resume)
    remaining = [(key, subject) for key, subject in GROUPS[done:]]
    paths = [path for key, _ in remaining for path in groups[key]]
    status = repo.status()
    extra = sorted(path for path in status if path not in paths and not excluded(path))
    if extra:
        raise PlanError("存在清单外或已提交组的新改动，需先更新/检查清单：" + ", ".join(extra[:12]))
    for path in paths:
        if status.get(path) != statuses[path]:
            raise PlanError("文件状态与清单不同（可能已恢复、删除或被暂存）：" + path)
    if paths:
        try:
            repo.git("diff", "--check", "--", *literal_paths(paths))
        except PlanError as error:
            raise PlanError("git diff --check 未通过，请先检查候选文件格式；未暂存。") from error
    initial = snapshot(repo, paths)
    digest = hashlib.sha256(json.dumps({"base": base, "head": repo.git("rev-parse", "HEAD").strip(),
                                       "groups": groups, "files": initial}, ensure_ascii=False, sort_keys=True).encode("utf-8")).hexdigest()
    if expect_plan and expect_plan != digest:
        raise PlanError("计划指纹变化；文件内容或提交计划已改变，请重新预览审核。")
    print("本地批量提交计划（不会推送）：已完成 {}/3 组".format(done))
    for key, subject in remaining:
        print("\n{}：{}（{} 个文件）".format(key, subject, len(groups[key])))
        for path in groups[key]:
            print("  {} {}".format(statuses[path], path))
    print("\n计划指纹：" + digest)
    if not remaining:
        print("没有剩余提交；工作区中排除项仍保留。")
        return digest
    if not execute:
        print("仅预览，未暂存或创建提交。审核差异后使用 --execute；可附 --expect-plan <指纹>。")
        return digest
    repo.git("var", "GIT_AUTHOR_IDENT")
    repo.git("var", "GIT_COMMITTER_IDENT")
    expected_head = repo.git("rev-parse", "HEAD").strip()
    for key, subject in remaining:
        repo.require_empty_index()
        if repo.git("rev-parse", "HEAD").strip() != expected_head or snapshot(repo, paths) != initial:
            raise PlanError("执行期间 HEAD 或候选文件变化；已完成提交保留，停止后续操作。")
        members = groups[key]
        repo.git("add", "-A", "--", *literal_paths(members))
        if repo.names("diff", "--cached", "--name-only", "-z") != set(members):
            raise PlanError("暂存文件与本组计划不同；保留暂存，停止。")
        repo.git("diff", "--cached", "--check", "--", *literal_paths(members))
        if repo.names("diff", "--name-only", "-z", "--", *literal_paths(members)) or snapshot(repo, paths) != initial:
            raise PlanError("暂存期间候选文件变化；保留暂存，停止。")
        indexed = {}
        for entry in filter(None, repo.git("ls-files", "--stage", "-z", "--", *literal_paths(members)).split("\0")):
            metadata, path = entry.split("\t", 1)
            mode, oid, stage = metadata.split()
            if stage != "0" or mode not in ("100644", "100755"):
                raise PlanError("暂存区存在非普通文件或未合并项；保留暂存，停止。")
            indexed[path] = oid
        if any(indexed.get(path) != (initial[path][0] if initial[path] else None) for path in members):
            raise PlanError("暂存内容与预览快照不同；保留暂存，停止。")
        expected_tree = repo.git("write-tree").strip()
        repo.git("commit", "-m", subject)
        commit = repo.git("rev-parse", "HEAD").strip()
        print("已创建本地提交：{} {}".format(commit, subject))
        if (repo.git("rev-parse", "HEAD^{tree}").strip() != expected_tree or
                repo.git("show", "-s", "--format=%P", commit).strip() != expected_head or
                repo.git("show", "-s", "--format=%s", commit).strip() != subject):
            raise PlanError("提交内容、父节点或标题被并发操作/hook 改变；提交保留，停止。")
        repo.require_empty_index()
        expected_head = commit
    print("\n三组本地提交已完成，未推送。使用 git log -3 --oneline 检查真实记录。")
    return digest


def main():
    parser = argparse.ArgumentParser(description="默认只预览；显式执行本地中文批量提交，永不推送。")
    parser.add_argument("--manifest", default=DEFAULT_MANIFEST, help="仓库内的 JSON 候选清单")
    parser.add_argument("--execute", action="store_true", help="执行暂存并创建三组本地提交")
    parser.add_argument("--resume", action="store_true", help="验证已完成的前序提交，再预览/执行剩余组")
    parser.add_argument("--expect-plan", help="要求计划与已审核的预览指纹一致")
    args = parser.parse_args()
    try:
        run(Path(__file__).resolve().parents[1], args.manifest, args.execute, args.resume, args.expect_plan)
    except (PlanError, OSError, ValueError, KeyError, TypeError) as error:
        print("停止：{}".format(error), file=sys.stderr)
        print("不会 reset、撤销提交、修改排除项或 push。请检查 git status 后人工处理。", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
