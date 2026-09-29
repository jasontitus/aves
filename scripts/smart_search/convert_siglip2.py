"""Rebuild the paired SigLIP2 selective-int8 pack (NOT convert.py's two older packs).

Python 3.12; install these exact conversion dependencies in an isolated environment
(and record the resolved transitive dependency lockfile for a release attempt):
  torch==2.14.0 torchvision==0.29.0 open_clip_torch==3.3.0 timm==1.0.30
  transformers==5.17.0 huggingface_hub==1.33.0 tokenizers==0.23.2
  onnx==1.23.0 onnxruntime==1.30.0 numpy==2.5.3 protobuf==7.36.2
  safetensors==0.8.0 Pillow==12.3.0

  python scripts/smart_search/convert_siglip2.py /scratch/siglip2-pack \
      --work-dir /scratch/siglip2-work \
      --goldens /scratch/siglip2-goldens.json --fixture-image /path/to/licensed-portrait.jpg
  python scripts/smart_search/convert_siglip2.py /scratch/siglip2-pack --verify-only
  python scripts/smart_search/convert_siglip2.py /scratch/siglip2-pack --goldens-only \
      --goldens /scratch/siglip2-goldens.json --fixture-image /path/to/licensed-portrait.jpg

The input checkpoint is timm/ViT-B-32-SigLIP2-256 revision
cd1efb47643f2794413dd79ef24397c175032780, NOT google/siglip2-base-patch32-256.
The app feeds RGB floats in [0,1] after bilinear 256x256 SQUASH (not crop); the
image graph applies mean/std 0.5 internally. Text uses this checkpoint's Gemma
HFTokenizer(clean='canonicalize'), EOS-only (no BOS), truncated with EOS last,
right-padded with <pad>=0 to 64 int64 IDs. Both outputs are L2-normalized 768-vectors.
No query templates, logits, or image crops belong in the export.

Conversion: torch.onnx.export(opset_version=18, dynamo=False); quantize_dynamic
QInt8 per_channel=True, image MatMul/Gemm (exclude 13 /mlp/fc2/MatMul),
text MatMul/Gemm/Gather (exclude 12 /mlp/c_proj/MatMul). Both exclusion counts
are enforced before quantizing. ORT conversion is equivalent to
  python -m onnxruntime.tools.convert_onnx_models_to_ort WORK/arm_input \
      --output_dir WORK/arm_output --optimization_style Fixed --target_platform arm

The optional JSON golden records token IDs and ORT embeddings for fixed Unicode,
empty and truncated queries, plus a user-supplied non-square image (only its hash,
shape, and embedding, not pixels). Compare with Android and fp32 OpenCLIP on the
same inputs; add a landscape fixture and a licensed multilingual retrieval gallery
before publishing. Goldens remain outside the repository. Conversion needs several
GB free; intermediates stay in --work-dir. The pinned tokenizer MUST match.
Observed with the versions above: re-exported fp32 and selectively quantized
ONNX matched the original trial's hashes exactly, while ORT files had different
SHA-256 and sometimes a 16-byte size difference. One rebuild produced
image 878ccb397ecf1a6e1d1ca3448a31248c652223a45001f95f14940d01d34d10c0,
text 72fe13e7d46985dee2f608a3a339bd2b6616e8481cfe77409e2b47d3c9459627.
Its image/Japanese-text embeddings matched the published pair exactly on one
local portrait and query; this is NOT a full quality or bit-for-bit proof.
The script reports Catalog hash/size discrepancies and warns but exits 0 for
ORT differences; it refuses a tokenizer mismatch. --verify-only exits 2 for ANY
Catalog mismatch. Do not overwrite the hosted pack without comparing embeddings,
rankings, target devices and licenses.
"""

import argparse
import hashlib
import json
import shutil
import subprocess
import sys
from pathlib import Path

MODEL = "timm/ViT-B-32-SigLIP2-256"
REVISION = "cd1efb47643f2794413dd79ef24397c175032780"
WEIGHTS_SHA256 = "7cdccf256ee0e4a9908c365f8e1c88d5db171980a3350c214f5221d50f8d9927"
# ModelCatalog.kt's pinned release, commit 7ab165533402fa42eedc575efc110d2b01ed61a5.
EXPECTED = {
    "image.ort": (195003280, "d6a54abc8d25ea7f5d633de8d27fbae6b3cdace0e87703140641e7317133efb6"),
    "text.ort": (368788736, "27c420458dfac229ed98c17dc57f516441dd10c912aa1682195cf0a2e578d591"),
    "tokenizer.json": (34362885, "220c63d496e0c14e63eb656c91e0215e926202e4c74b1f089e09f1920d779b04"),
}


def digest(path):
    sha = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            sha.update(chunk)
    return path.stat().st_size, sha.hexdigest()


