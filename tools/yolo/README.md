# BabyBuddy YOLO Pose 模型导出与部署工具链

本目录提供将 **Ultralytics YOLO26-pose / YOLO11-pose / YOLOv8-pose**（或针对婴儿数据集微调后的 `.pt` 权重）一键导出为 Android 与 Web 端可直接加载的 `.onnx` 模型的工具脚本。

## 1. 快速导出内置模型 (`yolo26n-pose.onnx`)

无需手动安装全局依赖，使用 `uv` 可在隔离环境中一键拉取并导出：

```bash
uv run --with ultralytics --with onnx --with onnxruntime python tools/yolo/export_yolo_pose.py --model yolo26n-pose.pt --imgsz 640
```

导出成功后会自动校验 ONNX 输入/输出张量维度，并拷贝至：
- Android 内置离线资源目录：`app/src/main/assets/models/yolo26n-pose.onnx`
- 如附加 `--copy-to-web`，同时同步至：`web/models/yolo26n-pose.onnx`

## 2. 支持的模型格式（客户端自适应解析）

客户端 `OnnxYoloPoseDetector` 自动识别以下两种标准 YOLO Pose ONNX 输出拓扑（COCO 17 关键点）：
1. **End-to-End NMS-Free 格式**：`[1, 300, 57]`（`[x1, y1, x2, y2, score, class_id, 17 × (x, y, conf)]`）
2. **Dense Anchor-Free 格式**：`[1, 56, 8400]` 或 `[1, 56, N]`（`[cx, cy, w, h, score, 17 × (x, y, conf)]`，客户端内置高效 IoU NMS）
