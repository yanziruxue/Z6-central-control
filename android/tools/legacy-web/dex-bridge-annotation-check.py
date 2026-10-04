#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""
产物级验证：直接解析 APK 里 classes.dex 的**注解表**，确认哪些方法真的带
`@JavascriptInterface`，并与「SHIM 实际调用的原生方法」做端到端比对。

为什么需要它（血泪教训）：
  v1.4.6 引入 getAllAppsJson() 时漏了 @JavascriptInterface，而 SHIM 是
  `R.getAllAppsJson()` 调的 → Android 4.2+ 下 JS 侧等同「方法不存在」→ 抛错 →
  SHIM 的 catch(e){cb([])} 静默转成空数组 → 页面显示「未读到已安装的应用」。
  拖了 8 个版本才定位，因为：
    ① 编译通过、运行不崩、logcat 无异常；
    ② jsdom 冒烟里的桥是 JS 桩，结构性地测不出「原生侧没暴露」；
    ③ 症状与「车机真没装应用」一模一样。
  源码级静态检查见 tools/bridge-contract-check.js；本脚本是**产物级**复查，
  用真实 dex 的注解表收口（工具链里 dexdump -d 不打印注解，无法用于此验证）。

用法:
  python android/tools/dex-bridge-annotation-check.py [apk路径]
  不传路径则取 dist/ 下版本号最大的 APK。
退出码 0 = 通过。
"""
import os
import re
import sys
import glob
import struct
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
JSI = 'Landroid/webkit/JavascriptInterface;'


def uleb128(b, o):
    r = 0
    s = 0
    while True:
        x = b[o]
        o += 1
        r |= (x & 0x7F) << s
        if not (x & 0x80):
            break
        s += 7
    return r, o


def parse_dex(data):
    """返回 (带 @JavascriptInterface 的方法名集合, 全部方法名集合)"""
    if data[:4] != b'dex\n':
        raise ValueError('不是合法 dex')
    map_off = struct.unpack_from('<I', data, 0x34)[0]
    str_size, str_off = struct.unpack_from('<II', data, 0x38)
    type_size, type_off = struct.unpack_from('<II', data, 0x40)
    meth_size, meth_off = struct.unpack_from('<II', data, 0x58)

    # ---- 字符串池 ----
    def string_at(idx):
        off = struct.unpack_from('<I', data, str_off + idx * 4)[0]
        _, o = uleb128(data, off)                      # MUTF-8 长度前缀
        end = data.index(b'\x00', o)
        return data[o:end].decode('utf-8', 'replace')

    # ---- 类型描述符 ----
    def type_at(idx):
        return string_at(struct.unpack_from('<I', data, type_off + idx * 4)[0])

    # ---- 方法名 ----
    def method_name(idx):
        return string_at(struct.unpack_from('<I', data, meth_off + idx * 8 + 4)[0])

    all_methods = {method_name(i) for i in range(meth_size)}

    # ---- 遍历 class_defs，用每个 class 的 annotations_off 精确定位注解表 ----
    # 不按 map 里 0x2006 段的顺序解析：annotations_directory_item 是**变长**结构，
    # 顺序遍历一旦某处尺寸算错就整体错位（实测直接读越界）。class_def_item 里的
    # annotations_off 是直接指针，定位精确可靠。
    cd_size, cd_off = struct.unpack_from('<II', data, 0x60)
    annotated = set()
    for ci in range(cd_size):
        base = cd_off + ci * 32
        ann_off = struct.unpack_from('<I', data, base + 20)[0]   # class_def.annotations_off
        if not ann_off:
            continue
        # ⚠️ annotations_directory_item 的头部是 **4 个 u4 = 16 字节**：
        #      class_annotations_off, fields_size, annotated_methods_size, annotated_parameters_size
        #    它**不含 class_idx**（别按「5 个 u4」读，多读 4 字节会让整个段错位、直接读越界 ——
        #    本脚本第一版就是这么错的）。
        _cann, f_sz, m_sz, p_sz = struct.unpack_from('<IIII', data, ann_off)
        o = ann_off + 16
        o += 8 * f_sz                        # annotated_fields[] (field_idx + annotations_off)
        meth = []
        for _k in range(m_sz):               # annotated_methods[]
            meth.append(struct.unpack_from('<II', data, o))
            o += 8
        o += 8 * p_sz                        # annotated_parameters[] 本体
        for _k in range(p_sz):               # 其后紧跟 ULEB128[p_sz]（参数个数数组）
            _, o = uleb128(data, o)

        for m_idx, a_off in meth:
            if not a_off:
                continue
            set_sz = struct.unpack_from('<I', data, a_off)[0]
            for e in range(set_sz):
                it_off = struct.unpack_from('<I', data, a_off + 4 + e * 4)[0]
                if not it_off:
                    continue
                # annotation_item: visibility(u1) + encoded_annotation{ type_idx, ... }
                t_idx, _ = uleb128(data, it_off + 1)
                if type_at(t_idx) == JSI:
                    annotated.add(method_name(m_idx))
                    break
    return annotated, all_methods


def shim_calls(java_path):
    src = open(java_path, encoding='utf-8').read()
    si = src.find('String SHIM =')
    seg = src[si:si + 12000]
    cut = seg.find('"}(catch(e){}})()"')
    if cut > 0:
        seg = seg[:cut]
    return {m.group(1) for m in re.finditer(r'R\.([A-Za-z_]\w*)\s*\(', seg)}


def main():
    apk = sys.argv[1] if len(sys.argv) > 1 else None
    if not apk:
        cands = glob.glob(os.path.join(ROOT, 'dist', 'Z6CC-*.apk'))
        if not cands:
            print('✗ dist/ 下没有 APK'); return 2

        def ver(p):
            m = re.search(r'Z6CC-(\d+)\.(\d+)\.(\d+)', p)
            return tuple(int(x) for x in m.groups()) if m else (0, 0, 0)
        apk = max(cands, key=ver)

    print('APK :', os.path.basename(apk), '(%d B)' % os.path.getsize(apk))
    with zipfile.ZipFile(apk) as z:
        data = z.read('classes.dex')
    print('dex :', len(data), 'B')

    annotated, all_methods = parse_dex(data)
    calls = shim_calls(os.path.join(ROOT, 'android', 'src', 'com', 'l6', 'carmedia', 'MainActivity.java'))

    print()
    print('dex 里带 @JavascriptInterface 的方法 : %d 个' % len(annotated))
    print('SHIM 实际调用的原生方法           : %d 个' % len(calls))
    missing = sorted(calls - annotated)
    print()
    print('== SHIM 调用的方法是否都真的带注解（端到端）==')
    if missing:
        print('  ✗ 缺注解（真机上会静默返回空数据）:', ', '.join(missing))
    else:
        print('  ✓ 全部 %d 个方法在 dex 注解表里都有 @JavascriptInterface' % len(calls))

    print()
    print('== 注解方法清单 ==')
    for n in sorted(annotated):
        print('   ·', n)
    if len(all_methods) < 5:
        print('  (解析异常：全部方法只读到 %d 个)' % len(all_methods))

    ok = not missing and len(annotated) > 0
    print()
    print('结果:', '✅ 通过' if ok else '❌ 未通过')
    return 0 if ok else 2


if __name__ == '__main__':
    sys.exit(main())
