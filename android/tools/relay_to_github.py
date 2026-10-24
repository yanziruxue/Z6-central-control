#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
relay_to_github.py —— 把构建好的 APK 发到 Release（Gitea 备份 / GitHub OTA 主源）。

两种用法（对应两条出包路线）：
  A) 本地已出包：直接把 dist/Z6CC-*.apk 同时发到 Gitea Release + GitHub Release
       python3 relay_to_github.py --tag v2.0.9 --local dist/Z6CC-2.0.9.apk
  B) Gitea Runner 已构建：从 Gitea 拉回本地，再发 GitHub（旧兼容路径）
       python3 relay_to_github.py --tag v2.0.9

默认两条都发（Gitea 备份 + GitHub OTA 主源）；可用 --no-gitea / --no-github 关掉其一。

配置（环境变量，secret 不落库）：
  GITEA_SERVER   默认 http://192.168.24.10:8024 （本机走局域网 IP，绕开域名；外网回退改此值）
  GITEA_REPO     默认 yanzi/Z6
  GITEA_TOKEN    Gitea 个人令牌（repo 权限）      —— 发 Gitea 或从其下载时必填
  GH_REPO        默认 yanziruxue/Z6-central-control
  GH_TOKEN       GitHub PAT（repo / 发布权限）     —— 发 GitHub 时必填
  TAG            版本 tag（如 v2.0.9）；也可用 --tag 覆盖

红线（与既有发版约定一致）：
  ★ tag 必须纯 vX.Y.Z，禁后缀（否则 Ota.normalizeGithubRelease 解析 versionCode=0 → 永远「已是最新」）。
  ★ 上传 GitHub 资产必须**直连** uploads.github.com（走中继会 301 丢 uploads. 前缀 → 全绿但 assets=0），
    本脚本对 uploads.github.com 强制不走代理；api.github.com 尊重环境 HTTPS_PROXY（需要时走中继）。
  ★ Gitea 一律走 GITEA_SERVER（局域网 IP），绝不读 browser_download_url（可能是域名，本机连不上）。
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
    # 返回 (tag, local, no_gitea, no_github)
    tag = os.environ.get("TAG", "")
    local = ""
    no_gitea = False
    no_github = False
    args = sys.argv[1:]
    i = 0
    while i < len(args):
        a = args[i]
        if a == "--tag" and i + 1 < len(args):
            tag = args[i + 1]; i += 2
        elif a.startswith("--tag="):
            tag = a.split("=", 1)[1]; i += 1
        elif a == "--local" and i + 1 < len(args):
            local = args[i + 1]; i += 2
        elif a.startswith("--local="):
            local = a.split("=", 1)[1]; i += 1
        elif a == "--no-gitea":
            no_gitea = True; i += 1
        elif a == "--no-github":
            no_github = True; i += 1
        else:
            i += 1
    return tag, local, no_gitea, no_github


def gitea_req(method, url, data=None, binary=False, extra=None):
    # Gitea 一律走 GITEA_SERVER（局域网 IP），不读任何域名地址
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


def download_from_gitea(tag):
    # 兼容旧路径：从 Gitea Release 拉 Z6CC-*.apk 到本地 dist/，返回 (local_path, asset_name)
    g_api = GITEA_SERVER + "/api/v1/repos/" + GITEA_REPO
    print("==> 从 Gitea 取 Release: %s @ %s" % (tag, GITEA_REPO))
    try:
        rel = json.load(gitea_req("GET", g_api + "/releases/tags/" + tag))
    except urllib.error.HTTPError as e:
        print("✗ Gitea 找不到该 tag 的 Release（先让 Gitea Runner 跑完）: %s" % e)
        sys.exit(1)
    asset_id = None
    asset_name = None
    for a in rel.get("assets", []):
        n = a.get("name", "")
        if n.startswith("Z6CC") and n.endswith(".apk"):
            # ★ 一律用 GITEA_SERVER（局域网 IP）构造资产 API 下载地址，绝不读 browser_download_url（可能是域名）
            asset_id = a.get("id")
            asset_name = n
            break
    if asset_id is None:
        print("✗ Gitea Release 里没有 Z6CC-*.apk 资产（构建是否完成？）")
        sys.exit(1)
    asset_url = GITEA_SERVER + "/api/v1/repos/" + GITEA_REPO + "/releases/assets/" + str(asset_id)
    os.makedirs("dist", exist_ok=True)
    local = os.path.join("dist", asset_name)
    print("==> 下载 APK: %s -> %s" % (asset_url, local))
    with gitea_req("GET", asset_url, extra={"Accept": "application/octet-stream"}) as r, open(local, "wb") as f:
        f.write(r.read())
    print("✓ 已拉回本地: %s (%d bytes)" % (local, os.path.getsize(local)))
    return local, asset_name


