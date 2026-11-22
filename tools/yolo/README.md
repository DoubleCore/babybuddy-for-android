# BabyBuddy 移动端硬件加速 (Tencent ncnn Vulkan FP16) 导出与部署工具链

针对手机端实时婴儿看护的低延迟与低发热需求，Android 客户端已内化 **腾讯 `ncnn` (20261020 Vulkan Compute Shaders + ARMv8.2 NEON FP16)** C++ 原生推理框架，配合 CameraX 硬件 **60 FPS `PreviewView`** 异步管线。

## 1. 一键导出 FP16 `.param` + `.bin` 权重

```bash
# 导出 320x320 极速版 (1.9 GFLOPs, Vulkan GPU ~6-15ms)
uv run --with ultralytics --with ncnn --with pnnx python tools/yolo/export_yolo_pose.py --model yolo26n-pose.pt --imgsz 320 --prefix yolo26n_pose_320

# 导出 480x480 远距超清版 (4.3 GFLOPs, Vulkan GPU ~14-25ms)
uv run --with ultralytics --with ncnn --with pnnx python tools/yolo/export_yolo_pose.py --model yolo26n-pose.pt --imgsz 480 --prefix yolo26n_pose_480
```

## 2. 自定义云端微调模型打包规范

若使用婴儿自有数据集微调得到 `.pt` 并导出为 `model.ncnn.param` 与 `model.ncnn.bin`，只需将两个文件打成 `.zip` 上传至任意 HTTPS/局域网地址，即可在 App 内「自定义 ncnn(.zip)」中一键拉取热切换。
