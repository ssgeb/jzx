from playwright.sync_api import sync_playwright
p = sync_playwright().start()
b = p.chromium.launch(
    headless=True,
    executable_path=r"C:\Users\19771\AppData\Local\ms-playwright\chromium-1223\chrome-win64\chrome.exe"
)
print('Chromium OK')
b.close()
p.stop()
