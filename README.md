# ChickenDupe (Folia)

Minecraft 鸡刷插件，支持 **Folia / Paper 1.21+**。

## 玩法

1. 手持物品，右键成年鸡 → 消耗 **1 级经验** 绑定（经验不足会提示）。
2. 绑定成功后**立刻掉落 1 个**，之后每 **5 分钟**掉落 1 个，**无论鸡所在区块是否加载**。
3. 鸡死亡后绑定自动清除。
4. `/dupe`：手持物品，消耗 **1/4 级经验** 直接复制，每日无限次。

## 配置 `config.yml`

| 项 | 默认 | 说明 |
| --- | --- | --- |
| DropInterval | 300 | 掉落间隔（秒） |
| DropAmount | 1 | 每次掉落数量 |
| BindCostLevels | 1 | 绑定消耗的经验等级 |
| CopyCostFraction | 0.25 | /dupe 消耗当前等级升级所需经验的比例 |
| CopyWholeStack | true | /dupe 复制整组还是 1 个 |

## 数据

SQLite：`plugins/ChickenDupe/chickendupe.db`（表 `bindings`）。

## 构建

```
./gradlew build
```

产物：`build/libs/ChickenDupe-<version>.jar`（需要 JDK 21）。

## 许可证

MIT
