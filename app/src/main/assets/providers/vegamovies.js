// app/src/main/assets/providers_src/vegamovies.js
var cheerio = require("cheerio-without-node-native");
var PROVIDER_NAME = "VegaMovies";
var BASE_URL = "https://vegamovies.mq";
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var DOMAINS_JSON_URL = "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json";
var REQUEST_TIMEOUT = 12e3;
var HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
  "Accept-Language": "en-US,en;q=0.5"
};
var MOBILE_UAS = [
  "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
  "Mozilla/5.0 (iPhone; CPU iPhone OS 16_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.5 Mobile/15E148 Safari/604.1",
  "Mozilla/5.0 (iPad; CPU OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
  "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/116.0.0.0 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/115.0.0.0 Mobile Safari/537.36",
  "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Mobile Safari/537.36"
];
function getMobileHeaders() {
  var ua = MOBILE_UAS[Math.floor(Math.random() * MOBILE_UAS.length)];
  return {
    "User-Agent": ua,
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "en-US,en;q=0.9",
    "Referer": baseUrl + "/"
  };
}
var EXCLUDED_BUTTONS = ["filepress", "gdtot", "dropgalaxy", "gdflix", "gdlink"];
function fetchSafe(url, options, timeout) {
  options = options || {};
  timeout = timeout || REQUEST_TIMEOUT;
  var mergedHeaders = Object.assign({}, HEADERS, options.headers || {}, { "Accept-Encoding": "identity" });
  var mergedOptions = Object.assign({}, options, { headers: mergedHeaders });
  var fetchPromise = fetch(url, mergedOptions);
  var timeoutPromise = new Promise(function(_, reject) {
    setTimeout(function() {
      reject(new Error("timeout"));
    }, timeout);
  });
  return Promise.race([fetchPromise, timeoutPromise]).catch(function(err) {
    if (err.message === "timeout") {
      console.error("[" + PROVIDER_NAME + "] Timeout: " + String(url).substring(0, 100));
    } else {
      console.error("[" + PROVIDER_NAME + "] fetchSafe error: " + String(url).substring(0, 100) + " -> " + err.message);
    }
    return null;
  });
}
function fetchJson(url, options) {
  return fetchSafe(url, options).then(function(res) {
    if (!res || !res.ok) return null;
    return res.text().then(function(txt) {
      try {
        return JSON.parse(txt);
      } catch (e) {
        return null;
      }
    });
  });
}
function fetchHtml(url, options) {
  return fetchSafe(url, options).then(function(res) {
    if (!res || !res.ok) return null;
    return res.text().then(function(txt) {
      return cheerio.load(txt);
    }).catch(function() {
      return null;
    });
  });
}
function getOrigin(url) {
  try {
    var parts = url.split("//");
    if (parts.length < 2) return url;
    return parts[0] + "//" + parts[1].split("/")[0];
  } catch (e) {
    return url;
  }
}
function fixUrl(url) {
  if (!url) return "";
  if (url.indexOf("http://") === 0 || url.indexOf("https://") === 0) return url;
  if (url.indexOf("//") === 0) return "https:" + url;
  if (url.indexOf("/") === 0) return baseUrl + url;
  return baseUrl + "/" + url;
}
function parseQuality(text) {
  var str = String(text || "");
  var m = str.match(/(2160|1080|720|480)\s*P/i);
  if (m) return m[1].toLowerCase() + "p";
  if (/4K|UHD/i.test(str)) return "2160p";
  if (/1440|2K/i.test(str)) return "1440p";
  return "HD";
}
function decodeEntities(str) {
  if (!str) return "";
  return str.replace(/&#8211;/g, "-").replace(/&#8212;/g, "-").replace(/&#038;/g, "&").replace(/&#8217;/g, "'").replace(/&amp;/g, "&").replace(/&ndash;/g, "-").replace(/&mdash;/g, "-").replace(/&quot;/g, '"');
}
function makeStream(name, titleRaw, url, quality, headers, episodeTitle) {
  var nameClean = decodeEntities(name).replace(/[\n\t]+/g, "").trim();
  var rawTitle = decodeEntities(titleRaw || "").replace(/[\n\t]+/g, " ").replace(/\s{2,}/g, " ").trim();
  var filename = "";
  var fnMatch = rawTitle.match(/\[\s*([^\]]+\.(?:mkv|mp4|avi|zip|rar|ts))\s*\]/i);
  if (fnMatch) {
    filename = fnMatch[1].trim();
    rawTitle = rawTitle.replace(fnMatch[0], "").trim();
  }
  var sizeStr = "N/A";
  var szMatch = titleRaw.match(/\[\s*(\d+(?:\.\d+)?\s*[MG]B)\s*\]/i);
  if (szMatch) sizeStr = szMatch[1].trim();
  var container = "MKV";
  if (filename && filename.toLowerCase().endsWith(".mp4")) container = "MP4";
  var ripType = "WEB-DL";
  if (/bluray|blu\-ray|bdrip/i.test(titleRaw)) ripType = "BluRay";
  else if (/hdrip|webrip/i.test(titleRaw)) ripType = "WEBRip";
  var imax = /imax/i.test(titleRaw) ? " | \u{1F441}\uFE0F iMAX" : "";
  var hdrStr = "";
  if (/dolby\s*vision|dovi/i.test(titleRaw.toLowerCase())) hdrStr = "Dolby Vision";
  else if (/hdr10/i.test(titleRaw)) hdrStr = "HDR10";
  else if (/hdr/i.test(titleRaw)) hdrStr = "HDR";
  else if (/10bit|10\-bit/i.test(titleRaw)) hdrStr = "10Bit";
  else if (/sdr/i.test(titleRaw.toLowerCase())) hdrStr = "SDR";
  var codec = "H.264";
  if (/hevc/i.test(titleRaw)) codec = "HEVC";
  else if (/x265|h265/i.test(titleRaw)) codec = "H.265";
  else if (/x264|h264/i.test(titleRaw)) codec = "H.264";
  var videoInfo = hdrStr ? " | \u{1F506} " + hdrStr + " \u2022 \u26A1 " + codec : " | \u26A1 " + codec;
  var audioStr = "";
  var audioMatch = titleRaw.match(/(TrueHD\s*7\.1|DDP\s*7\.1|DDP\s*5\.1|DD\s*5\.1|5\.1|AAC)/i);
  if (audioMatch) {
    var aLabel = audioMatch[1].toUpperCase().replace(/\s+/g, "");
    if (aLabel === "5.1") aLabel = "DDP5.1";
    if (aLabel.indexOf("TRUEHD") !== -1) aLabel = "TrueHD 7.1";
    audioStr = aLabel;
  } else if (/dolby\s*digital|dd/i.test(titleRaw)) audioStr = "Dolby Digital";
  else if (/dolby/i.test(titleRaw)) audioStr = "Dolby";
  if (/atmos/i.test(titleRaw)) {
    audioStr = audioStr ? audioStr + " \u2022 \u{1F50A} Atmos" : "\u{1F50A} Atmos";
  }
  if (!audioStr) audioStr = "Auto";
  var langs = [];
  var lowerRaw = titleRaw.toLowerCase();
  var isDual = /dual|hindi\-eng|eng\-hin/i.test(titleRaw || "");
  if (isDual) {
    langs.push("English \u{1F1FA}\u{1F1F8} \u2022 Hindi \u{1F1EE}\u{1F1F3}");
  } else {
    if (/hindi|hin/i.test(lowerRaw)) langs.push("Hindi \u{1F1EE}\u{1F1F3}");
    if (/english|eng/i.test(lowerRaw)) langs.push("English \u{1F1FA}\u{1F1F8}");
    if (langs.length === 0) langs.push("English \u{1F1FA}\u{1F1F8}");
  }
  var langStr = langs.join(" \u2022 ");
  var yearMatch = titleRaw.match(/\b(19\d{2}|20\d{2})\b/);
  var yrTag = yearMatch ? "(" + yearMatch[1] + ")" : "";
  var cleanTarget = filename || rawTitle;
  var seMatch = cleanTarget.match(/[sS](\d+)\s*[eE](\d+)/);
  var headerDisplay = "";
  if (seMatch) {
    var showName = cleanTarget.split(/[sS]\d+/i)[0].replace(/[\.\-_]/g, " ").replace(/[\{\[\(].*$/g, "").trim();
    headerDisplay = showName + " - S" + parseInt(seMatch[1], 10) + " E" + parseInt(seMatch[2], 10);
  } else {
    var movieName = rawTitle.split(/[\.\-_]\d{3,4}p/i)[0].replace(/[\.\-_]/g, " ").replace(/\d{3,4}p.*/i, "").replace(/[\{\[\(].*$/g, "").trim();
    headerDisplay = movieName + (yrTag ? " - " + yrTag : "");
  }
  headerDisplay = headerDisplay.replace(/\s+/g, " ").replace(/\s+-\s+-\s+/g, " - ").replace(/-\s*$/, "").trim();
  var resLabel = quality || "1080p";
  var audioMode = isDual ? "Dual-Audio" : "Single Audio";
  var mainName = PROVIDER_NAME + " | " + resLabel + " | " + audioMode;
  var serverHost = "Play Stream";
  var lowerUrl = (url || "").toLowerCase();
  if (lowerUrl.indexOf("/hub2/") !== -1 || lowerUrl.indexOf("hubcloud") !== -1 || lowerUrl.indexOf("homelander.buzz") !== -1 || lowerUrl.indexOf("whistle.lat") !== -1 || lowerUrl.indexOf("mandalorian.buzz") !== -1) {
    serverHost = "HubCloud";
  } else if (lowerUrl.indexOf(".r2.dev") !== -1 || lowerUrl.indexOf("vcloud") !== -1) {
    serverHost = "vCloud";
  }
  var line1 = "\u{1F3AC} " + headerDisplay;
  var line2 = "\u{1F48E} " + resLabel + " | \u{1F5E3}\uFE0F " + langStr + " | \u{1F4BE} " + sizeStr;
  var line3 = "\u{1F39E}\uFE0F " + container + " | \u{1F3A7} " + audioStr + videoInfo;
  var line4 = "\u{1F517} " + serverHost + " | \u2601\uFE0F " + ripType + imax;
  var formattedTitle = line1 + "\n" + line2 + "\n" + line3 + "\n" + line4;
  var sizeWeight = 0;
  if (szMatch) {
    var val = parseFloat(szMatch[1]);
    sizeWeight = val * (szMatch[1].toUpperCase().indexOf("GB") !== -1 ? 1024 : 1);
  }
  return {
    name: mainName,
    title: formattedTitle,
    url: url || "",
    quality: resLabel,
    _resWeight: resLabel.indexOf("2160") !== -1 || resLabel.toLowerCase().indexOf("4k") !== -1 ? 3 : resLabel.indexOf("1080") !== -1 ? 2 : 1,
    _sizeWeight: sizeWeight,
    behaviorHints: {
      notWebReady: true,
      proxyHeaders: {
        request: headers || { Referer: baseUrl + "/" }
      }
    }
  };
}
function dedupe(streams) {
  var seen = /* @__PURE__ */ new Set();
  return (streams || []).filter(function(s) {
    if (!s || !s.url || seen.has(s.url)) return false;
    seen.add(s.url);
    return true;
  });
}
function isStrictMatch(mediaTitle, mediaYear, postTitle, postYear, altTitles) {
  if (!postTitle) return false;
  var cleanPost = postTitle.toLowerCase().replace(/download\s*/gi, "").replace(/[^a-z0-9\s]/g, " ").trim().replace(/\s+/g, " ");
  var candidates = [mediaTitle].concat(altTitles || []).filter(Boolean);
  var titleMatched = false;
  for (var i = 0; i < candidates.length; i++) {
    var cleanCand = candidates[i].toLowerCase().replace(/[^a-z0-9\s]/g, " ").trim().replace(/\s+/g, " ");
    if (cleanCand.length > 0 && (cleanPost.indexOf(cleanCand) !== -1 || cleanPost.indexOf(cleanCand) === 0)) {
      titleMatched = true;
      break;
    }
  }
  if (!titleMatched) return false;
  if (mediaYear && postYear) {
    var mY = parseInt(mediaYear, 10);
    var pY = parseInt(postYear, 10);
    if (!isNaN(mY) && !isNaN(pY)) {
      if (Math.abs(mY - pY) > 1) return false;
    }
  }
  return true;
}
var cachedDomains = null;
var domainCacheTime = 0;
var DOMAIN_CACHE_TTL = 4 * 60 * 60 * 1e3;
var baseUrl = BASE_URL;
var cachedHubDomain = "https://hubcloud.foo";
var cachedVcDomain = "https://vcloud.zip";
function refreshDomains() {
  var now = Date.now();
  if (cachedDomains && now - domainCacheTime < DOMAIN_CACHE_TTL) {
    return Promise.resolve(cachedDomains);
  }
  return fetchJson(DOMAINS_JSON_URL, {}, 8e3).then(function(data) {
    if (data) {
      cachedDomains = data;
      domainCacheTime = now;
      if (data.vegamovies) baseUrl = data.vegamovies;
      if (data.hubcloud) cachedHubDomain = data.hubcloud;
      if (data.vcloud) cachedVcDomain = data.vcloud;
      console.log("[" + PROVIDER_NAME + "] Domains updated: site=" + baseUrl + " hub=" + cachedHubDomain + " vc=" + cachedVcDomain);
    }
    return cachedDomains || {};
  }).catch(function() {
    console.log("[" + PROVIDER_NAME + "] Domain refresh failed, using defaults");
    return {};
  });
}
function getLatestHubDomain() {
  return cachedHubDomain;
}
function getLatestVcDomain() {
  return cachedVcDomain;
}
function getTMDBInfo(id, type) {
  var idStr = String(id || "").trim();
  var isImdb = idStr.indexOf("tt") === 0;
  var tmdbType = type === "tv" || type === "series" ? "tv" : "movie";
  if (isImdb) {
    var url = "https://api.themoviedb.org/3/find/" + idStr + "?api_key=" + TMDB_API_KEY + "&external_source=imdb_id";
    return fetchJson(url, { headers: { "Accept-Encoding": "identity" } }).then(function(data) {
      var list = data ? tmdbType === "tv" ? data.tv_results : data.movie_results : null;
      if (list && list.length > 0) {
        var item = list[0];
        return {
          title: tmdbType === "tv" ? item.name : item.title,
          year: (item.first_air_date || item.release_date || "").split("-")[0],
          imdbId: idStr,
          tmdbId: item.id
        };
      }
      return { title: idStr, year: null, imdbId: idStr, tmdbId: null };
    }).catch(function() {
      return { title: idStr, year: null, imdbId: idStr, tmdbId: null };
    });
  } else {
    var url = "https://api.themoviedb.org/3/" + tmdbType + "/" + idStr + "?api_key=" + TMDB_API_KEY + "&append_to_response=external_ids,alternative_titles";
    return fetchJson(url, { headers: { "Accept-Encoding": "identity" } }).then(function(data) {
      if (data) {
        var alts = [];
        if (data.alternative_titles && data.alternative_titles.titles) {
          alts = data.alternative_titles.titles.map(function(t) {
            return String(t.title || "");
          });
        } else if (data.alternative_titles && data.alternative_titles.results) {
          alts = data.alternative_titles.results.map(function(t) {
            return String(t.title || "");
          });
        }
        return {
          title: tmdbType === "tv" ? data.name : data.title,
          year: (data.first_air_date || data.release_date || "").split("-")[0],
          imdbId: data.imdb_id || data.external_ids && data.external_ids.imdb_id || null,
          tmdbId: data.id,
          altTitles: alts
        };
      }
      return { title: idStr, year: null, imdbId: null, tmdbId: null };
    }).catch(function() {
      return { title: idStr, year: null, imdbId: null, tmdbId: null };
    });
  }
}
function searchByTitle(query, year) {
  if (!query) return Promise.resolve([]);
  var searchQuery = encodeURIComponent(query + (year ? " " + year : ""));
  var url = baseUrl + "/search.php?q=" + searchQuery + "&page=1&per_page=15";
  console.log("[" + PROVIDER_NAME + '] Search: "' + query.substring(0, 60) + '" -> ' + url.substring(0, 120));
  return fetchJson(url, { headers: Object.assign({}, getMobileHeaders(), { "Accept-Encoding": "identity" }) }).then(function(data) {
    if (!data || !data.hits || data.hits.length === 0) {
      console.log("[" + PROVIDER_NAME + "] Search: no results");
      return [];
    }
    console.log("[" + PROVIDER_NAME + "] Search: " + data.hits.length + " results");
    return data.hits.map(function(h) {
      var doc = h.document || {};
      var yr = null;
      if (doc.category && Array.isArray(doc.category)) {
        yr = doc.category.find(function(c) {
          return /^(19|20)\d{2}$/.test(String(c).trim());
        }) || null;
      }
      if (!yr) {
        var m = (doc.post_title || "").match(/\b(19|20)\d{2}\b/);
        yr = m ? m[0] : null;
      }
      return {
        postId: String(doc.id || ""),
        title: (doc.post_title || "").replace(/Download\s*/gi, "").trim(),
        permalink: doc.permalink || "",
        imdbId: doc.imdb_id || "",
        year: yr
      };
    });
  }).catch(function(e) {
    console.error("[" + PROVIDER_NAME + "] Search error: " + e.message);
    return [];
  });
}
function fetchPostContent(postId, permalink) {
  if (!postId) return Promise.resolve(null);
  var apiUrl = baseUrl + "/wp-json/wp/v2/posts/" + postId;
  console.log("[" + PROVIDER_NAME + "] Fetching post content for " + postId);
  return fetchSafe(apiUrl, { headers: getMobileHeaders() }, 15e3).then(function(res) {
    if (res && res.ok) {
      return res.text().then(function(txt) {
        try {
          var json = JSON.parse(txt);
          if (json && json.content && json.content.rendered) {
            var html = json.content.rendered;
            if (!/nexdrive|vcloud|hubcloud|fastdl|genxfm/i.test(html)) {
              throw new Error("WP-JSON payload stale or missing download links");
            }
            return {
              title: (json.title && json.title.rendered || "").replace(/Download\s*/gi, "").trim(),
              html
            };
          }
        } catch (e) {
          console.log("[" + PROVIDER_NAME + "] WP-JSON parse/validation failed. Falling back to raw HTML.");
        }
        return null;
      });
    }
    return null;
  }).then(function(result) {
    if (result) return result;
    var targetUrl = permalink ? fixUrl(permalink) : baseUrl + "/?p=" + postId;
    console.log("[" + PROVIDER_NAME + "] HTML Fallback fetching: " + targetUrl);
    return fetchHtml(targetUrl, { headers: getMobileHeaders() }).then(function($) {
      if ($) {
        var content = $(".entry-content").html() || $(".post-content").html();
        if (content) {
          return {
            title: $("title").text().replace(/Download\s*/gi, "").trim(),
            html: content
          };
        }
      }
      return null;
    });
  }).catch(function(err) {
    console.error("[" + PROVIDER_NAME + "] fetchPostContent error: " + err.message);
    return null;
  });
}
function extractNexdriveLinks(contentHtml) {
  if (!contentHtml) return [];
  var links = [];
  var $ = cheerio.load(contentHtml);
  var seenUrls = /* @__PURE__ */ new Set();
  $('a[href*="nexdrive"], a[href*="genxfm"], a[href*="fastdl"], a[href*="vcloud"], a[href*="hubcloud"]').each(function(i, el) {
    try {
      var href = $(el).attr("href");
      if (!href) return;
      var linkText = ($(el).text() || "").trim();
      if (EXCLUDED_BUTTONS.some(function(ex) {
        return linkText.toLowerCase().indexOf(ex) !== -1;
      })) return;
      if (seenUrls.has(href)) return;
      seenUrls.add(href);
      var quality = "HD";
      var label = linkText || "Download";
      var hrefPos = contentHtml.indexOf(href);
      if (hrefPos > 0) {
        var beforeHref = contentHtml.substring(Math.max(0, hrefPos - 3e3), hrefPos);
        var headingMatches = beforeHref.match(/<h[1-6][^>]*>([\s\S]*?)<\/h[1-6]>/gi);
        if (headingMatches && headingMatches.length > 0) {
          var hText = headingMatches[headingMatches.length - 1].replace(/<[^>]*>/g, "").trim().replace(/Download/ig, "");
          if (hText.length > 5) label = hText;
        }
        var qualityPattern = /(?:^|>|\s)(\d{3,4}p|4K|UHD|HDR)(?:<|\s|$)/gi;
        var qMatch;
        var lastMatch = null;
        var lastIndex = -1;
        while ((qMatch = qualityPattern.exec(beforeHref)) !== null) {
          if (qMatch.index > lastIndex) {
            lastIndex = qMatch.index;
            lastMatch = qMatch[1];
          }
        }
        if (lastMatch) quality = parseQuality(lastMatch);
        if (!quality || quality === "HD") {
          var headingQ = beforeHref.match(/<(?:h[1-6]|strong|b)[^>]*>[^<]*?(\d{3,4}p|4K|UHD)[^<]*?<\//i);
          if (headingQ) quality = parseQuality(headingQ[1]);
        }
      }
      if (quality === "480p") return;
      links.push({
        href: fixUrl(href),
        quality: quality || "HD",
        label
      });
    } catch (e) {
    }
  });
  return links;
}
function capLinksForEfficiency(links, maxTotal) {
  maxTotal = maxTotal || 15;
  if (!links || links.length <= maxTotal) return links;
  return links.slice(0, maxTotal);
}
function extractSeasonFromContent(contentHtml, targetSeason) {
  if (!contentHtml || targetSeason == null) return contentHtml;
  var mainContent = contentHtml.split('id="comments"')[0].split('class="comments-area"')[0];
  var seasonRegex = /(?:Season|Saison|Staffel)\s+0*(\d+)\b(?!\s*(?:-|–|to|and|&|&#))/gi;
  var match;
  var positions = [];
  while ((match = seasonRegex.exec(mainContent)) !== null) {
    var lastH = mainContent.lastIndexOf("<h", match.index);
    var lastStrong = mainContent.lastIndexOf("<strong", match.index);
    var pos = Math.max(lastH, lastStrong);
    if (pos < 0 || match.index - pos > 500) pos = match.index;
    var snippet = mainContent.substring(pos, match.index + 50);
    if (snippet.toLowerCase().indexOf("download") !== -1 || snippet.toLowerCase().indexOf("episode") !== -1) {
      continue;
    }
    positions.push({ season: parseInt(match[1], 10), index: pos });
  }
  if (positions.length === 0) return mainContent;
  var targetPos = positions.find(function(p) {
    return p.season === targetSeason;
  });
  if (!targetPos) return mainContent;
  var startIdx = targetPos.index;
  var nextPos = positions.find(function(p) {
    return p.index > startIdx && p.season !== targetSeason;
  });
  var endIdx = nextPos ? nextPos.index : mainContent.length;
  return mainContent.substring(startIdx, endIdx);
}
function extractSingleVc(vcUrl, referer, targetSeason, targetEpisode, displayLabel, quality, episodeTitle) {
  var streams = [];
  var lower = vcUrl.toLowerCase();
  if (lower.indexOf("vcloud") !== -1 || lower.indexOf("hubcloud") !== -1 || lower.indexOf("nexdrive") !== -1 || lower.indexOf("fastdl") !== -1) {
    var isHub = lower.indexOf("hubcloud") !== -1;
    var latestBase = isHub ? getLatestHubDomain() : getLatestVcDomain();
    var curBase = getOrigin(vcUrl);
    var targetVcUrl = vcUrl;
    if (curBase !== latestBase && (vcUrl.indexOf("vcloud") !== -1 || vcUrl.indexOf("hubcloud") !== -1)) {
      targetVcUrl = vcUrl.replace(curBase, latestBase);
    }
    return fetchHtml(targetVcUrl, {
      headers: Object.assign({}, getMobileHeaders(), { Referer: referer || baseUrl + "/", Cookie: "xla=s4t" }),
      redirect: "manual"
    }).then(function($) {
      if (!$) return streams;
      var rawHtml = $.html();
      var pageTitle = $("title").text() || "";
      if (targetSeason != null || targetEpisode != null) {
        var seMatch = pageTitle.match(/[.\s_\-](?:S|Season)\s*0*(\d{1,2})[.\s_\-]*(?:E|Ep|Episode)\s*0*(\d{1,2})[.\s_\-]/i);
        if (seMatch) {
          var vcSeason = parseInt(seMatch[1], 10);
          var vcEpisode = parseInt(seMatch[2], 10);
          if (targetSeason != null && vcSeason !== targetSeason) return streams;
          if (targetEpisode != null && vcEpisode !== targetEpisode) return streams;
        } else {
          var sMatch = pageTitle.match(/[.\s_\-](?:S|Season)\s*0*(\d{1,2})[.\s_\-]/i);
          if (sMatch && targetSeason != null) {
            var sNum = parseInt(sMatch[1], 10);
            if (sNum !== targetSeason) return streams;
          }
        }
      }
      var bridgeUrl = "";
      var doubleAtobMatch = rawHtml.match(/var\s+url\s*=\s*atob\(atob\('([^']+)'\)\)/);
      var varMatch = rawHtml.match(/var\s+url\s*=\s*['"]([^'"]+)['"]/);
      if (doubleAtobMatch) {
        try {
          bridgeUrl = atob(atob(doubleAtobMatch[1]));
        } catch (e) {
          bridgeUrl = doubleAtobMatch[1];
        }
      } else if (varMatch) {
        bridgeUrl = varMatch[1];
      }
      var tasks = [];
      var cardHeader = $("div.card-header").text() || "";
      var detectedQuality = parseQuality(cardHeader) || quality || "HD";
      if (bridgeUrl && bridgeUrl.indexOf(".workers.dev") !== -1) {
        var wUrl = bridgeUrl + "?s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes());
        tasks.push(function() {
          streams.push(makeStream("Worker | " + detectedQuality, (displayLabel || "Worker Server") + " [" + cardHeader + "]", wUrl, detectedQuality, { Referer: targetVcUrl }, episodeTitle));
        });
        bridgeUrl = "";
      }
      $("a.btn, a").each(function(i, el) {
        try {
          var href = $(el).attr("href") || "";
          var text = ($(el).text() || "").trim();
          var lowerText = text.toLowerCase();
          if (!href || href === "#" || href.toLowerCase().indexOf(".zip") !== -1) return;
          if (lowerText.indexOf("10gbps") !== -1 || lowerText.indexOf("gdflix") !== -1 || lowerText.indexOf("dropgalaxy") !== -1 || lowerText.indexOf("telegram") !== -1) return;
          if (lowerText.indexOf("fslv2") !== -1) {
            tasks.push(function() {
              streams.push(makeStream("FSLv2 (Fast) | " + detectedQuality, (displayLabel || text) + " [" + cardHeader + "]", href, detectedQuality, { Referer: targetVcUrl }, episodeTitle));
            });
          } else if (lowerText.indexOf("fsl") !== -1) {
            var syncedFsl = href.indexOf("?") !== -1 ? href + "&s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes()) : href + "?s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes());
            tasks.push(function() {
              streams.push(makeStream("FSL | " + detectedQuality, (displayLabel || text) + " [" + cardHeader + "]", syncedFsl, detectedQuality, { Referer: targetVcUrl }, episodeTitle));
            });
          } else if (lowerText.indexOf("worker") !== -1) {
            var syncedW = href.indexOf("?") !== -1 ? href + "&s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes()) : href + "?s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes());
            tasks.push(function() {
              streams.push(makeStream("Worker | " + detectedQuality, (displayLabel || text) + " [" + cardHeader + "]", syncedW, detectedQuality, { Referer: targetVcUrl }, episodeTitle));
            });
          }
        } catch (e) {
        }
      });
      if (tasks.length > 0) {
        tasks.forEach(function(fn) {
          try {
            fn();
          } catch (e) {
          }
        });
        return streams;
      }
      if (!bridgeUrl) {
        var downloadHref = $("#download").attr("href") || $("a").filter(function(i, el) {
          var h = $(el).attr("href") || "";
          return h.indexOf("hubcloud.php") !== -1 || h.indexOf("token") !== -1 || h.indexOf("dl") !== -1;
        }).first().attr("href");
        if (downloadHref) {
          bridgeUrl = downloadHref.indexOf("http") === 0 ? downloadHref : getOrigin(targetVcUrl) + "/" + downloadHref.replace(/^\//, "");
        }
      }
      if (!bridgeUrl) {
        var altVc = $('a[href*="vcloud.zip"]').filter(function(i, el) {
          var h = $(el).attr("href") || "";
          return h.indexOf("/api/") === -1 && h !== targetVcUrl;
        }).first().attr("href");
        if (altVc) {
          return extractSingleVc(altVc, referer, targetSeason, targetEpisode, displayLabel, quality, episodeTitle);
        }
      }
      if (!bridgeUrl) return streams;
      if (bridgeUrl.indexOf("://") < 0) bridgeUrl = getOrigin(targetVcUrl) + bridgeUrl;
      return fetchHtml(bridgeUrl, {
        headers: Object.assign({}, getMobileHeaders(), { Referer: targetVcUrl, Cookie: "xla=s4t" })
      }).then(function($bridge) {
        if (!$bridge) return streams;
        var bridgeRaw = $bridge.html();
        var bridgeHeader = $bridge("div.card-header").text() || "";
        var bridgeQuality = parseQuality(bridgeHeader) || detectedQuality;
        var bridgeTasks = [];
        var bridgeVarMatch = bridgeRaw.match(/var\s+url\s*=\s*['"]([^'"]+)['"]/);
        if (bridgeVarMatch && bridgeVarMatch[1].indexOf(".workers.dev") !== -1) {
          var wUrl2 = bridgeVarMatch[1] + "?s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes());
          bridgeTasks.push(function() {
            streams.push(makeStream("Worker | " + bridgeQuality, (displayLabel || "Worker Server") + " [" + bridgeHeader + "]", wUrl2, bridgeQuality, { Referer: bridgeUrl }, episodeTitle));
          });
        }
        $bridge("a.btn, a").each(function(i, el) {
          try {
            var href = $bridge(el).attr("href") || "";
            var text = ($bridge(el).text() || "").trim();
            var lowerText = text.toLowerCase();
            if (!href || href === "#" || href.toLowerCase().indexOf(".zip") !== -1) return;
            if (lowerText.indexOf("10gbps") !== -1 || lowerText.indexOf("gdflix") !== -1 || lowerText.indexOf("dropgalaxy") !== -1 || lowerText.indexOf("telegram") !== -1) return;
            if (lowerText.indexOf("fslv2") !== -1) {
              bridgeTasks.push(function() {
                streams.push(makeStream("FSLv2 (Fast) | " + bridgeQuality, (displayLabel || text) + " [" + bridgeHeader + "]", href, bridgeQuality, { Referer: bridgeUrl }, episodeTitle));
              });
            } else if (lowerText.indexOf("fsl") !== -1) {
              var syncedFsl = href.indexOf("?") !== -1 ? href + "&s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes()) : href + "?s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes());
              bridgeTasks.push(function() {
                streams.push(makeStream("FSL | " + bridgeQuality, (displayLabel || text) + " [" + bridgeHeader + "]", syncedFsl, bridgeQuality, { Referer: bridgeUrl }, episodeTitle));
              });
            }
          } catch (e) {
          }
        });
        if (bridgeTasks.length === 0) {
          var fslHref = $bridge("#fsl").attr("href");
          if (fslHref) {
            var syncedFsl2 = fslHref.indexOf("?") !== -1 ? fslHref + "&s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes()) : fslHref + "?s=" + (1 + (/* @__PURE__ */ new Date()).getMinutes());
            bridgeTasks.push(function() {
              streams.push(makeStream("FSL | " + bridgeQuality, (displayLabel || "FSL Server") + " [" + bridgeHeader + "]", syncedFsl2, bridgeQuality, { Referer: bridgeUrl }, episodeTitle));
            });
          }
        }
        bridgeTasks.forEach(function(fn) {
          try {
            fn();
          } catch (e) {
          }
        });
        return streams;
      });
    });
  }
  return Promise.resolve(streams);
}
function loadStreamsFromUrl(url, displayLabel, quality, referer, targetSeason, targetEpisode, episodeTitle) {
  var lower = url.toLowerCase();
  if (lower.indexOf("vcloud") !== -1 || lower.indexOf("hubcloud") !== -1) {
    return extractSingleVc(url, referer || url, targetSeason, targetEpisode, displayLabel, quality, episodeTitle);
  }
  if (lower.indexOf("nexdrive") !== -1 || lower.indexOf("genxfm") !== -1 || lower.indexOf("fastdl") !== -1) {
    return fetchHtml(url, {
      headers: Object.assign({}, getMobileHeaders(), { Referer: referer || baseUrl + "/" }),
      redirect: "manual"
    }).then(function($) {
      if (!$) return [];
      var streamTasks = [];
      $('a[href*="vcloud"], a[href*="hubcloud"]').each(function(i, el) {
        var href = $(el).attr("href");
        if (href) {
          if (href.indexOf("/api/index.php?link=") !== -1) {
            streamTasks.push(function() {
              return fetchHtml(href, { headers: Object.assign({}, getMobileHeaders(), { Referer: url }), redirect: "manual" }).then(function($api) {
                if (!$api) return [];
                var nextHref = $api("a.btn-success, a.btn").attr("href");
                if (nextHref) {
                  if (nextHref.indexOf("/") === 0) nextHref = getOrigin(href) + nextHref;
                  return extractSingleVc(nextHref, href, targetSeason, targetEpisode, displayLabel, quality, episodeTitle);
                }
                return [];
              });
            });
          } else {
            streamTasks.push(function() {
              return extractSingleVc(href, url, targetSeason, targetEpisode, displayLabel, quality, episodeTitle);
            });
          }
        }
      });
      if (targetEpisode != null) {
        var epIdx = targetEpisode - 1;
        if (epIdx >= 0 && epIdx < streamTasks.length) {
          return streamTasks[epIdx]().then(function(res) {
            if (Array.isArray(res) && res.length > 0) return res;
            return Promise.all(streamTasks.map(function(fn) {
              return fn().catch(function() {
                return [];
              });
            })).then(function(all) {
              var out = [];
              all.forEach(function(arr) {
                if (Array.isArray(arr)) out = out.concat(arr);
              });
              return out;
            });
          });
        }
      }
      return Promise.all(streamTasks.map(function(fn) {
        return fn().catch(function() {
          return [];
        });
      })).then(function(results) {
        var streams = [];
        results.forEach(function(r) {
          if (Array.isArray(r)) {
            r.forEach(function(s) {
              if (s && s.url) streams.push(s);
            });
          }
        });
        return streams;
      });
    });
  }
  return Promise.resolve([]);
}
function extractFromPost(postObj, label, isTv, targetSeason, targetEpisode, year) {
  try {
    var contentHtml = postObj.html;
    var seasonTag = "";
    if (isTv && targetSeason != null) {
      var filtered = extractSeasonFromContent(contentHtml, targetSeason);
      if (filtered) contentHtml = filtered;
      seasonTag = " S" + targetSeason;
      if (targetEpisode != null) seasonTag += "E" + targetEpisode;
    }
    var epTag = (seasonTag.trim() || String(year || "")).trim();
    var links = extractNexdriveLinks(contentHtml);
    var capped = capLinksForEfficiency(links);
    if (capped.length === 0) return Promise.resolve([]);
    console.log("[" + PROVIDER_NAME + "] Resolving " + capped.length + " links for post...");
    var tasks = capped.map(function(link) {
      var q = link.quality || "HD";
      var dispLabel = link.label || seasonTag + " [" + q + "]";
      return function() {
        return loadStreamsFromUrl(link.href, dispLabel, q, baseUrl + "/", targetSeason, targetEpisode, epTag);
      };
    });
    return Promise.all(tasks.map(function(fn) {
      return fn().catch(function() {
        return [];
      });
    })).then(function(results) {
      var streams = [];
      results.forEach(function(r) {
        if (Array.isArray(r)) {
          r.forEach(function(s) {
            if (s && s.url) streams.push(s);
          });
        }
      });
      return streams;
    });
  } catch (e) {
    console.error("[" + PROVIDER_NAME + "] extractFromPost fatal: " + e.message);
    return Promise.resolve([]);
  }
}
function getStreams(tmdbId, mediaType, season, episode, titleFallback) {
  console.log("[" + PROVIDER_NAME + "] Request: ID=" + tmdbId + " Type=" + mediaType + " S=" + season + " E=" + episode);
  return refreshDomains().then(function() {
    var isTv = mediaType === "tv" || mediaType === "series";
    return getTMDBInfo(tmdbId, mediaType).then(function(media) {
      var imdbId = media.imdbId;
      var mediaTitle = media.title || titleFallback;
      var mediaYear = media.year;
      if ((!imdbId || imdbId.indexOf("tt") !== 0) && String(tmdbId).indexOf("tt") === 0) {
        imdbId = String(tmdbId);
      }
      var searchPromise = Promise.resolve([]);
      if (imdbId && imdbId.indexOf("tt") === 0) {
        console.log("[" + PROVIDER_NAME + "] Searching by exact IMDb ID: " + imdbId);
        searchPromise = searchByTitle(imdbId, null);
      }
      return searchPromise.then(function(imdbResults) {
        var hasExactImdb = imdbResults.some(function(r) {
          return r.imdbId === imdbId;
        });
        if (imdbResults.length > 0 && hasExactImdb) {
          return imdbResults;
        }
        var q = mediaTitle;
        if (isTv && season != null) {
          q += " season " + Number(season);
        } else if (mediaYear) {
          q += " " + mediaYear;
        }
        console.log("[" + PROVIDER_NAME + "] Searching by title: " + q);
        return searchByTitle(q, mediaYear).then(function(titleResults) {
          if (titleResults.length === 0 && isTv && season != null) {
            return searchByTitle(mediaTitle, mediaYear);
          }
          return titleResults;
        });
      }).then(function(searchResults) {
        if (searchResults.length === 0) {
          console.log("[" + PROVIDER_NAME + "] No search results found");
          return [];
        }
        var matched = null;
        var targetImdb = imdbId && imdbId.indexOf("tt") === 0 ? imdbId : null;
        for (var i = 0; i < searchResults.length; i++) {
          var r = searchResults[i];
          if (targetImdb && r.imdbId === targetImdb) {
            if (!isTv || season == null) {
              matched = r;
              break;
            }
            var rangeMatch = /(?:s|season|staffel|saison)\s*0*(\d+)\s*(?:-|–|to|and|&|&#)\s*0*(\d+)\b/i.exec(r.title);
            var isS = false;
            if (rangeMatch) {
              var sStart = parseInt(rangeMatch[1], 10);
              var sEnd = parseInt(rangeMatch[2], 10);
              var reqS = parseInt(season, 10);
              if (reqS >= sStart && reqS <= sEnd) isS = true;
            }
            if (!isS) {
              isS = new RegExp("(?:s|season|staffel|saison)\\s*0*" + Number(season) + "\\b", "i").test(r.title);
            }
            if (isS) {
              matched = r;
              break;
            }
          }
          if (!matched) {
            if (isStrictMatch(mediaTitle, mediaYear, r.title, r.year, media.altTitles)) {
              matched = r;
            }
          }
        }
        if (!matched || !matched.postId) {
          console.log("[" + PROVIDER_NAME + "] No strict match found.");
          return [];
        }
        console.log("[" + PROVIDER_NAME + '] Matched: "' + matched.title + '"');
        return fetchPostContent(matched.postId, matched.permalink).then(function(postObj) {
          if (!postObj) return [];
          var displayTitle = postObj.title || matched.title;
          return extractFromPost(postObj, displayTitle, isTv, season != null ? Number(season) : null, episode != null ? Number(episode) : null, mediaYear);
        });
      }).then(function(streams) {
        var uniqueStreams = dedupe(streams).sort(function(a, b) {
          if (b._resWeight !== a._resWeight) {
            return b._resWeight - a._resWeight;
          }
          return b._sizeWeight - a._sizeWeight;
        });
        console.log("[" + PROVIDER_NAME + "] Total unique streams: " + uniqueStreams.length);
        return uniqueStreams;
      });
    });
  }).catch(function(e) {
    console.error("[" + PROVIDER_NAME + "] Fatal: " + e.message);
    return [];
  });
}
module.exports = {
  getStreams
};
