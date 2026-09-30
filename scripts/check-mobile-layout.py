"""浏览器布局回归：外壳使用本地预览，DSH 设置使用官方样式和结构夹具。
需要安装 Python playwright，并运行 pnpm run preview -- --port 4173。
截图和测试报告仅保存在忽略的 output 目录。
"""
import argparse
import json
from pathlib import Path
import re
from playwright.sync_api import sync_playwright

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / 'output' / 'mobile-layout'
OUT.mkdir(parents=True, exist_ok=True)


def check(condition, message):
    if not condition:
        raise AssertionError(message)


def official_fixture():
    files = list((ROOT / 'scripts/runtime-profile/node_modules/.pnpm').glob(
        '@deepseek-ai+dsh-client-ui-*/node_modules/@deepseek-ai/dsh-client-ui-settings-general/lib/client.js'))
    check(bool(files), '请先安装运行时 profile 依赖')
    source = files[0].read_text(encoding='utf-8')
    css = next(json.loads(match.group(1)) for match in re.finditer(
        r'const css(?:\$\d+)? = ("(?:[^"\\]|\\.)*");', source) if '_navList' in match.group(1))
    prefix = re.search(r'\.([A-Za-z0-9]+)_navList', css).group(1)
    tabs = ''.join(f'<button class="{prefix}_navCell">{name}</button>' for name in
                   ['常规设置', '模型供应商', '插件管理', '设备工具', '权限设置', '高级设置'])
    fields = ''.join(f'<label style="display:block;margin:16px 0">配置项目 {index}'
                    f'<input style="display:block;width:100%" value="示例设置"></label>' for index in range(25))
    html = f'''<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
      <style>:root {{--dsw-alias-bg-layer-2:white;--dsw-alias-label-primary:#17202b}}
      body {{font:16px sans-serif;background:#eef2f8}} input {{box-sizing:border-box}} {css}</style></head>
      <body><div class="{prefix}_overlay" role="presentation"><div class="{prefix}_mask"></div>
      <div class="{prefix}_panel" role="dialog" aria-modal="true" aria-labelledby="settings-title">
      <nav class="{prefix}_nav"><div class="{prefix}_navTitle" id="settings-title">设置</div>
      <div class="{prefix}_navList">{tabs}</div></nav><div class="{prefix}_content">
      <div class="{prefix}_header"><span>常规设置</span><button id="close" aria-label="关闭设置">关闭</button></div>
      <div class="{prefix}_options"><section data-slot="settings.section">{fields}
      <button id="save">保存设置</button></section></div></div></div></div></body></html>'''
    return html


def shell(page, url, width, height):
    failures = []
    page.on('pageerror', lambda error: failures.append(str(error)))
    page.goto(url, wait_until='networkidle')
    page.get_by_role('button', name='继续 / Continue').click()
    page.get_by_role('button', name='跳过引导').click()
    mobile = width < 900
    nav = page.locator('.bottom-navigation' if mobile else '.sidebar-nav')
    check(nav.is_visible(), '预期导航没有显示')
    if mobile:
        check(nav.locator('button').count() == 3, '手机底栏必须恰好三个图标')
        check(not page.locator('.app-sidebar').is_visible(), '手机不应保留侧栏')
    page.get_by_role('button', name='设置' if mobile else '应用设置', exact=True).first.click()
    page.get_by_role('heading', name='设置', exact=True).wait_for()
    check(page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), '设置页出现整体横向溢出')
    page.screenshot(path=str(OUT / f'shell-settings-{width}x{height}.png'))
    page.get_by_role('button', name='插件' if mobile else '插件管理', exact=True).first.click()
    page.get_by_role('heading', name='插件管理', exact=True).wait_for()
    check(page.evaluate('document.documentElement.scrollWidth <= innerWidth + 1'), '插件页出现整体横向溢出')
    page.screenshot(path=str(OUT / f'shell-plugins-{width}x{height}.png'))
    check(not failures, f'页面出现运行错误：{failures}')


def settings_dialog(page, width, height):
    page.set_content(official_fixture())
    page.add_style_tag(path=str(ROOT / 'harness-web/android.css'))
    dialog = page.get_by_role('dialog')
    box = dialog.bounding_box()
    check(box['x'] >= 0 and box['y'] >= 0 and box['x'] + box['width'] <= width + 1
          and box['y'] + box['height'] <= height + 1, '设置弹窗越出视口')
    options = page.locator('[class$=_options]')
    check(options.bounding_box()['width'] >= (width - 48 if width < 900 else 580), '设置正文被侧栏挤压')
    nav = page.locator('[class$=_navList]')
    check(nav.evaluate('(e) => getComputedStyle(e).flexDirection') == ('row' if width < 900 else 'column'), '分类导航方向错误')
    if width == 363:
        check(nav.evaluate('(e) => e.scrollWidth > e.clientWidth'), '窄屏分类应可横向滚动')
        nav.get_by_role('button', name='高级设置').click()
    page.locator('#save').click()
    check(options.evaluate('(e) => e.scrollTop > 0'), '底部设置没有滚动到可操作位置')
    close = page.get_by_role('button', name='关闭设置')
    close.click()
    check(close.is_visible(), '关闭按钮必须持续可见')
    page.screenshot(path=str(OUT / f'dsh-settings-fixture-{width}x{height}.png'))


def main():
    parser = argparse.ArgumentParser(description='检查横竖屏设置与插件布局')
    parser.add_argument('--url', default='http://127.0.0.1:4173/')
    parser.add_argument('--channel', default='msedge')
    args = parser.parse_args()
    report = []
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(channel=args.channel, headless=True)
        for width, height in [(363, 800), (800, 363), (1280, 900)]:
            context = browser.new_context(viewport={'width': width, 'height': height},
                has_touch=width < 900, is_mobile=width < 900, device_scale_factor=1)
            page = context.new_page()
            shell(page, args.url, width, height)
            settings_dialog(page, width, height)
            report.append({'width': width, 'height': height, '外壳': '通过', '官方设置结构夹具': '通过'})
            context.close()
        browser.close()
    (OUT / 'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps(report, ensure_ascii=False))


if __name__ == '__main__':
    main()
