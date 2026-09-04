/**
 * TMDB Cloudflare Worker Proxy
 *
 * Architecture:
 *
 * Alist-TVBox
 *      │
 *      │ Authorization: Bearer xxx
 *      │ or X-TMDB-API-Key: xxx
 *      ▼
 * Cloudflare Worker
 *      │
 *      ├── Cache API
 *      ├── KV Rate Limit
 *      │
 *      ▼
 * TMDB API
 *
 * Supported:
 *   /3/*
 *   /4/*
 *   /t/p/*
 *
 * API authentication:
 *   Authorization: Bearer <TMDB_API_READ_ACCESS_TOKEN>
 *   X-TMDB-API-Key: <TMDB_API_KEY>
 *
 * The Worker DOES NOT store any TMDB credential.
 */

const CONFIG = {
  // API response cache: 1 hour
  API_CACHE_TTL: 3600,

  // TMDB image cache: 30 days
  IMAGE_CACHE_TTL: 2592000,

  // Rate limit:
  // 300 requests / minute / IP
  RATE_LIMIT: 300,

  // Whether requests without TMDB credentials are allowed.
  //
  // Recommended: false
  //
  // If true:
  //   /3/movie/550
  // can be accessed without credentials.
  //
  // If false:
  //   Client must provide Authorization or X-TMDB-API-Key.
  REQUIRE_AUTH: true,

  // Enable API response cache.
  ENABLE_API_CACHE: true,

  // Enable image cache.
  ENABLE_IMAGE_CACHE: true,

  // Maximum URL length accepted by the Worker.
  MAX_URL_LENGTH: 8192,
};


// ============================================================
// Entry
// ============================================================

export default {
  async fetch(request, env, ctx) {
    try {
      return await handleRequest(request, env, ctx);
    } catch (error) {
      console.error("Unhandled error:", error);

      return jsonResponse(
        {
          status: 500,
          error: "Internal Server Error",
        },
        500
      );
    }
  },
};


// ============================================================
// Main Request Handler
// ============================================================

async function handleRequest(request, env, ctx) {
  const url = new URL(request.url);

  // ----------------------------------------------------------
  // Basic request validation
  // ----------------------------------------------------------

  if (url.href.length > CONFIG.MAX_URL_LENGTH) {
    return jsonResponse(
      {
        status: 414,
        error: "URI Too Long",
      },
      414
    );
  }

  // ----------------------------------------------------------
  // CORS preflight
  // ----------------------------------------------------------

  if (request.method === "OPTIONS") {
    return corsResponse();
  }

  // ----------------------------------------------------------
  // Only GET / HEAD are supported
  // ----------------------------------------------------------

  if (request.method !== "GET" && request.method !== "HEAD") {
    return jsonResponse(
      {
        status: 405,
        error: "Method Not Allowed",
      },
      405,
      {
        Allow: "GET, HEAD, OPTIONS",
      }
    );
  }

  // ----------------------------------------------------------
  // Rate limit
  // ----------------------------------------------------------

  const rateLimitResponse = await checkRateLimit(request, env);

  if (rateLimitResponse) {
    return rateLimitResponse;
  }

  // ----------------------------------------------------------
  // Routing
  // ----------------------------------------------------------

  const pathname = normalizePath(url.pathname);

  // TMDB images
  if (pathname.startsWith("/t/p/")) {
    return imageProxy(request, env, ctx);
  }

  // TMDB API
  if (
    pathname.startsWith("/3/") ||
    pathname === "/3" ||
    pathname.startsWith("/4/") ||
    pathname === "/4"
  ) {
    return apiProxy(request, env, ctx);
  }

  // Root endpoint
  if (pathname === "/" || pathname === "") {
    return jsonResponse({
      status: 200,
      service: "TMDB Proxy",
      version: "1.0.0",
      endpoints: [
        "/3/*",
        "/4/*",
        "/t/p/*",
      ],
    });
  }

  return jsonResponse(
    {
      status: 404,
      error: "Not Found",
    },
    404
  );
}


