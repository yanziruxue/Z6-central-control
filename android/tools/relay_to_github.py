#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
relay_to_github.py —— 把 Gitea Runner 构建好的 APK 拉回本地、再发到 GitHub Release。

流程（对应需求：打包好的 apk 拉到本地上传到 GitHub）：
  1. 从 Gitea Release（按 tag）下载 Z6CC-*.apk 到本地 dist/
  2. 在 GitHub 建 Release（draft）→ 上传 APK 资产 → 解除 draft 发为 Latest
     （车机 OTA 直拉 GitHub releases/latest）

配置（环境变量，secret 不落库）：
  GITEA_SERVER   默认 http://192.168.24.10:8024 （本机走局域网 IP，绕开域名；外网回退改此值）
  GITEA_REPO     默认 yanzi/Z6
  GITEA_TOKEN    Gitea 个人令牌（repo 权限）
  GH_REPO        默认 yanziruxue/Z6-central-control
  GH_TOKEN       GitHub PAT（repo / 发布权限）
  TAG            版本 tag（如 v2.0.10）；也可用 --tag 覆盖

用法：
  python3 android/tools/relay_to_github.py --tag v2.0.10
  TAG=v2.0.10 GITEA_TOKEN=xxx GH_TOKEN=yyy python3 android/tools/relay_to_github.py

红线（与既有发版约定一致）：
  ★ tag 必须纯 vX.Y.Z，禁后缀（否则 Ota.normalizeGithubRelease 解析 versionCode=0）。
  ★ 上传 GitHub 资产必须**直连** uploads.github.com（走中继会 301 丢 uploads. 前缀 → 全绿但 assets=0），
    本脚本对 uploads.github.com 强制不走代理；api.github.com 尊重环境 HTTPS_PROXY（需要时走中继）。
