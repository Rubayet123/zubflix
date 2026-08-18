// app/src/main/assets/providers_src/ctgmovie.js
var TMDB_API_KEY = "439c478a771f35c05022f9feabcca01c";
var TMDB_BASE_URL = "https://api.themoviedb.org/3";
var MAIN_URL = "https://ctgmovies.com";
var DEFAULT_API_BASE = "https://cockpit.103.109.92.178.nip.io/api/v1";
var UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36";
var AUTH_CONFIG = {
  token: "",
  cookie: ""
};
var WEB_HEADERS = {
  "User-Agent": UA,
  Accept: "application/json",
  "Accept-Language": "en",
  Referer: MAIN_URL + "/",
  Origin: MAIN_URL
};
var STREAM_HEADERS = {
  "User-Agent": UA,
  Accept: "video/webm,video/ogg,video/*;q=0.9,application/ogg;q=0.7,audio/*;q=0.6,*/*;q=0.5",
  "Accept-Language": "en-US,en;q=0.9",
  "Accept-Encoding": "identity",
  Referer: MAIN_URL + "/",
  "Sec-Fetch-Dest": "video",
  "Sec-Fetch-Mode": "no-cors",
  "Sec-Fetch-Site": "cross-site",
  DNT: "1"
};
function encodeUrl(_0x306cc0) {
  return encodeURIComponent(_0x306cc0 || "");
}
function yearFromDate(_0x204961) {
  if (!_0x204961) {
    return null;
  }
  const _0x16208c = _0x204961.match(/\d{4}/);
  if (_0x16208c) {
    return parseInt(_0x16208c[0], 10);
  } else {
    return null;
  }
}
function cleanDisplayTitle(_0x196df4) {
  if (!_0x196df4) {
    return "";
  }
  return _0x196df4.replace(/\b(1080p|720p|480p|2160p|4k|web[- ]?dl|webrip|bluray|hdrip|x264|x265|hevc|10bit|dual[- ]?audio|hindi[- ]?dubbed|dubbed|esub)\b/gi, " ").replace(/\[[^\]]*\]/g, " ").replace(/\s+/g, " ").trim();
}
function normalizedTitle(_0x89087b) {
  return cleanDisplayTitle(_0x89087b).toLowerCase().replace(/[^a-z0-9]+/g, " ").trim();
}
function optString(_0x2172aa, _0x4307dd) {
  if (!_0x2172aa || _0x2172aa[_0x4307dd] == null) {
    return null;
  }
  let _0x458f7a = _0x2172aa[_0x4307dd];
  if (typeof _0x458f7a !== "string") {
    _0x458f7a = String(_0x458f7a);
  }
  _0x458f7a = _0x458f7a.trim();
  if (!_0x458f7a || _0x458f7a === "null") {
    return null;
  }
  return _0x458f7a;
}
function optInt(_0x3152bb, _0x529129) {
  if (!_0x3152bb || _0x3152bb[_0x529129] == null) {
    return null;
  }
  const _0x47b0c3 = _0x3152bb[_0x529129];
  if (typeof _0x47b0c3 === "number") {
    return _0x47b0c3;
  }
  const _0x1e1728 = parseInt(String(_0x47b0c3), 10);
  if (isNaN(_0x1e1728)) {
    return null;
  } else {
    return _0x1e1728;
  }
}
function resolveMediaUrl(_0x32103e) {
  if (!_0x32103e) {
    return "";
  }
  let res = _0x32103e;
  if (res.startsWith("//")) {
    res = "http:" + res;
  }
  if (/^https?:\/\//i.test(res)) {
    if (res.includes("ctgfun.com") || res.includes("103.109.92.")) {
      res = res.replace(/^https:/i, "http:");
    }
    return res;
  }
  if (res.startsWith("/")) {
    return MAIN_URL + res;
  }
  return res;
}
function resolveSubtitleUrl(_0x206272) {
  if (!_0x206272) {
    return "";
  }
  if (_0x206272.startsWith("//")) {
    return "https:" + _0x206272;
  }
  if (/^https?:\/\//i.test(_0x206272)) {
    return _0x206272;
  }
  if (_0x206272.startsWith("/")) {
    return MAIN_URL + _0x206272;
  }
  return MAIN_URL + "/" + _0x206272;
}
function qualityFromUrl(_0x1ddb03) {
  if (!_0x1ddb03) {
    return "Unknown";
  }
  const _0x3d5cf9 = _0x1ddb03.match(/(2160p|1440p|1080p|720p|576p|540p|480p|360p|4k|uhd)/i);
  if (_0x3d5cf9) {
    const _0x46c84e = _0x3d5cf9[1].toLowerCase();
    if (_0x46c84e === "4k" || _0x46c84e === "uhd") {
      return "2160p";
    }
    return _0x46c84e;
  }
  return "Unknown";
}
function cleanSourceName(_0x51bf0e) {
  if (!_0x51bf0e) {
    return "";
  }
  let _0x2fc29d = _0x51bf0e.replace("auto:", "").replace(/:/g, " ").replace(/-/g, " ").trim();
  if (/^server\s*[a-z]$/i.test(_0x2fc29d)) {
    _0x2fc29d = _0x2fc29d.replace(/server\s*([a-z])/i, function(_0x38629f, _0x215e51) {
      return "Server " + _0x215e51.toUpperCase();
    });
  }
  return _0x2fc29d;
}
function subtitleLabelFromUrl(_0x537a4b) {
  if (!_0x537a4b) {
    return "Subtitle";
  }
  const _0xa21711 = _0x537a4b.split("?")[0].split("/").pop().replace(/%20/g, " ");
  const _0x4e6ebb = _0xa21711.toLowerCase();
  if (_0x4e6ebb.includes("bangla") || _0x4e6ebb.includes("bengali") || _0x4e6ebb.includes("ben")) {
    return "Bangla";
  }
  if (_0x4e6ebb.includes("english") || _0x4e6ebb.includes("eng")) {
    return "English";
  }
  if (_0x4e6ebb.includes("hindi") || _0x4e6ebb.includes("hin")) {
    return "Hindi";
  }
  return "Subtitle";
}
function formatBytes(_0x2cb7ec) {
  if (!_0x2cb7ec || _0x2cb7ec === 0) {
    return "Unknown";
  }
  const _0x195dd8 = 1024;
  const _0x535431 = ["Bytes", "KB", "MB", "GB", "TB"];
  const _0x53308e = Math.floor(Math.log(_0x2cb7ec) / Math.log(_0x195dd8));
  return parseFloat((_0x2cb7ec / Math.pow(_0x195dd8, _0x53308e)).toFixed(1)) + " " + _0x535431[_0x53308e];
}
function getTMDBDetails(_0x8dca7c, _0x38c07a) {
  const _0x3d1053 = _0x38c07a === "tv" ? "tv" : "movie";
  const _0x27d001 = TMDB_BASE_URL + "/" + _0x3d1053 + "/" + _0x8dca7c + "?api_key=" + TMDB_API_KEY + "&append_to_response=external_ids";
  return fetch(_0x27d001, {
    headers: {
      Accept: "application/json"
    }
  }).then((_0x552a9d) => {
    if (!_0x552a9d.ok) {
      throw new Error("TMDB HTTP " + _0x552a9d.status);
    }
    return _0x552a9d.json();
  }).then((_0x49e24c) => {
    const _0x3bef18 = _0x38c07a === "tv" ? _0x49e24c.name : _0x49e24c.title;
    const _0x475597 = _0x38c07a === "tv" ? _0x49e24c.first_air_date : _0x49e24c.release_date;
    return {
      title: _0x3bef18,
      year: _0x475597 ? parseInt(_0x475597.split("-")[0], 10) : null,
      imdbId: _0x49e24c.external_ids && _0x49e24c.external_ids.imdb_id || null
    };
  }).catch((_0x1d094e) => {
    console.error("[CTGMovies] TMDB fetch failed:", _0x1d094e.message);
    return null;
  });
}
function queryString(_0x1705e3) {
  _0x1705e3 = _0x1705e3 || {};
  const _0x55c7fc = Object.keys(_0x1705e3).filter((_0x33f506) => _0x1705e3[_0x33f506] != null).map((_0x384759) => encodeUrl(_0x384759) + "=" + encodeUrl(String(_0x1705e3[_0x384759])));
  if (_0x55c7fc.length) {
    return "?" + _0x55c7fc.join("&");
  } else {
    return "";
  }
}
function buildApiUrl(_0x247cc4, _0x1e5990) {
  const _0x131f2b = _0x247cc4.startsWith("/") ? _0x247cc4 : "/" + _0x247cc4;
  return DEFAULT_API_BASE + _0x131f2b + queryString(_0x1e5990);
}
function buildSameOriginUrl(_0x3c39fd, _0x18d1d7) {
  const _0x129018 = _0x3c39fd.startsWith("/") ? _0x3c39fd : "/" + _0x3c39fd;
  return MAIN_URL + "/api/v1" + _0x129018 + queryString(_0x18d1d7);
}
function getDetail(_0x169f63) {
  let _0x1f8184 = _0x169f63.kind || "movies";
  if (_0x1f8184 === "movie") {
    _0x1f8184 = "movies";
  }
  return apiGet("/" + _0x1f8184 + "/" + _0x169f63.id).then((_0x297bce) => {
    if (!_0x297bce) {
      return null;
    }
    try {
      return JSON.parse(_0x297bce);
    } catch (_0x2ca488) {
      return null;
    }
  });
}
function apiHeaders() {
  const _0x1dba0e = Object.assign({}, WEB_HEADERS);
  if (AUTH_CONFIG.token && AUTH_CONFIG.token.trim()) {
    const _0x325268 = AUTH_CONFIG.token.trim().replace(/^Bearer\s+/i, "");
    _0x1dba0e.Authorization = "Bearer " + _0x325268;
    _0x1dba0e["x-auth-token"] = _0x325268;
  }
  if (AUTH_CONFIG.cookie && AUTH_CONFIG.cookie.trim()) {
    _0x1dba0e.Cookie = AUTH_CONFIG.cookie.trim();
  }
  return _0x1dba0e;
}
function apiGet(_0x5b9cc6, _0x82ba72) {
  const _0x5b5473 = buildApiUrl(_0x5b9cc6, _0x82ba72);
  const _0x1c0573 = buildSameOriginUrl(_0x5b9cc6, _0x82ba72);
  const _0x39e02a = apiHeaders();
  function _0x182623(_0x32f83c, _0x3c6962) {
    _0x3c6962 = _0x3c6962 || 0;
    return fetch(_0x32f83c, {
      headers: _0x39e02a
    }).then((_0x357bcd) => {
      if (_0x357bcd.status >= 500 && _0x357bcd.status < 600 && _0x3c6962 < 1) {
        return new Promise((_0x4013e7) => setTimeout(_0x4013e7, 300)).then(() => _0x182623(_0x32f83c, _0x3c6962 + 1));
      }
      if (!_0x357bcd.ok) {
        throw new Error("HTTP " + _0x357bcd.status);
      }
      return _0x357bcd.text();
    });
  }
  return _0x182623(_0x5b5473).catch(() => _0x182623(_0x1c0573)).catch((_0x1dda93) => {
    console.error("[CTGMovies] apiGet " + _0x5b9cc6 + " failed:", _0x1dda93.message);
    return null;
  });
}
function toSearchItem(_0xe2fd2a, _0x341c01) {
  const _0x1fdcd8 = _0x341c01 === "movies";
  const _0x2d0768 = _0x341c01 === "anime" || _0xe2fd2a.is_anime === true && _0x341c01 !== "tv";
  const _0x53dcf7 = optString(_0xe2fd2a, "title") || optString(_0xe2fd2a, "name") || optString(_0xe2fd2a, "english_title");
  if (!_0x53dcf7) {
    return null;
  }
  const _0x210449 = optString(_0xe2fd2a, "slug") || optString(_0xe2fd2a, "id") || optString(_0xe2fd2a, "_id");
  if (!_0x210449) {
    return null;
  }
  const _0x20f1a2 = optString(_0xe2fd2a, "poster_url") || optString(_0xe2fd2a, "cover_url");
  const _0x2de495 = optInt(_0xe2fd2a, "year") || yearFromDate(optString(_0xe2fd2a, "release_date")) || yearFromDate(optString(_0xe2fd2a, "first_air_date"));
  let _0x54e90c = _0x1fdcd8 ? "movie" : _0x2d0768 ? "anime" : "tv";
  let _0x5dcd76 = MAIN_URL + "/" + (_0x1fdcd8 ? "movies" : _0x2d0768 ? "anime" : "tv") + "/" + _0x210449;
  return {
    title: cleanDisplayTitle(_0x53dcf7),
    url: _0x5dcd76,
    kind: _0x341c01,
    id: _0x210449,
    type: _0x54e90c,
    poster: _0x20f1a2,
    year: _0x2de495
  };
}
function parseSearchItems(_0x794105, _0x59df5d) {
  if (!_0x794105) {
    return [];
  }
  let _0x5930a9 = _0x794105.trim();
  let _0x3ada66;
  try {
    const _0x273d16 = JSON.parse(_0x5930a9);
    if (Array.isArray(_0x273d16)) {
      _0x3ada66 = _0x273d16;
    } else if (_0x273d16 && typeof _0x273d16 === "object") {
      _0x3ada66 = _0x273d16.movies || _0x273d16.results || _0x273d16.data || [];
    } else {
      _0x3ada66 = [];
    }
  } catch (_0x27f4a6) {
    return [];
  }
  const _0x52b019 = [];
  for (const _0x592b0f of _0x3ada66) {
    const _0x5a6a06 = toSearchItem(_0x592b0f, _0x59df5d);
    if (_0x5a6a06) {
      _0x52b019.push(_0x5a6a06);
    }
  }
  return _0x52b019;
}
function searchCtg(_0x153f4c) {
  const _0x3cf7bd = {
    search: _0x153f4c
  };
  const _0x24ab75 = apiGet("/movies", _0x3cf7bd).then((_0x3ce19e) => parseSearchItems(_0x3ce19e, "movies")).catch(() => []);
  const _0x53d37a = apiGet("/tv", _0x3cf7bd).then((_0x1d9988) => parseSearchItems(_0x1d9988, "tv")).catch(() => []);
  const _0x4cfb3b = apiGet("/anime", _0x3cf7bd).then((_0x8ac47e) => parseSearchItems(_0x8ac47e, "anime")).catch(() => []);
  return Promise.all([_0x24ab75, _0x53d37a, _0x4cfb3b]).then(([_0x12d3b1, _0x31b65f, _0x2fa174]) => _0x12d3b1.concat(_0x31b65f).concat(_0x2fa174));
}
function findBestMatch(_0x471698, _0x169eeb, _0x5e24f9) {
  if (!_0x169eeb.length) {
    return null;
  }
  const _0x40130f = normalizedTitle(_0x471698.title);
  const _0x5741a6 = _0x471698.year;
  let _0x29343e = null;
  let _0x452f18 = -1;
  for (const _0x5db85b of _0x169eeb) {
    const _0x445855 = normalizedTitle(_0x5db85b.title);
    let _0x4c8e3d = -1;
    if (_0x445855 === _0x40130f) {
      _0x4c8e3d = 100;
      if (_0x5741a6 && _0x5db85b.year === _0x5741a6) {
        _0x4c8e3d += 50;
      }
    } else if (_0x445855.includes(_0x40130f) && _0x40130f.length >= 4) {
      _0x4c8e3d = 60;
      if (_0x5741a6 && _0x5db85b.year === _0x5741a6) {
        _0x4c8e3d += 30;
      }
    } else if (_0x40130f.includes(_0x445855) && _0x445855.length >= 4) {
      _0x4c8e3d = 40;
    }
    if (_0x5e24f9 === "tv" && (_0x5db85b.type === "tv" || _0x5db85b.type === "anime")) {
      _0x4c8e3d += 10;
    }
    if (_0x5e24f9 === "movie" && (_0x5db85b.type === "movie" || _0x5db85b.type === "anime")) {
      _0x4c8e3d += 10;
    }
    if (_0x4c8e3d > _0x452f18) {
      _0x452f18 = _0x4c8e3d;
      _0x29343e = _0x5db85b;
    }
  }
  if (_0x452f18 >= 30) {
    return _0x29343e;
  } else {
    return null;
  }
}
function buildStreams(_0x3741c5, _0x14285e) {
  const _0x3f8495 = /* @__PURE__ */ new Set();
  const _0x16f72e = [];
  _0x3741c5.forEach((_0x4cb377, _0x2a2991) => {
    if (!_0x4cb377 || _0x4cb377.broken === true) {
      return;
    }
    const _0x4c02b5 = optString(_0x4cb377, "url") || optString(_0x4cb377, "file") || optString(_0x4cb377, "src") || optString(_0x4cb377, "link");
    if (!_0x4c02b5) {
      return;
    }
    const _0x20f24a = resolveMediaUrl(_0x4c02b5);
    if (!_0x20f24a || _0x3f8495.has(_0x20f24a)) {
      return;
    }
    _0x3f8495.add(_0x20f24a);
    let _0x3c29dd = qualityFromUrl(_0x20f24a);
    const _0x527f8d = optString(_0x4cb377, "quality") || "";
    if (_0x3c29dd === "Unknown") {
      const _0xbc93f9 = _0x527f8d.match(/(2160|1440|1080|720|576|540|480|360)p?/i);
      if (_0xbc93f9) {
        const _0x5ecfd5 = parseInt(_0xbc93f9[1], 10);
        _0x3c29dd = _0x5ecfd5 >= 2160 ? "2160p" : _0x5ecfd5 >= 1440 ? "1440p" : _0x5ecfd5 >= 1080 ? "1080p" : _0x5ecfd5 >= 720 ? "720p" : _0x5ecfd5 >= 576 ? "576p" : _0x5ecfd5 >= 480 ? "480p" : _0x5ecfd5 >= 360 ? "360p" : "Unknown";
      }
    }
    const _0xe1d117 = _0x3c29dd !== "Unknown" ? _0x3c29dd.toLowerCase() : "1080p";
    const _0x11a27e = (optString(_0x4cb377, "language") || "en").toLowerCase();
    let _0x4d122a = "English";
    let _0x1adc98 = "\u{1F30D}";
    if (_0x11a27e.includes("hin") || _0x527f8d.toLowerCase().includes("hindi")) {
      _0x4d122a = "Hindi";
      _0x1adc98 = "\u{1F1EE}\u{1F1F3}";
    } else if (_0x11a27e.includes("ben") || _0x11a27e.includes("bangla")) {
      _0x4d122a = "Bangla";
      _0x1adc98 = "\u{1F1E7}\u{1F1E9}";
    } else if (_0x11a27e.includes("eng") || _0x11a27e === "en") {
      _0x4d122a = "English";
      _0x1adc98 = "\u{1F1FA}\u{1F1F8}";
    }
    const _0x2cf04f = (_0x20f24a + " " + _0x527f8d + " " + (_0x4cb377.group_source || "") + " " + (_0x4cb377.source_display || "")).toLowerCase();
    let _0xcd7423 = "x264";
    if (_0x2cf04f.includes("x265") || _0x2cf04f.includes("h265")) {
      _0xcd7423 = "x265";
    } else if (_0x2cf04f.includes("hevc")) {
      _0xcd7423 = "HEVC";
    }
    let _0x51d353 = "WEB-DL";
    if (_0x2cf04f.includes("webrip") || _0x2cf04f.includes("web-rip")) {
      _0x51d353 = "WEB-Rip";
    } else if (_0x2cf04f.includes("bluray") || _0x2cf04f.includes("blu-ray") || _0x2cf04f.includes("brrip")) {
      _0x51d353 = "BluRay";
    } else if (_0x2cf04f.includes("hdrip")) {
      _0x51d353 = "HDRip";
    }
    let _0x256fe5 = "MKV";
    if (_0x20f24a.includes(".mp4")) {
      _0x256fe5 = "MP4";
    } else if (_0x20f24a.includes(".m3u8")) {
      _0x256fe5 = "M3U8";
    }
    const _0x539dc2 = optInt(_0x4cb377, "size_bytes") ? formatBytes(optInt(_0x4cb377, "size_bytes")) : "Unknown";
    let _0x1f5abd = "5.1 Surround";
    let _0x4844c6 = optString(_0x4cb377, "group_source") || optString(_0x4cb377, "source_display") || "Server " + (_0x2a2991 + 1);
    const _0x2e878f = cleanSourceName(_0x4844c6);
    const _0x11a46d = "CTGMovies | " + _0xe1d117 + " | " + _0x4d122a;
    const _0x5d5662 = "\u{1F37F} " + _0x14285e.title + " - (" + (_0x14285e.year || "2026") + ")";
    const _0x4921ef = "\u2B50 " + _0xe1d117 + " | " + _0x1adc98 + " " + _0x4d122a + " | \u{1F4BE} " + _0x539dc2;
    const _0x37bdce = "\u{1F516} " + _0x256fe5 + " | \u{1F3A5} " + _0xcd7423 + " | \u{1F3A7} " + _0x1f5abd;
    const _0x5e6e94 = "\u26D3\uFE0F\u{1F4A5} " + _0x2e878f + " | \u2601\uFE0F " + _0x51d353;
    const _0x15d868 = _0x5d5662 + "\n" + _0x4921ef + "\n" + _0x37bdce + "\n" + _0x5e6e94;
    const _0x19832e = [];
    const _0x34b375 = ["subtitle_tracks", "subtitles", "captions", "tracks"];
    for (const _0x3e0e8a of _0x34b375) {
      const _0x58ad43 = _0x4cb377[_0x3e0e8a];
      if (Array.isArray(_0x58ad43)) {
        for (const _0x16028e of _0x58ad43) {
          const _0x352dc2 = optString(_0x16028e, "url") || optString(_0x16028e, "file") || optString(_0x16028e, "src");
          if (!_0x352dc2) {
            continue;
          }
          const _0x39da28 = resolveSubtitleUrl(_0x352dc2);
          if (!_0x39da28 || _0x19832e.some((_0x2b93be) => _0x2b93be.url === _0x39da28)) {
            continue;
          }
          const _0x523e07 = optString(_0x16028e, "label") || optString(_0x16028e, "language") || subtitleLabelFromUrl(_0x39da28);
          _0x19832e.push({
            url: _0x39da28,
            lang: _0x523e07
          });
        }
      }
    }
    _0x16f72e.push({
      name: _0x11a46d,
      title: _0x15d868,
      size: _0x15d868,
      description: _0x15d868,
      url: _0x20f24a,
      quality: "",
      language: "",
      provider: "CTGMovies",
      headers: STREAM_HEADERS,
      subtitles: _0x19832e.length ? _0x19832e : void 0
    });
  });
  return _0x16f72e;
}
function getMovieStreams(_0x1a749b, _0x478076) {
  return getDetail(_0x1a749b).then((_0x22eaf3) => {
    if (!_0x22eaf3 || !_0x22eaf3.links) {
      return [];
    }
    return buildStreams(_0x22eaf3.links, _0x478076);
  }).catch(() => []);
}
function getEpisodeStreams(_0x5eddb8, _0x524e5b, _0x498f51, _0x9c9dbe) {
  return getDetail(_0x5eddb8).then((_0x198edb) => {
    if (!_0x198edb || !_0x198edb.episodes) {
      return [];
    }
    const _0x1587fa = [];
    for (const _0x3c6ae0 of _0x198edb.episodes) {
      const _0x55a6bc = optInt(_0x3c6ae0, "episode_number") || optInt(_0x3c6ae0, "absolute_number");
      const _0x4398e7 = optInt(_0x3c6ae0, "season_number") || 1;
      if (_0x4398e7 !== _0x498f51 || _0x55a6bc !== _0x9c9dbe) {
        continue;
      }
      const _0x3fc9a9 = _0x3c6ae0.links || [];
      for (const _0x4fc8b7 of _0x3fc9a9) {
        if (_0x4fc8b7 && _0x4fc8b7.broken !== true) {
          _0x1587fa.push(_0x4fc8b7);
        }
      }
    }
    return buildStreams(_0x1587fa, _0x524e5b);
  }).catch(() => []);
}
function scrape(_0x22beb2) {
  const _0x245aaf = _0x22beb2 && _0x22beb2.title;
  const _0x1a28c7 = _0x22beb2 && _0x22beb2.type || "movie";
  const _0xb11ab8 = _0x22beb2 && _0x22beb2.season;
  const _0x3dddfc = _0x22beb2 && _0x22beb2.episode;
  const _0x45d0bf = _0x22beb2 && _0x22beb2.year;
  if (!_0x245aaf) {
    return Promise.resolve([]);
  }
  const _0x533c06 = {
    title: _0x245aaf,
    year: _0x45d0bf || null,
    imdbId: _0x22beb2 && _0x22beb2.imdbId || null
  };
  return searchCtg(_0x245aaf).then((_0x571c46) => {
    if (!_0x571c46.length) {
      return [];
    }
    const _0x4a5a05 = findBestMatch(_0x533c06, _0x571c46, _0x1a28c7);
    if (!_0x4a5a05) {
      return [];
    }
    if (_0x1a28c7 === "tv" && _0xb11ab8 && _0x3dddfc) {
      _0x533c06.title = _0x533c06.title + " S" + String(_0xb11ab8).padStart(2, "0") + "E" + String(_0x3dddfc).padStart(2, "0");
      return getEpisodeStreams(_0x4a5a05, _0x533c06, _0xb11ab8, _0x3dddfc);
    }
    return getMovieStreams(_0x4a5a05, _0x533c06);
  }).catch(() => []);
}
function getStreams(_0x129610, _0x4320ec = "movie", _0x5c7bd6 = null, _0x1c7287 = null) {
  return getTMDBDetails(_0x129610, _0x4320ec).then((_0x150dbc) => {
    if (!_0x150dbc || !_0x150dbc.title) {
      return [];
    }
    return scrape({
      title: _0x150dbc.title,
      year: _0x150dbc.year,
      type: _0x4320ec,
      season: _0x5c7bd6,
      episode: _0x1c7287,
      imdbId: _0x150dbc.imdbId
    });
  }).catch(() => []);
}
if (typeof module !== "undefined" && module.exports) {
  module.exports = {
    getStreams,
    scrape
  };
}
