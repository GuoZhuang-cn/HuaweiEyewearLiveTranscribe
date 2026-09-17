# 眼镜实时转写 Demo（EyewearLiveTranscribe）

把 **华为智能眼镜 2（或任意蓝牙耳机）** 当 Mic，采集 16kHz PCM，实时上屏；ASR 可插拔。

## 你必须先做的两件事（真机）

1. 连接眼镜，打开系统开关：  
   **设置 → 声音和振动 → 更多声音和振动设置 → 蓝牙设备录音**
2. 用 Android Studio 打开本目录，连接手机 Run。

## 工程结构

| 模块 | 作用 |
|---|---|
| `audio/BtMicRecorder` | `setCommunicationDevice` 选蓝牙 SCO + `AudioRecord`；来电自动暂停 |
| `asr/AsrEngine` | 转写接口 |
| `asr/DebugAsrEngine` | 无网调试：电平 + 模拟字幕，验证眼镜 Mic 是否进流 |
| `asr/WebSocketAsrEngine` | 云端 WebSocket 骨架（JSON 协议，可换成讯飞/阿里） |
| `service/RecordForegroundService` | 前台麦克风服务，便于后台保持 |

## 验证路径

1. 先用 **调试引擎**（默认）：对着眼镜说话，音量条应跳动，字幕出现 `[调试] 检测到语音`。  
   - 若音量条不动 → 眼镜 Mic 未进流，检查「蓝牙设备录音」或换录音机 App 对比。
2. 再接云端：在 `MainActivity.toggle()` 里取消注释并填写  
   `AsrEngines.cloudGateway("wss://你的网关/stream", token = "...")`。  
   **建议把厂商 API Key 放在自己的网关**，不要写进手机 App。

## 接云端 ASR 的建议

- **讯飞实时语音转写** / **阿里云智能语音交互** / **华为云 ASR**：手机直连要签名，易泄露密钥。  
  更稳妥：手机 → 你的 HTTPS/WSS 网关 → 厂商 API。网关收 PCM，回 `{"type":"partial"|"final","text":"..."}` 即可与本 Demo 对齐。
- 需要改协议时，继承/改写 `WebSocketAsrEngine` 的 `startPayload` 与 `parse`。

## 已知限制

- SCO 为语音级音质，适合纪要转写，不是高保真录音。
- 持续 SCO 更耗眼镜/手机电。
- 来电时录音暂停，挂断后需重新点开始（Demo 简化处理）。
- HarmonyOS NEXT 上系统开关路径可能不同；本工程按 AOSP 31+ API 编写。

## 编译

Android Studio (AGP 8.5+) · minSdk 31 · Kotlin 1.9