"""
import os
import sys
import json
import time
import ssl
import urllib.request
import urllib.error
import urllib.parse

CTX = ssl.create_default_context()

GITEA_SERVER = os.environ.get("GITEA_SERVER", "http://192.168.24.10:8024").rstrip("/")
GITEA_REPO = os.environ.get("GITEA_REPO", "yanzi/Z6")
GITEA_TOKEN = os.environ.get("GITEA_TOKEN", "")
GH_REPO = os.environ.get("GH_REPO", "yanziruxue/Z6-central-control")
GH_TOKEN = os.environ.get("GH_TOKEN", "")


def parse_args():
    tag = os.environ.get("TAG", "")
    args = sys.argv[1:]
    for i, a in enumerate(args):
        if a == "--tag" and i + 1 < len(args):
            tag = args[i + 1]
        elif a.startswith("--tag="):
            tag = a.split("=", 1)[1]
    return tag


def gitea_req(method, url, data=None, binary=False, extra=None):
    h = {"Authorization": "token " + GITEA_TOKEN, "Content-Type": "application/json"}
    if extra:
        h.update(extra)
    body = None
    if data is not None:
        body = data if binary else json.dumps(data).encode()
    req = urllib.request.Request(url, data=body, method=method, headers=h)
    return urllib.request.urlopen(req, timeout=30, context=CTX)


def gh_req(method, url, data=None, binary=False, extra=None, opener=None):
    h = {"Authorization": "token " + GH_TOKEN, "Accept": "application/vnd.github+json"}
    if extra:
        h.update(extra)
    body = None
    if data is not None:
        body = data if binary else json.dumps(data).encode()
    req = urllib.request.Request(url, data=body, method=method, headers=h)
    opener = opener or urllib.request
    return opener.urlopen(req, timeout=30, context=CTX)


def main():
    tag = parse_args()
    if not tag or not tag.startswith("v"):
        print("✗ 缺少版本 tag（--tag vX.Y.Z 或环境变量 TAG）")
        sys.exit(1)
    if "_" in tag or "-" in tag[1:]:
        print("✗ tag 必须纯 vX.Y.Z，禁后缀（OTA 约束）：%s" % tag)
        sys.exit(1)
    if not GITEA_TOKEN or not GH_TOKEN:
        print("✗ 缺少 GITEA_TOKEN 或 GH_TOKEN")
        sys.exit(1)

    # ===== 1. 从 Gitea 下载 APK =====
    g_api = GITEA_SERVER + "/api/v1/repos/" + GITEA_REPO
    print("==> 从 Gitea 取 Release: %s @ %s" % (tag, GITEA_REPO))
    try:
        rel = json.load(gitea_req("GET", g_api + "/releases/tags/" + tag))
    except urllib.error.HTTPError as e:
        print("✗ Gitea 找不到该 tag 的 Release（先让 Gitea Runner 跑完）: %s" % e)
        sys.exit(1)
    asset_url = None
    asset_name = None
    for a in rel.get("assets", []):
        n = a.get("name", "")
        if n.startswith("Z6CC") and n.endswith(".apk"):
            # ★ 一律用 GITEA_SERVER（局域网 IP）构造资产 API 下载地址，绝不读 browser_download_url：
            #   Gitea 的 browser_download_url 按 ROOT_URL 生成（可能是域名），本机连不上域名会卡下载。
            #   API 端点 + Accept: application/octet-stream 返回二进制，且走 token 鉴权。
            asset_id = a.get("id")
            asset_url = GITEA_SERVER + "/api/v1/repos/" + GITEA_REPO + "/releases/assets/" + str(asset_id)
            asset_name = n
            break
    if not asset_url:
        print("✗ Gitea Release 里没有 Z6CC-*.apk 资产（构建是否完成？）")
        sys.exit(1)

    os.makedirs("dist", exist_ok=True)
    local = os.path.join("dist", asset_name)
    print("==> 下载 APK: %s -> %s" % (asset_url, local))
    with gitea_req("GET", asset_url, extra={"Accept": "application/octet-stream"}) as r, open(local, "wb") as f:
        f.write(r.read())
    print("✓ 已拉回本地: %s (%d bytes)" % (local, os.path.getsize(local)))

    # ===== 2. 发到 GitHub Release =====
    gh_api = "https://api.github.com/repos/" + GH_REPO
    # 直连 opener（uploads.github.com 强制不走代理，避免中继 301 丢前缀）
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    # 2a. 找已有 release（按 tag），否则建 draft
    rel_id = None
    try:
        rel_id = json.load(gh_req("GET", gh_api + "/releases/tags/" + tag))["id"]
        print("复用已有 GitHub Release id=%d" % rel_id)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    if rel_id is None:
        r = gh_req("POST", gh_api + "/releases",
                   {"tag_name": tag, "name": tag, "body": "Gitea 自动构建 → 中继发布 " + tag,
                    "draft": True, "prerelease": False})
        rel_id = json.load(r)["id"]
        print("已创建 GitHub Release(draft) id=%d" % rel_id)

    # 2b. 同名资产先删后传（幂等）
    try:
        for a in json.load(gh_req("GET", gh_api + "/releases/%d/assets" % rel_id)):
            if a["name"] == asset_name:
                gh_req("DELETE", gh_api + "/releases/assets/%d" % a["id"])
                print("删除旧资产", asset_name)
    except Exception as e:
        print("list assets warn:", e)

    # 2c. 上传 APK（直连 uploads.github.com，重试）
    data = open(local, "rb").read()
    up_url = ("https://uploads.github.com/repos/" + GH_REPO +
              "/releases/%d/assets?name=%s" % (rel_id, urllib.parse.quote(asset_name)))
    ok = False
    for i in range(8):
        try:
            gh_req("POST", up_url, data=data, binary=True,
                   extra={"Content-Type": "application/octet-stream"}, opener=direct)
            print("✓ GitHub 资产上传成功 %s (%d bytes)" % (asset_name, len(data)))
            ok = True
            break
        except Exception as e:
            print("上传重试 %d/8: %s" % (i + 1, e))
            time.sleep(4)
    if not ok:
        print("✗ GitHub 资产上传最终失败")
        sys.exit(1)

    # 2d. 解除 draft（发布为 Latest，车机 OTA 直拉）
    try:
        gh_req("PATCH", gh_api + "/releases/%d" % rel_id, {"draft": False})
        print("✓ Release 已发布为 Latest")
    except Exception as e:
        print("⚠️ 解除 draft 失败（资产已在，可手动发布）: %s" % e)

    print("完成。GitHub Release: https://github.com/%s/releases/tag/%s" % (GH_REPO, tag))


if __name__ == "__main__":
    main()
