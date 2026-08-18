"use strict";
var __defProp = Object.defineProperty;
var __getOwnPropSymbols = Object.getOwnPropertySymbols;
var __hasOwnProp = Object.prototype.hasOwnProperty;
var __propIsEnum = Object.prototype.propertyIsEnumerable;
var __defNormalProp = (obj, key, value) => key in obj ? __defProp(obj, key, { enumerable: true, configurable: true, writable: true, value }) : obj[key] = value;
var __spreadValues = (a, b) => {
  for (var prop in b || (b = {}))
    if (__hasOwnProp.call(b, prop))
      __defNormalProp(a, prop, b[prop]);
  if (__getOwnPropSymbols)
    for (var prop of __getOwnPropSymbols(b)) {
      if (__propIsEnum.call(b, prop))
        __defNormalProp(a, prop, b[prop]);
    }
  return a;
};
var __async = (__this, __arguments, generator) => {
  return new Promise((resolve, reject) => {
    var fulfilled = (value) => {
      try {
        step(generator.next(value));
      } catch (e) {
        reject(e);
      }
    };
    var rejected = (value) => {
      try {
        step(generator.throw(value));
      } catch (e) {
        reject(e);
      }
    };
    var step = (x) => x.done ? resolve(x.value) : Promise.resolve(x.value).then(fulfilled, rejected);
    step((generator = generator.apply(__this, __arguments)).next());
  });
};

