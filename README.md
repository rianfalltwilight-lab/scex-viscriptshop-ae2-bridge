# SCEX ViScriptShop × AE2 Bridge

> 出自 **Space Creator EX（SCEX）服务器**的实际玩法需求，是一个由服务器需求驱动、AI 辅助实现并由维护者审阅的 **Vibe Coding 成果**。

一个面向 Minecraft 1.21.1 + NeoForge 的 ViScriptShop 与 Applied Energistics 2 兼容附属。它保留 ViScriptShop 的原生商店界面和规则，让玩家通过自己放置的 **ME 商店连接器**，直接用个人 AE2 网络完成商店交易。

本项目不是 ViScriptShop、Applied Energistics 2 或 NeoForge 的官方项目。

## 它解决什么问题

原生 ViScriptShop 主要读取玩家背包。大型整合包后期，玩家的物品和货币通常存放在 ME 网络里，交易前必须反复取出、放回。

本模组增加一层服务端权威的兼容逻辑：

- 出售或以物换物时，商店可以读取并扣除已绑定 ME 网络中的物品；
- 购买获得的物品可以直接写入 ME 网络；
- 原生商店数量显示会合并背包与 ME 中的可用数量；
- 可直接识别 ME 中的 SCEX 实体货币，无须先取出放进钱袋；
- 数字余额与 ME 实体货币可以共同付款，大面额硬币的余值会转成数字找零；
- ME 空间不足、物品不足或网络状态变化时，整笔交易失败，不会只扣款不发货；
- 没有可用连接器时自动退回 ViScriptShop 原生的背包交易，不扫描附近网络，也不使用管理员坐标。

## ME 商店连接器

- 物品/方块 ID：`scex_viscriptshop_ae2:me_shop_connector`
- 玩家把连接器放进自己拥有且已供电的 AE2 网络后，它会成为该玩家的商店链接；再次放置会替换旧链接。
- 链接保存在世界数据中，服务器重启后仍然存在。
- 连接器需要供电、在线并获得 AE 频道。
- 未激活、断电、离线或缺频道时只显示基础材质；激活后才显示慢速、平滑的彩色信号动画。
- 物品栏与手持状态使用未激活外观。

合成表：

```text
福鲁伊克斯水晶  绿宝石        福鲁伊克斯水晶
绿宝石            ME 接口       绿宝石
福鲁伊克斯水晶  绿宝石        福鲁伊克斯水晶
```

准确材料 ID：

- `ae2:fluix_crystal` × 4
- `minecraft:emerald` × 4
- `ae2:interface` × 1

## SCEX 货币兼容

当前默认面额来自 Space Creator EX 服务器：

| 物品 ID | 面额 |
|---|---:|
| `scex:coin_1` | 1C |
| `scex:coin_2` | 5C |
| `scex:coin_3` | 10C |

这些 ID 是 SCEX 整合包约定。其他服务器可以复用通用物品交易能力；若要使用自己的实体货币，需要修改对应映射并重新构建。

## 一致性与恢复

交易先模拟 ME 奖励写入，再进行实际扣款。ME 物品、背包、数字余额、实体硬币、经验和商店库存纳入同一事务边界，并使用落盘事务日志处理异常退出后的恢复，尽量避免重复发货、吞物或半完成交易。

原生 ViScriptShop 的阶段、库存、经验、命令和成功/失败事件语义保持不变；命令只会在核心交易持久提交后执行。

## 运行环境

| 组件 | 版本 |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | 21.1.248（21.1.x） |
| Java | 21 |
| ViScriptShop | 1.2.0 |
| Applied Energistics 2 | 19.2.17 |

模组必须同时安装在服务端和客户端。依赖版本在 `neoforge.mods.toml` 中被有意收紧；尚未声明兼容其他补丁版本。

## 构建

仓库包含用于复现 SCEX 基线的固定开发依赖：

```powershell
.\gradlew.bat clean build --no-build-cache
```

正式 JAR 输出到 `build/libs/`。`*-sources.jar` 和 `*-gametest-probe.jar` 不应放入生产 `mods` 目录。

## 当前发布与验证边界

当前版本为 **0.3.3**。

- 0.3.0 的 AE 交易、ME 货币和事务恢复通过 16 项 GameTest 与 4 阶段外部强杀恢复矩阵；
- 0.3.2 的慢速动画版本再次通过 16/16 GameTest；
- 0.3.3 新增“只有 AE 节点激活才显示彩色信号”的状态同步，完成干净编译与静态 JAR 检查；该小版本按当次发布指令没有另跑自动化或客户端测试。

详细证据见 [`docs/TEST-PLAN.md`](docs/TEST-PLAN.md)、[`docs/TRANSACTION-JOURNAL.md`](docs/TRANSACTION-JOURNAL.md) 和各版本发布说明。

## Vibe Coding 声明

这是一个明确标注的 Vibe Coding 项目：需求、玩法边界和验收反馈来自 Space Creator EX 服务器，代码与文档主要由 AI 编程代理协助完成，维护者负责方向、素材、上线决策和实际服务器反馈。

“Vibe Coding”不是对正确性的保证。涉及物品、货币和存档一致性的实现仍应经过代码审阅、可复现构建、自动化测试、备份与真实服务器验证；每个版本实际完成的验证范围都会如实记录。

## 许可证与上游

本项目以 [GPL-3.0-only](LICENSE) 发布。ViScriptShop、Applied Energistics 2、GuideME 和 LowDragLib2 均属于各自作者；本仓库没有修改或内嵌它们的类到正式 Bridge JAR。完整署名和固定依赖哈希见 [`NOTICE.md`](NOTICE.md) 与 [`docs/UPSTREAM-AUDIT.md`](docs/UPSTREAM-AUDIT.md)。
