/*
 * e2e-api.mjs —— 复现 Android 客户端「设置 → 登录 → 搜索 → 详情 → 播放地址」的调用序列，
 * 并对返回的 m3u8 做真实拉取校验。
 *
 * 为什么需要：编译通过只证明代码能跑，接口实测也只证明「字段对得上」。
 * 真正会翻车的是「详情里给了地址，但那个地址在设备上拉不动」（CDN 要求 UA/Referer、
 * 防盗链、IPv6-only、需要 token…）。这个脚本把这一步也验证掉。
 *
 * 用法：
 *   node tools/e2e-api.mjs --url http://你的NAS:8899 --password 你的密码
 *   node tools/e2e-api.mjs --sources ../../libretv-synology/sources.json
 */
const args = process.argv.slice(2);
const argOf = (name, fallback) => {
  const i = args.indexOf(name);
  return i >= 0 && args[i + 1] ? args[i + 1] : fallback;
};

const BASE = argOf('--url', 'http://你的NAS:8899').replace(/\/+$/, '');
const PW = argOf('--password', '');
if (!PW) { console.error('缺少 --password'); process.exit(2); }
const SOURCES_FILE = argOf('--sources', '');

const BROWSER_UA =
  'Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36';

let pass = 0;
let fail = 0;
const check = (name, ok, detail = '') => {
  if (ok) { pass++; console.log(`  PASS  ${name}${detail ? '  (' + detail + ')' : ''}`); }
  else { fail++; console.log(`  FAIL  ${name}${detail ? '  (' + detail + ')' : ''}`); }
};

const j = (o) => JSON.stringify(o);