// app/src/main/assets/providers_src/moviebox.js
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var TMDB_BASE_URL = "https://api.themoviedb.org/3";
var TAG = "[MovieBox]";
var bearerToken = null;
function cleanTitle(str) {
  if (!str) return "";
  return str.toLowerCase().replace(/[^a-z0-9]/g, "").trim();
}
function titlesMatch(t1, t2) {
  if (!t1 || !t2) return false;
  const c1 = cleanTitle(t1);
  const c2 = cleanTitle(t2);
  return c1 === c2 || c1.includes(c2) || c2.includes(c1);
}
function getTMDBDetails(tmdbId, mediaType) {
  return __async(this, null, function* () {
    var _a;
    const type = mediaType === "tv" || mediaType === "series" ? "tv" : "movie";
    const url = `${TMDB_BASE_URL}/${type}/${tmdbId}?api_key=${TMDB_API_KEY}&append_to_response=external_ids`;
    try {
      const res = yield fetch(url, { headers: { Accept: "application/json" } });
      if (!res.ok) throw new Error("TMDB HTTP " + res.status);
      const data = yield res.json();
      const title = type === "tv" ? data.name : data.title;
      const releaseDate = type === "tv" ? data.first_air_date : data.release_date;
      return {
        title: title || "",
        year: releaseDate ? parseInt(releaseDate.split("-")[0], 10) : null,
        imdbId: ((_a = data.external_ids) == null ? void 0 : _a.imdb_id) || null
      };
    } catch (err) {
      console.error(TAG, "TMDB details failed:", err.message);
      return null;
    }
  });
}
function getBearerToken() {
  return __async(this, null, function* () {
    var _a, _b, _c, _d;
    if (bearerToken) return bearerToken;
    try {
      const url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/home?host=moviebox.ph";
      const res = yield fetch(url, {
        headers: {
          "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
          "Referer": "https://moviebox.ph/",
          "Origin": "https://moviebox.ph",
          "X-Client-Info": JSON.stringify({ timezone: "Asia/Dhaka" }),
          "X-Request-Lang": "en",
          "Accept": "application/json",
          "Content-Type": "application/json"
        }
      });
      const xUser = res.headers.get("x-user");
      if (xUser) {
        try {
          const json = JSON.parse(xUser);
          if (json.token) bearerToken = json.token;
        } catch (e) {
        }
      }
      if (!bearerToken) {
        const setCookie = res.headers.get("set-cookie") || "";
        const match = setCookie.match(/token=([^;]+)/);
        if (match) bearerToken = match[1];
      }
      if (!bearerToken) {
        const text = yield res.text();
        if (text) {
          try {
            const json = JSON.parse(text);
            const tok = ((_b = (_a = json.data) == null ? void 0 : _a.user) == null ? void 0 : _b.token) || ((_c = json.data) == null ? void 0 : _c.token) || ((_d = json.user) == null ? void 0 : _d.token);
            if (tok) bearerToken = tok;
          } catch (e) {
          }
        }
      }
    } catch (e) {
      console.error(TAG, "Error fetching bearer token:", e.message);
    }
    return bearerToken;
  });
}
function getHeaders() {
  return __async(this, arguments, function* (extraHeaders = {}) {
    const token = yield getBearerToken();
    const headers = __spreadValues({
      "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/148.0.0.0 Safari/537.36",
      "Referer": "https://moviebox.ph/",
      "Origin": "https://moviebox.ph",
      "X-Client-Info": JSON.stringify({ timezone: "Asia/Dhaka" }),
      "X-Request-Lang": "en",
      "Accept": "application/json",
      "Content-Type": "application/json"
    }, extraHeaders);
    if (token) {
      headers["Authorization"] = `Bearer ${token}`;
    }
    return headers;
  });
}
function searchMovieBox(query) {
  return __async(this, null, function* () {
    if (!query || !query.trim()) return [];
    const headers = yield getHeaders();
    const url = "https://h5-api.aoneroom.com/wefeed-h5api-bff/subject/search";
    try {
      const res = yield fetch(url, {
        method: "POST",
        headers,
        body: JSON.stringify({
          keyword: query.trim(),
          page: 1,
          perPage: 20
        })
      });
      if (!res.ok) return [];
      const text = yield res.text();
      if (!text.trim().startsWith("{")) return [];
      const json = JSON.parse(text);
      const dataObj = json.data || {};
      const rawItems = dataObj.items || dataObj.list || [];
      const items = [];
      for (const item of rawItems) {
        const sub = item.subject || item;
        const title = sub.title;
        if (!title || !title.trim()) continue;
        const subjectId = String(sub.subjectId || "");
        const detailPath = String(sub.detailPath || "");
        const isSeries = sub.subjectType === 2;
        const releaseDate = sub.releaseDate || "";
        const year = parseInt(String(releaseDate).slice(0, 4), 10) || null;
        if (subjectId || detailPath) {
          items.push({
            subjectId,
            detailPath,
            title,
            isSeries,
            year
          });
        }
      }
      return items;
    } catch (e) {
      console.error(TAG, "Search error:", e.message);
      return [];
    }
  });
}
function getDetails(subjectId, detailPath) {
  return __async(this, null, function* () {
    const headers = yield getHeaders();
    const candidates = [];
    if (detailPath) {
      candidates.push(`https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=${encodeURIComponent(detailPath)}`);
    }
    if (subjectId && subjectId !== detailPath) {
      candidates.push(`https://h5-api.aoneroom.com/wefeed-h5api-bff/detail?detailPath=${encodeURIComponent(subjectId)}`);
    }
    if (subjectId) {
      candidates.push(`https://api3.aoneroom.com/wefeed-mobile-bff/subject-api/get?subjectId=${encodeURIComponent(subjectId)}`);
    }
    for (const url of candidates) {
      try {
        const res = yield fetch(url, { headers });
        if (!res.ok) continue;
        const json = yield res.json();
        const dataObj = json.data;
        if (!dataObj) continue;
        const subject = dataObj.subject || (dataObj.title || dataObj.subjectId ? dataObj : null);
        if (!subject) continue;
        const realSubjectId = String(subject.subjectId || subjectId || "");
        const realDetailPath = String(subject.detailPath || detailPath || "");
        return {
          subjectId: realSubjectId,
          detailPath: realDetailPath,
          title: subject.title || "",
          isSeries: subject.subjectType === 2 || subject.subjectType === 7
        };
      } catch (e) {
      }
    }
    return null;
  });
}
function getMovieBoxBaseUrl() {
  return __async(this, null, function* () {
    var _a, _b;
    const configEndpoints = ["https://themoviebox.org", "https://m2box.org"];
    for (const endpoint of configEndpoints) {
      try {
        const res = yield fetch(endpoint, {
          headers: { "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36" }
        });
        if (res.ok) {
          const text = yield res.text();
          if (text.trim().startsWith("{")) {
            const json = JSON.parse(text);
            const urlObj = json.movieBoxWeb;
            const url = (_b = (_a = urlObj == null ? void 0 : urlObj.url) == null ? void 0 : _a.trim()) == null ? void 0 : _b.replace(/\/$/, "");
            if (url) {
              return url.startsWith("http") ? url : "https://" + url;
            }
          }
        }
      } catch (e) {
      }
    }
    return "https://m2box.org";
  });
}
function parseQuality(resolutions) {
  if (!resolutions) return null;
  const values = String(resolutions).split(",").map((v) => parseInt(v.trim(), 10)).filter((v) => [360, 480, 720, 1080, 2160].includes(v));
  if (values.length === 0) return null;
  const maxVal = Math.max(...values);
  return `${maxVal}p`;
}
function parseStreamType(format) {
  const f = String(format || "").toUpperCase();
  if (f === "HLS" || f === "M3U8") return "m3u8";
  if (f === "DASH") return "mpd";
  return "mp4";
}
function getCaptions(baseUrl, subjectId, detailPath, streamId, streamFormat, referer) {
  return __async(this, null, function* () {
    var _a;
    if (!streamId || !streamFormat) return "";
    try {
      const encodedPath = encodeURIComponent(detailPath);
      const encodedId = encodeURIComponent(subjectId);
      const encodedFormat = encodeURIComponent(streamFormat);
      const capStreamId = encodeURIComponent(streamId);
      const capUrl = `${baseUrl}/wefeed-h5api-bff/subject/caption?format=${encodedFormat}&id=${capStreamId}&subjectId=${encodedId}&detailPath=${encodedPath}`;
      const headers = yield getHeaders({
        "Referer": referer,
        "X-Client-Info": JSON.stringify({ timezone: "Asia/Colombo" }),
        "X-Source": ""
      });
      const res = yield fetch(capUrl, { headers });
      if (!res.ok) return "";
      const json = yield res.json();
      const captions = ((_a = json.data) == null ? void 0 : _a.captions) || [];
      let selectedUrl = "";
      for (const cap of captions) {
        const url = cap.url;
        const lan = cap.lan || cap.lanName || "";
        if (url) {
          if (/en/i.test(lan) || !selectedUrl) {
            selectedUrl = url;
            if (/en/i.test(lan)) break;
          }
        }
      }
      return selectedUrl;
    } catch (e) {
      return "";
    }
  });
}
function extractVideoLinks(subjectId, detailPath, season, episode, mediaTitle) {
  return __async(this, null, function* () {
    const dynamicBaseUrl = yield getMovieBoxBaseUrl();
    const baseUrls = Array.from(/* @__PURE__ */ new Set([
      dynamicBaseUrl,
      "https://m2box.org",
      "https://netfilm.world",
      "https://moviebox.ph",
      "https://h5-api.aoneroom.com"
    ]));
    const cleanDetailPath = detailPath.replace(/^\/+/, "").replace(/^movies?\//, "");
    const detailPathCandidates = Array.from(/* @__PURE__ */ new Set([cleanDetailPath, detailPath, subjectId])).filter(Boolean);
    const seEpCandidates = season && episode && season > 0 && episode > 0 ? [{ s: season, e: episode }, { s: null, e: null }] : [{ s: null, e: null }];
    const streamResults = [];
    for (const baseUrl of baseUrls) {
      for (const candPath of detailPathCandidates) {
        for (const seEp of seEpCandidates) {
          const s = seEp.s;
          const e = seEp.e;
          const watchParams = new URLSearchParams({
            id: subjectId,
            type: "/movie/detail",
            detailSe: s && s > 0 ? String(s) : "",
            detailEp: e && e > 0 ? String(e) : "",
            lang: "en"
          }).toString();
          const referer = `${baseUrl}/movies/${candPath}?${watchParams}`;
          const playParamsObj = { subjectId, detailPath: candPath };
          if (s && e && s > 0 && e > 0) {
            playParamsObj.se = String(s);
            playParamsObj.ep = String(e);
          }
          const playParams = new URLSearchParams(playParamsObj).toString();
          const playUrl = `${baseUrl}/wefeed-h5api-bff/subject/play?${playParams}`;
          try {
            const reqHeaders = yield getHeaders({
              "Referer": referer,
              "X-Client-Info": JSON.stringify({ timezone: "Asia/Colombo" }),
              "X-Source": ""
            });
            const res = yield fetch(playUrl, { headers: reqHeaders });
            if (!res.ok) continue;
            const text = yield res.text();
            if (!text.trim().startsWith("{")) continue;
            const json = JSON.parse(text);
            const code = json.code;
            const playData = json.data;
            if (code !== 0 || !playData || !playData.hasResource) continue;
            const availableSources = [];
            ["streams", "hls", "dash"].forEach((key) => {
              const arr = playData[key];
              if (Array.isArray(arr)) {
                arr.forEach((obj) => {
                  if (obj && obj.url && !obj.vipLocked) {
                    availableSources.push(obj);
                  }
                });
              }
            });
            if (availableSources.length === 0) continue;
            for (let i = 0; i < availableSources.length; i++) {
              const source = availableSources[i];
              const rawUrl = source.url;
              const streamId = source.id || "";
              const format = source.format || "";
              const resolutions = source.resolutions || "";
              const quality = parseQuality(resolutions) || source.quality || "HD";
              const streamType = parseStreamType(format);
              const subUrl = yield getCaptions(baseUrl, subjectId, candPath, streamId, format, referer);
              const displayQuality = quality.endsWith("p") || quality === "HD" || quality === "4K" ? quality : `${quality}p`;
              const playerHeaders = {
                "Referer": baseUrl.endsWith("/") ? baseUrl : baseUrl + "/",
                "Origin": baseUrl,
                "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
              };
              const streamTitleLines = [
                `\u{1F3AC} ${mediaTitle}`,
                `\u2B50 Quality: ${displayQuality} (${streamType.toUpperCase()})`,
                `\u{1F4FA} Server ${i + 1} | MovieBox Direct API`
              ];
              const subtitlesList = subUrl ? [{ url: subUrl, lang: "English" }] : [];
              streamResults.push({
                name: `MovieBox | ${displayQuality} | Server ${i + 1}`,
                title: streamTitleLines.join("\n"),
                url: rawUrl,
                quality: displayQuality,
                headers: playerHeaders,
                behaviorHints: {
                  bingeGroup: "moviebox",
                  notWebReady: false
                },
                subtitles: subtitlesList
              });
            }
            if (streamResults.length > 0) return streamResults;
          } catch (err) {
          }
        }
      }
    }
    return streamResults;
  });
}
function scrape(meta) {
  return __async(this, null, function* () {
    const title = meta && meta.title;
    const type = meta && meta.type || "movie";
    const season = meta && meta.season;
    const episode = meta && meta.episode;
    const year = meta && meta.year;
    if (!title || !title.trim()) return [];
    try {
      const searchCandidates = yield searchMovieBox(title);
      if (!searchCandidates || searchCandidates.length === 0) return [];
      const isTargetSeries = type === "series" || type === "tv";
      let matchedCandidates = searchCandidates.filter((candidate) => {
        const titleMatches = titlesMatch(candidate.title, title);
        const typeMatches = isTargetSeries ? candidate.isSeries : !candidate.isSeries;
        const yearMatches = year != null && candidate.year != null ? Math.abs(year - candidate.year) <= 1 : true;
        return titleMatches && typeMatches && yearMatches;
      });
      if (matchedCandidates.length === 0) {
        matchedCandidates = searchCandidates.filter((c) => titlesMatch(c.title, title));
      }
      if (matchedCandidates.length === 0 && searchCandidates.length > 0) {
        matchedCandidates = [searchCandidates[0]];
      }
      const results = [];
      for (const candidate of matchedCandidates.slice(0, 2)) {
        let subId = candidate.subjectId;
        let detPath = candidate.detailPath;
        const details = yield getDetails(subId, detPath);
        if (details) {
          if (details.subjectId) subId = details.subjectId;
          if (details.detailPath) detPath = details.detailPath;
        }
        const streams = yield extractVideoLinks(
          subId,
          detPath,
          isTargetSeries ? season : null,
          isTargetSeries ? episode : null,
          candidate.title
        );
        results.push(...streams);
      }
      return results;
    } catch (err) {
      console.error(TAG, "MovieBox scraper error:", err.message);
      return [];
    }
  });
}
function getStreams(tmdbId, type = "movie", season = null, episode = null) {
  return __async(this, null, function* () {
    const mediaType = type === "series" || type === "tv" ? "tv" : "movie";
    const se = mediaType === "tv" ? season ? parseInt(season, 10) : 1 : null;
    const ep = mediaType === "tv" ? episode ? parseInt(episode, 10) : 1 : null;
    const tmdbMeta = yield getTMDBDetails(tmdbId, mediaType);
    if (!tmdbMeta || !tmdbMeta.title) return [];
    return scrape({
      title: tmdbMeta.title,
      year: tmdbMeta.year,
      type: mediaType,
      season: se,
      episode: ep,
      imdbId: tmdbMeta.imdbId
    });
  });
}
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    getStreams,
    scrape
  };
}
