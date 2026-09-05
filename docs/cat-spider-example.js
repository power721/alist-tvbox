// ============================================================================
// 猫源爬虫示例(模板)—— MacCMS 采集站 + 网盘线路接后端 /parse 文件夹化
//
// 本文件同时兼容两种部署方式:
//
// 【方式 A:进 CatVodOpen 工程】
//   1. 拷到 open/nodejs/src/spider/video/{key}.js(本文件假设 key = example)
//   2. open/nodejs/src/router.js 顶部 import,并把变量加进 spiders 数组
//      (插入位置 = 站点在 App 里的排序,数组顺序即站点顺序)
//   3. 工程根跑 ./build-cat-runtime.sh —— 打包 index.js + md5 + zip 四件套替换
//   4. 校验:open/nodejs/check-spiders.mjs(node 版全链路自测,
//      判活以 /test 通过为准,test HTTP 500 = App 端不可用)
//
// 【方式 B:网页上传自定义爬虫】(无需重打包,后端 cat 自定义爬虫功能)
//   1. alist-tvbox 网页 → 猫源/自定义爬虫 → 上传本文件
//      (文件名即站点 key 派生源,建议 ASCII 文件名,如 example.js)
//   2. 装载器已兜住源码复制形态依赖:'../../util/req.js' 相对 import 映射内置
//      req 实例、crypto-js/lodash 惰性注入、node 内建 require 兜底
//   3. 排障看服务端日志 heartbeat 信标:grep 'stage='
//      (ok_{key}=装载成功 / fail_{key}_原因=失败)
//   4. App 端刷新配置后生效
//
// ----------------------------------------------------------------------------
// 契约要点(违反任意一条 = App 端站点异常):
//   * 导出 { meta: {key, name, type}, api: async (fastify) => {...} }
//   * type 分区:3=视频 10=阅读 20=漫画 30=音乐 40=网盘(见 router.js /config 分桶)
//   * 六个 POST 端点:/init /home /homeVod /category /detail /play /search
//     参数一律从 request.body 取(category {id,page,filters} / detail {id} /
//     play {id,flag} / search {wd,page}),返回对象即响应体
//   * GET /test 供 check 脚本自测,用 fastify.inject 自调全链路
//   * 图片代理(可选):防盗链/加密图站点在 vod_pic 里返回本爬虫的
//     /proxy/image?u={token} 地址,由爬虫代抓+解密后回图——token 只对
//     列表/详情阶段"见过"的图 URL 有效(白名单防任意 URL 代理)
//   * 模块级变量(如 host)不能依赖 init 被调——爬虫可能被直接 POST /category,
//     init 不保证先来;后端地址(atv_pan 段)同样必须每个 handler 请求时
//     从 inReq.server.config 现读
//   * vod_play_from / vod_play_url 多线路用 $$$ 分隔,线路内多集用 # 分隔,
//     单集格式 "集名$播放id"
// ============================================================================
import req from '../../util/req.js';
import crypto from 'crypto';
import { load } from 'cheerio';

// ---------- 站点常量:按目标站改这一段 ----------
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36';
let host = 'https://www.example.com';

const CATEGORIES = [
    {type_id: '1', type_name: '电影'},
    {type_id: '2', type_name: '电视剧'},
    {type_id: '4', type_name: '动漫'},
];

function group(key, name, values) {
    return {key, name, value: [{n: '全部', v: ''}].concat(values.map(v => ({n: v, v})))};
}

// filters 的键必须与 CATEGORIES 的 type_id 对齐;group.key 即 category 收到的
// extend 键名(class/area/year/...),值是选中的 v
const FILTERS = {
    '1': [group('class', '按剧情', ['喜剧', '动作', '科幻']), group('year', '按年份', ['2026', '2025', '2024'])],
    '2': [group('year', '按年份', ['2026', '2025', '2024'])],
    '4': [group('year', '按年份', ['2026', '2025', '2024'])],
};

