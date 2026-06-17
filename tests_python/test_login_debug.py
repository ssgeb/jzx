# -*- coding: utf-8 -*-
"""调试登录页面结构"""
from playwright.sync_api import sync_playwright
import time

with sync_playwright() as p:
    browser = p.chromium.launch(headless=True, channel="chrome")
    page = browser.new_page(viewport={"width": 1400, "height": 900})

    page.goto("http://localhost:3001/login")
    page.wait_for_load_state("networkidle")
    time.sleep(2)

    # 截图
    page.screenshot(path="/tmp/debug_login.png", full_page=True)

    # 获取所有input
    inputs = page.locator("input").all()
    print(f"找到 {len(inputs)} 个 input 元素")
    for i, inp in enumerate(inputs):
        input_type = inp.get_attribute("type") or "text"
        placeholder = inp.get_attribute("placeholder") or ""
        visible = inp.is_visible()
        print(f"  input[{i}]: type={input_type}, placeholder={placeholder}, visible={visible}")

    # 获取页面HTML
    html = page.content()
    # 查找input相关内容
    import re
    input_matches = re.findall(r'<input[^>]*>', html)
    for m in input_matches:
        print(f"HTML input: {m}")

    browser.close()
