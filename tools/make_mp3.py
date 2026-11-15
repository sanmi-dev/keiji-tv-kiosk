# 掲示物テレビの MP3 予備ページ用の自作BGM（第三者音源なし）を作る。
# 実行: python tools/make_mp3.py [lameenc のあるフォルダ]
#   既定の lameenc 置き場: C:/Users/sanmi/Documents/Codex/2026-11-11/new-chat-5/work/python-libs
# 出力: app/src/main/assets/mp3/<名前>.mp3（モノラル 96kbps・各約70秒）
import math
import random
import struct
import sys
from pathlib import Path

LIBS = sys.argv[1] if len(sys.argv) > 1 else 'C:/Users/sanmi/Documents/Codex/2026-11-11/new-chat-5/work/python-libs'
sys.path.insert(0, LIBS)
import lameenc  # noqa: E402

RATE = 44100
DEST = Path(__file__).resolve().parents[1] / 'app' / 'src' / 'main' / 'assets' / 'mp3'

def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)

# 曲ごと: (ファイル名, テンポ(拍/分), 和音進行(ルートMIDI番号, 長短), 秒数, 乱数の種)
SONGS = [
    ('morning-light', 72, [(60, 'M'), (67, 'M'), (69, 'm'), (65, 'M')], 70, 1),
    ('quiet-garden', 66, [(57, 'm'), (65, 'M'), (60, 'M'), (67, 'M')], 72, 2),
    ('soft-breeze', 60, [(62, 'm'), (67, 'M'), (60, 'M'), (69, 'm')], 68, 3),
]

def chord(root, kind):
    third = 4 if kind == 'M' else 3
    return [root, root + third, root + 7]

def render(bpm, prog, seconds, seed):
    rng = random.Random(seed)
    n = RATE * seconds
    buf = [0.0] * n
    beat = 60.0 / bpm
    bar = beat * 4
    # 和音のパッド（ゆっくり立ち上がる正弦波）
    t_bar = 0.0
    i = 0
    while t_bar < seconds:
        root, kind = prog[i % len(prog)]
        notes = [midi(x - 12) for x in chord(root, kind)]
        s0, s1 = int(t_bar * RATE), min(n, int((t_bar + bar) * RATE))
        for s in range(s0, s1):
            lt = (s - s0) / RATE
            env = min(lt / 0.8, 1.0) * min((bar - lt) / 0.6, 1.0)
            t = s / RATE
            v = 0.0
            for f in notes:
                v += math.sin(2 * math.pi * f * t)
            buf[s] += 0.045 * env * v
        # アルペジオ（ベル風の減衰音）
        tones = chord(root, kind) + [chord(root, kind)[0] + 12]
        for k in range(8):
            if rng.random() < 0.2:
                continue
            f = midi(rng.choice(tones) + 12)
            st = t_bar + k * beat / 2
            a0 = int(st * RATE)
            a1 = min(n, a0 + int(RATE * 2.0))
            for s in range(a0, a1):
                lt = (s - a0) / RATE
                env = min(lt / 0.01, 1.0) * math.exp(-lt * 2.2)
                buf[s] += 0.11 * env * (math.sin(2 * math.pi * f * lt) + 0.25 * math.sin(4 * math.pi * f * lt))
        t_bar += bar
        i += 1
    # 曲頭・曲尾のフェード
    fade = RATE * 3
    for s in range(n):
        g = min(s / fade, (n - 1 - s) / fade, 1.0)
        buf[s] *= g
    pcm = bytearray()
    for v in buf:
        v = max(-0.95, min(0.95, v))
        pcm.extend(struct.pack('<h', int(v * 32767)))
    return bytes(pcm)

def main():
    DEST.mkdir(parents=True, exist_ok=True)
    total = 0
    for name, bpm, prog, sec, seed in SONGS:
        enc = lameenc.Encoder()
        enc.set_bit_rate(96)
        enc.set_in_sample_rate(RATE)
        enc.set_channels(1)
        enc.set_quality(2)
        data = enc.encode(render(bpm, prog, sec, seed)) + enc.flush()
        (DEST / (name + '.mp3')).write_bytes(data)
        total += len(data)
        print(name, sec, 's', len(data), 'bytes')
    print('total', total, 'bytes')

if __name__ == '__main__':
    main()