// ============================================================
// API Proxy
// ============================================================

async function apiProxy(request, env, ctx) {
  const clientUrl = new URL(request.url);

  const pathname = normalizePath(clientUrl.pathname);

  // ----------------------------------------------------------
  // Validate API path
  // ----------------------------------------------------------

  if (
    !pathname.startsWith("/3/") &&
    pathname !== "/3" &&
    !pathname.startsWith("/4/") &&
    pathname !== "/4"
  ) {
    return jsonResponse(
      {
        status: 400,
        error: "Invalid TMDB API path",
      },
      400
    );
  }

  // ----------------------------------------------------------
  // Authentication
  // ----------------------------------------------------------

  const auth = getTMDBCredentials(request);

  if (CONFIG.REQUIRE_AUTH && !auth) {
    return jsonResponse(
      {
        status: 401,
        error: "TMDB credentials required",
        message:
          "Provide Authorization: Bearer <token> or X-TMDB-API-Key: <api_key>",
      },
      401
    );
  }

  // ----------------------------------------------------------
  // Build TMDB URL
  // ----------------------------------------------------------

  const tmdbUrl = new URL(
    `https://api.themoviedb.org${pathname}`
  );

  // Copy query parameters.
  //
  // Important:
  // Do not copy api_key from the client.
  //
  // The credential is handled below.
  for (const [key, value] of clientUrl.searchParams.entries()) {
    if (key.toLowerCase() === "api_key") {
      continue;
    }

    tmdbUrl.searchParams.append(key, value);
  }

  // ----------------------------------------------------------
  // Apply authentication
  // ----------------------------------------------------------

  const upstreamHeaders = new Headers();

  upstreamHeaders.set("Accept", "application/json");

  if (auth?.type === "bearer") {
    upstreamHeaders.set(
      "Authorization",
      `Bearer ${auth.value}`
    );
  } else if (auth?.type === "api_key") {
    tmdbUrl.searchParams.set(
      "api_key",
      auth.value
    );
  }

  // ----------------------------------------------------------
  // Determine cacheability
  // ----------------------------------------------------------

  const cacheable = isCacheableAPIRequest(
    request,
    pathname
  );

  // ----------------------------------------------------------
  // Cache key
  //
  // DO NOT include Authorization/API Key.
  //
  // Public TMDB metadata responses should be identical
  // regardless of which user's credential is used.
  // ----------------------------------------------------------

  const cacheKey = new Request(
    `${clientUrl.origin}${pathname}${clientUrl.search}`,
    {
      method: "GET",
    }
  );

  const cache = caches.default;

  // ----------------------------------------------------------
  // Cache lookup
  // ----------------------------------------------------------

  if (
    CONFIG.ENABLE_API_CACHE &&
    cacheable &&
    request.method === "GET"
  ) {
    const cached = await cache.match(cacheKey);

    if (cached) {
      console.log(
        "API cache HIT:",
        pathname,
        clientUrl.search
      );

      return addCORSHeaders(
        addCacheStatus(cached, "HIT")
      );
    }
  }

  console.log(
    "API cache MISS:",
    pathname,
    clientUrl.search
  );

  // ----------------------------------------------------------
  // Fetch TMDB
  // ----------------------------------------------------------

  let upstreamResponse;

  try {
    upstreamResponse = await fetch(
      tmdbUrl.toString(),
      {
        method: request.method,
        headers: upstreamHeaders,
        redirect: "follow",
      }
    );
  } catch (error) {
    console.error(
      "TMDB API fetch failed:",
      error
    );

    return jsonResponse(
      {
        status: 502,
        error: "Bad Gateway",
        message: "Unable to connect to TMDB",
      },
      502
    );
  }

  // ----------------------------------------------------------
  // Handle response
  // ----------------------------------------------------------

  const responseHeaders = new Headers(
    upstreamResponse.headers
  );

  responseHeaders.delete("Set-Cookie");

  responseHeaders.set(
    "Cache-Control",
    `public, max-age=${CONFIG.API_CACHE_TTL}`
  );

  responseHeaders.set(
    "CDN-Cache-Control",
    `public, max-age=${CONFIG.API_CACHE_TTL}`
  );

  responseHeaders.set(
    "X-TMDB-Proxy",
    "Cloudflare-Worker"
  );

  responseHeaders.set(
    "X-TMDB-Proxy-Cache",
    "MISS"
  );

  addCORSHeadersToHeaders(responseHeaders);

  const response = new Response(
    upstreamResponse.body,
    {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    }
  );

  // ----------------------------------------------------------
  // Cache successful GET responses only
  // ----------------------------------------------------------

  if (
    CONFIG.ENABLE_API_CACHE &&
    cacheable &&
    request.method === "GET" &&
    upstreamResponse.ok
  ) {
    ctx.waitUntil(
      cache.put(cacheKey, response.clone())
    );
  }

  return response;
}