def pinned_snapshot(include_weights):
    from huggingface_hub import hf_hub_download

    files = ["open_clip_config.json", "tokenizer.json", "tokenizer_config.json", "special_tokens_map.json"]
    if include_weights:
        files.append("open_clip_model.safetensors")
    # Individual, revision-pinned downloads work with HF_HUB_OFFLINE=1 if cached;
    # snapshot_download in hub 1.33 still tries to list the repo while offline.
    paths = [Path(hf_hub_download(MODEL, file, revision=REVISION)) for file in files]
    if len({path.parent for path in paths}) != 1:
        raise RuntimeError("Pinned model assets did not resolve to one snapshot")
    return paths[0].parent


def verify(out):
    matches = True
    for name, expected in EXPECTED.items():
        path = out / name
        if not path.is_file():
            print(f"MISSING {path} (expected bytes={expected[0]} sha256={expected[1]})")
            matches = False
            continue
        actual = digest(path)
        ok = actual == expected
        matches &= ok
        print(f"{'MATCH' if ok else 'MISMATCH'} {path}: bytes={actual[0]} sha256={actual[1]}"
              f"; ModelCatalog expected bytes={expected[0]} sha256={expected[1]}", flush=True)
    return matches


def exclusions(onnx_path, suffix, count):
    import onnx

    graph = onnx.load(str(onnx_path), load_external_data=False).graph
    names = [node.name for node in graph.node if node.name.endswith(suffix) and node.op_type == "MatMul"]
    if len(names) != count or len(names) != len(set(names)):
        raise RuntimeError(f"{onnx_path}: expected {count} unique {suffix} MatMul nodes, found {names}")
    print(f"{onnx_path.name}: preserving {len(names)} {suffix} nodes in fp32", flush=True)
    return names


def make_goldens(path, image_path, tokenizer, out):
    import numpy as np
    import onnxruntime as ort
    import torch

    queries = ["", "a dog on grass", "Hund auf der Wiese", "犬が芝生の上に座っている",
               "كلب على العشب", "कुत्ता घास पर बैठा है", "chien 犬 on grass",
               "a very long query " * 30]
    session = ort.InferenceSession(str(out / "text.ort"), providers=["CPUExecutionProvider"])
    records = []
    for text in queries:
        ids = tokenizer([text]).to(dtype=torch.int64).numpy()
        assert ids.shape == (1, 64) and ids[0, -1] == (1 if text == queries[-1] else 0)
        vector = session.run(["embedding"], {"input_ids": ids})[0][0]
        records.append({"text": text, "input_ids": ids[0].tolist(), "embedding": vector.tolist()})
    golden = {"model": MODEL, "revision": REVISION, "pack_sha256": {
        name: digest(out / name)[1] for name in EXPECTED}, "queries": records}
    if image_path is not None:
        from PIL import Image
        from io import BytesIO

        source = image_path.read_bytes()
        with Image.open(BytesIO(source)) as image:
            rgb = image.convert("RGB")
            original_size = list(rgb.size)
            # Pillow bilinear is a reference; native decoders/resamplers must be checked independently.
            resized = rgb.resize((256, 256), Image.Resampling.BILINEAR)
            pixels = np.asarray(resized, dtype=np.float32).transpose(2, 0, 1)[None] / 255.0
        session = ort.InferenceSession(str(out / "image.ort"), providers=["CPUExecutionProvider"])
        vector = session.run(["embedding"], {"pixel_values": pixels})[0][0]
        golden["image"] = {"source_sha256": hashlib.sha256(source).hexdigest(),
                            "source_size": original_size, "pixel_values_sha256": hashlib.sha256(pixels.tobytes()).hexdigest(),
                            "embedding": vector.tolist()}
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(golden, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"wrote local-only fixtures {path}", flush=True)


