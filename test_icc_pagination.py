import requests
import re
from bs4 import BeautifulSoup

session = requests.Session()
res = session.get("http://10.16.100.244/")
print("Status:", res.status_code)

match = re.search(r'name="session"\s+value="([^"]+)"', res.text)
if match:
    session_token = match.group(1)
    print("Session:", session_token)
    
    # Login
    login_res = session.post("http://10.16.100.244/", data={
        "username": "FTL",
        "password": "FTL",
        "session": session_token
    })
    
    dash_match = re.search(r'dashboard\.php\?session=([a-f0-9]+)', login_res.text)
    if dash_match:
        dash_session = dash_match.group(1)
        print("Dash Session:", dash_session)
        
        # Dashboard page 1
        dash_url = f"http://10.16.100.244/dashboard.php?session={dash_session}&category=0"
        dash_res = session.get(dash_url)
        print("Dashboard Category 0 length:", len(dash_res.text))
        
        # Page 2 POST
        page2_res = session.post("http://10.16.100.244/command.php", data={
            "cpage": "2",
            "cCat": "0" # guessing?
        }, headers={
            "X-Requested-With": "XMLHttpRequest",
            "Referer": dash_url,
            "Origin": "http://10.16.100.244"
        })
        print("Page 2 len:", len(page2_res.text))
        print(page2_res.text[:200])
        
        if len(page2_res.text) < 100:
            # Let's try passing session
             page2_res2 = session.post("http://10.16.100.244/command.php", data={
                "cpage": "2",
                "cCat": "0",
                "session": dash_session
            }, headers={
                "X-Requested-With": "XMLHttpRequest",
                "Referer": dash_url,
                "Origin": "http://10.16.100.244"
            })
             print("Page 2 with session len:", len(page2_res2.text))
             print(page2_res2.text[:200])
             
