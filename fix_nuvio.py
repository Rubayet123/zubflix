import sys

with open('app/src/main/java/com/example/zubflix/sources/NuvioSource.kt', 'r') as f:
    content = f.read()

old_process = """            val processStreams = suspend { streams: List<Pair<String, String>> ->
                if (streams.isNotEmpty()) {
                    val batch = LinkedHashMap<String, String>()
                    for ((name, url) in streams) {
                        var uniqueName = name
                        var duplicateCount = 1
                        while (!uniqueNames.add(uniqueName)) {
                            uniqueName = "$name ($duplicateCount)"
                            duplicateCount++
                        }
                        batch[uniqueName] = url
                    }
                    onStreamFound(batch)
                }
            }"""

new_process = """            suspend fun processStreams(streams: List<Pair<String, String>>) {
                if (streams.isNotEmpty()) {
                    val batch = LinkedHashMap<String, String>()
                    for ((name, url) in streams) {
                        var uniqueName = name
                        var duplicateCount = 1
                        while (!uniqueNames.add(uniqueName)) {
                            uniqueName = "$name ($duplicateCount)"
                            duplicateCount++
                        }
                        batch[uniqueName] = url
                    }
                    onStreamFound(batch)
                }
            }"""

content = content.replace(old_process, new_process)
with open('app/src/main/java/com/example/zubflix/sources/NuvioSource.kt', 'w') as f:
    f.write(content)
