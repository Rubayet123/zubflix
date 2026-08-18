// app/src/main/assets/providers_src/icc_ftp.js
var cheerio = require("cheerio-without-node-native");
var BASE = "http://10.16.100.244";
var SESSION_TOKEN = "83408fdbf5821f154e708b55c2df1f8056a8052315ee92cb17047e51bf8fae2f";
var SESSION_URL = BASE + "/dashboard.php?session=" + SESSION_TOKEN;
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var DEBUG = true;
var DEFAULT_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
  "Accept-Language": "en-US,en;q=0.9",
  "Connection": "keep-alive"
};
var sessionPrimed = false;
var cookieStore = "";
function dbg() {
  if (!DEBUG) return;
  console.log.apply(console, ["[ICC FTP DEBUG]"].concat(Array.prototype.slice.call(arguments)));
}
function assign(target, source) {
  var out = {};
  var k;
  target = target || {};
  source = source || {};
  for (k in target) out[k] = target[k];
  for (k in source) out[k] = source[k];
  return out;
}
function updateCookies(res) {
  var setCookies = res.headers.getSetCookie ? res.headers.getSetCookie() : [];
  if (!setCookies || setCookies.length === 0) {
    var raw = res.headers.get("set-cookie");
    if (raw) {
      setCookies = [raw];
    }
  }
  if (setCookies && setCookies.length > 0) {
    setCookies.forEach(function(cookieStr) {
      var part = cookieStr.split(";")[0].trim();
      if (part) {
        var name = part.split("=")[0];
        var reg = new RegExp(name + "=[^;]+");
        if (cookieStore.match(reg)) {
          cookieStore = cookieStore.replace(reg, part);
        } else {
          cookieStore = cookieStore ? cookieStore + "; " + part : part;
        }
      }
    });
  }
}
function getHeaders(extra) {
  var headers = assign(DEFAULT_HEADERS, extra || {});
  if (cookieStore) {
    headers["Cookie"] = cookieStore;
  }
  return headers;
}
function fetchJson(url, options) {
  options = options || {};
  return fetch(url, {
    method: options.method || "GET",
    headers: assign(DEFAULT_HEADERS, options.headers || {}),
    body: options.body
  }).then(function(res) {
    if (!res.ok) throw new Error("HTTP " + res.status + " -> " + url);
    return res.json();
  });
}
function ensureSession() {
  if (sessionPrimed) return Promise.resolve();
  dbg("Ensuring ICC FTP session primed via URL:", SESSION_URL);
  return fetch(SESSION_URL, {
    headers: getHeaders()
  }).then(function(res) {
    updateCookies(res);
    if (res.ok) {
      sessionPrimed = true;
      dbg("ICC FTP session primed successfully!");
    } else {
      dbg("Session priming failed with HTTP status:", res.status);
    }
  }).catch(function(err) {
    dbg("Session priming error:", err.message);
  });
}
function getTmdbNames(tmdbId, mediaType) {
  var type = mediaType === "movie" ? "movie" : "tv";
  var url = "https://api.themoviedb.org/3/" + type + "/" + tmdbId + "?api_key=" + TMDB_API_KEY;
  return fetchJson(url).then(function(data) {
    var title = data.name || data.title || "";
    var year = (data.release_date || data.first_air_date || "").split("-")[0];
    var duration = data.runtime ? data.runtime + "m" : "";
    return {
      title,
      original: data.original_name || data.original_title || title,
      year,
      duration
    };
  }).catch(function() {
    return { title: "", original: "", year: "", duration: "" };
  });
}
function getTmdbEpisodeName(tmdbId, season, episode) {
  if (!season || !episode) return Promise.resolve("");
  var url = "https://api.themoviedb.org/3/tv/" + tmdbId + "/season/" + season + "/episode/" + episode + "?api_key=" + TMDB_API_KEY;
  return fetchJson(url).then(function(data) {
    return data.name || "";
  }).catch(function() {
    return "";
  });
}
function searchServer(query) {
  dbg("Searching ICC FTP for query:", query);
  var form = "cSearch=" + encodeURIComponent(query);
  return fetch(BASE + "/command.php", {
    method: "POST",
    headers: getHeaders({
      "Content-Type": "application/x-www-form-urlencoded; charset=UTF-8",
      "X-Requested-With": "XMLHttpRequest",
      "Referer": BASE + "/"
    }),
    body: form
  }).then(function(res) {
    updateCookies(res);
    if (!res.ok) {
      throw new Error("Search failed with HTTP " + res.status);
    }
    return res.json();
  }).then(function(data) {
    dbg("Search raw results count:", Array.isArray(data) ? data.length : 0);
    return Array.isArray(data) ? data : [];
  }).catch(function(err) {
    dbg("Search error:", err.message);
    return [];
  });
}
function titlesMatch(a, b) {
  var n1 = String(a || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  var n2 = String(b || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  return n1.indexOf(n2) !== -1 || n2.indexOf(n1) !== -1;
}
function extractQuality(url) {
  var u = String(url || "").toLowerCase();
  if (u.indexOf("2160") !== -1 || u.indexOf("4k") !== -1) return "4K";
  if (u.indexOf("1080") !== -1) return "1080p";
  if (u.indexOf("720") !== -1) return "720p";
  if (u.indexOf("480") !== -1) return "480p";
  return "SD";
}
function scoreQuality(quality) {
  if (quality === "4K") return 40;
  if (quality === "1080p") return 30;
  if (quality === "720p") return 20;
  return 10;
}
function padZero(num) {
  var n = parseInt(num, 10);
  if (isNaN(n)) return num;
  return n < 10 ? "0" + n : String(n);
}
function extractSize(text) {
  var t = String(text || "");
  var match = t.match(/(\d+(?:\.\d+)?\s*(?:GB|MB|KB|gb|mb|kb))/i);
  if (match) {
    return match[1].toUpperCase();
  }
  return "";
}
function buildStream(label, url, quality, meta) {
  var isSeries = !!(meta && (meta.season || meta.episode));
  var displayTitle = meta && meta.title ? meta.title : isSeries ? "Series" : "Movie";
  var year = meta && meta.year ? " - " + meta.year : "";
  var line1;
  if (isSeries) {
    var epTitlePart = meta.episodeTitle ? " - " + meta.episodeTitle : "";
    line1 = "\u{1F4FA} S" + padZero(meta.season) + "E" + padZero(meta.episode) + epTitlePart + " | " + displayTitle + year;
  } else {
    line1 = "\u{1F3AC} " + displayTitle + year;
  }
  var size = extractSize(label);
  var sizeStr = size ? " | \u{1F4BE} " + size : "";
  var line2 = "\u{1F4FA} " + quality + " | \u{1F30D} Bangla/English" + sizeStr;
  var line3 = "\u{1F39E}\uFE0F ICC FTP Stream | \u2139\uFE0F High Speed BDIX Connection";
  var streamTitle = "";
  if (meta && meta.title) {
    streamTitle = meta.title;
    if (isSeries) {
      streamTitle += " S" + padZero(meta.season) + "E" + padZero(meta.episode);
    }
  }
  var nameParts = ["ICC FTP"];
  if (streamTitle) {
    nameParts.push(streamTitle);
  }
  nameParts.push(quality);
  if (size) {
    nameParts.push(size);
  }
  var finalName = nameParts.join(" | ");
  return {
    name: finalName,
    title: line1 + "\n" + line2 + "\n" + line3,
    url,
    quality
  };
}
function extractStreams(id) {
  var url = BASE + "/player.php?session=" + SESSION_TOKEN + "&play=" + id;
  dbg("Extracting streams for item ID:", id, "URL:", url);
  return fetch(url, {
    headers: getHeaders()
  }).then(function(res) {
    updateCookies(res);
    if (!res.ok) {
      throw new Error("Player page load failed with HTTP " + res.status);
    }
    return res.text();
  }).then(function(html) {
    var titleMatch = html.match(/<title>([^<]+)<\/title>/i);
    var pageTitle = "";
    if (titleMatch) {
      pageTitle = titleMatch[1].trim().replace(/ - ICC/gi, "").replace(/ - Player/gi, "").trim();
    }
    var sources = [];
    var sourceRegex = /<source\s+src=['"]([^'"]+)['"]/gi;
    var match;
    while ((match = sourceRegex.exec(html)) !== null) {
      var src = match[1];
      if (src.match(/\.(rar|zip|iso|txt|srt)$/i)) {
        continue;
      }
      sources.push(src);
    }
    return sources.map(function(link) {
      var parts = link.split("/");
      var fileName = parts[parts.length - 1];
      try {
        fileName = decodeURIComponent(fileName);
      } catch (e) {
      }
      var isGenericPageTitle = !pageTitle || pageTitle.toLowerCase() === "icc ftp" || pageTitle.toLowerCase() === "icc ftp server" || pageTitle.toLowerCase() === "player" || pageTitle.toLowerCase() === "icc player" || pageTitle.toLowerCase().indexOf("ftp server") !== -1;
      var titleLabel = "";
      var lowerFileName = fileName.toLowerCase();
      var isGenericFileName = lowerFileName.indexOf("player") !== -1 || lowerFileName.indexOf("index") !== -1 || lowerFileName.indexOf("stream") !== -1 || lowerFileName.indexOf("video") !== -1 || lowerFileName.indexOf("play") !== -1 || lowerFileName.indexOf("source") !== -1;
      if (fileName && !isGenericFileName) {
        titleLabel = fileName;
      } else if (!isGenericPageTitle) {
        titleLabel = pageTitle;
      }
      return {
        url: link,
        title: titleLabel
      };
    });
  }).catch(function(err) {
    dbg("extractStreams error:", err.message);
    return [];
  });
}
function shouldInclude(url, type, year, season, episode) {
  var u = String(url || "");
  if (type === "movie" || type === "movie") {
    if (year) {
      var match = u.match(/\b(19|20)\d{2}\b/);
      if (match) {
        var uYear = parseInt(match[0], 10);
        return Math.abs(uYear - parseInt(year, 10)) <= 1;
      }
    }
    return true;
  }
  if (type === "series" || type === "tv") {
    if (season !== void 0 && episode !== void 0) {
      var regex = new RegExp("S0?" + season + "\\D*E0?" + episode, "i");
      return regex.test(u);
    }
  }
  return true;
}
function getStreams(tmdbId, mediaType, season, episode) {
  dbg("Starting ICC FTP streams fetching for TMDB:", tmdbId, "| MediaType:", mediaType);
  return getTmdbNames(tmdbId, mediaType).then(function(tmdbData) {
    var epPromise = mediaType === "tv" || mediaType === "series" ? getTmdbEpisodeName(tmdbId, season, episode) : Promise.resolve("");
    return epPromise.then(function(epTitle) {
      var meta = {
        title: tmdbData.title || "Movie",
        year: tmdbData.year || "",
        season,
        episode,
        episodeTitle: epTitle
      };
      if (!meta.title) {
        dbg("No title resolved from TMDB, skipping.");
        return [];
      }
      return ensureSession().then(function() {
        return searchServer(meta.title).then(function(searchResults) {
          if (!searchResults || searchResults.length === 0) {
            dbg("No search results found on ICC FTP for:", meta.title);
            return [];
          }
          var filtered = searchResults.filter(function(item) {
            var itemType = String(item.type || "").toLowerCase();
            var isSeriesItem = itemType.indexOf("tv") !== -1 || itemType.indexOf("series") !== -1;
            var requestedSeries = mediaType === "tv" || mediaType === "series";
            if (isSeriesItem !== requestedSeries) return false;
            return item.name && titlesMatch(item.name, meta.title);
          });
          dbg("Matching items count:", filtered.length);
          if (filtered.length === 0) return [];
          var extractionPromises = filtered.map(function(item) {
            return extractStreams(item.id).then(function(extractedList) {
              var validStreams = [];
              extractedList.forEach(function(extracted) {
                if (shouldInclude(extracted.url, mediaType, meta.year, season, episode)) {
                  var q = extractQuality(extracted.url);
                  validStreams.push(buildStream(extracted.title || item.name, extracted.url, q, meta));
                }
              });
              return validStreams;
            });
          });
          return Promise.all(extractionPromises).then(function(resultsArray) {
            var combinedStreams = [];
            resultsArray.forEach(function(list) {
              combinedStreams = combinedStreams.concat(list || []);
            });
            combinedStreams.sort(function(a, b) {
              return scoreQuality(b.quality) - scoreQuality(a.quality);
            });
            return combinedStreams;
          });
        });
      });
    });
  }).catch(function(e) {
    dbg("getStreams failure:", e.message);
    return [];
  });
}
module.exports = {
  getStreams
};