def publish_gitea(local, asset_name, tag):
    # 发 Gitea Release（draft → 传资产 → 解除 draft），作备份/分发站
    g_api = GITEA_SERVER + "/api/v1/repos/" + GITEA_REPO
    rel_id = None
    created_draft = False
    try:
        rel_id = json.load(gitea_req("GET", g_api + "/releases/tags/" + tag))["id"]
        print("复用已有 Gitea Release id=%d" % rel_id)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    if rel_id is None:
        r = gitea_req("POST", g_api + "/releases",
                      {"tag_name": tag, "name": tag, "body": "本地构建发布 " + tag,
                       "draft": True, "prerelease": False, "make_latest": True})
        rel_id = json.load(r)["id"]
        created_draft = True
        print("已创建 Gitea Release(draft) id=%d" % rel_id)

    # 同名资产先删后传（幂等）
    try:
        for a in json.load(gitea_req("GET", g_api + "/releases/%d/assets" % rel_id)):
            if a["name"] == asset_name:
                gitea_req("DELETE", g_api + "/releases/assets/%d" % a["id"])
                print("删除旧 Gitea 资产", asset_name)
    except Exception as e:
        print("list Gitea assets warn:", e)

    # 上传资产（二进制，重试）
    data = open(local, "rb").read()
    up_url = g_api + "/releases/%d/assets?name=%s" % (rel_id, urllib.parse.quote(asset_name))
    ok = False
    for i in range(8):
        try:
            gitea_req("POST", up_url, data=data, binary=True,
                      extra={"Content-Type": "application/octet-stream"})
            print("✓ Gitea 资产上传成功 %s (%d bytes)" % (asset_name, len(data)))
            ok = True
            break
        except Exception as e:
            print("Gitea 上传重试 %d/8: %s" % (i + 1, e))
            time.sleep(3)
    if not ok:
        print("✗ Gitea 资产上传最终失败")
        sys.exit(1)

    # 若新建的 draft，解除 draft（发布为 latest）
    if created_draft:
        try:
            gitea_req("PATCH", g_api + "/releases/%d" % rel_id, {"draft": False})
            print("✓ Gitea Release 已发布")
        except Exception as e:
            print("⚠️ 解除 Gitea draft 失败（资产已在，可手动发布）: %s" % e)
    print("Gitea Release: %s/%s/releases/tag/%s" % (GITEA_SERVER, GITEA_REPO, tag))


def publish_github(local, asset_name, tag):
    # 发 GitHub Release（draft → 传资产[直连] → 解除 draft），车机 OTA 主源
    gh_api = "https://api.github.com/repos/" + GH_REPO
    direct = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # uploads.github.com 强制直连

    rel_id = None
    created_draft = False
    try:
        rel_id = json.load(gh_req("GET", gh_api + "/releases/tags/" + tag))["id"]
        print("复用已有 GitHub Release id=%d" % rel_id)
    except urllib.error.HTTPError as e:
        if e.code != 404:
            raise
    if rel_id is None:
        r = gh_req("POST", gh_api + "/releases",
                   {"tag_name": tag, "name": tag, "body": "本地构建发布 " + tag,
                    "draft": True, "prerelease": False})
        rel_id = json.load(r)["id"]
        created_draft = True
        print("已创建 GitHub Release(draft) id=%d" % rel_id)

    try:
        for a in json.load(gh_req("GET", gh_api + "/releases/%d/assets" % rel_id)):
            if a["name"] == asset_name:
                gh_req("DELETE", gh_api + "/releases/assets/%d" % a["id"])
                print("删除旧 GitHub 资产", asset_name)
    except Exception as e:
        print("list GitHub assets warn:", e)

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
            print("GitHub 上传重试 %d/8: %s" % (i + 1, e))
            time.sleep(4)
    if not ok:
        print("✗ GitHub 资产上传最终失败")
        sys.exit(1)

    if created_draft:
        try:
            gh_req("PATCH", gh_api + "/releases/%d" % rel_id, {"draft": False})
            print("✓ GitHub Release 已发布为 Latest")
        except Exception as e:
            print("⚠️ 解除 GitHub draft 失败（资产已在，可手动发布）: %s" % e)
    print("GitHub Release: https://github.com/%s/releases/tag/%s" % (GH_REPO, tag))


def main():
    tag, local, no_gitea, no_github = parse_args()
    if not tag or not tag.startswith("v"):
        print("✗ 缺少版本 tag（--tag vX.Y.Z 或环境变量 TAG）")
        sys.exit(1)
    if "_" in tag or "-" in tag[1:]:
        print("✗ tag 必须纯 vX.Y.Z，禁后缀（OTA 约束）：%s" % tag)
        sys.exit(1)

    post_gitea = not no_gitea
    post_github = not no_github
    need_gitea_dl = (not local) and post_gitea  # 本地没给包又要发 Gitea → 先从 Gitea 下载
    if (need_gitea_dl or post_gitea) and not GITEA_TOKEN:
        print("✗ 发/取 Gitea 需要 GITEA_TOKEN")
        sys.exit(1)
    if post_github and not GH_TOKEN:
        print("✗ 发 GitHub 需要 GH_TOKEN")
        sys.exit(1)

    # ===== 确定本地 APK 路径 =====
    if local:
        apk = os.path.abspath(local)
        if not os.path.exists(apk):
            print("✗ 本地包不存在: %s" % apk)
            sys.exit(1)
        asset_name = os.path.basename(apk)
        print("==> 使用本地包: %s" % apk)
    else:
        apk, asset_name = download_from_gitea(tag)

    # ===== 双平台发布 =====
    if post_gitea:
        publish_gitea(apk, asset_name, tag)
    if post_github:
        publish_github(apk, asset_name, tag)

    print("完成。")


if __name__ == "__main__":
    main()