// 网盘识别与展示名(盘搜站标配;磁力站则删掉这半边,play 直接透传磁力链)
const PAN_NAMES = {baidu: '百度', quark: '夸克', uc: 'UC', aliyun: '阿里', xunlei: '迅雷', '123': '123', mobile: '移动', '115': '115', tianyi: '天翼'};
const DISK_PRIORITY = {baidu: 1, quark: 2, uc: 3, aliyun: 4, xunlei: 5, '123': 6, mobile: 7, '115': 8, tianyi: 9};

// ---------- 图片代理常量 ----------
// 站点图片无加密时:删掉 KEY/IV 和 decryptImage,代理只做代抓+透传
const IMAGE_MAX_BYTES = 12 * 1024 * 1024;
const IMAGE_CACHE_LIMIT = 2000;
// 加密图的对称密钥(按目标站逆向结果替换;数字数组形态避免源码里出现裸密文)
const IMAGE_KEY = Buffer.from([102, 53, 100, 57, 54, 53, 100, 102, 55, 53, 51, 51, 54, 50, 55, 48]);
const IMAGE_IV = Buffer.from([57, 55, 98, 54, 48, 51, 57, 52, 97, 98, 99, 50, 102, 98, 101, 49]);

// 后端地址:请求时现读,不落模块级初值依赖 init(见契约要点)
let panApi = '';
let panToken = '';

// ---------- 工具 ----------
function cleanText(text) {
    return String(text || '').replace(/\xa0/g, ' ').replace(/\s+/g, ' ').trim();
}

