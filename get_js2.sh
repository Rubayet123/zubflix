#!/bin/bash
html=$(curl -s http://10.16.100.244/ --max-time 5)
session=$(echo "$html" | grep -oP 'name="session"\s+value="\K[^"]+')
login_html=$(curl -s -X POST http://10.16.100.244/ -d "username=FTL&password=FTL&session=$session" -c cookies.txt -b cookies.txt --max-time 5)
dash_session=$(echo "$login_html" | grep -oP 'dashboard\.php\?session=\K[^"]+' | head -n 1)

dash_url="http://10.16.100.244/dashboard.php?session=${dash_session}&category=0"
curl -s "$dash_url" -c cookies.txt -b cookies.txt --max-time 5 | grep -i cpage -B 10 -A 20
