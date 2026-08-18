import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.FormBody
import java.util.HashMap

class InMemoryCookieJar : CookieJar {
    private val cookieStore = HashMap<String, MutableList<Cookie>>()
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        val host = url.host
        val currentCookies = cookieStore[host] ?: ArrayList()
        val newCookies = cookies.toMutableList()
        val mergedCookies = currentCookies.filter { existing ->
            newCookies.none { new -> new.name == existing.name }
        }.toMutableList()
        mergedCookies.addAll(newCookies)
        cookieStore[host] = mergedCookies
        println("Saved cookies for $host: $mergedCookies")
    }
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val host = url.host
        val cookies = cookieStore[host] ?: emptyList()
        println("Loaded cookies for $host: $cookies")
        return cookies
    }
}

fun main() {
    val client = OkHttpClient.Builder()
        .cookieJar(InMemoryCookieJar())
        .build()
        
    val mainUrl = "http://10.16.100.244"
    val sessionToken = "83408fdbf5821f154e708b55c2df1f8056a8052315ee92cb17047e51bf8fae2f"
    val categoryId = "/dashboard.php?session=$sessionToken&category=0"
    
    println("Requesting page 1...")
    val req1 = Request.Builder().url(mainUrl + categoryId).build()
    client.newCall(req1).execute().use { it.body?.string() }
    
    println("Requesting page 2...")
    val catId = categoryId.substringAfter("category=").substringBefore("&")
    val formBody = FormBody.Builder()
        .add("cpage", "2")
        .add("cCat", catId)
        .build()

    val request = Request.Builder()
        .url("$mainUrl/command.php")
        .post(formBody)
        .header("X-Requested-With", "XMLHttpRequest")
        .header("Referer", "$mainUrl$categoryId")
        .build()
        
    client.newCall(request).execute().use { res ->
        val html = res.body?.string() ?: ""
        println("Page 2 response length: ${html.length}")
        println("Page 2 snippet: ${html.take(200)}")
    }
}
