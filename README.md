# 回声 Echo 2.0

无感语音记忆 Agent —— 开机即在、从不打扰、重要的事不会忘。

纯原生 Android（无 H5 / 无 Gradle），端侧运行 Gemma 4 E2B + EmbeddingGemma 2 + 声纹识别，零云端依赖。

## 它做什么

```
你说话（对任何人、在任何场景）
  → 无感采集 → 声纹过滤（只认你）→ 转写 + LLM 纠错
  → 三层抽取 → 待办 / 记忆 / 提醒闹钟
  → 每晚 21:30 回顾 → 记忆整合
```

**你不操作任何东西**——说到的事到点自动响铃，别人的事标 [受托]，环境素材自动沉淀。

## 三层信息架构

| 层 | 来源 | 产出 | 展示 |
| --- | --- | --- | --- |
| L1 主线 | [主人] 自己的承诺 | 待办 + 精准提醒 | 首页 / 待办页 |
| L2 受托 | 他人指派给主人的任务 | 待办（标 [受托]） | 待办页 |
| L3 素材 | 环境中有价值的话题/观点 | 素材记忆（折叠展示） | 记忆页 / 回顾故事线 |
| 噪音 | 系统提示音等 | 丢弃 | 不入库 |

## 通话复用

通话中回声主动让出麦克风（StepOS 的 AI 通话录音独占），挂断后 root 读取 StepOS 的 RecorderDb 复用【它已生成的转写文本】直接出待办——全程不重录、不冲突。

## 架构（19 个源文件）

```
感知层   RecorderService · Vad · VoiceTile      常驻采集 + 攒帧 VAD + 快捷磁贴
理解层   GemmaEngine · Pipeline                 Gemma 4 E2B 转写 + 三层抽取 + LLM 纠错
记忆层   Store · MemoryEmbedder · Consolidator  结构化记忆 + 768 维向量 + 夜间整合
行动层   ReminderAlarm · Notifier · CallLink    精准闹钟 + 通知 + 通话复用
声纹层   SpeakerId                              CAM++ 192 维 · 阈值 0.62
UI 层    MainActivity · Ui · VoiceTile          亮色主题 · 声纹引导 · 磁贴
```

## 构建

无 Gradle，手动 aapt2 + javac + d8 链：

```bash
./build.sh
# 产物: echo.apk
```

模型文件（首次安装后 push 到 `/sdcard/Android/data/dev.echo.app/files/`）：

| 文件 | 大小 | 用途 |
| --- | --- | --- |
| gemma-4-E2B-it.litertlm | 2.4 GB | 转写 + 抽取 + 回顾 |
| embeddinggemma-2-740m.litertlm | 463 MB | 语义搜索（768 维） |
| campplus_zh.onnx | 28 MB | 声纹识别（192 维） |

运行时权限（adb 或首启引导自动申请）：
`RECORD_AUDIO` · `POST_NOTIFICATIONS` · `READ_PHONE_STATE`

可选 root（KernelSU）：通话转写复用需 root 读 StepOS RecorderDb。

## StepOS 适配备忘

| 项目 | 说明 |
| --- | --- |
| 麦克风仲裁 | `com.stepos.ailauncher` 的 CallListen 会抢麦，需禁用或共存（当前已禁用） |
| 进程冻结 | 前台通知保活 + 15 分钟守夜自检 |
| 6 麦拓扑 | 6× SoundWire DMIC（dmic1-6），BE max_channels=6；常听 ACD 只用 2 DMIC LPI |
| 采集档位 | voice-recognition / voice-communication / unprocessed 三档可切 |

## License

MIT
