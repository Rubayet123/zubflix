import sys

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

content = content.replace("parseNuvioStreamName(item.first, item.second)", "parseStream(item.first, item.second)")

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)
