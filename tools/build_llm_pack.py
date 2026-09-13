"""把 TinyStories-1M 打成一个能在 26.3 后处理链里跑起来的资源包。

产物是一个 zip：权重、词表、字形各一张 PNG，十四个着色器，一条 72 个通道的链。
装上它、挂上 `gtllm:llm`，模型就在 GPU 上一帧写一个词。

## 为什么是 8bit

fp32 存法（把浮点位模式塞进 RGBA8，一个纹素一个数）要 15 MB。
换成 per-row 对称 int8、一个纹素装四个权重，权重部分降到 3.74 MB——**四分之一**，
而生成质量看不出差别（量化细节与实测代价见 `llm_weights.py` 的模块注释）。
剩下的 scale 与 bias 仍走 float32 位打包：它们只有 24 万个数，
却直接决定每一行的量程，省这点空间不值得。

## 三个尺寸是怎么定的

- `MAXSEQ = 256`：KV cache 是 `MAXSEQ × 2HL` 的持久目标，而后处理**每个通道都得整张重画**
  （同一通道不能既读又写，也没有 scissor）。256 时每帧搬 26 万个纹素，512 就翻倍。
  模型的局部注意力窗口本来也是 256，超出去的历史对一半的层没有意义。
- `SLICES = 64`：argmax 把 5 万行词表切成 64 段并行扫。
- `DETOK_W = 12`：GPT-2 的 token 最长十几个字节，12 能覆盖绝大多数，超出的截断。

用法：
    python tools/build_llm_pack.py                    # 下载权重并生成到 build/distributions/
    python tools/build_llm_pack.py --keep-dir out/    # 另外摊开一份，方便看生成了什么
"""

import argparse
import io
import json
import pathlib
import sys
import zipfile

import numpy as np
from PIL import Image, ImageDraw, ImageFont

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
import llm_weights as W

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "src/main/resources/assets/gtshaders/llm"
NS = "gtllm"

ATLASW = 2048          # 三张数据图统一的宽度，着色器里靠 % 和 / 还原二维坐标
MAXSEQ = 256
SLICES = 64
DETOK_W = 12
CMW, CMH = 96, 24      # 字符网格
GLYPH, FCOLS, FFIRST = 8, 16, 32
INK = (0.45, 1.0, 0.55)

VSH = "minecraft:core/screenquad"


# ---------------------------------------------------------------- 数据图

def _grid(n_texels):
    return ATLASW, (n_texels + ATLASW - 1) // ATLASW


def weights_png(q_i8):
    """int8 权重，一个纹素装四个。存的是 v+128，着色器读回来减掉。"""
    n = (q_i8.size + 3) // 4
    w, h = _grid(n)
    buf = np.zeros(w * h * 4, np.uint8)
    buf[: q_i8.size] = (q_i8.astype(np.int16) + 128).astype(np.uint8)
    return Image.fromarray(buf.reshape(h, w, 4), "RGBA")


def floats_png(values):
    """float32 位模式，一个纹素一个数。与工程的 gtPackFloat 逐位一致。"""
    v = np.asarray(values, "<f4")
    w, h = _grid(v.size)
    buf = np.zeros(w * h, "<f4")
    buf[: v.size] = v
    return Image.fromarray(np.frombuffer(buf.tobytes(), np.uint8).reshape(h, w, 4), "RGBA")


def detok_png(vocab_path, vocab_size):
    """token -> 最多 12 个字节的可打印文本。

    换行统一压成空格：字符网格是线性排布的，真换行要额外记录列偏移，
    对一个「把故事铺在屏幕上」的展示来说不值这个复杂度。
    """
    enc = json.loads(pathlib.Path(vocab_path).read_text(encoding="utf-8"))
    dec = {v: k for k, v in enc.items()}
    bs = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    u2b = {chr(c): b for b, c in zip(bs, cs)}

    table = np.zeros((vocab_size, DETOK_W), np.uint8)
    for t in range(vocab_size):
        piece = dec.get(t, "")
        try:
            text = bytearray(u2b[c] for c in piece).decode("utf-8", errors="replace")
        except KeyError:
            text = "?"
        j = 0
        for ch in text:
            if j >= DETOK_W:
                break
            o = ord(ch)
            if o in (10, 13, 9):
                o = 32
            table[t, j] = o if 32 <= o < 127 else ord("?")
            j += 1
    flat = table.ravel()
    ntex = (flat.size + 3) // 4
    w, h = _grid(ntex)
    buf = np.zeros(w * h * 4, np.uint8)
    buf[: flat.size] = flat
    return Image.fromarray(buf.reshape(h, w, 4), "RGBA"), h


