"""Reproduces the Aves smart search model packs.

Steps, per model:
1. export the OpenCLIP image and text encoders to ONNX (opset 18), with L2-normalized outputs,
2. dynamic int8 quantization, per channel:
   - image encoder: MatMul/Gemm,
   - text encoder: MatMul/Gemm/Gather, except the first block MLP output projection (`resblocks.0/mlp/c_proj`),
     whose activation outliers break int8 text embeddings (Flickr30k T2I R@1 0.41 -> 0.66 for ViT-B/32),
3. conversion to ORT format, optimized for ARM.

Tested with: torch 2.8.0, open_clip_torch 3.3.0, onnx 1.19.1 (export),
onnxruntime 1.30.0 (quantization and ORT conversion).

Usage:
  python convert.py openclip-vitb32-laion2b-v1 'ViT-B-32/laion2b_s34b_b79k' out/
  python convert.py pe-core-b16-224-v1 'hf-hub:timm/PE-Core-B-16' out/

The outputs are published at https://huggingface.co/sliderforthewin/aves-smart-search
(see `ModelCatalog.kt` for the pinned revision and the SHA-256 of each file).
"""
import os
import shutil
import subprocess
import sys

import open_clip
import open_clip.model as oc_model
import open_clip.transformer as oc_transformer
import torch
import torch.nn.functional as F
from onnxruntime.quantization import QuantType, quantize_dynamic

model_id, spec, out_root = sys.argv[1], sys.argv[2], sys.argv[3]
out = os.path.join(out_root, model_id)
work = os.path.join(out_root, f'{model_id}.work')
os.makedirs(out, exist_ok=True)
os.makedirs(work, exist_ok=True)

# ONNX Runtime has no int64 ArgMax kernel: pool at the end-of-text token (the largest ID) using a float argmax
_orig_pool = oc_transformer.text_global_pool


def _pool(x, text=None, pool_type='argmax', eos_token_id=None):
    if pool_type == 'argmax':
        return x[torch.arange(x.shape[0]), text.to(torch.float32).argmax(dim=-1)]
    return _orig_pool(x, text, pool_type, eos_token_id)


oc_transformer.text_global_pool = _pool
if hasattr(oc_model, 'text_global_pool'):
    oc_model.text_global_pool = _pool

if spec.startswith('hf-hub:'):
    model, _, _ = open_clip.create_model_and_transforms(spec)
    tokenizer = open_clip.get_tokenizer(spec)
else:
    arch, pretrained = spec.split('/')
    model, _, _ = open_clip.create_model_and_transforms(arch, pretrained=pretrained)
    tokenizer = open_clip.get_tokenizer(arch)
model.eval()
# the fused attention fast path cannot be exported
torch.backends.mha.set_fastpath_enabled(False)

res = model.visual.image_size
res = res[0] if isinstance(res, (tuple, list)) else res


class Image(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, x):
        return F.normalize(self.m.encode_image(x), dim=-1)


class Text(torch.nn.Module):
    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, t):
        return F.normalize(self.m.encode_text(t), dim=-1)


with torch.no_grad():
    torch.onnx.export(Image(model), torch.randn(1, 3, res, res), f'{work}/image_fp32.onnx',
                      input_names=['pixel_values'], output_names=['embedding'], opset_version=18, dynamo=False)
    torch.onnx.export(Text(model), tokenizer(['a photo of a dog']), f'{work}/text_fp32.onnx',
                      input_names=['input_ids'], output_names=['embedding'], opset_version=18, dynamo=False)

quantize_dynamic(f'{work}/image_fp32.onnx', f'{work}/image.onnx', weight_type=QuantType.QInt8, per_channel=True,
                 op_types_to_quantize=['MatMul', 'Gemm'])

import onnx  # noqa: E402

text_graph = onnx.load(f'{work}/text_fp32.onnx', load_external_data=False)
excluded = [n.name for n in text_graph.graph.node if n.name.endswith('resblocks.0/mlp/c_proj/MatMul')]
assert len(excluded) == 1, excluded
quantize_dynamic(f'{work}/text_fp32.onnx', f'{work}/text.onnx', weight_type=QuantType.QInt8, per_channel=True,
                 op_types_to_quantize=['MatMul', 'Gemm', 'Gather'], nodes_to_exclude=excluded)

subprocess.run([sys.executable, '-m', 'onnxruntime.tools.convert_onnx_models_to_ort', work, '--output_dir', work,
                '--optimization_style', 'Fixed', '--target_platform', 'arm'], check=True)
for name in ['image', 'text']:
    shutil.copy(f'{work}/{name}.ort', f'{out}/{name}.ort')
shutil.copy(open_clip.tokenizer.default_bpe(), f'{out}/bpe_simple_vocab_16e6.txt.gz')
print(f'written to {out}')
