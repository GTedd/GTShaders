"""TinyStories-1M 的权重取用与 8bit 量化。

这一份只管「从哪拿权重、怎么变成 GPU 能读的字节」，不碰着色器与资源包——
那是 `build_llm_pack.py` 的事。拆开是因为对拍工具 `llm_simulate.py` 也要用同一份量化结果，
两边各量化一次的话，一旦参数不同，对拍就变成了自己跟自己比。

## 为什么不装 torch

`pytorch_model.bin` 就是一个 zip：一份 `data.pkl` 加若干裸张量字节。
用 `pickle` 的自定义 `Unpickler` 把 `torch._utils._rebuild_tensor_v2` 换成自己的还原函数就能读，
不需要 2 GB 的 torch。这台机器上 `pip install torch` 要走境外源，几乎必然失败。

## 量化方案

**per-row 对称 int8**，`scale[r] = max|w[r]| / 127`。

- 为什么按行：矩阵按「输出通道」分行，各行的动态范围差好几个数量级
  （`wte` 尤其明显——没训到的 token 那一行范数极小）。per-tensor 会把小行全压成 0。
- 为什么对称（不带 zero-point）：着色器里少一次减法，而权重本来就近似零对称，非对称换不到精度。
- 为什么 scale 用 `max/127` 而不搜 MSE 最优：**试过，没用**。在 [0.55, 1.0] 上格搜 24 个候选，
  三个代表性张量的重建 RMSE 一位都没变——这些权重没有离群值，裁剪换不来收益。
- bias 与 layernorm 的 weight/bias **不量化**：它们总共才 4224 个数，
  却直接决定输出的偏移，量化收益为零、风险不小。走 float32 位打包存。

实测代价（与 fp32 参考同前缀比 top-1）：**一致率 91.7%**，
分歧处 fp32 自己的 top1-top2 间距中位数只有 0.064——翻转都发生在「两个候选几乎并列」的地方。
生成质量不受影响，但**逐 token 复现 fp32 是做不到的**，greedy 解码会在十几步后分岔。
"""

import hashlib
import io
import json
import pickle
import urllib.request
import zipfile

import numpy as np

MODEL = "roneneldan/TinyStories-1M"
# huggingface.co 在这台机器上不通，hf-mirror 通。换源只要改这一行
BASE = "https://hf-mirror.com/%s/resolve/main" % MODEL
FILES = ("config.json", "pytorch_model.bin", "vocab.json", "merges.txt")

_STORAGE_DTYPES = {
    "FloatStorage": np.dtype("<f4"), "HalfStorage": np.dtype("<f2"),
    "DoubleStorage": np.dtype("<f8"), "LongStorage": np.dtype("<i8"),
    "IntStorage": np.dtype("<i4"), "BoolStorage": np.dtype("?"),
    "BFloat16Storage": np.dtype("<u2"),
}

#: 每层里需要量化的六个矩阵，顺序即打包顺序
LAYER_MATS = (
    "attn.attention.q_proj.weight", "attn.attention.k_proj.weight",
    "attn.attention.v_proj.weight", "attn.attention.out_proj.weight",
    "mlp.c_fc.weight", "mlp.c_proj.weight",
)
#: 每层里不量化的向量，顺序即打包顺序
LAYER_VECS = (
    "ln_1.weight", "ln_1.bias", "attn.attention.out_proj.bias",
    "ln_2.weight", "ln_2.bias", "mlp.c_fc.bias", "mlp.c_proj.bias",
)


def fetch(cache_dir, progress=print):
    """把四个文件下到 `cache_dir`，已存在就跳过。返回目录路径。"""
    cache_dir.mkdir(parents=True, exist_ok=True)
    for name in FILES:
        dst = cache_dir / name
        if dst.exists() and dst.stat().st_size > 0:
            continue
        progress("下载 %s ..." % name)
        with urllib.request.urlopen("%s/%s" % (BASE, name), timeout=300) as r:
            dst.write_bytes(r.read())
        progress("  %s %.1f KB" % (name, dst.stat().st_size / 1024))
    return cache_dir


def load_state_dict(path):
    """无 torch 读取 `pytorch_model.bin`。"""
    z = zipfile.ZipFile(path)
    root = z.namelist()[0].split("/")[0]

    class _Storage:
        def __init__(self, key, dtype):
            self.key, self.dtype = key, dtype

    def rebuild(storage, offset, shape, stride, *_rest):
        flat = np.frombuffer(z.read("%s/data/%s" % (root, storage.key)), storage.dtype)
        want = tuple(int(s) for s in shape)
        if not want:
            return flat[offset:offset + 1].reshape(())
        n = int(np.prod(want))
        expected, acc = [], 1
        for d in reversed(want):
            expected.append(acc)
            acc *= d
        if tuple(int(s) for s in stride) == tuple(reversed(expected)):
            return flat[offset:offset + n].reshape(want)
        return np.lib.stride_tricks.as_strided(
            flat[offset:], want, tuple(int(s) * flat.itemsize for s in stride))

    class _U(pickle.Unpickler):
        def find_class(self, mod, name):
            if mod == "torch._utils" and name in ("_rebuild_tensor_v2", "_rebuild_tensor"):
                return rebuild
            if mod == "torch" and name in _STORAGE_DTYPES:
                return name
            if mod == "collections" and name == "OrderedDict":
                return dict
            raise pickle.UnpicklingError("权重里有不认识的类 %s.%s" % (mod, name))

        def persistent_load(self, pid):
            kind, stype, key, _loc, _numel = pid
            assert kind == "storage", kind
            return _Storage(key, _STORAGE_DTYPES[stype])

    return _U(io.BytesIO(z.read("%s/data.pkl" % root))).load()


