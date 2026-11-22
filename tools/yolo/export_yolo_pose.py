#!/usr/bin/env python3
"""
Exports Ultralytics YOLO Pose models (YOLO26-pose / YOLO11-pose / YOLOv8-pose or fine-tuned infant .pt)
to ONNX format ready for Android (onnxruntime-android) and Web (onnxruntime-web) deployment.

Usage:
  uv run --with ultralytics --with onnx --with onnxruntime python tools/yolo/export_yolo_pose.py --model yolo26n-pose.pt --imgsz 640
"""

import argparse
import os
import shutil
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Export YOLO Pose model to ONNX for BabyBuddy Android & Web")
    parser.add_argument("--model", default="yolo26n-pose.pt", help="Model name or .pt path (e.g. yolo26n-pose.pt, yolo26s-pose.pt, yolo11s-pose.pt)")
    parser.add_argument("--imgsz", type=int, default=640, help="Input image resolution (default: 640)")
    parser.add_argument("--opset", type=int, default=17, help="ONNX opset version (default: 17)")
    parser.add_argument(
        "--copy-to-android-assets",
        action="store_true",
        default=True,
        help="Copy exported .onnx into app/src/main/assets/models/",
    )
    parser.add_argument(
        "--copy-to-web",
        action="store_true",
        default=False,
        help="Also copy exported .onnx into web/models/",
    )
    args = parser.parse_args()

    from ultralytics import YOLO
    import onnxruntime as ort

    repo_root = Path(__file__).resolve().parents[2]
    print(f"[1/3] Loading YOLO Pose model: {args.model}")
    model = YOLO(args.model)

    print(f"[2/3] Exporting to ONNX (imgsz={args.imgsz}, opset={args.opset}, nms=True)...")
    exported_str = model.export(format="onnx", imgsz=args.imgsz, opset=args.opset, nms=True)
    exported_path = Path(exported_str)
    size_mb = exported_path.stat().st_size / (1024 * 1024)

    sess = ort.InferenceSession(str(exported_path), providers=["CPUExecutionProvider"])
    in_meta = sess.get_inputs()[0]
    out_meta = sess.get_outputs()[0]
    print(f"[3/3] Verified ONNX graph: {exported_path.name} ({size_mb:.2f} MB)")
    print(f"      Input : name={in_meta.name}, shape={in_meta.shape}, type={in_meta.type}")
    print(f"      Output: name={out_meta.name}, shape={out_meta.shape}, type={out_meta.type}")

    if args.copy_to_android_assets:
        assets_dir = repo_root / "app" / "src" / "main" / "assets" / "models"
        assets_dir.mkdir(parents=True, exist_ok=True)
        dst = assets_dir / exported_path.name
        shutil.copy2(exported_path, dst)
        print(f"      Copied to Android assets: {dst}")

    if args.copy_to_web:
        web_dir = repo_root / "web" / "models"
        web_dir.mkdir(parents=True, exist_ok=True)
        dst_web = web_dir / exported_path.name
        shutil.copy2(exported_path, dst_web)
        print(f"      Copied to Web models    : {dst_web}")


if __name__ == "__main__":
    main()
