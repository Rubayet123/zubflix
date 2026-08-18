import androidx.media3.datasource.DefaultHttpDataSource

fun main() {
    val factory = DefaultHttpDataSource.Factory()
    factory.setDefaultRequestProperties(mapOf("Key" to "Value"))
    println("OK")
}