def font_png(font_path):
    """8×8 点阵图集，ASCII 32..126。后处理拿不到游戏字体，要显示文字就得自己带一张。"""
    chars = [chr(c) for c in range(FFIRST, 127)]
    rows = (len(chars) + FCOLS - 1) // FCOLS
    img = Image.new("RGBA", (FCOLS * GLYPH, rows * GLYPH), (0, 0, 0, 0))
    draw = ImageDraw.Draw(img)
    font = ImageFont.truetype(str(font_path), GLYPH)
    for i, ch in enumerate(chars):
        draw.text(((i % FCOLS) * GLYPH, (i // FCOLS) * GLYPH - 1), ch,
                  font=font, fill=(255, 255, 255, 255))
    return img, rows


# ---------------------------------------------------------------- 维度宏

def dims_glsl(meta, detok_rows):
    d = {
        "ATLASW": ATLASW, "H": meta["H"], "HD": meta["HD"], "NH": meta["NH"],
        "NL": meta["L"], "INTER": meta["INTER"], "MAXSEQ": MAXSEQ,
        "VOCAB": meta["VOCAB"], "SLICES": SLICES, "DETOK_W": DETOK_W,
        "CMW": CMW, "CMH": CMH, "GLYPH": GLYPH, "FCOLS": FCOLS, "FFIRST": FFIRST,
        "PROMPT_VEC4": MAXSEQ // 4,
        "WTE_WOFF": meta["off"]["wte.weight"]["w"], "WTE_SOFF": meta["off"]["wte.weight"]["s"],
        "WPE_WOFF": meta["off"]["wpe.weight"]["w"], "WPE_SOFF": meta["off"]["wpe.weight"]["s"],
    }
    lines = ["// 由 tools/build_llm_pack.py 生成，不要手改"]
    lines += ["#define %s %d" % (k, v) for k, v in d.items()]
    lines.append("#define EPS %r" % meta["EPS"])
    for name, v in zip(("COLR", "COLG", "COLB"), INK):
        lines.append("#define %s %s" % (name, float(v)))
    return "\n".join(lines) + "\n"


# ---------------------------------------------------------------- 通道链

def _u(name, typ, value):
    return {"name": name, "type": typ, "value": value}


def _pass(shader, inputs, out, uniforms=None):
    o = {"vertex_shader": VSH, "fragment_shader": "%s:post/%s" % (NS, shader),
         "inputs": inputs, "output": out}
    if uniforms:
        o["uniforms"] = uniforms
    return o


def _t(name, sampler):
    return {"sampler_name": sampler, "target": name}


def _tex(loc, sampler, w, h):
    return {"sampler_name": sampler, "location": loc, "width": w, "height": h,
            "bilinear": False}


def build_chain(meta, sizes):
    """72 个通道。顺序即依赖，改动前先读 docs/reference/着色器里的语言模型.md 第三节。"""
    H, L, INTER = meta["H"], meta["L"], meta["INTER"]
    off = meta["off"]
    KVROWS = 2 * H * L
    QKVROWS = 3 * H * L

    def tgt(w, h, persist=False):
        o = {"width": w, "height": h}
        if persist:
            o["persistent"] = True
            o["clear_color"] = 0
        return o

    targets = {
        "state": tgt(8, 1, True), "state_swap": tgt(8, 1),
        "seq": tgt(MAXSEQ, 1, True), "seq_swap": tgt(MAXSEQ, 1),
        "tok": tgt(1, 1, True),
        "layout": tgt(MAXSEQ, 1, True), "layout_swap": tgt(MAXSEQ, 1),
        "charmap": tgt(CMW, CMH, True), "charmap_swap": tgt(CMW, CMH),
        "kv": tgt(MAXSEQ, KVROWS, True), "kv_swap": tgt(MAXSEQ, KVROWS),
        "qkv_a": tgt(1, QKVROWS), "qkv_b": tgt(1, QKVROWS),
        "hid_a": tgt(1, H), "hid_b": tgt(1, H),
        "ln": tgt(1, H), "attn": tgt(1, H), "ffn": tgt(1, INTER), "lnf": tgt(1, H),
        "a1": tgt(SLICES, 1),
        "swap": {},
    }

    WT = _tex("%s:weights" % NS, "Wt", *sizes["weights"])
    MT = _tex("%s:meta" % NS, "Meta", *sizes["meta"])
    DT = _tex("%s:detok" % NS, "Detok", *sizes["detok"])
    FT = _tex("%s:font" % NS, "Font", *sizes["font"])

    # mod 每帧改写的控制块：epoch / prompt 长度 / 生成上限，外加 prompt 的 token 序列。
    # 资源包里写的是「空 prompt」，没有 mod 时它就静静地什么都不生成。
    ctl = {"LlmCtl": [_u("Ctl", "vec4", [0, 0, 0, 0])]
                     + [_u("P%d" % i, "vec4", [0, 0, 0, 0]) for i in range(MAXSEQ // 4)]}

    p = []
    p.append(_pass("copy", [_t("state", "In")], "state_swap"))
    p.append(_pass("copy", [_t("seq", "In")], "seq_swap"))
    p.append(_pass("copy", [_t("layout", "In")], "layout_swap"))
    p.append(_pass("copy", [_t("charmap", "In")], "charmap_swap"))
    # 先写序列再推进状态：seqput 要的是<b>上一帧</b>的位置，顺序反了就会错开一位
    p.append(_pass("seqput", [_t("seq_swap", "Seq"), _t("state_swap", "State"),
                              _t("tok", "Tok")], "seq", ctl))
    p.append(_pass("ingest", [_t("state_swap", "State")], "state", ctl))
    p.append(_pass("embed", [WT, MT, _t("seq", "Seq"), _t("state", "State")], "hid_a"))

    cur, other = "hid_a", "hid_b"
    qkv_cur, qkv_prev = "qkv_a", "qkv_b"
    for i in range(L):
        pre = "h.%d." % i
        win = meta["WINDOW"] if meta["attn_local"][i] else 0

        def two(a, b):
            return {"LlmPass": [_u("A", "vec4", a), _u("B", "vec4", b)]}

        p.append(_pass("layernorm", [WT, MT, _t(cur, "X")], "ln",
                       two([H, 0, off[pre + "ln_1.weight"]["v"], off[pre + "ln_1.bias"]["v"]],
                           [0, 0, 0, 0])))
        p.append(_pass("qkv", [WT, MT, _t("ln", "X"), _t(qkv_prev, "Prev")], qkv_cur,
                       two([i, off[pre + W.LAYER_MATS[0]]["w"], off[pre + W.LAYER_MATS[1]]["w"],
                            off[pre + W.LAYER_MATS[2]]["w"]],
                           [off[pre + W.LAYER_MATS[0]]["s"], off[pre + W.LAYER_MATS[1]]["s"],
                            off[pre + W.LAYER_MATS[2]]["s"], 0])))
        p.append(_pass("attention", [_t(qkv_cur, "Qkv"), _t("kv", "Kv"), _t("state", "State")],
                       "attn", two([i, win, 0, 0], [0, 0, 0, 0])))
        p.append(_pass("matvec", [WT, MT, _t("attn", "X"), _t(cur, "R")], other,
                       two([H, H, off[pre + W.LAYER_MATS[3]]["w"], off[pre + W.LAYER_MATS[3]]["s"]],
                           [off[pre + "attn.attention.out_proj.bias"]["v"], 0, 1, 0])))
        cur, other = other, cur
        qkv_cur, qkv_prev = qkv_prev, qkv_cur

        p.append(_pass("layernorm", [WT, MT, _t(cur, "X")], "ln",
                       two([H, 0, off[pre + "ln_2.weight"]["v"], off[pre + "ln_2.bias"]["v"]],
                           [0, 0, 0, 0])))
        p.append(_pass("matvec", [WT, MT, _t("ln", "X"), _t("ln", "R")], "ffn",
                       two([H, INTER, off[pre + W.LAYER_MATS[4]]["w"],
                            off[pre + W.LAYER_MATS[4]]["s"]],
                           [off[pre + "mlp.c_fc.bias"]["v"], 1, 0, 0])))
        p.append(_pass("matvec", [WT, MT, _t("ffn", "X"), _t(cur, "R")], other,
                       two([INTER, H, off[pre + W.LAYER_MATS[5]]["w"],
                            off[pre + W.LAYER_MATS[5]]["s"]],
                           [off[pre + "mlp.c_proj.bias"]["v"], 0, 1, 0])))
        cur, other = other, cur

    p.append(_pass("layernorm", [WT, MT, _t(cur, "X")], "lnf",
                   {"LlmPass": [_u("A", "vec4", [H, 0, off["ln_f.weight"]["v"],
                                                 off["ln_f.bias"]["v"]]),
                                _u("B", "vec4", [0, 0, 0, 0])]}))
    p.append(_pass("argmax1", [WT, MT, _t("lnf", "H")], "a1"))
    p.append(_pass("argmax2", [WT, MT, _t("lnf", "H"), _t("a1", "A1")], "tok"))
    # qkv_prev 才是最后一层写出去的那张：循环末尾又交换了一次
    p.append(_pass("copy", [_t("kv", "In")], "kv_swap"))
    p.append(_pass("kvmerge", [_t("kv_swap", "Cache"), _t(qkv_prev, "Qkv"),
                               _t("state", "State")], "kv"))
    p.append(_pass("layoutput", [DT, _t("layout_swap", "Layout"), _t("seq", "Seq"),
                                 _t("state", "State")], "layout"))
    p.append(_pass("charmapput", [DT, _t("charmap_swap", "Char"), _t("layout", "Layout"),
                                  _t("seq", "Seq"), _t("state", "State")], "charmap"))
    p.append(_pass("display", [_t("minecraft:main", "In"), _t("charmap", "Char"),
                               FT, _t("state", "State")], "swap",
                   {"LlmView": [_u("View", "vec4", [2, 16, 0, 0])]}))
    p.append({"vertex_shader": VSH, "fragment_shader": "minecraft:post/blit",
              "inputs": [_t("swap", "In")], "output": "minecraft:main",
              "uniforms": {"BlitConfig": [_u("ColorModulate", "vec4", [1, 1, 1, 1])]}})
    return {"targets": targets, "passes": p}


# ---------------------------------------------------------------- 组装

def build(cache_dir, output, font_path, keep_dir=None, progress=print):
    W.fetch(cache_dir, progress)
    packed = W.build(cache_dir, progress)
    meta = dict(packed.meta)
    meta["off"] = packed.offsets

    wimg = weights_png(packed.q_i8)
    # scale 与不量化的向量拼成一张：着色器里一个 metaAt(i) 就能取，不必分两张图
    mimg = floats_png(np.concatenate([packed.scales, packed.vecs]))
    dimg, _ = detok_png(cache_dir / "vocab.json", meta["VOCAB"])
    fimg, _ = font_png(font_path)
    # vecs 接在 scales 后面，所以每个向量的偏移要整体后移
    for name, o in packed.offsets.items():
        if "v" in o:
            o["v"] += int(packed.scales.size)

    sizes = {"weights": wimg.size, "meta": mimg.size, "detok": dimg.size, "font": fimg.size}
    chain = build_chain(meta, sizes)

    files = {}

    def put(path, data):
        files[path] = data if isinstance(data, bytes) else data.encode("utf-8")

    def png(img):
        b = io.BytesIO()
        img.save(b, "PNG", optimize=True)
        return b.getvalue()

    put("pack.mcmeta", json.dumps({"pack": {
        "description": "GTShaders · 在后处理里跑的语言模型 (TinyStories-1M int8)",
        "pack_format": 97, "min_format": 97, "max_format": 99}}, ensure_ascii=False, indent=1))
    for nm, img in (("weights", wimg), ("meta", mimg), ("detok", dimg), ("font", fimg)):
        put("assets/%s/textures/effect/%s.png" % (NS, nm), png(img))
    put("assets/%s/shaders/include/dims.glsl" % NS, dims_glsl(meta, 0))
    for f in sorted((SRC / "include").glob("*.glsl")):
        put("assets/%s/shaders/include/%s" % (NS, f.name), f.read_text(encoding="utf-8"))
    for f in sorted((SRC / "post").glob("*.fsh")):
        put("assets/%s/shaders/post/%s" % (NS, f.name), f.read_text(encoding="utf-8"))
    put("assets/%s/post_effect/llm.json" % NS, json.dumps(chain, indent=1))
    # tokenizer 跟着资源包走而不是塞进 mod jar：它和权重是配套的，
    # 换模型就得整套换，分开放迟早会出现「jar 里的词表对不上包里的权重」这种事。
    # 而且 1.2 MB 的词表进 jar 会让所有不用这个功能的人也背着它。
    for name in ("vocab.json", "merges.txt"):
        put("assets/%s/tokenizer/%s" % (NS, name), (cache_dir / name).read_bytes())

    layout = {"model": meta["model"], "digest": W.digest(packed), "dims": {
        k: meta[k] for k in ("H", "L", "NH", "HD", "INTER", "VOCAB", "WPE_MAX", "WINDOW", "EPS")},
        "MAXSEQ": MAXSEQ, "SLICES": SLICES, "DETOK_W": DETOK_W, "ATLASW": ATLASW,
        "CMW": CMW, "CMH": CMH, "passes": len(chain["passes"]),
        "targets": len(chain["targets"]), "offsets": packed.offsets,
        "n_scales": int(packed.scales.size), "textures": {k: list(v) for k, v in sizes.items()}}
    put("layout.json", json.dumps(layout, indent=1))

    output.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as z:
        for path, data in sorted(files.items()):
            z.writestr(path, data)
    if keep_dir:
        keep = pathlib.Path(keep_dir)
        for path, data in files.items():
            dst = keep / path
            dst.parent.mkdir(parents=True, exist_ok=True)
            dst.write_bytes(data)

    total = output.stat().st_size
    progress("=" * 66)
    for nm, img in (("weights", wimg), ("meta", mimg), ("detok", dimg), ("font", fimg)):
        raw = len(files["assets/%s/textures/effect/%s.png" % (NS, nm)])
        progress("  %-8s %5d×%-5d  PNG %7.1f KB" % (nm, img.width, img.height, raw / 1024))
    progress("通道 %d 个，目标 %d 个" % (len(chain["passes"]), len(chain["targets"])))
    progress("资源包 %s  %.2f MB" % (output.name, total / 1e6))
    return layout


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--cache", type=pathlib.Path, default=ROOT / "build/llm-cache",
                    help="权重下载目录（不入库）")
    ap.add_argument("--output", type=pathlib.Path,
                    default=ROOT / "build/distributions/GTShaders-LLM-26.3.zip")
    ap.add_argument("--font", type=pathlib.Path, default=pathlib.Path("C:/Windows/Fonts/consola.ttf"))
    ap.add_argument("--keep-dir", type=pathlib.Path, default=None,
                    help="额外摊开一份到这个目录，便于查看")
    a = ap.parse_args()
    build(a.cache, a.output, a.font, a.keep_dir)


if __name__ == "__main__":
    main()
