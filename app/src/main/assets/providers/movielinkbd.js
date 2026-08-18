// app/src/main/assets/providers_src/movielinkbd.js
var cheerio = require("cheerio-without-node-native");
var DEFAULT_MAIN_URL = "https://movielinkbd.io";
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var DEBUG = true;
var DEFAULT_HEADERS = {
  "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36",
  "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.7",
  "Accept-Language": "en-US,en;q=0.9",
  "Connection": "keep-alive",
  "Cache-Control": "max-age=0"
};
var cachedBaseUrl = null;
var lastBaseFetchTs = 0;
var BASE_CACHE_TTL = 30 * 60 * 1e3;
function dbg() {
  if (!DEBUG) return;
  console.log.apply(console, ["[MovieLinkBD DEBUG]"].concat(Array.prototype.slice.call(arguments)));
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
function fetchText(url, options) {
  options = options || {};
  return fetch(url, {
    method: options.method || "GET",
    redirect: options.redirect || "follow",
    headers: assign(DEFAULT_HEADERS, options.headers || {}),
    body: options.body
  }).then(function(res) {
    if (!res.ok) {
      throw new Error("HTTP " + res.status + " -> " + url);
    }
    return res.text();
  });
}
function fetchJson(url, options) {
  options = options || {};
  return fetch(url, {
    method: options.method || "GET",
    redirect: options.redirect || "follow",
    headers: assign(DEFAULT_HEADERS, options.headers || {}),
    body: options.body
  }).then(function(res) {
    if (!res.ok) throw new Error("HTTP " + res.status + " -> " + url);
    return res.json();
  });
}
function fetchResponse(url, options) {
  options = options || {};
  return fetch(url, {
    method: options.method || "GET",
    redirect: options.redirect || "follow",
    headers: assign(DEFAULT_HEADERS, options.headers || {}),
    body: options.body
  });
}
function getBaseUrl() {
  var now = Date.now();
  if (cachedBaseUrl && now - lastBaseFetchTs < BASE_CACHE_TTL) {
    return Promise.resolve(cachedBaseUrl);
  }
  dbg("Probing active domain starting from:", DEFAULT_MAIN_URL);
  return fetchResponse(DEFAULT_MAIN_URL, { method: "GET" }).then(function(res) {
    var finalUrl = res.url || DEFAULT_MAIN_URL;
    try {
      var parsed = new URL(finalUrl);
      var base = parsed.protocol + "//" + parsed.host;
      cachedBaseUrl = base;
      lastBaseFetchTs = now;
      dbg("Resolved active domain to:", base);
      return base;
    } catch (e) {
      cachedBaseUrl = DEFAULT_MAIN_URL;
      lastBaseFetchTs = now;
      return DEFAULT_MAIN_URL;
    }
  }).catch(function(err) {
    dbg("Error probing base domain:", err.message);
    return DEFAULT_MAIN_URL;
  });
}
function fixUrl(url, baseUrl) {
  if (!url) return "";
  if (url.indexOf("http://") === 0 || url.indexOf("https://") === 0) return url;
  if (url.indexOf("//") === 0) return "https:" + url;
  try {
    return new URL(url, baseUrl).toString();
  } catch (e) {
    return url;
  }
}
function normalizeTitle(value) {
  return String(value || "").toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}
function cleanTitleHelper(title) {
  var t = String(title || "");
  var idx1 = t.indexOf("[");
  if (idx1 !== -1) t = t.substring(0, idx1);
  var idx2 = t.indexOf("(");
  if (idx2 !== -1) t = t.substring(0, idx2);
  return t.trim();
}
function levenshteinDistance(s, t) {
  if (s === t) return 0;
  var n = s.length, m = t.length;
  if (n === 0) return m;
  if (m === 0) return n;
  var d = [];
  var i, j, cost;
  for (i = 0; i <= n; i += 1) {
    d[i] = [];
    d[i][0] = i;
  }
  for (j = 0; j <= m; j += 1) d[0][j] = j;
  for (i = 1; i <= n; i += 1) {
    for (j = 1; j <= m; j += 1) {
      cost = s.charAt(i - 1) === t.charAt(j - 1) ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
    }
  }
  return d[n][m];
}
function extractQualityLabel(text) {
  var t = String(text || "").toLowerCase();
  if (t.indexOf("4k") !== -1 || t.indexOf("2160") !== -1) return "2160p";
  if (t.indexOf("1080") !== -1) return "1080p";
  if (t.indexOf("720p hevc") !== -1 || t.indexOf("720 hevc") !== -1) return "720p HEVC";
  if (t.indexOf("720") !== -1) return "720p";
  if (t.indexOf("480") !== -1) return "480p";
  if (t.indexOf("360") !== -1) return "360p";
  return "Auto";
}
function cleanLabelText(text) {
  return String(text || "").replace(/\s+/g, " ").replace(/Watch Online/gi, "").replace(/Download/gi, "").trim();
}
function inferLang(text) {
  var t = String(text || "").toLowerCase();
  var langs = [];
  if (t.indexOf("bangla") !== -1 || t.indexOf("bengali") !== -1) langs.push("Bangla");
  if (t.indexOf("hindi") !== -1) langs.push("Hindi");
  if (t.indexOf("tamil") !== -1) langs.push("Tamil");
  if (t.indexOf("telugu") !== -1) langs.push("Telugu");
  if (t.indexOf("english") !== -1 || /\beng\b/.test(t)) langs.push("English");
  if (langs.length > 2) return "Multi Audio";
  if (langs.length === 2) return langs.join("-");
  if (langs.length === 1) return langs[0];
  if (t.indexOf("dual audio") !== -1 || t.indexOf("dual") !== -1) return "Dual Audio";
  return "English";
}
function buildStream(label, finalUrl, finalQuality, streamHeaders, size, tech, langHint, meta) {
  var ui = buildMeta(meta, label, finalQuality, size, tech, langHint);
  return {
    name: ui.name,
    title: ui.title,
    url: finalUrl,
    quality: finalQuality,
    headers: Object.keys(streamHeaders || {}).length ? streamHeaders : void 0,
    behaviorHints: {
      bingeGroup: "movielinkbd-" + String(finalQuality || "auto").toLowerCase()
    }
  };
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
function buildMeta(meta, label, quality, size, tech, langHint) {
  var cleanedLabel = cleanLabelText(label);
  var lang = inferLang((langHint || "") + " " + cleanedLabel);
  var isSeries = !!(meta && (meta.season || meta.episode));
  var displayTitle = meta && meta.title ? meta.title : isSeries ? "Series" : "Movie";
  var year = meta && meta.year ? " - " + meta.year : "";
  var line1;
  if (isSeries) {
    var epTitlePart = meta.episodeTitle ? " - " + meta.episodeTitle : "";
    line1 = "\u{1F4FA} S" + meta.season + "E" + meta.episode + epTitlePart + " | " + displayTitle + year;
  } else {
    line1 = "\u{1F3AC} " + displayTitle + year;
  }
  if (!size) {
    size = extractSize(label);
  }
  var qIcon = quality.indexOf("2160") !== -1 || quality.indexOf("4K") !== -1 ? "\u{1F48E}" : "\u{1F4FA}";
  var line2 = qIcon + " " + quality + " | \u{1F30D} " + lang + (size ? " | \u{1F4BE} " + size : "");
  var line3 = "\u{1F39E}\uFE0F Direct Stream | \u2139\uFE0F " + (tech || "WEB-DL");
  var streamTitle = "";
  if (meta && meta.title) {
    streamTitle = meta.title;
    if (isSeries) {
      streamTitle += " S" + padZero(meta.season) + "E" + padZero(meta.episode);
    }
  }
  var nameParts = ["MovieLinkBD"];
  if (streamTitle) {
    nameParts.push(streamTitle);
  }
  nameParts.push(quality);
  if (size) {
    nameParts.push(size);
  }
  if (tech) {
    nameParts.push(tech);
  }
  var finalName = nameParts.join(" | ");
  return {
    name: finalName,
    title: line1 + "\n" + line2 + "\n" + line3
  };
}
function uniqueBy(list, keyFn) {
  var seen = {};
  var out = [];
  var i, key;
  for (i = 0; i < list.length; i += 1) {
    key = keyFn(list[i]);
    if (seen[key]) continue;
    seen[key] = 1;
    out.push(list[i]);
  }
  return out;
}
function dedupeStreams(streams) {
  return uniqueBy(streams, function(s) {
    var urlKey = String(s.url || "").split("?")[0].toLowerCase();
    var qualityKey = String(s.quality || "").toLowerCase();
    return urlKey + "|" + qualityKey;
  });
}
function hostConfidence(url) {
  var u = String(url || "").toLowerCase();
  if (u.indexOf("r2.dev") !== -1 || u.indexOf("cloudflarestorage.com") !== -1) return 95;
  if (u.indexOf("movielinkbd.mom") !== -1) return 90;
  if (u.indexOf("instantcloud.org") !== -1) return 85;
  if (u.indexOf("/open/") !== -1) return 80;
  if (u.indexOf("movielinkbd") !== -1) return 50;
  return 10;
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
function searchContent(query, mediaType, year) {
  return getBaseUrl().then(function(baseUrl) {
    var searchQuery = query;
    var searchUrl = baseUrl + "/search?q=" + encodeURIComponent(searchQuery);
    dbg("[searchContent] URL:", searchUrl, "| type:", mediaType, "| year:", year);
    return fetchText(searchUrl).then(function(html) {
      var $ = cheerio.load(html);
      var results = [];
      var CARD_SELECTOR = [
        "div.movie-item",
        "div.item-box",
        "div.film-item",
        "div.post-item",
        ".movie-card"
      ].join(", ");
      var cards = $(CARD_SELECTOR);
      if (cards.length > 0) {
        cards.each(function(_, el) {
          var card = $(el);
          var aTag = card.find("a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']").first();
          if (!aTag.length) return;
          var href = fixUrl(aTag.attr("href"), baseUrl);
          if (!href) return;
          var title = card.find(".title, .movie-title, h3, h2").first().text().trim() || aTag.attr("title") || aTag.attr("aria-label") || "";
          if (!title) return;
          var poster = card.find("img").attr("data-src") || card.find("img").attr("src") || "";
          poster = fixUrl(poster, baseUrl);
          var isSeriesCard = href.indexOf("/series/") !== -1 || href.indexOf("/anime/") !== -1;
          if (mediaType === "movie" && isSeriesCard) return;
          if (mediaType !== "movie" && !isSeriesCard) return;
          var cleanedTitle = cleanTitleHelper(title);
          var yearMatch = title.match(/\((\d{4})\)/) || href.match(/\b(19|20)\d{2}\b/);
          var itemYear = yearMatch ? parseInt(yearMatch[1] || yearMatch[0], 10) : 0;
          var distance = levenshteinDistance(normalizeTitle(cleanedTitle), normalizeTitle(query));
          var yearDistance = year && itemYear ? Math.abs(itemYear - year) : 0;
          var exactBoost = normalizeTitle(cleanedTitle) === normalizeTitle(query) ? -100 : 0;
          var includesBoost = normalizeTitle(cleanedTitle).indexOf(normalizeTitle(query)) !== -1 ? -10 : 0;
          results.push({
            href,
            title: cleanedTitle,
            year: itemYear,
            distance,
            yearDistance,
            score: distance + yearDistance + exactBoost + includesBoost,
            poster
          });
        });
      }
      if (results.length === 0) {
        var movieLinkPattern = "a[href*='/movie/'], a[href*='/series/'], a[href*='/anime/'], a[href*='/download18plus/']";
        var seen = {};
        $(movieLinkPattern).each(function(_, el) {
          var a = $(el);
          var href = fixUrl(a.attr("href"), baseUrl);
          if (!href) return;
          if (seen[href]) return;
          seen[href] = true;
          var titleEl = a.parent().find(".title, .movie-title, h3, h2, [class*='name']").first();
          var title = (titleEl.length ? titleEl.text().trim() : "") || a.attr("title") || a.text().trim();
          if (!title) return;
          if (title.length < 4) return;
          var isSeriesCard = href.indexOf("/series/") !== -1 || href.indexOf("/anime/") !== -1;
          if (mediaType === "movie" && isSeriesCard) return;
          if (mediaType !== "movie" && !isSeriesCard) return;
          var cleanedTitle = cleanTitleHelper(title);
          var yearMatch = title.match(/\((\d{4})\)/) || href.match(/\b(19|20)\d{2}\b/);
          var itemYear = yearMatch ? parseInt(yearMatch[1] || yearMatch[0], 10) : 0;
          var distance = levenshteinDistance(normalizeTitle(cleanedTitle), normalizeTitle(query));
          var yearDistance = year && itemYear ? Math.abs(itemYear - year) : 0;
          var exactBoost = normalizeTitle(cleanedTitle) === normalizeTitle(query) ? -100 : 0;
          var includesBoost = normalizeTitle(cleanedTitle).indexOf(normalizeTitle(query)) !== -1 ? -10 : 0;
          results.push({
            href,
            title: cleanedTitle,
            year: itemYear,
            distance,
            yearDistance,
            score: distance + yearDistance + exactBoost + includesBoost
          });
        });
      }
      dbg("[searchContent] Candidates match scores count:", results.length);
      if (!results.length) return null;
      results.sort(function(a, b) {
        return a.score - b.score || a.distance - b.distance;
      });
      dbg("[searchContent] Selected Best Link:", results[0].href);
      return results[0].href || null;
    });
  });
}
function collectTargetLinks($, contentUrl, isSeries, season, episode) {
  var targetLinks = [];
  var linkAnchors = [];
  $("a[href*='/getLink/']").each(function(_, el) {
    var href = fixUrl($(el).attr("href"), contentUrl);
    if (href) linkAnchors.push({ label: $(el).text().trim(), url: href });
  });
  var watchAnchors = [];
  $("a[href*='/getWatch/']").each(function(_, el) {
    var href = fixUrl($(el).attr("href"), contentUrl);
    if (href) watchAnchors.push({ label: $(el).text().trim(), url: href });
  });
  if (!isSeries) {
    targetLinks = linkAnchors.concat(watchAnchors);
  } else {
    var eNum = Number(episode || 1);
    var episodeSections = $([
      "div.episode-section",
      "div.season-section",
      "h3:contains(Episode)",
      "h4:contains(Episode)",
      "div[class*='episode']",
      "div[class*='season']",
      "strong:contains(Ep)",
      "b:contains(Ep)",
      "strong:contains(Episode)",
      "b:contains(Episode)"
    ].join(", "));
    if (episodeSections.length > 0) {
      episodeSections.each(function(_, el) {
        var section = $(el);
        var sectionText = section.text();
        var epMatch = sectionText.match(/(?:Ep|Episode)[^\d]*(\d+)(?:[^\d]+(\d+))?/i);
        if (epMatch) {
          var start = parseInt(epMatch[1], 10);
          var end = epMatch[2] ? parseInt(epMatch[2], 10) : start;
          if (eNum >= start && eNum <= end) {
            var sib = section.next();
            while (sib.length > 0) {
              if (/^h[1-6]$/i.test(sib[0].tagName)) {
                break;
              }
              sib.find("a[href*='/getLink/'], a[href*='/getWatch/']").each(function(_2, a) {
                var href = fixUrl($(a).attr("href"), contentUrl);
                if (href) {
                  targetLinks.push({ label: $(a).text().trim(), url: href });
                }
              });
              sib = sib.next();
            }
          }
        }
      });
    }
    if (targetLinks.length === 0) {
      var merged = linkAnchors.concat(watchAnchors);
      var idx = eNum - 1;
      if (idx >= 0 && idx < merged.length) {
        targetLinks.push(merged[idx]);
      }
    }
  }
  return targetLinks;
}
function resolveGetWatch(url, label, qualityLabel, qualityValue, meta) {
  dbg("Resolving /getWatch/ page:", url);
  return fetchText(url).then(function(html) {
    var $ = cheerio.load(html);
    var videoSrc = $("video source").attr("src") || $("video").attr("src") || $("iframe").attr("src") || "";
    if (!videoSrc) return [];
    var fullVideoSrc = fixUrl(videoSrc, url);
    var type = fullVideoSrc.indexOf(".m3u8") !== -1 ? "m3u8" : "video";
    var stream = buildStream(
      label + " [Stream]",
      fullVideoSrc,
      qualityLabel || "Auto",
      DEFAULT_HEADERS,
      "",
      "Stream",
      "",
      meta
    );
    return [stream];
  }).catch(function(e) {
    dbg("Error in resolveGetWatch:", e.message);
    return [];
  });
}
function resolveGetLink(url, label, qualityLabel, qualityValue, meta) {
  dbg("Resolving /getLink/ page:", url);
  return fetchText(url).then(function(html) {
    var $ = cheerio.load(html);
    var fileAnchor = $("a[href*='/file/']").first();
    var fileUrl = fileAnchor.length ? fixUrl(fileAnchor.attr("href"), url) : "";
    var promises = [];
    if (fileUrl) {
      promises.push(resolveFileUrl(fileUrl, label, qualityLabel, qualityValue, meta));
    }
    var mirrors = [];
    $("a[href]").each(function(_, el) {
      var href = fixUrl($(el).attr("href"), url);
      if (!href) return;
      if (href.indexOf("http") === 0 && href.indexOf("movielinkbd") === -1 && href.indexOf("telegram") === -1 && href.indexOf("t.me") === -1 && href.indexOf("facebook") === -1 && href.indexOf("google.com") === -1) {
        mirrors.push(buildStream(
          label + " (Mirror Extractor)",
          href,
          qualityLabel || "Auto",
          DEFAULT_HEADERS,
          "",
          "Mirror",
          "",
          meta
        ));
      }
    });
    return Promise.all(promises).then(function(results) {
      var streams = [];
      if (results.length > 0 && results[0]) {
        streams = streams.concat(results[0]);
      }
      streams = streams.concat(mirrors);
      return streams;
    });
  }).catch(function(e) {
    dbg("Error in resolveGetLink:", e.message);
    return [];
  });
}
function resolveFileUrl(fileUrl, label, qualityLabel, qualityValue, meta) {
  dbg("Resolving /file/ page:", fileUrl);
  return fetchText(fileUrl).then(function(html) {
    var $ = cheerio.load(html);
    var directLinkEl = $("a[href*='token=']").first();
    if (!directLinkEl.length) {
      $("a").each(function(_, el) {
        var text = $(el).text();
        if (text.indexOf("Open Direct Download Link") !== -1) {
          directLinkEl = $(el);
          return false;
        }
      });
    }
    if (directLinkEl.length > 0) {
      var href = directLinkEl.attr("href").trim();
      var directUrl = fixUrl(href, fileUrl);
      dbg("Found direct download wrapper link:", directUrl);
      return fetchText(directUrl).then(function(directHtml) {
        var directDoc = cheerio.load(directHtml);
        var streams = [];
        directDoc("a[href]").each(function(_, aEl) {
          var rawHref = directDoc(aEl).attr("href") || "";
          var fullPlayUrl = fixUrl(rawHref, directUrl);
          var lowerHref = rawHref.toLowerCase();
          if (lowerHref.indexOf("/open/") !== -1 || lowerHref.indexOf("r2.dev") !== -1 || lowerHref.indexOf("movielinkbd.mom") !== -1 || lowerHref.indexOf("instantcloud.org") !== -1 || lowerHref.indexOf("cloudflarestorage.com") !== -1) {
            var sourceLabel = "Direct Link";
            if (lowerHref.indexOf("r2.dev") !== -1 || lowerHref.indexOf("cloudflarestorage.com") !== -1) {
              sourceLabel = "Fast R2 Cloud";
            } else if (lowerHref.indexOf("movielinkbd.mom") !== -1) {
              sourceLabel = "Direct Mom";
            } else if (lowerHref.indexOf("/open/") !== -1) {
              sourceLabel = "Direct Open";
            } else if (lowerHref.indexOf("instantcloud.org") !== -1) {
              sourceLabel = "Instant Cloud";
            }
            streams.push(buildStream(
              label + " (" + sourceLabel + ")",
              fullPlayUrl,
              qualityLabel || "Auto",
              DEFAULT_HEADERS,
              "",
              sourceLabel,
              "",
              meta
            ));
          }
        });
        if (streams.length === 0) {
          streams.push(buildStream(
            label + " (Direct Link)",
            directUrl,
            qualityLabel || "Auto",
            DEFAULT_HEADERS,
            "",
            "Direct",
            "",
            meta
          ));
        }
        return streams;
      }).catch(function(e) {
        dbg("Error opening direct download wrapped page:", e.message);
        return [
          buildStream(
            label + " (File Link)",
            fileUrl,
            qualityLabel || "Auto",
            DEFAULT_HEADERS,
            "",
            "Download",
            "",
            meta
          )
        ];
      });
    } else {
      dbg("No Open Direct Download Link anchor on file page, returning page URL.");
      return [
        buildStream(
          label + " (File Page)",
          fileUrl,
          qualityLabel || "Auto",
          DEFAULT_HEADERS,
          "",
          "Download",
          "",
          meta
        )
      ];
    }
  }).catch(function(e) {
    dbg("Error in resolveFileUrl:", e.message);
    return [];
  });
}
function findContentUrl(tmdbId, mediaType) {
  return getTmdbNames(tmdbId, mediaType).then(function(names) {
    if (!names.title && !names.original) return null;
    return searchContent(names.title, mediaType, names.year).then(function(found) {
      if (found) return found;
      if (names.original && names.original !== names.title) {
        return searchContent(names.original, mediaType, names.year);
      }
      return null;
    });
  });
}
function extractFromPage(contentUrl, mediaType, season, episode, meta) {
  return fetchText(contentUrl).then(function(html) {
    var $ = cheerio.load(html);
    var isSeries = mediaType === "tv" || mediaType === "series";
    var targetLinks = collectTargetLinks($, contentUrl, isSeries, season, episode);
    dbg("[extractFromPage] Found target links on page:", targetLinks.length);
    if (!targetLinks.length) return [];
    return Promise.all(targetLinks.map(function(item) {
      var qualityLabel = extractQualityLabel(item.label);
      if (item.url.indexOf("/getWatch/") !== -1) {
        return resolveGetWatch(item.url, item.label, qualityLabel, null, meta);
      } else if (item.url.indexOf("/getLink/") !== -1) {
        return resolveGetLink(item.url, item.label, qualityLabel, null, meta);
      } else if (item.url.indexOf("/file/") !== -1) {
        return resolveFileUrl(item.url, item.label, qualityLabel, null, meta);
      } else {
        return [buildStream(item.label, item.url, qualityLabel, DEFAULT_HEADERS, "", "", "", meta)];
      }
    })).then(function(groups) {
      var streams = [];
      for (var i = 0; i < groups.length; i += 1) {
        streams = streams.concat(groups[i] || []);
      }
      streams = dedupeStreams(streams);
      streams.sort(function(a, b) {
        return hostConfidence(b.url) - hostConfidence(a.url);
      });
      return streams;
    });
  });
}
function getStreams(tmdbId, mediaType, season, episode) {
  dbg("Starting MovieLinkBD streams fetching for TMDB:", tmdbId, "| MediaType:", mediaType);
  return getTmdbNames(tmdbId, mediaType).then(function(tmdbData) {
    var epPromise = mediaType === "tv" ? getTmdbEpisodeName(tmdbId, season, episode) : Promise.resolve("");
    return epPromise.then(function(epTitle) {
      return findContentUrl(tmdbId, mediaType).then(function(contentUrl) {
        if (!contentUrl) {
          dbg("No content URL found for TMDB id:", tmdbId);
          return [];
        }
        var meta = {
          title: tmdbData.title || "Movie",
          year: tmdbData.year || "",
          season,
          episode,
          episodeTitle: epTitle
        };
        dbg("Content URL located successfully:", contentUrl);
        return extractFromPage(contentUrl, mediaType, season, episode, meta);
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
