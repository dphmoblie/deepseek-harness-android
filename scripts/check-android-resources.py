#!/usr/bin/env python3
"""Android 资源一致性检查（不需要 Android SDK / Gradle，几秒跑完）。

挡什么问题：
  1. 资源 XML 解析不了（标签没闭合、编码不是 UTF-8）——aapt2 会在这里失败，
     但那时已经过了构建、装了 SDK，本检查把它提到最前面。
  2. 同一个 values 目录里同名资源键重复（aapt2 报 duplicate resource）。
  3. 翻译缺漏：默认语言 values/strings.xml 的键在 values-<语言>/strings.xml 里缺失
     （用户会看到中英混排），或者翻译目录多出默认语言没有的键（aapt2 直接报错）。
  4. 资源值是空字符串或只有空白（界面上会出现空按钮/空标题）。
  5. 引用解析不了：AndroidManifest.xml 与 res 下 XML 里的 @string/x、@color/x、
     @style/x、@drawable/x、@mipmap/x、@xml/x、@layout/x 等必须能在 res 里找到定义
     （@android: 系统资源与 ?attr/ 主题属性跳过）。
  6. 兜底：一个资源文件都没找到、或者默认 strings.xml 一个键都没有时直接失败，
     避免目录被改名后这个检查变成空转。

不在覆盖范围（由别的门禁负责，避免重复劳动）：
  * Kotlin/Java 代码里的 R.string.x / R.layout.x 引用：kotlinc 编译期就会挡住。
  * lint 的资源相关规则（UnusedResources / MissingTranslation 的细节判定）：由
    `./gradlew lintRelease` 负责，本检查只是不需要 SDK 的快速前置门禁。

用法：
  python3 scripts/check-android-resources.py
  python3 scripts/check-android-resources.py --res <res 目录> --manifest <AndroidManifest.xml>
退出码：0 通过；1 发现问题；2 用法错误或目录不存在。
"""

from __future__ import annotations

import argparse
import re
import sys
import xml.etree.ElementTree as ElementTree
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_RES = Path("android/app/src/main/res")
DEFAULT_MANIFEST = Path("android/app/src/main/AndroidManifest.xml")

# 资源类型目录名（res/<type>[-qualifiers]/...），用于从文件名反推已定义的资源。
RESOURCE_TYPE_DIRS = frozenset(
    {
        "anim",
        "animator",
        "array",
        "attr",
        "bool",
        "color",
        "dimen",
        "drawable",
        "font",
        "integer",
        "interpolator",
        "layout",
        "menu",
        "mipmap",
        "navigation",
        "plurals",
        "raw",
        "string",
        "style",
        "transition",
        "values",
        "xml",
    }
)

# values-<qualifiers> 里带这些限定符的不是语言（夜色、版本、屏幕方向等），不参与翻译比对。
NON_LOCALE_QUALIFIERS = frozenset(
    {
        "night",
        "notnight",
        "car",
        "desk",
        "television",
        "watch",
        "appliance",
        "vrheadset",
        "land",
        "port",
        "round",
        "tall",
        "notround",
        "wide",
        "anydpi",
        "nodpi",
        "ldrtl",
        "ldltr",
        "mcc310",
    }
)
# 语言限定符：en / en-rUS / zh-rCN / b+zh+Hans 之类。
LOCALE_QUALIFIER = re.compile(
    r"^(?:b\+[A-Za-z0-9+]+|[a-z]{2,3}(?:-r[A-Z]{2}|-[A-Z]{2})?(?:-ldrtl|-ldltr)?)$"
)

# XML 里的资源引用：@string/x、@+id/x、@color/x……
REFERENCE = re.compile(
    r"@(?:\+)?(?P<type>anim|animator|array|attr|bool|color|dimen|drawable|font|integer|"
    r"interpolator|layout|menu|mipmap|navigation|plurals|raw|string|style|transition|xml)"
    r"/(?P<name>[A-Za-z0-9_.]+)"
)

# values 目录里带 name 属性的资源元素。
VALUE_TAGS = frozenset(
    {
        "array",
        "attr",
        "bool",
        "color",
        "dimen",
        "integer",
        "item",
        "plurals",
        "string",
        "string-array",
        "style",
        "integer-array",
    }
)

