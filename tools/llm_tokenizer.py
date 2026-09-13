"""GPT-2 的 byte-level BPE，纯 Python 实现。

模型侧不需要它——着色器只认 token id。需要它的是两头：
把玩家输入的 prompt 切成 id 喂进 uniform，以及在对拍时把 id 还原成人能读的文本。

`transformers` 装不上（这台机器上 pip 走境外源），而 BPE 本身只有几十行，
真正的内容全在 `vocab.json` 与 `merges.txt` 里。
"""

import functools
import json
import re


def _byte_encoder():
    """GPT-2 那套「把任意字节映射到可打印字符」的表。"""
    bs = list(range(33, 127)) + list(range(161, 173)) + list(range(174, 256))
    cs = bs[:]
    n = 0
    for b in range(256):
        if b not in bs:
            bs.append(b)
            cs.append(256 + n)
            n += 1
    return dict(zip(bs, [chr(c) for c in cs]))


B2U = _byte_encoder()
U2B = {v: k for k, v in B2U.items()}

#: GPT-2 的切分正则。前缀空格跟着后一个词走，这是它和普通分词器最大的差别
PAT = re.compile(r"'s|'t|'re|'ve|'m|'ll|'d| ?[A-Za-z]+| ?[0-9]+| ?[^\sA-Za-z0-9]+|\s+(?!\S)|\s+")


class BPE:
    def __init__(self, vocab_path, merges_path):
        with open(vocab_path, encoding="utf-8") as f:
            self.encoder = json.load(f)
        self.decoder = {v: k for k, v in self.encoder.items()}
        with open(merges_path, encoding="utf-8") as f:
            lines = f.read().split("\n")[1:]        # 第一行是版本号
        pairs = [tuple(l.split()) for l in lines if l.strip()]
        self.ranks = dict(zip(pairs, range(len(pairs))))

    @functools.lru_cache(maxsize=200000)
    def _merge(self, token):
        word = tuple(token)
        while len(word) > 1:
            pairs = set(zip(word[:-1], word[1:]))
            best = min(pairs, key=lambda p: self.ranks.get(p, 1 << 40))
            if best not in self.ranks:
                break
            a, b = best
            out, i = [], 0
            while i < len(word):
                if i < len(word) - 1 and word[i] == a and word[i + 1] == b:
                    out.append(a + b)
                    i += 2
                else:
                    out.append(word[i])
                    i += 1
            word = tuple(out)
        return word

    def encode(self, text):
        ids = []
        for chunk in PAT.findall(text):
            piece = "".join(B2U[b] for b in chunk.encode("utf-8"))
            ids += [self.encoder[p] for p in self._merge(piece)]
        return ids

    def decode(self, ids):
        s = "".join(self.decoder.get(int(i), "") for i in ids)
        return bytearray(U2B[c] for c in s if c in U2B).decode("utf-8", errors="replace")