function buildUrl(path) {
    const raw = String(path || '').trim();
    if (!raw) return '';
    if (/^https?:\/\//i.test(raw)) return raw;
    if (raw.startsWith('//')) return 'https:' + raw;
    return host + (raw.startsWith('/') ? '' : '/') + raw;
}

// 部分站按 Accept 头分流:axios 默认 Accept:application/json 会拿到无卡片版,
// 抓 HTML 一律带 Accept: text/html
async function requestHtml(url) {
    try {
        const res = await req.get(url, {headers: {'User-Agent': UA, Accept: 'text/html', Referer: host + '/'}, timeout: 15000, validateStatus: s => s === 200});
        return res.data;
    } catch {
        return '';
    }
}

function isNetdiskUrl(value) {
    return /drive\.uc\.cn|pan\.quark\.cn|pan\.baidu\.com|pan\.xunlei\.com|alipan\.com|aliyundrive\.com|123pan\.|123684\.com|yun\.139\.com|mcloud\.139\.com|115\.com|cloud\.189\.cn/i.test(String(value || ''));
}

function diskFromUrl(url) {
    const text = String(url || '').toLowerCase();
    if (text.includes('pan.baidu.com')) return 'baidu';
    if (text.includes('pan.quark.cn')) return 'quark';
    if (/drive\.uc\.cn|uc\.cn/.test(text)) return 'uc';
    if (/alipan\.com|aliyundrive\.com/.test(text)) return 'aliyun';
    if (text.includes('pan.xunlei.com')) return 'xunlei';
    if (/123pan\.|123684\.com/.test(text)) return '123';
    if (/yun\.139\.com|mcloud\.139\.com/.test(text)) return 'mobile';
    if (/115\.com/.test(text)) return '115';
    if (/cloud\.189\.cn/.test(text)) return 'tianyi';
    return '';
}

function diskFromName(text) {
    const value = cleanText(text).toLowerCase();
    if (value.includes('百度')) return 'baidu';
    if (value.includes('夸克')) return 'quark';
    if (value.startsWith('uc')) return 'uc';
    if (value.includes('阿里')) return 'aliyun';
    if (value.includes('迅雷')) return 'xunlei';
    if (value.includes('123')) return '123';
    if (value.includes('移动') || value.includes('139')) return 'mobile';
    if (value.includes('115')) return '115';
    if (value.includes('天翼')) return 'tianyi';
    return value || 'netdisk';
}

// ---------- 图片代理(防盗链/加密图站点) ----------
// vod_pic 不直接给站点原图,改给 {origin}{prefix}/proxy/image?u={token}。
// App 端按普通 http 图片加载,爬虫代抓(带 Referer 过防盗链)+可选解密后回图。

// 请求 origin:经反代时 host/proto 在 x-forwarded-* 头里,不能只看 request.host
function requestOrigin(request) {
    const proto = String(request?.headers?.['x-forwarded-proto'] || request?.protocol || 'http').split(',')[0].trim() || 'http';
    const hostHeader = String(request?.headers?.['x-forwarded-host'] || request?.headers?.host || '').split(',')[0].trim();
    return hostHeader ? proto + '://' + hostHeader : '';
}

// 本爬虫路由前缀:工程形态=注册前缀 /spider/{key}/{type};
// 网页自定义上传形态=custom.js 通配分发,装载器会经 Proxy 视图补出等效前缀;
// 都拿不到时按 meta 硬拼兜底
function routePrefix(request) {
    if (request?.server && typeof request.server.prefix === 'string') {
        return request.server.prefix.replace(/\/+$/, '');
    }
    return '/spider/example/3';
}

// 目标图 URL ↔ token:base64url;只对"见过"的 URL 生效(见 rememberImage),
// 防止代理端点被当成任意 URL 中转
function encodeImageToken(url) {
    return Buffer.from(String(url || ''), 'utf8').toString('base64url');
}

function decodeImageToken(token) {
    try {
        return Buffer.from(String(token || ''), 'base64url').toString('utf8').trim();
    } catch {
        return '';
    }
}

// 白名单 Map:stable(去 query/hash)→ original;FIFO 淘汰限内存
const imageTargets = new Map();

function rememberImage(rawUrl) {
    const original = buildUrl(rawUrl);
    if (!original) return '';
    let stable = original;
    try {
        const url = new URL(original);
        url.search = '';
        url.hash = '';
        stable = url.toString();
    } catch {
        return '';
    }
    if (!imageTargets.has(stable) && imageTargets.size >= IMAGE_CACHE_LIMIT) {
        imageTargets.delete(imageTargets.keys().next().value);
    }
    imageTargets.set(stable, original);
    return stable;
}

// 生成代理地址;origin 拿不到(无请求上下文)或 URL 无效时返回空,调用方回落原图
function imageProxyUrl(request, rawUrl) {
    const stable = rememberImage(rawUrl);
    const origin = requestOrigin(request);
    if (!stable || !origin) return '';
    return origin + routePrefix(request) + '/proxy/image?u=' + encodeURIComponent(encodeImageToken(stable));
}

function imageHeaders(referer) {
    return {
        'User-Agent': UA,
        Accept: 'image/avif,image/webp,image/apng,image/svg+xml,image/*,*/*;q=0.8',
        Referer: referer || host + '/',
    };
}

// 魔数嗅探:防盗链站有时把错误页当图回,不能只信 Content-Type
function isImageBytes(data) {
    if (!Buffer.isBuffer(data) || data.length < 12) return false;
    return data[0] === 0xff && data[1] === 0xd8
        || data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
        || data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP'
        || data.subarray(0, 6).toString('ascii') === 'GIF87a'
        || data.subarray(0, 6).toString('ascii') === 'GIF89a';
}

function imageContentType(data) {
    if (data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) return 'image/png';
    if (data.subarray(0, 4).toString('ascii') === 'RIFF' && data.subarray(8, 12).toString('ascii') === 'WEBP') return 'image/webp';
    if (data.subarray(0, 6).toString('ascii') === 'GIF87a' || data.subarray(0, 6).toString('ascii') === 'GIF89a') return 'image/gif';
    return 'image/jpeg';
}

function stripPadding(data) {
    if (!data.length) return data;
    const padding = data[data.length - 1];
    if (padding < 1 || padding > 16 || padding > data.length) return data;
    for (let index = data.length - padding; index < data.length; index += 1) {
        if (data[index] !== padding) return data;
    }
    return data.subarray(0, data.length - padding);
}

// JPEG 截到 FFD9、PNG 截到 IEND:部分站会在图片尾部拼脏字节
function trimImage(data) {
    if (data[0] === 0xff && data[1] === 0xd8) {
        const end = data.lastIndexOf(Buffer.from([0xff, 0xd9]));
        return end >= 0 ? data.subarray(0, end + 2) : data;
    }
    if (data.subarray(0, 8).equals(Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))) {
        const end = data.lastIndexOf('IEND');
        return end >= 0 ? data.subarray(0, end + 8) : data;
    }
    return data;
}