TRANSLATABLE_VALUE_TAGS = frozenset({"string"})
CONTAINER_VALUE_TAGS = frozenset(
    {"array", "plurals", "string-array", "integer-array", "style", "attr"}
)


class Problem(Exception):
    """检查不通过；消息直接面向读日志的人。"""


def strip_namespace(attribute: str) -> str:
    return attribute.rsplit("}", 1)[-1]


def parse_xml(path: Path) -> ElementTree.Element:
    try:
        text = path.read_text(encoding="utf-8")
    except UnicodeDecodeError as error:
        raise Problem(f"{path} 不是 UTF-8 编码：{error}") from error
    except OSError as error:
        raise Problem(f"读不到 {path}：{error}") from error
    try:
        return ElementTree.fromstring(text)
    except ElementTree.ParseError as error:
        line, column = error.position
        raise Problem(f"{path} 不是合法 XML（第 {line} 行第 {column} 列）：{error.msg}") from error


def qualifier_of(directory_name: str) -> str | None:
    """values-en -> en；values-night -> None（不是语言）；values -> ''（默认）。"""
    if directory_name == "values":
        return ""
    if not directory_name.startswith("values-"):
        return None
    qualifier = directory_name[len("values-") :]
    head = qualifier.split("-")[0]
    if head in NON_LOCALE_QUALIFIERS:
        return None
    return qualifier if LOCALE_QUALIFIER.match(qualifier) else None


def value_key(element: ElementTree.Element) -> tuple[str, str] | None:
    name = element.attrib.get("name")
    if name is None:
        return None
    tag = element.tag
    if tag == "item":
        item_type = element.attrib.get("type")
        if item_type is None:
            return None
        return item_type, name
    return tag, name


def element_text(element: ElementTree.Element) -> str:
    return "".join(element.itertext()).strip()


def check_values_directory(directory: Path, problems: list[str]) -> dict[tuple[str, str], str]:
    """解析一个 values* 目录，返回 {（类型, 名字）: 值}，并把问题追加到 problems。"""
    definitions: dict[tuple[str, str], str] = {}
    for path in sorted(directory.glob("*.xml")):
        try:
            root = parse_xml(path)
        except Problem as problem:
            problems.append(str(problem))
            continue
        if root.tag != "resources":
            problems.append(f"{path}：根元素应当是 <resources>，实际是 <{root.tag}>")
            continue
        for element in root:
            key = value_key(element)
            if key is None:
                if element.tag in VALUE_TAGS:
                    problems.append(f"{path}：<{element.tag}> 缺少 name 属性")
                continue
            resource_type, name = key
            if key in definitions:
                problems.append(
                    f"{path}：资源 {resource_type}/{name} 在同一个 values 目录里重复定义"
                    f"（aapt2 会报 duplicate resource）"
                )
                continue
            value = element_text(element)
            definitions[key] = value
            if element.tag in TRANSLATABLE_VALUE_TAGS and not value:
                problems.append(f"{path}：字符串 {name} 的值是空的（界面上会出现空白文案）")
            if element.tag in CONTAINER_VALUE_TAGS and len(element) == 0 and not value:
                problems.append(f"{path}：{resource_type}/{name} 没有任何条目")
    return definitions


def collect_file_definitions(res_root: Path) -> set[tuple[str, str]]:
    """从文件名收集非 values 资源：drawable-nodpi/a.png -> (drawable, a)。"""
    definitions: set[tuple[str, str]] = set()
    for path in res_root.rglob("*"):
        if not path.is_file():
            continue
        directory = path.parent.name
        head = directory.split("-")[0]
        if head not in RESOURCE_TYPE_DIRS or head == "values":
            continue
        if path.suffix not in {".xml", ".png", ".jpg", ".jpeg", ".webp", ".9.png", ".svg"}:
            continue
        name = path.name[: -len(".9.png")] if path.name.endswith(".9.png") else path.stem
        definitions.add((head, name))
    return definitions