async function main() {
  console.log(`服务器: ${BASE}\n`);

  /* --- 1. 状态 + 登录 ------------------------------------------------- */
  console.log('[1] 状态与登录');
  const statusRes = await fetch(`${BASE}/api/status`);
  const status0 = await statusRes.json();
  check('/api/status 可达', statusRes.ok && !!status0.version, `v${status0.version}`);

  const loginRes = await fetch(`${BASE}/api/auth`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: j({ password: PW }),
  });
  const setCookie = loginRes.headers.get('set-cookie') || '';
  const cookie = setCookie.split(';')[0];
  const loginBody = await loginRes.json().catch(() => ({}));
  check('登录成功返回 success', loginRes.ok && loginBody.success === true, `HTTP ${loginRes.status}`);
  check('拿到会话 Cookie', cookie.startsWith('ltv_session='), cookie.slice(0, 18) + '…');

  const status1 = await (await fetch(`${BASE}/api/status`, { headers: { Cookie: cookie } })).json();
  check('登录后 verified=true（会话生效）', status1.verified === true, `verified=${status1.verified}`);

  /* --- 2. 片源 ------------------------------------------------------- */
  console.log('\n[2] 片源');
  let sources = [];
  if (SOURCES_FILE) {
    const fs = await import('node:fs');
    const raw = JSON.parse(fs.readFileSync(SOURCES_FILE, 'utf8'));
    sources = (raw.sources || []).map((s) => ({
      name: s.name, url: s.url, detail: s.detail || '', isAdult: !!s.isAdult,
    }));
    console.log(`  从 ${SOURCES_FILE} 读到 ${sources.length} 个源`);
  } else if ((status0.defaultSources || []).length) {
    sources = status0.defaultSources;
    console.log(`  服务器预置 ${sources.length} 个源`);
  } else {
    sources = [{ name: '暴风资源', url: 'https://bfzyapi.com/api.php/provide/vod', detail: 'https://bfzyapi.com', isAdult: false }];
    console.log('  未提供片源文件，使用内置测试源 1 个');
  }
  check('至少有一个片源可用于测试', sources.length > 0, `${sources.length} 个`);

  /* --- 3. 搜索 ------------------------------------------------------- */
  console.log('\n[3] 搜索');
  const keyword = argOf('--keyword', '流浪地球');
  const t0 = Date.now();
  const searchRes = await fetch(`${BASE}/api/search`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Cookie: cookie },
    body: j({ wd: keyword, sources: sources.slice(0, 3), filterAdult: true }),
  });
  const searchData = await searchRes.json();
  const items = searchData.list || [];
  console.log(`  搜「${keyword}」: ${items.length} 条，${Date.now() - t0} ms，失败源 ${(searchData.failures || []).length} 个`);
  check('搜索返回结果', items.length > 0, `${items.length} 条`);
  check('结果含 vodId 与片名', items.every((it) => !!it.vodId && !!it.name), `例: ${items[0]?.name} / ${items[0]?.vodId}`);
  check('结果含封面地址', items.some((it) => String(it.pic || '').startsWith('http')));

  if (!items.length) {
    console.log('\n没有结果，后续检查跳过');
    summary();
    return;
  }

  /* --- 4. 详情 + 播放地址真实性 -------------------------------------- */
  console.log('\n[4] 详情与播放地址');
  const item = items[0];
  const src = sources.find((s) => s.url === item.sourceUrl) || { name: item.sourceName, url: item.sourceUrl, detail: '', isAdult: false };
  const detailUrl = `${BASE}/api/detail?id=${encodeURIComponent(item.vodId)}&source=${encodeURIComponent(j(src))}`;
  const detail = await (await fetch(detailUrl, { headers: { Cookie: cookie } })).json();
  const eps = detail.episodes || [];
  check('详情返回集数', eps.length > 0, `${eps.length} 集`);
  check('集地址是 http(s)', eps.every((u) => /^https?:\/\//.test(u)), `例: ${String(eps[0]).slice(0, 60)}…`);
  check('详情含片名/封面', !!detail.videoInfo?.title, detail.videoInfo?.title);

  if (eps.length) {
    const playUrl = eps[0];
    console.log(`  拉取第一集地址: ${playUrl.slice(0, 80)}…`);
    // 3a. 无特殊 UA（ExoPlayer 默认会发自己的 UA）
    const plain = await probe(playUrl, null);
    check('播放地址可访问（默认 UA）', plain.ok, `HTTP ${plain.status} ${plain.kind}`);
    // 3b. 浏览器 UA：有些 CDN 限制 UA，需要给 ExoPlayer 也设置
    const withUa = await probe(playUrl, BROWSER_UA);
    check('播放地址可访问（浏览器 UA）', withUa.ok, `HTTP ${withUa.status} ${withUa.kind}`);
    if (!plain.ok && withUa.ok) {
      console.log('  ⚠ 该 CDN 需要浏览器 UA —— ExoPlayer 必须设置 setUserAgent，否则会播放失败');
    }
    check('返回的是 HLS 播放列表', plain.body?.startsWith('#EXTM3U') || withUa.body?.startsWith('#EXTM3U'),
      (plain.body || withUa.body || '').slice(0, 30).replace(/\s+/g, ' '));
  }

  /* --- 5. 首页推荐 + 图片代理 ---------------------------------------- */
  console.log('\n[5] 首页推荐与图片代理');
  const douban = await (await fetch(
    `${BASE}/api/douban?type=movie&tag=${encodeURIComponent('热门')}&pageStart=0&pageSize=6`,
    { headers: { Cookie: cookie } },
  )).json();
  const dItems = douban.items || [];
  check('推荐返回条目', dItems.length > 0, `${dItems.length} 条`);
  check('推荐条目含封面', dItems.some((d) => String(d.cover || '').startsWith('http')), dItems[0]?.title);
  if (dItems[0]?.cover) {
    const img = await fetch(`${BASE}/api/proxy?url=${encodeURIComponent(dItems[0].cover)}`, { headers: { Cookie: cookie } });
    const buf = await img.arrayBuffer();
    check('图片代理可出图', img.ok && /image\//.test(img.headers.get('content-type') || ''),
      `${img.status} ${img.headers.get('content-type')} ${Math.round(buf.byteLength / 1024)}KB`);
  }

  /* --- 6. 直播 ------------------------------------------------------- */
  console.log('\n[6] 直播');
  const liveUrl = argOf('--live', 'https://iptv-org.github.io/iptv/countries/cn.m3u');
  const live = await (await fetch(`${BASE}/api/live/playlist?url=${encodeURIComponent(liveUrl)}`,
    { headers: { Cookie: cookie } })).json();
  const chans = live.channels || [];
  check('直播返回频道', chans.length > 0, `${chans.length} 个频道`);
  check('频道含名称与地址', chans.every((c) => !!c.name && !!c.url), `例: ${chans[0]?.name}`);
  const m3u8Chan = chans.find((c) => /\.m3u8(\?|$)/i.test(c.url));
  if (m3u8Chan) {
    const pr = await probe(m3u8Chan.url, BROWSER_UA);
    check('抽一个 HLS 直播源可访问', pr.ok, `${m3u8Chan.name} HTTP ${pr.status}`);
  } else {
    console.log('  （这批频道里没有 .m3u8 地址，跳过）');
  }

  summary();
}

/** 拉取地址的前若干字节，判断是否真是播放列表 */
async function probe(url, ua) {
  try {
    const ctl = new AbortController();
    const timer = setTimeout(() => ctl.abort(), 12000);
    const res = await fetch(url, {
      headers: ua ? { 'User-Agent': ua } : {},
      signal: ctl.signal,
      redirect: 'follow',
    });
    const buf = Buffer.from(await res.arrayBuffer()).subarray(0, 400);
    clearTimeout(timer);
    const text = buf.toString('utf8');
    const kind = text.startsWith('#EXTM3U') ? 'HLS' : (res.headers.get('content-type') || '').split(';')[0] || 'binary';
    return { ok: res.ok, status: res.status, kind, body: text };
  } catch (e) {
    return { ok: false, status: 0, kind: e.name || 'error', body: '' };
  }
}

function summary() {
  console.log(`\n结果: ${pass} 通过 / ${fail} 失败`);
  process.exitCode = fail === 0 ? 0 : 1;
}

main().catch((e) => {
  console.error('\n脚本异常:', e);
  process.exitCode = 2;
});
