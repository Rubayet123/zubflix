// app/src/main/assets/providers_src/dhakaflix.js
var PROVIDER_NAME = "Dhakaflix";
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var DEBUG = true;
var SERVERS = {
  movie: { url: "http://172.16.50.14", name: "DHAKA-FLIX-14" },
  series: { url: "http://172.16.50.12", name: "DHAKA-FLIX-12" }
};
var DEFAULT_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  "Accept": "application/json, text/plain, */*",
  "Accept-Language": "en-US,en;q=0.9",
  "Connection": "keep-alive"
};
function dbg() {
  if (!DEBUG) return;
  console.log.apply(console, ["[Dhakaflix DEBUG]"].concat(Array.prototype.slice.call(arguments)));
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
function getNameFromPath(p) {
  var parts = String(p || "").split("/");
  var name = parts[parts.length - 1] || parts[parts.length - 2] || "";
  try {
    name = decodeURIComponent(name);
  } catch (e) {
  }
  return name;
}
function isFile(href) {
  var h = String(href || "").toLowerCase();
  return h.endsWith(".mkv") || h.endsWith(".mp4");
}
function titlesMatch(a, b) {
  var n1 = String(a || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  var n2 = String(b || "").toLowerCase().replace(/[^a-z0-9]+/g, "");
  return n1.indexOf(n2) !== -1 || n2.indexOf(n1) !== -1;
}
function extractSeasonEpisode(name) {
  var n = String(name || "").toLowerCase();
  var match = n.match(/s(\d+)\D*e(\d+)/i) || n.match(/s(\d+)e(\d+)/i) || n.match(/season\D*(\d+)\D*episode\D*(\d+)/i);
  if (match) {
    return {
      season: parseInt(match[1], 10),
      episode: parseInt(match[2], 10)
    };
  }
  return null;
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
function getSearchTerms(title) {
  var clean = String(title || "").replace(/[:\-–—]/g, " ").replace(/\s+/g, " ").trim();
  var words = clean.split(" ");
  var first = words[0];
  var terms = [];
  if (first) {
    terms.push(first);
  }
  if (clean && clean !== first) {
    terms.push(clean);
  }
  return terms;
}
function padZero(num) {
  var n = parseInt(num, 10);
  if (isNaN(n)) return num;
  return n < 10 ? "0" + n : String(n);
}
function buildStream(fileName, url, quality, meta) {
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
  var line3 = "\u{1F39E}\uFE0F Dhakaflix Stream | \u2139\uFE0F High Speed BDIX Connection";
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
function searchSubfolder(query, server, folderHref) {
  dbg("Searching Dhakaflix subfolder:", folderHref, "for query:", query);
  var searchUrl = server.url + "/" + server.name + "/";
  var payload = {
    action: "get",
    search: {
      href: folderHref,
      pattern: query,
      ignorecase: true
    }
  };
  return fetch(searchUrl, {
    method: "POST",
    headers: assign(DEFAULT_HEADERS, {
      "Content-Type": "application/json"
    }),
    body: JSON.stringify(payload)
  }).then(function(res) {
    if (!res.ok) {
      throw new Error("Dhakaflix subfolder search failed with HTTP " + res.status);
    }
    return res.json();
  }).then(function(json) {
    var searchResults = json.search || [];
    dbg("Found", searchResults.length, "subfolder search hits for query:", query);
    return searchResults.map(function(item) {
      var href = item.href || "";
      return {
        href,
        name: getNameFromPath(href),
        fullUrl: server.url + href
      };
    });
  }).catch(function(err) {
    dbg("searchSubfolder error:", err.message);
    return [];
  });
}
function searchServer(query, server) {
  dbg("Searching Dhakaflix server:", server.name, "for query:", query);
  var searchUrl = server.url + "/" + server.name + "/";
  var payload = {
    action: "get",
    search: {
      href: "/" + server.name + "/",
      pattern: query,
      ignorecase: true
    }
  };
  return fetch(searchUrl, {
    method: "POST",
    headers: assign(DEFAULT_HEADERS, {
      "Content-Type": "application/json"
    }),
    body: JSON.stringify(payload)
  }).then(function(res) {
    if (!res.ok) {
      throw new Error("Dhakaflix search failed with HTTP " + res.status);
    }
    return res.json();
  }).then(function(json) {
    var searchResults = json.search || [];
    dbg("Found", searchResults.length, "search hits for query:", query);
    return searchResults.map(function(item) {
      var href = item.href || "";
      return {
        href,
        name: getNameFromPath(href),
        fullUrl: server.url + href
      };
    });
  }).catch(function(err) {
    dbg("searchServer error:", err.message);
    return null;
  });
}
function findMovies(results, meta) {
  var filtered = results.filter(function(item) {
    return isFile(item.href) && (titlesMatch(item.name, meta.title) || titlesMatch(item.href, meta.title));
  });
  return filtered.map(function(item) {
    var q = extractQuality(item.name);
    return buildStream(item.name, item.fullUrl, q, meta);
  });
}
function findSeries(results, meta, s, e, isSubfolderSearch) {
  var filtered = results.filter(function(item) {
    if (!isFile(item.href)) {
      return false;
    }
    if (!isSubfolderSearch && !titlesMatch(item.name, meta.title) && !titlesMatch(item.href, meta.title)) {
      return false;
    }
    var se = extractSeasonEpisode(item.name);
    return se && se.season === s && se.episode === e;
  });
  return filtered.map(function(item) {
    var q = extractQuality(item.name);
    return buildStream(item.name, item.fullUrl, q, meta);
  });
}
function getStreams(tmdbId, mediaType, season, episode, titleFallback) {
  dbg("Starting Dhakaflix streams fetching for TMDB ID:", tmdbId, "| MediaType:", mediaType, "| Title Fallback:", titleFallback);
  var isSeriesType = mediaType === "tv" || mediaType === "series";
  var serverType = isSeriesType ? "series" : "movie";
  var server = SERVERS[serverType];
  if (!server) {
    dbg("Invalid mediaType or server mapping not found.");
    return Promise.resolve([]);
  }
  return getTmdbNames(tmdbId, mediaType).then(function(tmdbData) {
    var epPromise = isSeriesType ? getTmdbEpisodeName(tmdbId, season, episode) : Promise.resolve("");
    return epPromise.then(function(epTitle) {
      var resolvedTitle = tmdbData.title || titleFallback || (isSeriesType ? "Series" : "Movie");
      var meta = {
        title: resolvedTitle,
        year: tmdbData.year || "",
        season,
        episode,
        episodeTitle: epTitle
      };
      if (!meta.title) {
        dbg("No title resolved from TMDB, skipping.");
        return [];
      }
      var terms = getSearchTerms(meta.title);
      dbg("Generated search terms:", JSON.stringify(terms));
      var p = Promise.resolve([]);
      function executeTermSearch(index) {
        if (index >= terms.length) {
          return Promise.resolve([]);
        }
        var term = terms[index];
        return searchServer(term, server).then(function(results) {
          if (results && results.length > 0) {
            dbg("Found matched results for term:", term, "Processing streams...");
            if (!isSeriesType) {
              var streams = findMovies(results, meta);
              if (streams.length > 0) {
                streams.sort(function(a, b) {
                  return scoreQuality(b.quality) - scoreQuality(a.quality);
                });
                return streams;
              }
            } else {
              var matchedFolder = results.find(function(item) {
                return item.href.endsWith("/") && (titlesMatch(item.name, meta.title) || titlesMatch(item.href, meta.title));
              });
              if (matchedFolder) {
                dbg("Found matched series folder:", matchedFolder.href, matchedFolder.name);
                return Promise.all([
                  searchSubfolder("mkv", server, matchedFolder.href),
                  searchSubfolder("mp4", server, matchedFolder.href)
                ]).then(function(subResults) {
                  var combined = (subResults[0] || []).concat(subResults[1] || []);
                  dbg("Total files found inside series folder:", combined.length);
                  var streams2 = findSeries(combined, meta, season || 1, episode || 1, true);
                  if (streams2.length > 0) {
                    streams2.sort(function(a, b) {
                      return scoreQuality(b.quality) - scoreQuality(a.quality);
                    });
                    return streams2;
                  }
                  var fallbackStreams = findSeries(results, meta, season || 1, episode || 1, false);
                  if (fallbackStreams.length > 0) {
                    fallbackStreams.sort(function(a, b) {
                      return scoreQuality(b.quality) - scoreQuality(a.quality);
                    });
                    return fallbackStreams;
                  }
                  return executeTermSearch(index + 1);
                });
              } else {
                var streams = findSeries(results, meta, season || 1, episode || 1, false);
                if (streams.length > 0) {
                  streams.sort(function(a, b) {
                    return scoreQuality(b.quality) - scoreQuality(a.quality);
                  });
                  return streams;
                }
              }
            }
          }
          return executeTermSearch(index + 1);
        });
      }
      return executeTermSearch(0);
    });
  }).catch(function(e) {
    dbg("getStreams failure:", e.message);
    return [];
  });
}
module.exports = {
  getStreams
};