def check(res_root: Path, manifest: Path) -> list[str]:
    problems: list[str] = []
    if not res_root.is_dir():
        raise Problem(f"资源目录不存在：{res_root}")
    if not manifest.is_file():
        raise Problem(f"AndroidManifest.xml 不存在：{manifest}")

    resource_files = sorted(path for path in res_root.rglob("*") if path.is_file())
    if not resource_files:
        raise Problem(f"资源目录是空的：{res_root}（目录被改名后这个检查会变成空转，所以直接失败）")

    definitions = collect_file_definitions(res_root)
    by_qualifier: dict[str, dict[tuple[str, str], str]] = {}
    for directory in sorted(path for path in res_root.iterdir() if path.is_dir()):
        qualifier = qualifier_of(directory.name)
        if qualifier is None:
            continue
        if qualifier in by_qualifier:
            problems.append(f"发现两个默认语言目录：{directory.name}")
            continue
        by_qualifier[qualifier] = check_values_directory(directory, problems)

    default = by_qualifier.get("")
    if default is None:
        raise Problem(f"没有默认语言目录 res/values（只看 {res_root}）")
    string_keys = {name for resource_type, name in default if resource_type == "string"}
    if not string_keys and not problems:
        raise Problem("res/values 里一个字符串都没有（strings.xml 被改名或清空时这个检查会变成空转，所以直接失败）")

    # 翻译双向比对：缺翻译（回落默认语言）与多出来的键（aapt2 报错）都要报。
    # 注意：definitions 的键是（资源类型, 名字）二元组，所以比对前先各自取名字集合。
    default_strings = {name for resource_type, name in default if resource_type == "string"}
    for qualifier, translated in sorted(by_qualifier.items()):
        if qualifier == "":
            continue
        label = f"values-{qualifier}"
        translated_strings = {
            name for resource_type, name in translated if resource_type == "string"
        }
        for name in sorted(default_strings - translated_strings):
            problems.append(f"res/{label}/strings.xml：缺少默认语言里的字符串 {name}（用户会看到回落文案）")
        for name in sorted(translated_strings - default_strings):
            problems.append(f"res/{label}/strings.xml：多出默认语言没有的字符串 {name}（aapt2 会报错）")

    # 引用解析：manifest 与 res 下所有 XML。
    known = set(definitions) | set(default)
    for qualifier, translated in by_qualifier.items():
        known |= set(translated)
    for path in [manifest, *[path for path in resource_files if path.suffix == ".xml"]]:
        try:
            root = parse_xml(path)
        except Problem as problem:
            problems.append(str(problem))
            continue
        for element in root.iter():
            for attribute, raw in element.attrib.items():
                for match in REFERENCE.finditer(raw):
                    key = (match.group("type"), match.group("name"))
                    if key not in known:
                        problems.append(
                            f"{path}：{strip_namespace(attribute)}=\"{raw}\" 引用的"
                            f" {match.group('type')}/{match.group('name')} 在 res 里没有定义"
                        )
    return problems


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(
        description="Android 资源一致性检查（不需要 SDK）",
    )
    parser.add_argument("--res", default=None, help=f"res 目录（默认 {DEFAULT_RES}）")
    parser.add_argument("--manifest", default=None, help=f"AndroidManifest.xml（默认 {DEFAULT_MANIFEST}）")
    parser.add_argument("--root", default=None, help="仓库根目录（默认脚本所在目录的上一级）")
    args = parser.parse_args(argv)

    root = Path(args.root).resolve() if args.root else REPO_ROOT
    res_root = Path(args.res).resolve() if args.res else root / DEFAULT_RES
    manifest = Path(args.manifest).resolve() if args.manifest else root / DEFAULT_MANIFEST

    try:
        problems = check(res_root, manifest)
    except Problem as problem:
        print(f"[资源检查] 无法完成检查：{problem}", file=sys.stderr)
        return 2

    if problems:
        print(f"[资源检查] 不通过，共 {len(problems)} 处问题：", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1

    file_count = len([path for path in res_root.rglob("*") if path.is_file()])
    string_count = len(
        [
            element
            for path in (res_root / "values").glob("*.xml")
            for element in parse_xml(path)
            if value_key(element) is not None and value_key(element)[0] == "string"
        ]
    )
    print(f"[资源检查] 通过：{file_count} 个资源文件、{string_count} 个默认语言字符串，引用全部可解析")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