// 原图本身就是明文图片字节 → 直接裁剪;否则按 AES-128-CBC 解密再校验
function decryptImage(raw) {
    if (isImageBytes(raw)) return trimImage(raw);
    if (!raw.length || raw.length % 16 !== 0) return null;
    try {
        const decipher = crypto.createDecipheriv('aes-128-cbc', IMAGE_KEY, IMAGE_IV);
        decipher.setAutoPadding(false);
        const decrypted = stripPadding(Buffer.concat([decipher.update(raw), decipher.final()]));
        if (!isImageBytes(decrypted)) return null;
        return trimImage(decrypted);
    } catch {
        return null;
    }
}

// 列表页卡片解析:选择器按目标站模板改
// MacCMS storm 模板 = .module-item;dida 的 myui 模板 = .myui-vodlist__box
// request 用于生成图片代理绝对地址(需请求上下文里的 host 头)
function parseCards(html, request) {
    const $ = load(html || '');
    const items = [];
    const seen = new Set();
    $('.module-item').each((_, node) => {
        const el = $(node);
        const titleEl = el.find('a[href]').first();
        // vod_id 取详情页 URL 里的数字段(也可用整段 path,只要 detail 能还原)
        const m = buildUrl(titleEl.attr('href')).match(/\/detail\/(\d+)\.html/);
        const title = cleanText(el.find('.video-name').first().text()) || cleanText(titleEl.attr('title'));
        if (!m || !title || seen.has(m[1])) return;
        seen.add(m[1]);
        const img = el.find('img').first();
        const pic = img.attr('data-original') || img.attr('data-src') || img.attr('src');
        items.push({
            vod_id: m[1],
            vod_name: title,
            // 防盗链/加密图站:代理地址优先,生成失败回落原图
            vod_pic: imageProxyUrl(request, pic) || buildUrl(pic),
            vod_remarks: cleanText(el.find('.module-item-text').first().text()),
        });
    });
    return items;
}

// 后端配置每个 handler 现读(不依赖 init 被调,契约要点)
function readBackend(inReq) {
    const backend = (inReq.server.config.atv_pan) || {};
    panApi = String(backend.api || '').replace(/\/$/, '');
    panToken = String(backend.token || '');
}

async function fetchImage(url) {
    // arraybuffer + transformResponse 裸返:防 axios 把二进制当文本/JSON 处理
    const res = await req.get(url, {
        headers: imageHeaders(host + '/'),
        timeout: 15000,
        maxRedirects: 5,
        responseType: 'arraybuffer',
        transformResponse: [(body) => body],
        maxBodyLength: IMAGE_MAX_BYTES,
        maxContentLength: IMAGE_MAX_BYTES,
        validateStatus: () => true,
    });
    if (res.status < 200 || res.status >= 300) return null;
    const data = Buffer.from(res.data || []);
    return data.length > 0 && data.length <= IMAGE_MAX_BYTES ? data : null;
}

// GET /proxy/image?u={token}:白名单校验 → 代抓 → 解密/裁剪 → 按魔数回 Content-Type
async function imageProxy(request, reply) {
    const stable = decodeImageToken(request?.query?.u);
    const original = imageTargets.get(stable);
    if (!stable || !original) return reply.code(403).send('无效的海报地址');
    let raw = null;
    // stable(去参)与 original(带参)都试:部分站签名在 query 里
    for (const url of [...new Set([stable, original])]) {
        try {
            raw = await fetchImage(url);
        } catch {
            raw = null;
        }
        if (raw) break;
    }
    const image = raw ? decryptImage(raw) : null;
    if (!image) return reply.code(502).send('海报获取或解密失败');
    return reply
        .code(200)
        .header('Content-Type', imageContentType(image))
        .header('Content-Length', String(image.length))
        .header('Access-Control-Allow-Origin', '*')
        .header('Cache-Control', 'public, max-age=86400')
        .header('X-Content-Type-Options', 'nosniff')
        .send(image);
}

