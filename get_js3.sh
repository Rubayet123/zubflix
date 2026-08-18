#!/bin/bash
html=$(curl -s http://10.16.100.244/ --max-time 10)
session=$(echo "$html" | grep -oP 'name="session"\s+value="\K[^"]+')
echo "Session: $session"

login_html=$(curl -s -X POST http://10.16.100.244/ -d "username=FTL&password=FTL&session=$session" -c cookies.txt -b cookies.txt --max-time 10)
dash_session=$(echo "$login_html" | grep -oP 'dashboard\.php\?session=\K[^"]+' | head -n 1)

dash_url="http://10.16.100.244/dashboard.php?session=${dash_session}&category=0"
dash_html=$(curl -s "$dash_url" -c cookies.txt -b cookies.txt --max-time 10)

js_files=$(echo "$dash_html" | grep -oP '<script[^>]*src="\K[^"]+')
for js in $js_files; do
  echo "Checking JS: $js"
  js_url="http://10.16.100.244/$js"
  if [[ $js == http* ]]; then
    js_url=$js
  fi
  curl -s "$js_url" -c cookies.txt -b cookies.txt | grep -i -C 5 "cpage"
done
