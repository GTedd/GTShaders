"""按着色器的语义把整条链在 numpy 上重跑一遍，和参考实现对拍。

## 这一道在验什么

这台机器上没有 Minecraft，`checkLlmPack` 能证明「着色器编得过」，
`PostChainConfig` 能证明「JSON 读得进」，但两者都不能证明**算出来的是对的**。
一个把 KV cache 行号算错、把 scale 偏移写反的链，编译一样通过、加载一样成功，
只是屏幕上蹦出一串乱码——那时候已经没有任何线索指向真正的错处。

所以这里做的是：**不碰 GPU，但严格按着色器的写法重算一遍**。

- 权重、scale、词表全部**从生成好的 PNG 里读回来**，不走内存里的原始数组。
  这样打包布局、偏移表、int8 的 ±128 偏移、float32 位模式，错一个就会被逮住。
- 按链里的通道顺序推进，每帧一个位置，KV cache 的读写时机与着色器完全一致。
- 全程 float32，因为 GPU 就是 float32。

对拍的基准是 `llm_weights` 量化出来的同一份权重在 float64 下的前向。
两者应当**逐 token 相同**——如果不同，那就是链本身写错了，
而不是量化误差（量化误差已经在基准里了）。

用法：
    python tools/llm_simulate.py                      # 用默认 prompt 跑 24 个 token
    python tools/llm_simulate.py --tokens 40 --prompt "Lily went to the park"
"""

import argparse
import io
import json
import pathlib
import sys
import zipfile

import numpy as np
from PIL import Image

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import llm_weights as W

ROOT = pathlib.Path(__file__).resolve().parent.parent


class Pack:
    """从生成好的 zip 里把数据读回来——刻意绕开内存里的原始权重。"""

    def __init__(self, zip_path):
        z = zipfile.ZipFile(zip_path)
        self.layout = json.loads(z.read("layout.json"))
        ns = "gtllm"

        def img(name):
            return np.asarray(Image.open(io.BytesIO(
                z.read("assets/%s/textures/effect/%s.png" % (ns, name)))).convert("RGBA"), np.uint8)

        self.chain = json.loads(z.read("assets/%s/post_effect/llm.json" % ns))
        # int8：打包时存的是 v+128
        self.w8 = img("weights").reshape(-1).astype(np.int16) - 128
        # float32 位模式
        self.meta = np.frombuffer(img("meta").tobytes(), "<f4")
        self.detok = img("detok").reshape(-1)
        d = self.layout["dims"]
        self.H, self.L, self.NH = d["H"], d["L"], d["NH"]
        self.HD, self.INTER, self.VOCAB = d["HD"], d["INTER"], d["VOCAB"]
        self.EPS, self.WINDOW = d["EPS"], d["WINDOW"]
        self.MAXSEQ, self.SLICES = self.layout["MAXSEQ"], self.layout["SLICES"]
        self.DETOK_W = self.layout["DETOK_W"]
        self.off = self.layout["offsets"]
        self.local = [1 if i % 2 else 0 for i in range(self.L)]  # config 的 global/local 交替

    def mat(self, name):
        """按着色器的取法还原一个量化矩阵：w8 × per-row scale。"""
        o = self.off[name]
        r, c = o["rows"], o["cols"]
        q = self.w8[o["w"]: o["w"] + r * c].reshape(r, c).astype(np.float32)
        s = self.meta[o["s"]: o["s"] + r].astype(np.float32)
        return q * s[:, None]

    def vec(self, name):
        o = self.off[name]
        return self.meta[o["v"]: o["v"] + o["len"]].astype(np.float32)

    def token_text(self, t):
        row = self.detok[t * self.DETOK_W: (t + 1) * self.DETOK_W]
        return "".join(chr(c) for c in row if c != 0)


def ln32(x, w, b, eps):
    mu = np.float32(x.mean())
    var = np.float32(((x - mu) ** 2).mean())
    return ((x - mu) / np.sqrt(var + np.float32(eps)) * w + b).astype(np.float32)


def gelu32(x):
    return (np.float32(0.5) * x * (1 + np.tanh(
        np.float32(0.7978845608028654) * (x + np.float32(0.044715) * x ** 3)))).astype(np.float32)