def quantize_rows(w):
    """per-row 对称 int8。返回 (int8 矩阵, float32 scale 向量)。"""
    w = np.asarray(w, np.float64)
    amax = np.abs(w).max(1)
    # 整行全零时 scale 取一个极小正数，避免除零；反量化回来仍是 0
    scale = np.where(amax == 0, 1e-12, amax / 127.0).astype(np.float32)
    q = np.rint(w / scale[:, None]).clip(-127, 127).astype(np.int8)
    return q, scale


class Packed:
    """量化后的全部数据，外加着色器需要知道的偏移表。

    布局刻意做得「一维、无空洞」：着色器里一个整数索引就能定位任何一个权重，
    不必为每个张量单独传一组 uniform。
    """

    def __init__(self, cfg, q_i8, scales, vecs, offsets, meta):
        self.cfg = cfg
        self.q_i8 = q_i8          # int8 一维数组，所有量化矩阵首尾相接
        self.scales = scales      # float32 一维数组，所有 per-row scale
        self.vecs = vecs          # float32 一维数组，所有 bias / layernorm
        self.offsets = offsets    # 每个张量在上面三个数组里的起点
        self.meta = meta          # 维度、行数等，写进 layout.json 供 GLSL 生成宏


def build(cache_dir, progress=print):
    """读权重 → 量化 → 拼成一维布局。"""
    cfg = json.loads((cache_dir / "config.json").read_text(encoding="utf-8"))
    sd = load_state_dict(cache_dir / "pytorch_model.bin")
    t = {k.replace("transformer.", ""): np.asarray(v, np.float32)
         for k, v in sd.items() if getattr(v, "ndim", 0) > 0 and v.dtype != np.bool_}

    H = cfg["hidden_size"]
    L = cfg["num_layers"]
    inter = cfg["intermediate_size"] or 4 * H

    mats, vecs_named = [("wte.weight", t["wte.weight"]), ("wpe.weight", t["wpe.weight"])], []
    for i in range(L):
        for nm in LAYER_MATS:
            mats.append(("h.%d.%s" % (i, nm), t["h.%d.%s" % (i, nm)]))
        for nm in LAYER_VECS:
            vecs_named.append(("h.%d.%s" % (i, nm), t["h.%d.%s" % (i, nm)]))
    vecs_named += [("ln_f.weight", t["ln_f.weight"]), ("ln_f.bias", t["ln_f.bias"])]

    offsets, q_parts, s_parts, v_parts = {}, [], [], []
    q_off = s_off = v_off = 0
    for name, w in mats:
        q, s = quantize_rows(w)
        offsets[name] = {"w": q_off, "s": s_off, "rows": int(w.shape[0]), "cols": int(w.shape[1])}
        q_parts.append(q.ravel())
        s_parts.append(s)
        q_off += q.size
        s_off += s.size
    for name, v in vecs_named:
        offsets[name] = {"v": v_off, "len": int(v.size)}
        v_parts.append(np.asarray(v, np.float32).ravel())
        v_off += v.size

    q_i8 = np.concatenate(q_parts).astype(np.int8)
    scales = np.concatenate(s_parts).astype(np.float32)
    fvecs = np.concatenate(v_parts).astype(np.float32)

    meta = {
        "model": MODEL, "H": H, "L": L, "NH": cfg["num_heads"],
        "HD": H // cfg["num_heads"], "INTER": inter,
        "EPS": float(cfg["layer_norm_epsilon"]), "WINDOW": cfg["window_size"],
        "VOCAB": int(t["wte.weight"].shape[0]), "WPE_MAX": int(t["wpe.weight"].shape[0]),
        "attn_local": [1 if a == "local" else 0 for a in cfg["attention_layers"]],
        "n_weights": int(q_i8.size), "n_scales": int(scales.size), "n_vecs": int(fvecs.size),
    }
    progress("量化完成：%d 个 int8 权重、%d 个 scale、%d 个未量化向量元素"
             % (q_i8.size, scales.size, fvecs.size))
    progress("  裸字节：权重 %.2f MB + scale %.2f MB + 向量 %.1f KB"
             % (q_i8.size / 1e6, scales.size * 4 / 1e6, fvecs.size * 4 / 1e3))
    return Packed(cfg, q_i8, scales, fvecs, offsets, meta)


def dequantized(packed, name):
    """把某个矩阵反量化回 float，供对拍用。"""
    o = packed.offsets[name]
    rows, cols = o["rows"], o["cols"]
    q = packed.q_i8[o["w"]: o["w"] + rows * cols].reshape(rows, cols).astype(np.float64)
    s = packed.scales[o["s"]: o["s"] + rows].astype(np.float64)
    return q * s[:, None]


def vector(packed, name):
    o = packed.offsets[name]
    return packed.vecs[o["v"]: o["v"] + o["len"]].astype(np.float64)


def digest(packed):
    """给产物一个可复现的指纹，写进 layout.json，方便核对着色器与数据是否配套。"""
    h = hashlib.sha256()
    h.update(packed.q_i8.tobytes())
    h.update(packed.scales.tobytes())
    h.update(packed.vecs.tobytes())
    return h.hexdigest()[:16]
