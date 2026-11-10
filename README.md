# SbEdge

One UI 状态栏左右边界的 LSPosed 模块（libxposed API 102），作用域只有 SystemUI。

One UI 的状态栏按挖孔类型选用不同的布局算法类（`IndicatorGardenAlgorithmNoCutout`、
`IndicatorGardenAlgorithmCenterCutout`、`IndicatorGardenAlgorithmSidelingCenterCutout`），
每个算法用 `calculateLeftPadding` / `calculateRightPadding` 算出内容到屏幕边缘的总距离，
其中包括系统在两侧留出的空白边。本模块直接替换这两个方法的返回值，
所以设为 0 时内容真正贴到屏幕边缘。用 overlay 去掉挖孔后（NoCutout 算法）同样有效。

## 设置

单位 dp，修改后重启 SystemUI 生效：

```sh
settings put global sbedge_left_dp 0
settings put global sbedge_right_dp 0
pkill -f com.android.systemui
```

- 不设置时默认 0。
- 设为负数（如 `-1`）表示这一侧保持系统原值。
- 恢复默认：`settings delete global sbedge_left_dp`、`settings delete global sbedge_right_dp`。

## 构建

推送到 GitHub 后，Actions 会自动编译，并发布一个 Release（`build-<编号>`），APK 可以直接在手机上下载。

`signing/release.jks` 是固定的签名密钥（密码 `sbedge`），保证以后的版本能直接覆盖安装。
仓库如果公开，任何人都能用这个密钥签名，只用于个人模块问题不大；介意的话可以换成 Secrets。

## 排查

LSPosed 管理器 → 日志，搜索 `SbEdge`：

- `hooked ... calculateLeftPadding()`：hook 成功。
- `IndicatorGardenAlgorithmNoCutout.calculateLeftPadding: 88 -> 0`：第一次生效时记录的原值和新值。
- `not found` / `hooked 0 method(s)`：这个 One UI 版本的类名或方法名不一样，日志里会列出各算法类的全部方法名，可以据此调整。