class ChainSim:
    """一帧一个位置，目标与着色器一一对应。"""

    def __init__(self, pk):
        self.pk = pk
        H, L = pk.H, pk.L
        self.kv = np.zeros((2 * H * L, pk.MAXSEQ), np.float32)   # 行 × 列，对应 target 的 (x=列)
        self.seq = np.zeros(pk.MAXSEQ, np.int32)
        self.pos = 0
        self.tok = 0

    def step(self):
        """跑一帧：处理 seq[pos-1]，把下一个 token 留在 self.tok。"""
        pk = self.pk
        H, L, NH, HD = pk.H, pk.L, pk.NH, pk.HD
        p = min(max(self.pos - 1, 0), pk.MAXSEQ - 1)

        # --- embed ---
        tok = int(np.clip(self.seq[p], 0, pk.VOCAB - 1))
        x = (pk.mat("wte.weight")[tok] + pk.mat("wpe.weight")[p]).astype(np.float32)

        qkv = np.zeros(3 * H * L, np.float32)
        for i in range(L):
            pre = "h.%d." % i
            win = pk.WINDOW if pk.local[i] else 0
            h = ln32(x, pk.vec(pre + "ln_1.weight"), pk.vec(pre + "ln_1.bias"), pk.EPS)
            base = i * 3 * H
            qkv[base:base + H] = (pk.mat(pre + W.LAYER_MATS[0]) @ h).astype(np.float32)
            qkv[base + H:base + 2 * H] = (pk.mat(pre + W.LAYER_MATS[1]) @ h).astype(np.float32)
            qkv[base + 2 * H:base + 3 * H] = (pk.mat(pre + W.LAYER_MATS[2]) @ h).astype(np.float32)

            # --- attention：j<p 读 cache，j==p 用本帧的 qkv，与着色器一致 ---
            out = np.zeros(H, np.float32)
            j0 = max(0, p - win + 1) if win > 0 else 0
            for hh in range(NH):
                hb = hh * HD
                q = qkv[base + hb: base + hb + HD]
                scores = []
                for j in range(j0, p + 1):
                    k = (qkv[base + H + hb: base + H + hb + HD] if j == p
                         else self.kv[i * H + hb: i * H + hb + HD, j])
                    scores.append(np.float32(np.dot(q, k)))     # GPT-Neo 不缩放
                s = np.array(scores, np.float32)
                e = np.exp(s - s.max())
                a = e / e.sum()
                acc = np.zeros(HD, np.float32)
                for n, j in enumerate(range(j0, p + 1)):
                    v = (qkv[base + 2 * H + hb: base + 2 * H + hb + HD] if j == p
                         else self.kv[H * L + i * H + hb: H * L + i * H + hb + HD, j])
                    acc += a[n] * v
                out[hb:hb + HD] = acc

            x = (x + pk.mat(pre + W.LAYER_MATS[3]) @ out
                 + pk.vec(pre + "attn.attention.out_proj.bias")).astype(np.float32)
            h = ln32(x, pk.vec(pre + "ln_2.weight"), pk.vec(pre + "ln_2.bias"), pk.EPS)
            f = gelu32((pk.mat(pre + W.LAYER_MATS[4]) @ h
                        + pk.vec(pre + "mlp.c_fc.bias")).astype(np.float32))
            x = (x + pk.mat(pre + W.LAYER_MATS[5]) @ f
                 + pk.vec(pre + "mlp.c_proj.bias")).astype(np.float32)

        lnf = ln32(x, pk.vec("ln_f.weight"), pk.vec("ln_f.bias"), pk.EPS)

        # --- argmax：先分段扫出候选，再回算候选分数，与两个通道的分工一致 ---
        wte = pk.mat("wte.weight")
        per = (pk.VOCAB + pk.SLICES - 1) // pk.SLICES
        cands = []
        for slot in range(pk.SLICES):
            lo, hi = slot * per, min((slot + 1) * per, pk.VOCAB)
            if lo >= hi:
                cands.append(lo if lo < pk.VOCAB else 0)
                continue
            cands.append(lo + int((wte[lo:hi] @ lnf).argmax()))
        scores = wte[cands] @ lnf
        self.tok = int(cands[int(scores.argmax())])

        # --- kvmerge：本帧的 k/v 落进第 p 列 ---
        for i in range(L):
            b = i * 3 * H
            self.kv[i * H:(i + 1) * H, p] = qkv[b + H: b + 2 * H]
            self.kv[H * L + i * H: H * L + (i + 1) * H, p] = qkv[b + 2 * H: b + 3 * H]

    def run(self, prompt_ids, n_new):
        pk = self.pk
        self.seq[:] = 0
        self.seq[:len(prompt_ids)] = prompt_ids
        self.pos = 1
        out = list(prompt_ids)
        # 前 plen 帧是预热：照跑，但把结果丢掉，只为把 prompt 的 k/v 填进 cache
        while self.pos < len(prompt_ids):
            self.step()
            self.pos += 1
        for _ in range(n_new):
            self.step()
            if self.pos < pk.MAXSEQ:
                self.seq[self.pos] = self.tok
            out.append(self.tok)
            self.pos += 1
        return out


