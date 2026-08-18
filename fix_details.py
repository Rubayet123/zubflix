import sys

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()
    
# Replace the old layout text
content = content.replace('tvTitle.text = "Stremio Addon Streams"', 'tvTitle.text = "Stream Source"')
with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)
