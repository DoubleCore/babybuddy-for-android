#!/usr/bin/env python3
"""
Exports Ultralytics YOLO Pose models (YOLO26-pose / YOLO11-pose / custom infant .pt)
to Tencent ncnn FP16 (.param + .bin) for ultra-fast Vulkan GPU / ARMv8.2 NEON mobile inference.

Usage:
  uv run --with ultralytics --with ncnn --with pnnx python tools/yolo/export_yolo_pose.py --model yolo26n-pose.pt --imgsz 320 --prefix yolo26n_pose_320
"""

import argparse
import shutil
from pathlib import Path


def main() -> None:
    parser = argparse.ArgumentParser(description="Export YOLO Pose model to Tencent ncnn FP16 for BabyBuddy Android")
    parser.add_argument("--model", default="yolo26n-pose.pt", help="Model name or .pt path")
    parser.add_argument("--imgsz", type=int, default=320, help="Input size (320 for ultra-fast, 480 for HD)")
    parser.add_argument("--prefix", default="yolo26n_pose_320", help="Output filename prefix in assets/models/")
    args = parser.parse_args()

    from ultralytics import YOLO

    repo_root = Path(__file__).resolve().parents[2]
    model = YOLO(args.model)
    out_dir = Path(model.export(format="ncnn", imgsz=args.imgsz, half=True))

    assets_dir = repo_root / "app" / "src" / "main" / "assets" / "models"
    assets_dir.mkdir(parents=True, exist_ok=True)
    shutil.copy2(out_dir / "model.ncnn.param", assets_dir / f"{args.prefix}.param")
    shutil.copy2(out_dir / "model.ncnn.bin", assets_dir / f"{args.prefix}.bin")
    print(f"Exported FP16 ncnn pair to {assets_dir}/{args.prefix}.param & .bin")


if __name__ == "__main__":
    main()
