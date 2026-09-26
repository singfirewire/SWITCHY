"""เรนเดอร์ไอคอน SWITCHY จากไฟล์ VectorDrawable จริง (res/drawable/*.xml)

อ่าน pathData + gradient จากไฟล์ XML ตรง ๆ (ไม่วาดซ้ำด้วยมือ) แล้วประกอบเป็น SVG
เพื่อให้เห็นภาพเดียวกับที่ Android จะวาดจริง จากนั้นมาสก์เป็นวงกลม 72/108 ตามสเปก adaptive icon

ใช้:  python render_icon_from_xml.py <out.png> [--circle]
"""
from __future__ import annotations

import re
import subprocess
import sys
from pathlib import Path

RES = Path(__file__).resolve().parents[2] / "android/app/src/main/res/drawable"
CHROME = Path("C:/Program Files/Google/Chrome/Application/chrome.exe")
OUT_DIR = Path(__file__).resolve().parent


def read(name: str) -> str:
    return (RES / name).read_text(encoding="utf-8")


def paths_with_gradients(xml: str) -> list[dict]:
    """ดึง <path> แต่ละอันพร้อม gradient ที่ผูกอยู่"""
    out: list[dict] = []
    # แยกทีละ <path ...> ... </path> หรือ <path ... />
    for block in re.findall(r"<path\b.*?(?:/>|</path>)", xml, flags=re.S):
        d = re.search(r'android:pathData="([^"]+)"', block)
        if not d:
            continue
        item: dict = {"d": d.group(1)}
        alpha = re.search(r'android:fillAlpha="([\d.]+)"', block)
        if alpha:
            item["alpha"] = float(alpha.group(1))
        color = re.search(r'android:fillColor="(#[0-9A-Fa-f]{6,8})"', block)
        if color:
            item["color"] = color.group(1)
        grad = re.search(r"<gradient\b(.*?)/>", block, flags=re.S)
        if grad:
            g = grad.group(1)
            item["gradient"] = {
                "type": re.search(r'android:type="(\w+)"', g).group(1),
                "colors": re.findall(r'android:(?:start|center|end)Color="(#[0-9A-Fa-f]{6,8})"', g),
                "centerX": re.search(r'android:centerX="([\d.]+)"', g),
                "centerY": re.search(r'android:centerY="([\d.]+)"', g),
                "radius": re.search(r'android:gradientRadius="([\d.]+)"', g),
                "startY": re.search(r'android:startY="([\d.]+)"', g),
                "endY": re.search(r'android:endY="([\d.]+)"', g),
            }
        out.append(item)
    return out


def split_color(c: str) -> tuple[str, float]:
    """Android รับทั้ง #RRGGBB และ #AARRGGBB — คืน (สี CSS, ความทึบ)"""
    if len(c) == 9:  # #AARRGGBB
        return "#" + c[3:9], int(c[1:3], 16) / 255
    return c, 1.0


def to_svg(layers: list[tuple[str, list[dict]]]) -> str:
    defs, body = [], []
    for li, (_, items) in enumerate(layers):
        for pi, it in enumerate(items):
            gid = f"g{li}_{pi}"
            if "gradient" in it:
                g = it["gradient"]
                cols = g["colors"] or ["#888888"]
                stops = "".join(
                    f'<stop offset="{i / max(1, len(cols) - 1):.2f}" '
                    f'stop-color="{split_color(c)[0]}" stop-opacity="{split_color(c)[1]:.3f}"/>'
                    for i, c in enumerate(cols)
                )
                if g["type"] == "radial":
                    defs.append(
                        f'<radialGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                        f'cx="{g["centerX"].group(1)}" cy="{g["centerY"].group(1)}" '
                        f'r="{g["radius"].group(1)}">{stops}</radialGradient>'
                    )
                else:
                    defs.append(
                        f'<linearGradient id="{gid}" gradientUnits="userSpaceOnUse" '
                        f'x1="0" y1="{g["startY"].group(1)}" x2="0" y2="{g["endY"].group(1)}">'
                        f"{stops}</linearGradient>"
                    )
                fill = f"url(#{gid})"
            else:
                fill = it.get("color", "#FFFFFF")
            op = f' fill-opacity="{it["alpha"]}"' if "alpha" in it else ""
            body.append(f'<path d="{it["d"]}" fill="{fill}"{op}/>')
    return (
        '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">'
        f"<defs>{''.join(defs)}</defs>{''.join(body)}</svg>"
    )


def main() -> int:
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else OUT_DIR / "icon-from-xml.png"
    circle = "--circle" in sys.argv
    bg = paths_with_gradients(read("ic_launcher_background.xml"))
    fg = paths_with_gradients(read("ic_launcher_foreground.xml"))
    svg = to_svg([("bg", bg), ("fg", fg)])

    mask_css = ""
    if circle:  # มาสก์วงกลม = 72 จาก 108 หน่วย (สเปก adaptive icon ของ Android)
        r = 36 * 4  # สเกล 4 เท่า (108 -> 432)
        mask_css = (
            "<style>html,body{margin:0;width:432px;height:432px;overflow:hidden;"
            "background:transparent}#m{position:absolute;left:0;top:0;width:432px;height:432px;"
            f"overflow:hidden;border-radius:50%;clip-path:circle({r}px at 216px 216px)}}</style>"
            '<div id="m">'
        )
    html = (
        "<!doctype html><html><head><meta charset='utf-8'>"
        f"{mask_css}</head><body>{svg}{'</div>' if circle else ''}</body></html>"
    )
    htmlfile = OUT_DIR / "icon-from-xml.html"
    htmlfile.write_text(html, encoding="utf-8")

    cmd = [
        str(CHROME), "--headless=new", "--disable-gpu", "--no-first-run", "--hide-scrollbars",
        "--user-data-dir=C:/Users/Bon6have6Mouse/AppData/Local/Temp/chrome-icon-prof",
        "--force-device-scale-factor=1", "--window-size=432,432", "--default-background-color=00000000",
        f"--screenshot={str(out).replace(chr(92), '/')}", f"file:///{str(htmlfile).replace(chr(92), '/')}",
    ]
    subprocess.run(cmd, check=False, capture_output=True)
    print(f"เรนเดอร์จาก XML → {out}")
    print(f"  พื้นหลัง {len(bg)} path · ภาพ {len(fg)} path · มาสก์วงกลม: {circle}")
    return 0 if out.exists() else 1


if __name__ == "__main__":
    raise SystemExit(main())
