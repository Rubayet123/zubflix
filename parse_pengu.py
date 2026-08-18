import urllib.request
import json

url = "https://pengu.uk/%7B%22source_111477%22%3A%22on%22%2C%22source_4khdhub%22%3A%22on%22%2C%22source_moviebox%22%3A%22on%22%2C%22source_moviesdrives%22%3A%22on%22%2C%22source_vaplayer%22%3A%22on%22%2C%22source_hdghartv%22%3A%22on%22%2C%22res_1080%22%3A%22on%22%2C%22res_720%22%3A%22on%22%2C%22disable_direct%22%3A%22on%22%7D/stream/movie/tt37287335.json"
req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
resp = urllib.request.urlopen(req)
data = json.loads(resp.read().decode('utf-8'))

for stream in data.get('streams', []):
    stream_url = stream.get('url', '')
    if stream_url and stream_url != 'null':
        print(f"FOUND: {stream.get('name', '')} - {stream.get('title', '')} -> {stream_url}")
