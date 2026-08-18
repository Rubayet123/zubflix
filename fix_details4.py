import sys

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

# Remove setBackgroundResource calls
content = content.replace("view.setBackgroundResource(R.drawable.bg_stream_item_focused)", "")
content = content.replace("view.setBackgroundResource(R.drawable.bg_stream_item)", "")

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)
