## 改了什么

<!-- 一两句话说明这个 PR 解决了什么问题 -->

## 关联

<!-- Closes #123 -->

## 验证方式

<!-- 说明你怎么确认改动是对的：编译通过 / 单测 / 真机步骤 -->

- [ ] `./gradlew :app:assembleDebug` 通过
- [ ] `./gradlew test` 通过
- [ ] 真机验证过（说明机型与步骤）

## 注意

- 涉及容器脚本（`app/src/main/assets/scripts/`）的改动，请确认 `bash -n` 通过
- 涉及 AstrBot 接口的改动，请说明目标 AstrBot 版本（当前锁定 `>=4.29,<4.30`）
- 不要修改 `versionCode` / `versionName` 以外的发布配置，版本号由维护者统一升
