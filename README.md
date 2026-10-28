# 回声 Echo 2.0

无感语音记忆 Agent —— 开机即在、从不打扰、重要的事不会忘。

纯原生 Android，端侧运行 SenseVoice ASR + Gemma 4 E2B + 声纹识别，零云端依赖。

## 它做什么

```
你说话（对任何人、在任何场景）
  → 常驻采集（三档节能）→ VAD 分段 → 声纹过滤 → SenseVoice 转写
  → Gemma 纠错 → 三层抽取 → 待办 / 记忆 / 提醒闹钟
  → 每晚 21:30 回顾 → 记忆整合 → 7 天后自动清理原始录音
```

## 三层信息架构

| 层 | 来源 | 产出 | 展示 |
| --- | --- | --- | --- |
| L1 主线 | [主人] 自己的承诺 | 待办 + 精准提醒 | 首页 / 待办页 |
| L2 受托 | 他人指派给主人的任务 | 待办（标 [受托]） | 待办页 |
| L3 素材 | 环境中有价值的话题/观点 | 素材记忆 | 记忆页折叠区 |
| 噪音 | 系统提示音等 | 丢弃 | 不入库 |

## 节能监听

| 状态 | 条件 | CPU 开销 |
| --- | --- | --- |
| 全量 | 有人说话 / 刚说完 | 正常 VAD |
| 半待机 | 静默 2 分钟 | 跳 VAD，只查 RMS |
| 极简待机 | 静默 10 分钟 | 只等声音恢复 |

## 通话复用

通话中回声主动让出麦克风，挂断后 root 读取 StepOS 的 RecorderDb 复用它的转写文本直接出待办——零冲突、零重录。

## 构建

```bash
./build.sh
# 产物: echo.apk
```

模型文件推到 `/sdcard/Android/data/dev.echo.app/files/`：

| 文件 | 大小 | 用途 |
| --- | --- | --- |
| sensevoice-model.onnx | 226 MB | 中文 ASR（主转写） |
| sensevoice-tokens.txt | 308 KB | ASR 词表 |
| gemma-4-E2B-it.litertlm | 2.4 GB | 抽取 + 纠错 + 回顾 |
| embeddinggemma-2-740m.litertlm | 463 MB | 语义搜索（768 维） |
| campplus_zh.onnx | 28 MB | 声纹识别（192 维） |

## 架构（20 个源文件）

```
感知层   RecorderService · Vad · VoiceTile       常驻采集 + 三档节能 + QS 磁贴
声纹层   SpeakerId                                CAM++ 192 维 · 阈值 0.62
转写层   SenseVoiceAsr                            SenseVoice int8 · 中文优化
理解层   GemmaEngine · Pipeline                   Gemma 4 E2B 抽取 + 纠错
记忆层   Store · MemoryEmbedder · Consolidator    结构化 + 向量 + 夜间整合
行动层   ReminderAlarm · Notifier · CallLink      闹钟 + 通知 + 通话复用
UI 层    MainActivity · Ui                        亮色多巴胺主题
```

## License

MIT