// ============================================================
// Image Proxy
// ============================================================

async function imageProxy(request, env, ctx) {
  const clientUrl = new URL(request.url);

  const pathname = normalizePath(
    clientUrl.pathname
  );

  // ----------------------------------------------------------
  // Validate image path
  // ----------------------------------------------------------

  if (!pathname.startsWith("/t/p/")) {
    return jsonResponse(
      {
        status: 400,
        error: "Invalid TMDB image path",
      },
      400
    );
  }

  // ----------------------------------------------------------
  // Build image URL
  // ----------------------------------------------------------

  const tmdbUrl =
    `https://image.tmdb.org${pathname}`;

  // ----------------------------------------------------------
  // Cache key
  // ----------------------------------------------------------

  const cacheKey = new Request(
    `${clientUrl.origin}${pathname}`,
    {
      method: "GET",
    }
  );

  const cache = caches.default;

  // ----------------------------------------------------------
  // Cache lookup
  // ----------------------------------------------------------

  if (
    CONFIG.ENABLE_IMAGE_CACHE &&
    request.method === "GET"
  ) {
    const cached = await cache.match(cacheKey);

    if (cached) {
      console.log(
        "Image cache HIT:",
        pathname
      );

      return addCORSHeaders(
        addCacheStatus(cached, "HIT")
      );
    }
  }

  console.log(
    "Image cache MISS:",
    pathname
  );

  // ----------------------------------------------------------
  // Fetch TMDB image
  // ----------------------------------------------------------

  let upstreamResponse;

  try {
    upstreamResponse = await fetch(
      tmdbUrl,
      {
        method: request.method,
        headers: {
          Accept: "image/avif,image/webp,image/apng,image/*,*/*;q=0.8",
        },
        redirect: "follow",
      }
    );
  } catch (error) {
    console.error(
      "TMDB image fetch failed:",
      error
    );

    return jsonResponse(
      {
        status: 502,
        error: "Bad Gateway",
        message: "Unable to connect to TMDB image server",
      },
      502
    );
  }

  // ----------------------------------------------------------
  // Response
  // ----------------------------------------------------------

  const responseHeaders = new Headers(
    upstreamResponse.headers
  );

  responseHeaders.delete("Set-Cookie");

  responseHeaders.set(
    "Cache-Control",
    `public, max-age=${CONFIG.IMAGE_CACHE_TTL}, immutable`
  );

  responseHeaders.set(
    "CDN-Cache-Control",
    `public, max-age=${CONFIG.IMAGE_CACHE_TTL}`
  );

  responseHeaders.set(
    "X-TMDB-Proxy",
    "Cloudflare-Worker"
  );

  responseHeaders.set(
    "X-TMDB-Proxy-Cache",
    "MISS"
  );

  addCORSHeadersToHeaders(responseHeaders);

  const response = new Response(
    upstreamResponse.body,
    {
      status: upstreamResponse.status,
      statusText: upstreamResponse.statusText,
      headers: responseHeaders,
    }
  );

  // ----------------------------------------------------------
  // Cache image
  // ----------------------------------------------------------

  if (
    CONFIG.ENABLE_IMAGE_CACHE &&
    request.method === "GET" &&
    upstreamResponse.ok
  ) {
    ctx.waitUntil(
      cache.put(cacheKey, response.clone())
    );
  }

  return response;
}


