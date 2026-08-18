import sys

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'r') as f:
    content = f.read()

# Fix unsupported escape sequence
content = content.replace("5\.1", "5\\\\.1")
content = content.replace("7\.1", "7\\\\.1")

# Fix lifecycleScope
content = content.replace("androidx.lifecycle.lifecycleScope.launch", "lifecycleScope.launch")

with open('app/src/main/java/com/example/zubflix/DetailsActivity.kt', 'w') as f:
    f.write(content)