// 网盘分享链接 → 后端 /parse → 文件集列表(条目 "文件名(大小)$1@pid@0@0")
// 失败回退单链接透传,不拖垮整线路
async function parsePanUrl(shareUrl, title) {
    if (!panApi) return null;
    try {
        const res = await req.post(`${panApi}/parse/${panToken}?ac=play`,
            {url: shareUrl, title: title || ''},
            {timeout: 20000, headers: {'User-Agent': UA}});
        const first = res.data && res.data.list && res.data.list[0];
        if (first && first.vod_play_url) return first.vod_play_url;
    } catch { /* 回退 */ }
    return null;
}

// ---------- 六个契约端点 ----------

async function init(inReq) {
    // 站点级配置段(键名 = meta.key):index.config.js 里 example: {url: ...} 可覆盖 host
    const cfg = inReq.server.config.example || {};
    if (cfg.url || cfg.host) host = String(cfg.url || cfg.host).replace(/\/$/, '');
    return {};
}

async function home() {
    return {class: CATEGORIES, filters: FILTERS};
}

// 首页推荐:无数据可返回空列表(App 端展示空首页;纯搜索型站点才返回 {},
// 且搜索型站点不能排在站点列表第一位——App 默认进第一个站点)
async function homeVod(inReq) {
    return {list: parseCards(await requestHtml(buildUrl('/')), inReq)};
}

async function category(inReq) {
    readBackend(inReq);
    let pg = parseInt(inReq.body.page, 10) || 1;
    if (pg <= 0) pg = 1;
    const tid = String(inReq.body.id || '');
    const extend = inReq.body.filters || {};
    // MacCMS 段式 URL:vod/show/分类-地区-排序-剧情-语言-字母---页---年份.html
    const path = `vod/show/${tid}-${extend.area || ''}-${extend.sort || 'time'}-`
        + `${extend.class || ''}-${extend.lang || ''}-${extend.letter || ''}---${pg}---${extend.year || ''}`;
    const items = parseCards(await requestHtml(buildUrl(path)), inReq);
    // pagecount 给 pg+1 表示可能还有下一页(App 靠它决定是否继续翻页)
    return {list: items, page: pg, pagecount: items.length ? pg + 1 : pg, limit: 12, total: pg * 30 + items.length};
}

async function detail(inReq) {
    readBackend(inReq);
    const ids = !Array.isArray(inReq.body.id) ? [inReq.body.id] : inReq.body.id;
    const result = {list: []};
    for (const rawId of ids) {
        const vodId = String(rawId || '').trim();
        if (!vodId) continue;
        const html = await requestHtml(buildUrl(`/detail/${vodId}.html`));
        const $ = load(html);
        const title = cleanText($('h1').first().text());
        // 网盘链接行:按目标站 DOM 改选择器;此处假设 .module-link-item 下
        // b=盘名、a=分享链接
        const grouped = {};
        const orderSeen = [];
        $('.module-link-item').each((_, node) => {
            const el = $(node);
            const rawName = cleanText(el.find('b').first().text()).replace(/[：:]/g, '');
            const href = cleanText(el.find('a[href]').first().attr('href'));
            if (!href || !isNetdiskUrl(href)) return;
            const disk = diskFromUrl(href) || diskFromName(rawName);
            if (!grouped[disk]) {
                grouped[disk] = [];
                orderSeen.push(disk);
            }
            if (!grouped[disk].some(item => item.link === href)) {
                grouped[disk].push({title: cleanText(el.find('a').first().text()) || disk, link: href});
            }
        });
        // 同盘多链接并发解析,按盘优先级排线路;解析失败回退"名称$链接"透传
        const names = orderSeen.sort((a, b) => (DISK_PRIORITY[a] || 99) - (DISK_PRIORITY[b] || 99));
        const fromParts = [];
        const urlParts = [];
        for (const disk of names) {
            const lines = await Promise.all(grouped[disk].map(item => parsePanUrl(item.link, title).then(r => r || `${item.title}$${item.link}`)));
            fromParts.push(PAN_NAMES[disk] || disk);
            urlParts.push(lines.join('#'));
        }
        result.list.push({
            vod_id: vodId,
            vod_name: title,
            vod_pic: imageProxyUrl(inReq, $('.module-item-pic img').first().attr('data-original') || $('.module-item-pic img').first().attr('src'))
                || buildUrl($('.module-item-pic img').first().attr('data-original') || $('.module-item-pic img').first().attr('src')),
            vod_content: cleanText($('.module-info-introduction-content, .detail-content').first().text()),
            vod_play_from: fromParts.join('$$$'),
            vod_play_url: urlParts.join('$$$'),
        });
    }
    return result;
}