// ============================================================
// TMDB Credentials
// ============================================================

function getTMDBCredentials(request) {
  // ----------------------------------------------------------
  // 1. Authorization: Bearer xxx
  // ----------------------------------------------------------

  const authorization =
    request.headers.get("Authorization");

  if (authorization) {
    const match =
      authorization.match(
        /^Bearer\s+(.+)$/i
      );

    if (match) {
      const token = match[1].trim();

      if (token) {
        return {
          type: "bearer",
          value: token,
        };
      }
    }
  }

  // ----------------------------------------------------------
  // 2. X-TMDB-API-Key: xxx
  // ----------------------------------------------------------

  const apiKey =
    request.headers.get("X-TMDB-API-Key");

  if (apiKey) {
    const value = apiKey.trim();

    if (value) {
      return {
        type: "api_key",
        value,
      };
    }
  }

  // ----------------------------------------------------------
  // 3. Optional compatibility:
  //
  // Authorization without Bearer:
  //
  // Authorization: <api_key>
  //
  // Not enabled intentionally.
  // ----------------------------------------------------------

  return null;
}


// ============================================================
// API Cache Policy
// ============================================================

function isCacheableAPIRequest(
  request,
  pathname
) {
  if (request.method !== "GET") {
    return false;
  }

  // ----------------------------------------------------------
  // Never cache authentication/account related APIs.
  //
  // This is intentionally conservative.
  // ----------------------------------------------------------

  const nonCacheablePatterns = [
    /^\/3\/account(?:\/|$)/,
    /^\/3\/authentication(?:\/|$)/,
    /^\/3\/guest_session(?:\/|$)/,
    /^\/3\/session(?:\/|$)/,
    /^\/3\/watchlist(?:\/|$)/,
    /^\/3\/favorite(?:\/|$)/,
    /^\/3\/list(?:\/|$)/,
    /^\/3\/rating(?:\/|$)/,
    /^\/3\/movie\/[^/]+\/rating(?:\/|$)/,
    /^\/3\/tv\/[^/]+\/rating(?:\/|$)/,
    /^\/3\/tv\/[^/]+\/season\/[^/]+\/episode\/[^/]+\/rating(?:\/|$)/,
  ];

  for (const pattern of nonCacheablePatterns) {
    if (pattern.test(pathname)) {
      return false;
    }
  }

  return true;
}


// ============================================================
// Rate Limit
// ============================================================

async function checkRateLimit(
  request,
  env
) {
  // ----------------------------------------------------------
  // If KV is not configured, skip rate limiting.
  //
  // This allows the Worker to run without KV during testing.
  // ----------------------------------------------------------

  if (!env.RATE_LIMIT_KV) {
    return null;
  }

  const ip =
    request.headers.get("CF-Connecting-IP") ||
    request.headers.get("X-Forwarded-For") ||
    "unknown";

  const now = Math.floor(
    Date.now() / 1000
  );

  const minute =
    Math.floor(now / 60);

  const key =
    `rl:${ip}:${minute}`;

  try {
    const current =
      await env.RATE_LIMIT_KV.get(key);

    const count =
      current ? Number(current) : 0;

    // --------------------------------------------------------
    // IMPORTANT:
    //
    // KV is eventually consistent and increments are not atomic.
    //
    // This is suitable for approximate abuse protection,
    // NOT strict rate limiting.
    // --------------------------------------------------------

    if (count >= CONFIG.RATE_LIMIT) {
      console.warn(
        "Rate limit exceeded:",
        ip,
        count
      );

      return new Response(
        JSON.stringify({
          status: 429,
          error: "Too Many Requests",
          message:
            "Request rate limit exceeded. Please try again later.",
        }),
        {
          status: 429,
          headers: {
            "Content-Type":
              "application/json; charset=utf-8",

            "Retry-After": "60",

            "Access-Control-Allow-Origin": "*",

            "X-RateLimit-Limit":
              String(CONFIG.RATE_LIMIT),

            "X-RateLimit-Remaining":
              "0",
          },
        }
      );
    }

    // --------------------------------------------------------
    // Increment asynchronously.
    // --------------------------------------------------------

    const nextCount =
      count + 1;

    // Expire slightly after the minute window.
    //
    // KV expiration is used instead of manually deleting keys.
    //
    // waitUntil is not available here, so we don't block
    // the request on the write.
    //
    // Promise intentionally not awaited.
    // --------------------------------------------------------

    env.RATE_LIMIT_KV.put(
      key,
      String(nextCount),
      {
        expirationTtl: 120,
      }
    ).catch((error) => {
      console.error(
        "Rate limit KV write failed:",
        error
      );
    });

    return null;
  } catch (error) {
    console.error(
      "Rate limit KV read failed:",
      error
    );

    // Fail open.
    //
    // If KV temporarily fails, don't make TMDB unavailable.
    return null;
  }
}


