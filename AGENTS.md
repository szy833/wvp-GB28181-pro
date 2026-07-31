# Repository Instructions

- Only read, modify, create, delete, or execute project-related files and commands within `/data/shizy/wvp-GB28181-pro`.
- Do not operate on files or directories outside `/data/shizy/wvp-GB28181-pro`.

## 快速了解项目

首次处理任务前先阅读：

- `docs/项目结构与开发协作指南.md`：项目结构、GB28181 主流程、排障路径、测试和多 agent 协作边界。
- `docs/GB28181点播InviteInfo状态与媒体确认流程.md`：点播中 InviteInfo、SIP 200 OK、RTP 资源和 ZLM 媒体确认的时序。
- `docs/sip-invite-session-manager-usage.md`：SipInviteSessionManager 的调用方、Redis V2 索引、TTL 和迁移规则。

修改前后执行 `git status --short`，保留用户已有改动；不要删除、修改或提交 `t.cap`。

需要真实环境集成测试的项目及其风险统一记录到 `docs` 文档中，后续集中安排和执行。
- 使用中文进行 git 提交。
