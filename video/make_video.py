"""生成 DeepSeek Harness 安卓版介绍视频（1920x1080，30fps，H.264）。

用法：python video/make_video.py
依赖：Pillow、imageio-ffmpeg（自带 ffmpeg 可执行文件）；素材取自仓库根目录截图与 docs/images。
"""
import subprocess
from pathlib import Path

import imageio_ffmpeg
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "video" / "deepseek-harness-intro.mp4"
W, H, FPS, SCENE_SEC, FADE = 1920, 1080, 30, 5, 12  # FADE 为淡入淡出帧数
BG, FG, ACCENT, MUTED = (15, 20, 35), (240, 244, 255), (110, 160, 255), (150, 160, 185)
FONT = "C:/Windows/Fonts/msyh.ttc"
FONT_B = "C:/Windows/Fonts/msyhbd.ttc"


def font(size, bold=False):
    return ImageFont.truetype(FONT_B if bold else FONT, size)


def canvas():
    im = Image.new("RGB", (W, H), BG)
    d = ImageDraw.Draw(im)
    # 简单的纵向渐变背景
    for y in range(H):
        t = y / H
        d.line([(0, y), (W, y)], fill=(int(15 + 10 * t), int(20 + 15 * t), int(35 + 30 * t)))
    return im, d


def center_text(d, y, text, f, fill):
    w = d.textlength(text, font=f)
    d.text(((W - w) / 2, y), text, font=f, fill=fill)


def phone(path, height=920):
    shot = Image.open(ROOT / path).convert("RGB")
    shot = shot.resize((int(shot.width * height / shot.height), height), Image.LANCZOS)
    mask = Image.new("L", shot.size, 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, *shot.size], radius=36, fill=255)
    return shot, mask


def scene_title():
    im, d = canvas()
    icon = Image.open(ROOT / "docs/images/app-icon-512.png").convert("RGBA").resize((300, 300))
    im.paste(icon, ((W - 300) // 2, 170), icon)
    center_text(d, 520, "DeepSeek Harness 安卓版", font(88, True), FG)
    center_text(d, 650, "面向 Android 的本地 AI 工作台", font(48), ACCENT)
    center_text(d, 740, "无需 Root · 本地运行 · 开源 MIT", font(36), MUTED)
    return im


def scene_text(title, lines):
    im, d = canvas()
    center_text(d, 260, title, font(76, True), FG)
    for i, line in enumerate(lines):
        center_text(d, 440 + i * 90, line, font(44), MUTED if i else ACCENT)
    return im


def scene_shot(path, title, bullets):
    im, d = canvas()
    shot, mask = phone(path)
    im.paste(shot, (1180, (H - shot.height) // 2), mask)
    d.text((160, 330), title, font=font(68, True), fill=FG)
    for i, b in enumerate(bullets):
        d.ellipse([160, 482 + i * 90, 180, 502 + i * 90], fill=ACCENT)
        d.text((210, 465 + i * 90), b, font=font(42), fill=MUTED)
    return im


def scene_grid():
    im, d = canvas()
    center_text(d, 110, "安全与扩展", font(72, True), FG)
    cards = [
        ("多模型供应商", "DeepSeek / OpenAI / Claude / Gemini", "凭据经 Android Keystore 加密"),
        ("本机控制台", "只绑定 127.0.0.1", "每次启动生成 256 位传输令牌"),
        ("插件管理", "npm / HTTPS 压缩包 / git+https", "支持更新与回滚"),
        ("可选 Shizuku", "设备 Shell 终端、目标应用副屏", "需用户主动授权"),
    ]
    for i, (t, a, b) in enumerate(cards):
        x, y = 200 + (i % 2) * 790, 280 + (i // 2) * 340
        d.rounded_rectangle([x, y, x + 730, y + 290], radius=28, fill=(30, 40, 66), outline=(60, 80, 130), width=2)
        d.text((x + 50, y + 45), t, font=font(50, True), fill=ACCENT)
        d.text((x + 50, y + 135), a, font=font(34), fill=FG)
        d.text((x + 50, y + 195), b, font=font(34), fill=MUTED)
    return im


def scene_outro():
    im, d = canvas()
    center_text(d, 330, "立即体验", font(90, True), FG)
    center_text(d, 500, "github.com/dphmoblie/deepseek-harness-android", font(46), ACCENT)
    center_text(d, 600, "Android 8.0+ · arm64-v8a · 支持离线安装", font(38), MUTED)
    return im


SCENES = [
    scene_title(),
    scene_text("手机上的完整 Linux 智能体环境",
               ["Ubuntu 24.04 · Node.js 24 · PRoot 用户空间容器",
                "不依赖云服务器，无需注册账号",
                "内置运行时包，SHA-256 校验，离线也能安装"]),
    scene_shot("screen1.png", "适配手机的 Harness 控制台",
               ["窄屏 / 横屏 / 安全区适配", "斜杠指令与 @ 文件对话", "悬浮球快捷进入 AI 小窗"]),
    scene_shot("screen2.png", "首页即会话工作区",
               ["一键打开本机 Harness 服务", "进程被系统结束后引导重连", "会话搜索与新建"]),
    scene_shot("screen5.png", "应用内运行时自检",
               ["逐项探测 Shell、Node、沙箱、PTY", "执行位缺失可就地修复", "日志命中特征直接给出结论"]),
    scene_grid(),
    scene_outro(),
]


def main():
    cmd = [imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-f", "rawvideo", "-pix_fmt", "rgb24",
           "-s", f"{W}x{H}", "-r", str(FPS), "-i", "-", "-c:v", "libx264",
           "-pix_fmt", "yuv420p", "-crf", "20", "-movflags", "+faststart", str(OUT)]
    proc = subprocess.Popen(cmd, stdin=subprocess.PIPE)
    black = Image.new("RGB", (W, H), (0, 0, 0))
    n = FPS * SCENE_SEC
    for scene in SCENES:
        for f in range(n):
            # 首尾 FADE 帧与黑场混合实现淡入淡出
            a = min(1.0, f / FADE, (n - 1 - f) / FADE)
            frame = scene if a >= 1 else Image.blend(black, scene, max(a, 0.0))
            proc.stdin.write(frame.tobytes())
    proc.stdin.close()
    if proc.wait() != 0:
        raise SystemExit("ffmpeg 编码失败")
    print(f"已生成 {OUT}（{len(SCENES) * SCENE_SEC} 秒）")


if __name__ == "__main__":
    main()