// ============================================================
// Path Normalization
// ============================================================

function normalizePath(pathname) {
  if (!pathname) {
    return "/";
  }

  // Prevent encoded path traversal.
  const decoded = safeDecodeURIComponent(
    pathname
  );

  if (
    decoded.includes("..") ||
    decoded.includes("\\")
  ) {
    return "/";
  }

  // Normalize duplicate slashes.
  return pathname.replace(
    /\/+/g,
    "/"
  );
}


// ============================================================
// Safe Decode
// ============================================================

function safeDecodeURIComponent(value) {
  try {
    return decodeURIComponent(value);
  } catch {
    return value;
  }
}


// ============================================================
// CORS
// ============================================================

function corsResponse() {
  const headers = new Headers();

  addCORSHeadersToHeaders(headers);

  headers.set(
    "Access-Control-Max-Age",
    "86400"
  );

  return new Response(null, {
    status: 204,
    headers,
  });
}


function addCORSHeaders(response) {
  const headers = new Headers(
    response.headers
  );

  addCORSHeadersToHeaders(headers);

  return new Response(
    response.body,
    {
      status: response.status,
      statusText: response.statusText,
      headers,
    }
  );
}


function addCORSHeadersToHeaders(headers) {
  headers.set(
    "Access-Control-Allow-Origin",
    "*"
  );

  headers.set(
    "Access-Control-Allow-Methods",
    "GET, HEAD, OPTIONS"
  );

  headers.set(
    "Access-Control-Allow-Headers",
    "Authorization, X-TMDB-API-Key, Content-Type"
  );

  headers.set(
    "Access-Control-Expose-Headers",
    [
      "Content-Type",
      "Content-Length",
      "Cache-Control",
      "CDN-Cache-Control",
      "X-TMDB-Proxy",
      "X-TMDB-Proxy-Cache",
      "X-RateLimit-Limit",
      "X-RateLimit-Remaining",
    ].join(", ")
  );
}


// ============================================================
// Cache Status
// ============================================================

function addCacheStatus(
  response,
  status
) {
  const headers = new Headers(
    response.headers
  );

  headers.set(
    "X-TMDB-Proxy-Cache",
    status
  );

  return new Response(
    response.body,
    {
      status: response.status,
      statusText: response.statusText,
      headers,
    }
  );
}


// ============================================================
// JSON Response
// ============================================================

function jsonResponse(
  data,
  status = 200,
  extraHeaders = {}
) {
  const headers = new Headers({
    "Content-Type":
      "application/json; charset=utf-8",
  });

  for (const [key, value] of Object.entries(
    extraHeaders
  )) {
    headers.set(key, value);
  }

  addCORSHeadersToHeaders(headers);

  return new Response(
    JSON.stringify(data),
    {
      status,
      headers,
    }
  );
}