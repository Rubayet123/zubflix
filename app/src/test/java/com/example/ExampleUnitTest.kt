package com.example
 
import com.example.zubflix.bdix.BDIXUtils
import org.junit.Assert.*
import org.junit.Test
 
class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun testTitleMatchAmongUsAndThisIsUs() {
    // "Among Us" and "This is us" must NOT match
    val match = BDIXUtils.titlesMatch("This is us", "Among us")
    assertFalse("This is us should not match Among us", match)

    // Exact matches must match
    assertTrue(BDIXUtils.titlesMatch("Among Us", "Among Us"))
    assertTrue(BDIXUtils.titlesMatch("Among Us (2024)", "Among Us"))
    assertTrue(BDIXUtils.titlesMatch("This Is Us", "this is us"))

    // Different shows should not match
    assertFalse(BDIXUtils.titlesMatch("The Last of Us", "Among Us"))
    assertFalse(BDIXUtils.titlesMatch("Us", "Among Us"))
  }

  @Test
  fun testCircleFtpLinkToIp() {
    val domainUrl = "http://ftp3.circleftp.net/movies/test.mp4"
    val ipUrl = com.example.zubflix.sources.CircleFtpSource.linkToIp(domainUrl)
    assertEquals("http://15.1.4.7/movies/test.mp4", ipUrl)

    val indexUrl = "http://index.circleftp.net/series/ep1.mkv"
    val indexIpUrl = com.example.zubflix.sources.CircleFtpSource.linkToIp(indexUrl)
    assertEquals("http://15.1.4.2/series/ep1.mkv", indexIpUrl)
  }
}


