// app/src/main/assets/providers_src/discovery.js
var PROVIDER_NAME = "Discovery FTP";
var BASE = "https://movies.discoveryftp.net";
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var DEBUG = true;
var DEFAULT_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
  "Accept-Language": "en-US,en;q=0.9",
  "Connection": "keep-alive"
};
function dbg() {
  if (!DEBUG) return;
  console.log.apply(console, ["[Discovery FTP DEBUG]"].concat(Array.prototype.slice.call(arguments)));
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
function searchServer(query, mediaType) {
  dbg("Searching Discovery FTP for query:", query, "mediaType:", mediaType);
  var searchType = mediaType === "movie" ? "m" : "s";
  var formBody = "term=" + encodeURIComponent(query) + "&types=" + searchType;
  return fetch(BASE + "/search", {
    method: "POST",
    headers: assign(DEFAULT_HEADERS, {
      "Content-Type": "application/x-www-form-urlencoded"
    }),
    body: formBody
  }).then(function(res) {
    if (!res.ok) {
      throw new Error("Search failed with HTTP " + res.status);
    }
    return res.text();
  }).then(function(html) {
    return parseSearchResults(html);
  }).catch(function(err) {
    dbg("Search error:", err.message);
    return [];
  });
}
function parseSearchResults(html) {
  var list = [];
  var regex = /<a href="(\/[ms]\/view\/\d+)"[^>]*>[\s\S]*?<div class="searchtitle"[^>]*>([^<]+)<\/div>[\s\S]*?<div class="searchdetails"[^>]*>([\s\S]*?)<\/div>/gi;
  var match;
  while ((match = regex.exec(html)) !== null) {
    list.push({
      url: BASE + match[1],
      title: match[2].trim(),
      details: match[3].trim()
    });
  }
  dbg("Found", list.length, "raw search results.");
  return list;
}
function extractYear(text) {
  var match = String(text || "").match(/\b(19\d{2}|20\d{2})\b/);
  return match ? parseInt(match[0], 10) : null;
}
function scoreSearchItem(item, meta) {
  var score = 10;
  var yearText = item.details + " " + item.title;
  var itemYear = extractYear(yearText);
  if (itemYear && meta.year) {
    var diff = Math.abs(itemYear - parseInt(meta.year, 10));
    if (diff === 0) {
      score += 15;
    } else if (diff === 1) {
      score += 8;
    } else {
      score -= 5;
    }
  }
  return score;
}
function extractQuality(url) {
  var u = String(url || "").toLowerCase();
  if (u.indexOf("2160") !== -1 || u.indexOf("4k") !== -1) return "4K";
  if (u.indexOf("1080") !== -1) return "1080p";
  if (u.indexOf("720") !== -1) return "720p";
  if (u.indexOf("480") !== -1) return "480p";
  return "SD";
}
function padZero(num) {
  var n = parseInt(num, 10);
  if (isNaN(n)) return num;
  return n < 10 ? "0" + n : String(n);
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
  var line2 = "\u{1F4FA} " + quality + " | \u{1F30D} Bangla/English";
  var line3 = "\u{1F39E}\uFE0F Discovery FTP Stream | \u2139\uFE0F High Speed BDIX Connection";
  var streamTitle = "";
  if (meta && meta.title) {
    streamTitle = meta.title;
    if (isSeries) {
      streamTitle += " S" + padZero(meta.season) + "E" + padZero(meta.episode);
    }
  }
  var nameParts = [PROVIDER_NAME];
  if (streamTitle) {
    nameParts.push(streamTitle);
  }
  nameParts.push(quality);
  var finalName = nameParts.join(" | ");
  return {
    name: finalName,
    title: line1 + "\n" + line2 + "\n" + line3,
    url,
    quality
  };
}
function extractLinks(html) {
  var links = [];
  var regex = /href=['"](https?:\/\/p?cdn[^\s'"]+\.(?:mkv|mp4))['"]/gi;
  var match;
  while ((match = regex.exec(html)) !== null) {
    if (links.indexOf(match[1]) === -1) {
      links.push(match[1]);
    }
  }
  return links;
}
function getMovieStreams(url, meta) {
  dbg("Fetching movie details page:", url);
  return fetch(url, {
    headers: DEFAULT_HEADERS
  }).then(function(res) {
    if (!res.ok) throw new Error("Failed to fetch movie page: " + res.status);
    return res.text();
  }).then(function(html) {
    var links = extractLinks(html);
    dbg("Extracted links for movie:", links.length);
    return links.map(function(link) {
      var q = extractQuality(link);
      return buildStream(meta.title, link, q, meta);
    });
  }).catch(function(err) {
    dbg("getMovieStreams error:", err.message);
    return [];
  });
}
function shouldIncludeSeriesLink(link, season, episode) {
  var l = String(link || "").toUpperCase();
  var sPattern = "S" + season;
  var sPatternZero = "S" + padZero(season);
  var ePattern = "E" + episode;
  var ePatternZero = "E" + padZero(episode);
  var hasSeason = l.indexOf(sPattern) !== -1 || l.indexOf(sPatternZero) !== -1;
  var hasEpisode = l.indexOf(ePattern) !== -1 || l.indexOf(ePatternZero) !== -1;
  var regex1 = new RegExp("S0?" + season + "\\D*E0?" + episode, "i");
  if (regex1.test(l)) {
    return true;
  }
  return hasSeason || hasEpisode;
}
function getSeriesStreams(url, season, episode, meta) {
  dbg("Fetching series details page:", url);
  return fetch(url, {
    headers: DEFAULT_HEADERS
  }).then(function(res) {
    if (!res.ok) throw new Error("Failed to fetch series page: " + res.status);
    return res.text();
  }).then(function(html) {
    var links = extractLinks(html);
    dbg("Extracted series links total:", links.length);
    var filtered = links.filter(function(link) {
      return shouldIncludeSeriesLink(link, season, episode);
    });
    dbg("Filtered series links matching S" + season + " E" + episode + ":", filtered.length);
    return filtered.map(function(link) {
      var q = extractQuality(link);
      return buildStream(meta.title, link, q, meta);
    });
  }).catch(function(err) {
    dbg("getSeriesStreams error:", err.message);
    return [];
  });
}
function getStreams(tmdbId, mediaType, season, episode) {
  dbg("Starting Discovery FTP streams fetching for TMDB ID:", tmdbId, "| MediaType:", mediaType);
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
      return searchServer(meta.title, mediaType).then(function(searchResults) {
        if (!searchResults || searchResults.length === 0) {
          dbg("No search results found on Discovery FTP for:", meta.title);
          return [];
        }
        var scoredItems = searchResults.map(function(item) {
          return {
            item,
            score: scoreSearchItem(item, meta)
          };
        });
        scoredItems.sort(function(a, b) {
          return b.score - a.score;
        });
        var bestMatch = scoredItems[0].item;
        dbg("Best match selected:", bestMatch.title, "Score:", scoredItems[0].score, "URL:", bestMatch.url);
        if (mediaType === "movie" || mediaType === "movie") {
          return getMovieStreams(bestMatch.url, meta);
        } else {
          return getSeriesStreams(bestMatch.url, season || 1, episode || 1, meta);
        }
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