def export(out, work, goldens, image_path):
    import open_clip
    import torch
    import torch.nn.functional as F
    from onnxruntime.quantization import QuantType, quantize_dynamic

    snapshot = pinned_snapshot(include_weights=True)
    if digest(snapshot / "open_clip_model.safetensors")[1] != WEIGHTS_SHA256:
        raise RuntimeError("Pinned upstream checkpoint SHA-256 mismatch")
    if digest(snapshot / "tokenizer.json") != EXPECTED["tokenizer.json"]:
        raise RuntimeError("Pinned upstream tokenizer differs from ModelCatalog; do not substitute Google's tokenizer")
    # local-dir loads BOTH model config/weights and tokenizer from the pinned snapshot,
    # never resolves a mutable default branch inside OpenCLIP or Transformers.
    spec = f"local-dir:{snapshot}"
    torch.backends.mha.set_fastpath_enabled(False)
    model, _, _ = open_clip.create_model_and_transforms(spec)
    model.eval().requires_grad_(False)
    tokenizer = open_clip.get_tokenizer(spec)
    ids = tokenizer(["a photo of a cat"]).to(torch.int64).contiguous()
    assert ids.shape == (1, 64) and ids[0, -1] == 0

    class ImageTower(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, x):
            return F.normalize(self.m.encode_image((x - 0.5) / 0.5), dim=-1)

    class TextTower(torch.nn.Module):
        def __init__(self, m):
            super().__init__()
            self.m = m

        def forward(self, t):
            return F.normalize(self.m.encode_text(t), dim=-1)

    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    with torch.no_grad():
        torch.onnx.export(ImageTower(model), torch.randn(1, 3, 256, 256), str(work / "image_fp32.onnx"),
                          input_names=["pixel_values"], output_names=["embedding"], opset_version=18,
                          dynamo=False, dynamic_axes={"pixel_values": {0: "batch"}, "embedding": {0: "batch"}})
        torch.onnx.export(TextTower(model), ids, str(work / "text_fp32.onnx"),
                          input_names=["input_ids"], output_names=["embedding"], opset_version=18,
                          dynamo=False, dynamic_axes={"input_ids": {0: "batch"}, "embedding": {0: "batch"}})
    for tower, suffix, count, ops in [
        ("image", "/mlp/fc2/MatMul", 13, ["MatMul", "Gemm"]),
        ("text", "/mlp/c_proj/MatMul", 12, ["MatMul", "Gemm", "Gather"]),
    ]:
        excludes = exclusions(work / f"{tower}_fp32.onnx", suffix, count)
        quantize_dynamic(str(work / f"{tower}_fp32.onnx"), str(work / f"{tower}.onnx"),
                         weight_type=QuantType.QInt8, per_channel=True,
                         op_types_to_quantize=ops, nodes_to_exclude=excludes)
    arm_input = work / "arm_input"
    arm_output = work / "arm_output"
    arm_input.mkdir(exist_ok=True)
    arm_output.mkdir(exist_ok=True)
    for tower in ("image", "text"):
        shutil.copyfile(work / f"{tower}.onnx", arm_input / f"{tower}.onnx")
    subprocess.run([sys.executable, "-m", "onnxruntime.tools.convert_onnx_models_to_ort", str(arm_input),
                    "--output_dir", str(arm_output), "--optimization_style", "Fixed", "--target_platform", "arm"],
                   check=True)
    for tower in ("image", "text"):
        shutil.copyfile(arm_output / f"{tower}.ort", out / f"{tower}.ort")
    shutil.copyfile(snapshot / "tokenizer.json", out / "tokenizer.json")
    if goldens:
        make_goldens(goldens, image_path, tokenizer, out)
    if not verify(out):
        if digest(out / "tokenizer.json") != EXPECTED["tokenizer.json"]:
            raise RuntimeError("tokenizer.json: unexpected size/SHA-256; refusing mismatched output")
        print("WARNING: ORT hashes and/or sizes differ from the published pack. "
              "Do not publish without comparing embeddings/rankings and testing on target devices.", file=sys.stderr)


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("output", type=Path, help="Directory for image.ort, text.ort, tokenizer.json")
    parser.add_argument("--work-dir", type=Path, help="Separate directory for multi-GB intermediate ONNX files")
    parser.add_argument("--verify-only", action="store_true", help="Check output hashes/sizes without conversion dependencies")
    parser.add_argument("--goldens-only", action="store_true", help="Generate fixtures from a verified existing pack")
    parser.add_argument("--goldens", type=Path, help="Write local-only tokenizer/ORT inference fixtures as JSON")
    parser.add_argument("--fixture-image", type=Path, help="A licensed non-square image for optional image golden")
    args = parser.parse_args()
    if args.fixture_image and not args.goldens:
        parser.error("--fixture-image requires --goldens")
    if args.verify_only and args.goldens_only:
        parser.error("--verify-only and --goldens-only are mutually exclusive")
    if args.verify_only:
        if not verify(args.output):
            sys.exit(2)
        return
    if args.goldens_only:
        if args.goldens is None:
            parser.error("--goldens-only requires --goldens")
        if not verify(args.output):
            sys.exit(2)
        import open_clip

        snapshot = pinned_snapshot(include_weights=False)
        if digest(snapshot / "tokenizer.json") != EXPECTED["tokenizer.json"]:
            raise RuntimeError("Pinned upstream tokenizer differs from ModelCatalog")
        make_goldens(args.goldens, args.fixture_image, open_clip.get_tokenizer(f"local-dir:{snapshot}"), args.output)
        return
    if args.work_dir is None:
        parser.error("--work-dir is required for export")
    if args.output.resolve() == args.work_dir.resolve():
        parser.error("output and --work-dir must differ")
    export(args.output, args.work_dir, args.goldens, args.fixture_image)


if __name__ == "__main__":
    main()