def reference(cache_dir, prompt_ids, n_new):
    """同一份量化权重、float64、每步整段重算（不用 KV cache）——作为基准。

    刻意不用 cache：链那边用了 cache，基准这边不用，两者跑出同样的结果才说明
    cache 的读写时机是对的。若两边都用 cache，写反了也看不出来。
    """
    packed = W.build(cache_dir, progress=lambda *_: None)
    m = packed.meta
    H, L, NH, HD = m["H"], m["L"], m["NH"], m["HD"]
    EPS, WIN = m["EPS"], m["WINDOW"]
    local = [bool(v) for v in m["attn_local"]]

    def M(n):
        return W.dequantized(packed, n)

    def V(n):
        return W.vector(packed, n)

    def ln(x, w, b):
        mu = x.mean(-1, keepdims=True)
        return (x - mu) / np.sqrt(x.var(-1, keepdims=True) + EPS) * w + b

    def gelu(v):
        return 0.5 * v * (1 + np.tanh(0.7978845608028654 * (v + 0.044715 * v ** 3)))

    ids = list(prompt_ids)
    wte = M("wte.weight")
    wpe = M("wpe.weight")
    for _ in range(n_new):
        n = len(ids)
        x = wte[ids] + wpe[:n]
        for i in range(L):
            pre = "h.%d." % i
            win = WIN if local[i] else 0
            h = ln(x, V(pre + "ln_1.weight"), V(pre + "ln_1.bias"))
            q = (h @ M(pre + W.LAYER_MATS[0]).T).reshape(n, NH, HD)
            k = (h @ M(pre + W.LAYER_MATS[1]).T).reshape(n, NH, HD)
            v = (h @ M(pre + W.LAYER_MATS[2]).T).reshape(n, NH, HD)
            o = np.zeros((n, NH, HD))
            for hh in range(NH):
                s = q[:, hh] @ k[:, hh].T          # GPT-Neo 不做 1/sqrt(d) 缩放
                msk = np.triu(np.ones((n, n)), 1).astype(bool)
                if win > 0:
                    msk |= np.tril(np.ones((n, n)), -win).astype(bool)
                s = np.where(msk, -1e9, s)
                s -= s.max(-1, keepdims=True)
                a = np.exp(s)
                a /= a.sum(-1, keepdims=True)
                o[:, hh] = a @ v[:, hh]
            x = (x + o.reshape(n, H) @ M(pre + W.LAYER_MATS[3]).T
                 + V(pre + "attn.attention.out_proj.bias"))
            h = ln(x, V(pre + "ln_2.weight"), V(pre + "ln_2.bias"))
            f = gelu(h @ M(pre + W.LAYER_MATS[4]).T + V(pre + "mlp.c_fc.bias"))
            x = x + f @ M(pre + W.LAYER_MATS[5]).T + V(pre + "mlp.c_proj.bias")
        logits = ln(x, V("ln_f.weight"), V("ln_f.bias"))[-1] @ wte.T
        ids.append(int(logits.argmax()))
    return ids


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--pack", type=pathlib.Path,
                    default=ROOT / "build/distributions/GTShaders-LLM-26.3.zip")
    ap.add_argument("--cache", type=pathlib.Path, default=ROOT / "build/llm-cache")
    ap.add_argument("--prompt", default="Once upon a time, there was a dragon")
    ap.add_argument("--tokens", type=int, default=24)
    a = ap.parse_args()

    sys.path.insert(0, str(ROOT / "tools"))
    from llm_tokenizer import BPE
    bpe = BPE(a.cache / "vocab.json", a.cache / "merges.txt")
    ids = bpe.encode(a.prompt)

    pk = Pack(a.pack)
    print("资源包 %s：%d 通道 / %d 目标，指纹 %s"
          % (a.pack.name, pk.layout["passes"], pk.layout["targets"], pk.layout["digest"]))
    sim = ChainSim(pk).run(ids, a.tokens)
    ref = reference(a.cache, ids, a.tokens)

    got, want = sim[len(ids):], ref[len(ids):]
    same = sum(1 for x, y in zip(got, want) if x == y)
    print("=" * 70)
    print("链模拟 :", bpe.decode(sim))
    print("-" * 70)
    print("参考   :", bpe.decode(ref))
    print("=" * 70)
    print("逐 token 一致 %d/%d" % (same, len(want)))
    if same != len(want):
        first = next(i for i, (x, y) in enumerate(zip(got, want)) if x != y)
        print("首个分歧在第 %d 个：链算出 %d(%r)，参考是 %d(%r)"
              % (first, got[first], pk.token_text(got[first]),
                 want[first], pk.token_text(want[first])))
        return 1
    print("通过：整条链的算法与参考实现完全一致")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