async function play(inReq) {
    readBackend(inReq);
    const id = String(inReq.body.id || '').trim();
    // 文件集条目(含 @ 且非 http)→ 后端 /play 代理直链(/p/{token}/1@pid 形态)
    if (panApi && id.includes('@') && !id.startsWith('http')) {
        try {
            const res = await req.get(`${panApi}/play/${panToken}`, {
                params: {id},
                timeout: 20000,
                headers: {'User-Agent': UA},
            });
            return res.data;
        } catch (e) {
            console.error('[example] backend play failed:', e.message);
            return {parse: 0, playUrl: '', url: ''};
        }
    }
    // 回退形态:裸分享链接或直链 → parse:0 透传由播放器处理
    if (isNetdiskUrl(id) || /^https?:\/\//i.test(id) || id.startsWith('magnet:')) {
        return {parse: 0, playUrl: '', url: id};
    }
    return {parse: 0, playUrl: '', url: ''};
}

async function search(inReq) {
    readBackend(inReq);
    let pg = parseInt(inReq.body.page, 10) || 1;
    const wd = cleanText(inReq.body.wd);
    if (!wd) return {page: pg, total: 0, list: [], pagecount: pg};
    // MacCMS 标准搜索 URL;搜索页卡片选择器通常是 .module-search-item
    const items = parseCards(await requestHtml(`${host}/search/-------------.html?wd=${encodeURIComponent(wd)}`), inReq);
    return {page: pg, total: items.length, list: items, pagecount: items.length ? pg + 1 : pg};
}

// GET /test:check-spiders.mjs 判活依据;HTTP 500 = App 端不可用
// 搜索词用"柯南"这类热门词,冷门词搜不到会误报
async function test(inReq, outResp) {
    try {
        const prefix = inReq.server.prefix;
        const dataResult = {};
        const inject = (p, body) => inReq.server.inject().post(`${prefix}${p}`).payload(body || {}).then(r => r.json());
        dataResult.init = await inject('/init');
        dataResult.home = await inject('/home');
        if (dataResult.home?.class?.length > 0) {
            dataResult.category = await inject('/category', {id: dataResult.home.class[0].type_id, page: 1});
            // 图片代理自检:首条 vod_pic 是本爬虫代理地址则实际拉一次
            const pic = dataResult.category?.list?.[0]?.vod_pic;
            if (pic && pic.includes('/proxy/image?')) {
                dataResult.imageProxy = await inReq.server.inject().get(pic.slice(pic.indexOf('/proxy/image')))
                    .then(r => ({code: r.statusCode, type: r.headers['content-type']}));
            }
            if (dataResult.category?.list?.length > 0) {
                dataResult.detail = await inject('/detail', {id: dataResult.category.list[0].vod_id});
                const d = dataResult.detail?.list?.[0];
                if (d?.vod_play_url) {
                    const first = d.vod_play_url.split('$$$')[0].split('#')[0].split('$').pop();
                    dataResult.play = [await inject('/play', {id: first})];
                }
            }
        }
        dataResult.search = await inject('/search', {wd: '柯南', page: 1});
        return dataResult;
    } catch (err) {
        console.error(err);
        outResp.code(500);
        return {err: err.message, tip: 'check debug console output'};
    }
}

export default {
    meta: {
        key: 'example',       // 站点唯一键,App 端显示为 nodejs_example
        name: '示例站',        // App 端站点名
        type: 3,              // 3=视频 10=阅读 20=漫画 30=音乐 40=网盘
    },
    api: async (fastify) => {
        fastify.post('/init', init);
        fastify.post('/home', home);
        fastify.post('/homeVod', homeVod);
        fastify.post('/category', category);
        fastify.post('/detail', detail);
        fastify.post('/play', play);
        fastify.post('/search', search);
        fastify.get('/proxy/image', imageProxy);   // 图片代理子路由(可选)
        fastify.get('/test', test);
    },
};
