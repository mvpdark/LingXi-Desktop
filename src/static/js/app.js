/* ═══════════════════════════════════════════════════════════
   LingXi — PROFESSIONAL PHOTO ATELIER
   Full App Logic · Multi-language i18n · Windows Desktop Style
   ═══════════════════════════════════════════════════════════ */
"use strict";

/* ─── I18N ─── */
const I18N = {
  lang: localStorage.getItem("lx_lang") || "en",
  labels: {},
  texts: {
    "zh-CN": {
      brand_sub: "专业摄影暗房",
      username_placeholder: "用户名",
      password_placeholder: "密码",
      enter_darkroom: "进入暗房",
      create_account: "创建账号",
      sign_in: "登录",
      nav_dashboard: "仪表盘",
      nav_editor: "编辑器",
      nav_gallery: "画廊",
      welcome_back: "欢迎回来",
      welcome_sub: "暗房已就绪，今天想创作什么？",
      import_photo: "导入照片",
      card_enhance_title: "AI 增强",
      card_enhance_desc: "一键提升清晰度。",
      card_rembg_title: "背景移除",
      card_rembg_desc: "U2Net 精准抠图。",
      card_presets_title: "预设滤镜",
      card_presets_desc: "10 种专业调色。",
      card_batch_title: "批量处理",
      card_batch_desc: "批量编辑照片。",
      badge_new: "新",
      recent_projects: "最近项目",
      section_view_all: "查看全部",
      recent_empty: "拖入照片开始第一个项目",
      tool_pointer: "指针",
      tool_crop: "裁剪",
      tool_brush: "画笔",
      tool_rembg: "移除背景",
      tool_enhance: "AI增强",
      drop_photo: "拖入照片开始",
      adjustments: "调整",
      slider_exposure: "曝光",
      slider_contrast: "对比度",
      slider_highlights: "高光",
      slider_shadows: "阴影",
      slider_saturation: "饱和度",
      presets_title: "预设",
      export_image: "导出图像",
      dock_placeholder: "描述你的编辑需求…",
      gallery_title: "画廊",
      gallery_all: "全部",
      gallery_enhanced: "已增强",
      gallery_nobg: "已去背景",
      gallery_exported: "已导出",
      gallery_empty: "暂无照片，开始编辑来建立你的画廊。",
      processing: "处理中…",
      toast_upload_first: "请先上传图片",
      toast_select_preset: "从右侧面板选择预设",
      toast_image_unavailable: "图片不可用",
      toast_removing_bg: "移除背景中…",
      toast_enhancing: "增强中…",
      toast_done: "完成！",
      toast_failed: "处理失败",
      toast_network_error: "网络错误",
      toast_no_image: "没有可导出的图片",
      toast_exported: "已导出！",
      toast_batch_soon: "批量处理即将上线",
      toast_applying_preset: "应用预设中：",
      toast_batch_processing: "批量处理中…",
      toast_batch_done: "完成 {ok}/{total} 张",
      toast_auth_failed: "认证失败",
      toast_fill_fields: "请填写所有字段",
      toast_signing_in: "登录中…",
      toast_creating: "创建账号中…",
    },
    en: {
      brand_sub: "PROFESSIONAL PHOTO ATELIER",
      username_placeholder: "Username",
      password_placeholder: "Password",
      enter_darkroom: "Enter Darkroom",
      create_account: "Create Account",
      sign_in: "Sign In",
      nav_dashboard: "Dashboard",
      nav_editor: "Editor",
      nav_gallery: "Gallery",
      welcome_back: "Welcome back",
      welcome_sub: "Your darkroom is ready. What would you like to create today?",
      import_photo: "Import Photo",
      card_enhance_title: "AI Enhance",
      card_enhance_desc: "One-click clarity boost.",
      card_rembg_title: "Background Removal",
      card_rembg_desc: "U2Net precision cutout.",
      card_presets_title: "Preset Filters",
      card_presets_desc: "10 pro color grades.",
      card_batch_title: "Batch Processing",
      card_batch_desc: "Edit multiple photos at once.",
      badge_new: "NEW",
      recent_projects: "Recent Projects",
      section_view_all: "View all",
      recent_empty: "Drop photos to start your first project",
      tool_pointer: "Pointer",
      tool_crop: "Crop",
      tool_brush: "Brush",
      tool_rembg: "Remove Background",
      tool_enhance: "AI Enhance",
      drop_photo: "Drop photo to begin",
      adjustments: "Adjustments",
      slider_exposure: "Exposure",
      slider_contrast: "Contrast",
      slider_highlights: "Highlights",
      slider_shadows: "Shadows",
      slider_saturation: "Saturation",
      presets_title: "Presets",
      export_image: "Export Image",
      dock_placeholder: "Describe your edits…",
      gallery_title: "Gallery",
      gallery_all: "All",
      gallery_enhanced: "Enhanced",
      gallery_nobg: "No Background",
      gallery_exported: "Exported",
      gallery_empty: "No photos yet. Start editing to build your gallery.",
      processing: "Processing…",
      toast_upload_first: "Please upload an image first",
      toast_select_preset: "Select a preset from the right panel",
      toast_image_unavailable: "Image unavailable",
      toast_removing_bg: "Removing background…",
      toast_enhancing: "Enhancing…",
      toast_done: "Done!",
      toast_failed: "Processing failed",
      toast_network_error: "Network error",
      toast_no_image: "No image to export",
      toast_exported: "Exported!",
      toast_batch_soon: "Batch processing coming soon",
      toast_applying_preset: "Applying preset: ",
      toast_batch_processing: "Batch processing…",
      toast_batch_done: "Done {ok}/{total} photos",
      toast_auth_failed: "Authentication failed",
      toast_fill_fields: "Please fill in all fields",
      toast_signing_in: "Signing in…",
      toast_creating: "Creating account…",
    },
    ja: {
      brand_sub: "プロフェッショナルフォトアトリエ",
      username_placeholder: "ユーザー名",
      password_placeholder: "パスワード",
      enter_darkroom: "暗室に入る",
      create_account: "アカウント作成",
      sign_in: "サインイン",
      nav_dashboard: "ダッシュボード",
      nav_editor: "エディター",
      nav_gallery: "ギャラリー",
      welcome_back: "おかえりなさい",
      welcome_sub: "暗室の準備ができました。今日は何を作りますか？",
      import_photo: "写真をインポート",
      card_enhance_title: "AI強化",
      card_enhance_desc: "ワンクリックで高画質化。",
      card_rembg_title: "背景除去",
      card_rembg_desc: "U2Net精密カットアウト。",
      card_presets_title: "プリセットフィルター",
      card_presets_desc: "10種のプロカラー。",
      card_batch_title: "バッチ処理",
      card_batch_desc: "複数写真を一括編集。",
      badge_new: "新",
      recent_projects: "最近のプロジェクト",
      section_view_all: "すべて表示",
      recent_empty: "写真をドロップして最初のプロジェクトを開始",
      tool_pointer: "ポインター",
      tool_crop: "切り抜き",
      tool_brush: "ブラシ",
      tool_rembg: "背景を削除",
      tool_enhance: "AI強化",
      drop_photo: "写真をドロップして開始",
      adjustments: "調整",
      slider_exposure: "露出",
      slider_contrast: "コントラスト",
      slider_highlights: "ハイライト",
      slider_shadows: "シャドウ",
      slider_saturation: "彩度",
      presets_title: "プリセット",
      export_image: "画像を書き出し",
      dock_placeholder: "編集内容を説明…",
      gallery_title: "ギャラリー",
      gallery_all: "すべて",
      gallery_enhanced: "強化済み",
      gallery_nobg: "背景なし",
      gallery_exported: "書出し済み",
      gallery_empty: "まだ写真がありません。編集を始めてギャラリーを作りましょう。",
      processing: "処理中…",
      toast_upload_first: "最初に画像をアップロードしてください",
      toast_select_preset: "右パネルからプリセットを選択",
      toast_image_unavailable: "画像が利用できません",
      toast_removing_bg: "背景を削除中…",
      toast_enhancing: "強化中…",
      toast_done: "完了！",
      toast_failed: "処理に失敗しました",
      toast_network_error: "ネットワークエラー",
      toast_no_image: "書き出す画像がありません",
      toast_exported: "書き出しました！",
      toast_batch_soon: "バッチ処理は近日公開",
      toast_applying_preset: "プリセット適用中：",
      toast_batch_processing: "バッチ処理中…",
      toast_batch_done: "{ok}/{total}枚完了",
      toast_auth_failed: "認証に失敗しました",
      toast_fill_fields: "すべての項目を入力してください",
      toast_signing_in: "サインイン中…",
      toast_creating: "アカウント作成中…",
    },
    ko: {
      brand_sub: "전문 사진 암실",
      username_placeholder: "사용자 이름",
      password_placeholder: "비밀번호",
      enter_darkroom: "암실 입장",
      create_account: "계정 만들기",
      sign_in: "로그인",
      nav_dashboard: "대시보드",
      nav_editor: "편집기",
      nav_gallery: "갤러리",
      welcome_back: "돌아오신 것을 환영합니다",
      welcome_sub: "암실이 준비되었습니다. 오늘은 무엇을 만들어 볼까요?",
      import_photo: "사진 가져오기",
      card_enhance_title: "AI 향상",
      card_enhance_desc: "원클릭 화질 향상.",
      card_rembg_title: "배경 제거",
      card_rembg_desc: "U2Net 정밀 컷아웃.",
      card_presets_title: "프리셋 필터",
      card_presets_desc: "10가지 프로 컬러.",
      card_batch_title: "일괄 처리",
      card_batch_desc: "여러 사진 일괄 편집.",
      badge_new: "신규",
      recent_projects: "최근 프로젝트",
      section_view_all: "전체 보기",
      recent_empty: "사진을 드롭하여 첫 프로젝트 시작",
      tool_pointer: "포인터",
      tool_crop: "자르기",
      tool_brush: "브러시",
      tool_rembg: "배경 제거",
      tool_enhance: "AI 향상",
      drop_photo: "사진을 드롭하여 시작",
      adjustments: "조정",
      slider_exposure: "노출",
      slider_contrast: "대비",
      slider_highlights: "하이라이트",
      slider_shadows: "그림자",
      slider_saturation: "채도",
      presets_title: "프리셋",
      export_image: "이미지 내보내기",
      dock_placeholder: "편집 내용 설명…",
      gallery_title: "갤러리",
      gallery_all: "전체",
      gallery_enhanced: "향상됨",
      gallery_nobg: "배경 제거됨",
      gallery_exported: "내보냄",
      gallery_empty: "아직 사진이 없습니다. 편집을 시작하여 갤러리를 구축하세요.",
      processing: "처리 중…",
      toast_upload_first: "먼저 이미지를 업로드하세요",
      toast_select_preset: "오른쪽 패널에서 프리셋 선택",
      toast_image_unavailable: "이미지를 사용할 수 없습니다",
      toast_removing_bg: "배경 제거 중…",
      toast_enhancing: "향상 중…",
      toast_done: "완료!",
      toast_failed: "처리 실패",
      toast_network_error: "네트워크 오류",
      toast_no_image: "내보낼 이미지가 없습니다",
      toast_exported: "내보내기 완료!",
      toast_batch_soon: "일괄 처리는 곧 제공됩니다",
      toast_applying_preset: "프리셋 적용 중: ",
      toast_batch_processing: "일괄 처리 중…",
      toast_batch_done: "{ok}/{total}장 완료",
      toast_auth_failed: "인증 실패",
      toast_fill_fields: "모든 필드를 입력하세요",
      toast_signing_in: "로그인 중…",
      toast_creating: "계정 생성 중…",
    },
  },
  init() {
    if (!this.texts[this.lang]) this.lang = "en";
    this.labels = this.texts[this.lang];
    this.applyAll();
  },
  setLang(lang) {
    if (!this.texts[lang]) return;
    this.lang = lang;
    localStorage.setItem("lx_lang", lang);
    this.labels = this.texts[lang];
    document.documentElement.lang = lang;
    this.applyAll();
    // Update label
    const lb = document.getElementById("lang-label");
    if (lb) {
      const flags = { "zh-CN": "中", en: "EN", ja: "日", ko: "한" };
      lb.textContent = flags[lang] || lang.toUpperCase();
    }
    // Update active option
    document.querySelectorAll(".lang-opt").forEach(o => {
      o.classList.toggle("active", o.dataset.lang === lang);
    });
  },
  t(key) {
    return this.labels[key] || `[${key}]`;
  },
  applyAll() {
    // data-i18n
    document.querySelectorAll("[data-i18n]").forEach(el => {
      const key = el.dataset.i18n;
      if (this.labels[key]) el.textContent = this.labels[key];
    });
    // data-i18n-placeholder
    document.querySelectorAll("[data-i18n-placeholder]").forEach(el => {
      const key = el.dataset.i18nPlaceholder;
      if (this.labels[key]) el.placeholder = this.labels[key];
    });
    // data-i18n-title
    document.querySelectorAll("[data-i18n-title]").forEach(el => {
      const key = el.dataset.i18nTitle;
      if (this.labels[key]) el.title = this.labels[key];
    });
  },
};

/* ─── State ─── */
const S = {
  token: localStorage.getItem("lx_t") || "",
  refresh: localStorage.getItem("lx_r") || "",
  user: null,
  canvasImg: null,
  pendingFiles: [],
  filmstrip: [],
  curView: "dashboard",
  isRegister: false,
};
let API = "";

/* ─── Utils ─── */
const $ = id => document.getElementById(id);
const QA = (sel, el) => [...(el || document).querySelectorAll(sel)];
const ce = (tag, cls, html) => { const e = document.createElement(tag); if (cls) e.className = cls; if (html != null) e.innerHTML = html; return e; };

async function loadConfig() { try { const r = await fetch("/config.json"); const c = await r.json(); API = c.apiBase || ""; } catch { API = ""; } }

async function api(path, opts = {}) {
  const h = { ...(opts.headers || {}) };
  if (S.token) h["Authorization"] = `Bearer ${S.token}`;
  if (opts.body && !(opts.body instanceof FormData)) h["Content-Type"] = "application/json";
  let r = await fetch(`${API}${path}`, { ...opts, headers: h });
  if (r.status === 401 && S.refresh && await tryRefresh()) {
    h["Authorization"] = `Bearer ${S.token}`;
    r = await fetch(`${API}${path}`, { ...opts, headers: h });
  }
  return r;
}

async function tryRefresh() {
  try {
    const r = await fetch(`${API}/api/auth/refresh`, { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ refresh_token: S.refresh }) });
    if (r.ok) { const d = await r.json(); S.token = d.access_token; S.refresh = d.refresh_token || ""; localStorage.setItem("lx_t", S.token); if (S.refresh) localStorage.setItem("lx_r", S.refresh); return true; }
  } catch {}
  doLogout(); return false;
}

function toast(msg) { const t = $("toast"); t.textContent = msg; t.classList.remove("hidden"); setTimeout(() => t.classList.add("hidden"), 2400); }
function showLoading() { $("loading-text").textContent = I18N.t("processing"); $("loading").classList.remove("hidden"); }
function hideLoading() { $("loading").classList.add("hidden"); }
function fileToB64(f) { return new Promise(r => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.readAsDataURL(f); }); }
async function b64ToFile(b64) { try { const r = await fetch(b64); const b = await r.blob(); return new File([b], "canvas.png", { type: b.type || "image/png" }); } catch { return null; } }

/* ─── Auth ─── */
function doLogout() { S.token = ""; S.refresh = ""; S.user = null; localStorage.removeItem("lx_t"); localStorage.removeItem("lx_r"); $("auth").classList.remove("hidden"); $("app").classList.add("hidden"); }

async function handleAuth() {
  const u = $("au-user").value.trim(); const p = $("au-pass").value.trim();
  if (!u || !p) { $("au-err").textContent = I18N.t("toast_fill_fields"); return; }
  const ep = S.isRegister ? "/api/auth/register" : "/api/auth/login";
  showLoading();
  try {
    const r = await api(ep, { method: "POST", body: JSON.stringify({ username: u, password: p }) });
    const d = await r.json();
    hideLoading();
    if (d.ok && d.access_token) { S.token = d.access_token; S.refresh = d.refresh_token || ""; localStorage.setItem("lx_t", S.token); if (S.refresh) localStorage.setItem("lx_r", S.refresh); await initApp(); }
    else { $("au-err").textContent = d.error || I18N.t("toast_auth_failed"); }
  } catch { hideLoading(); $("au-err").textContent = I18N.t("toast_network_error"); }
}

async function fetchUser() {
  const r = await api("/api/auth/me");
  if (r.ok) { const d = await r.json(); if (d.ok) { S.user = d; $("uname-sm").textContent = d.username || "User"; $("avatar-sm").textContent = (d.username || "?")[0].toUpperCase(); if (d.balance != null) { $("balance-chip").textContent = `¥${Number(d.balance).toFixed(1)}`; $("balance-chip").style.display = ""; } } }
}

/* ─── Views ─── */
function switchView(name) {
  S.curView = name;
  QA(".view").forEach(v => v.classList.remove("active"));
  const v = document.getElementById(`view-${name}`);
  if (v) v.classList.add("active");
  QA(".tb-nav-btn").forEach(b => b.classList.toggle("active", b.dataset.view === name));
}

/* ─── Canvas & Filmstrip ─── */
function handleFiles(files) {
  for (const f of files) { if (!f.type.startsWith("image/")) continue; S.pendingFiles.push(f); fileToB64(f).then(b => { setCanvasImage(b); S.canvasImg = { file: f, b64: b }; addFilmstrip(b, f.name); }); }
}
function setCanvasImage(src) { const img = $("canvas-img"), e = $("canvas-empty"); if (src) { img.src = src; img.classList.remove("hidden"); e.classList.add("hidden"); } else { img.classList.add("hidden"); img.src = ""; e.classList.remove("hidden"); } }
function addFilmstrip(src, name) { S.filmstrip.push({ src, name, time: Date.now() }); renderFilmstrip(); }
function renderFilmstrip() {
  const tr = $("fs-track"); tr.innerHTML = "";
  S.filmstrip.forEach((f, i) => {
    const t = ce("img", "fs-thumb"); t.src = f.src; t.title = f.name;
    t.onclick = () => { setCanvasImage(f.src); S.canvasImg = { b64: f.src }; QA(".fs-thumb").forEach(x => x.classList.remove("active")); t.classList.add("active"); };
    if (i === S.filmstrip.length - 1) t.classList.add("active");
    tr.appendChild(t);
  });
}

/* ─── Feature Cards ─── */
function handleFeatureCard(action) {
  switchView("editor");
  if (action === "enhance") handleTool("enhance");
  else if (action === "rembg") handleTool("rembg");
  else if (action === "presets") {
    if (!S.canvasImg) { toast(I18N.t("toast_upload_first")); return; }
    const pr = $("panel-right"); if (pr) pr.scrollTop = pr.scrollHeight;
    toast(I18N.t("toast_select_preset"));
  }
  else if (action === "batch") $("batch-pick").click();
}

/* ─── Tools ─── */
async function handleTool(act) {
  if (!S.canvasImg) { toast(I18N.t("toast_upload_first")); return; }
  const endpoints = { rembg: "/api/rembg-remove", enhance: "/api/ffmpeg-enhance" };
  if (!endpoints[act]) return;
  const file = S.canvasImg.file || await b64ToFile(S.canvasImg.b64);
  if (!file) { toast(I18N.t("toast_image_unavailable")); return; }
  showLoading();
  try {
    const fd = new FormData(); fd.append("file", file);
    if (act === "rembg") fd.append("alpha_matting", "true");
    if (act === "enhance") { fd.append("mode", "super_resolution"); fd.append("scale", "2"); }
    const r = await api(endpoints[act], { method: "POST", body: fd });
    const d = await r.json();
    hideLoading();
    if (d.success && d.image) { setCanvasImage(d.image); S.canvasImg = { b64: d.image }; addFilmstrip(d.image, `${act}_result`); toast(I18N.t("toast_done")); fetchUser(); }
    else toast(d.error || I18N.t("toast_failed"));
  } catch { hideLoading(); toast(I18N.t("toast_network_error")); }
}

/* ─── Viewer ─── */
function openViewer(src) { $("viewer-img").src = src; $("viewer").classList.remove("hidden"); }
function closeViewer() { $("viewer").classList.add("hidden"); $("viewer-img").src = ""; }
function downloadImg(src) { const a = document.createElement("a"); a.href = src; a.download = `lingxi_${Date.now()}.png`; a.click(); }

/* ─── Export ─── */
async function exportImg() {
  if (!S.canvasImg) { toast(I18N.t("toast_no_image")); return; }
  const file = S.canvasImg.file || await b64ToFile(S.canvasImg.b64);
  if (!file) { toast(I18N.t("toast_image_unavailable")); return; }
  showLoading();
  try {
    const fd = new FormData(); fd.append("file", file); fd.append("format", "png");
    const r = await api("/api/export", { method: "POST", body: fd });
    const d = await r.json();
    hideLoading();
    if (d.success && d.image) { downloadImg(d.image); toast(I18N.t("toast_exported")); }
    else toast(d.error || I18N.t("toast_failed"));
  } catch { hideLoading(); toast(I18N.t("toast_network_error")); }
}

/* ─── Drag & Drop ─── */
function initDragDrop() {
  const canvas = $("canvas"); if (!canvas) return;
  canvas.addEventListener("dragover", e => { e.preventDefault(); e.stopPropagation(); });
  canvas.addEventListener("drop", e => { e.preventDefault(); e.stopPropagation(); const files = [...e.dataTransfer.files].filter(f => f.type.startsWith("image/")); if (files.length) handleFiles(files); });
  const dash = $("view-dashboard"); if (!dash) return;
  dash.addEventListener("dragover", e => { e.preventDefault(); });
  dash.addEventListener("drop", e => { e.preventDefault(); const files = [...e.dataTransfer.files].filter(f => f.type.startsWith("image/")); if (files.length) { handleFiles(files); switchView("editor"); } });
}

/* ─── Presets (from API) ─── */
let PRESETS = [];
async function loadPresets() {
  try {
    const r = await api("/api/presets");
    const d = await r.json();
    if (d.success && d.presets) { PRESETS = d.presets; buildPresets(); }
  } catch {}
}
function buildPresets() {
  const g = $("presets-grid"); if (!g) return; g.innerHTML = "";
  PRESETS.forEach(p => {
    const d = ce("div", "ep-preset-item");
    d.title = p.description || p.name;
    d.innerHTML = `<span class="ep-preset-icon">${p.icon || "🎨"}</span><span class="ep-preset-name">${p.name}</span>`;
    d.onclick = () => applyPreset(p.id, p.name);
    g.appendChild(d);
  });
}
async function applyPreset(presetId, presetName) {
  if (!S.canvasImg) { toast(I18N.t("toast_upload_first")); return; }
  const file = S.canvasImg.file || await b64ToFile(S.canvasImg.b64);
  if (!file) { toast(I18N.t("toast_image_unavailable")); return; }
  showLoading();
  toast(I18N.t("toast_applying_preset") + presetName);
  try {
    const fd = new FormData(); fd.append("file", file); fd.append("preset_id", presetId);
    const r = await api("/api/preset-apply", { method: "POST", body: fd });
    const d = await r.json();
    hideLoading();
    if (d.success && d.image) { setCanvasImage(d.image); S.canvasImg = { b64: d.image }; addFilmstrip(d.image, `preset_${presetId}`); toast(I18N.t("toast_done")); fetchUser(); }
    else toast(d.error || I18N.t("toast_failed"));
  } catch { hideLoading(); toast(I18N.t("toast_network_error")); }
}

/* ─── Batch Processing ─── */
async function handleBatch(files) {
  if (!files || !files.length) return;
  showLoading();
  toast(I18N.t("toast_batch_processing"));
  try {
    const fd = new FormData();
    [...files].forEach(f => fd.append("files", f));
    const r = await api("/api/batch-rembg", { method: "POST", body: fd });
    const d = await r.json();
    hideLoading();
    if (d.success) {
      toast(I18N.t("toast_batch_done").replace("{ok}", d.success_count).replace("{total}", d.total));
      fetchUser();
      if (d.results) {
        d.results.forEach(res => {
          if (res.success && res.image) { addFilmstrip(res.image, res.filename); }
        });
        const firstOk = d.results.find(r => r.success && r.image);
        if (firstOk) { setCanvasImage(firstOk.image); S.canvasImg = { b64: firstOk.image }; }
      }
    } else toast(d.error || I18N.t("toast_failed"));
  } catch { hideLoading(); toast(I18N.t("toast_network_error")); }
}

/* ─── Init ─── */
async function initApp() { $("auth").classList.add("hidden"); $("app").classList.remove("hidden"); await fetchUser(); switchView("dashboard"); await loadPresets(); initDragDrop(); }

/* ─── Language Switcher ─── */
function initLangSwitcher() {
  const btn = $("lang-btn"); const drop = $("lang-drop");
  btn.onclick = e => { e.stopPropagation(); drop.classList.toggle("open"); };
  document.addEventListener("click", () => drop.classList.remove("open"));
  QA(".lang-opt").forEach(o => {
    o.onclick = e => { e.stopPropagation(); I18N.setLang(o.dataset.lang); drop.classList.remove("open"); };
  });
  // Initial state
  const lb = $("lang-label");
  const flags = { "zh-CN": "中", en: "EN", ja: "日", ko: "한" };
  lb.textContent = flags[I18N.lang] || I18N.lang.toUpperCase();
  QA(".lang-opt").forEach(o => o.classList.toggle("active", o.dataset.lang === I18N.lang));
}

/* ─── Events ─── */
function bindEvents() {
  // Auth
  $("au-go").onclick = handleAuth;
  $("au-toggle").onclick = () => { S.isRegister = !S.isRegister; $("au-go").textContent = S.isRegister ? I18N.t("create_account") : I18N.t("enter_darkroom"); $("au-toggle").textContent = S.isRegister ? I18N.t("sign_in") : I18N.t("create_account"); $("au-err").textContent = ""; };
  $("au-pass").addEventListener("keypress", e => { if (e.key === "Enter") handleAuth(); });
  // Title bar nav
  QA(".tb-nav-btn").forEach(b => b.onclick = () => switchView(b.dataset.view));
  // Logout
  $("tb-user").onclick = () => doLogout();
  // Feature cards
  QA(".feature-card").forEach(c => c.onclick = () => handleFeatureCard(c.dataset.action));
  // Upload buttons
  $("btn-hero-upload").onclick = () => $("file-pick").click();
  $("btn-upload").onclick = () => $("file-pick").click();
  // File picker
  $("file-pick").onchange = e => { if (e.target.files.length) { handleFiles(e.target.files); switchView("editor"); e.target.value = ""; } };
  // Batch picker
  $("batch-pick").onchange = e => { if (e.target.files.length) { handleBatch(e.target.files); switchView("editor"); e.target.value = ""; } };
  // Editor tools
  QA(".et-btn").forEach(b => b.onclick = () => { const act = b.dataset.act; if (act) handleTool(act); if (b.dataset.tool) { QA(".et-btn[data-tool]").forEach(x => x.classList.remove("active")); b.classList.add("active"); } });
  // Export
  $("btn-export").onclick = exportImg;
  // Send
  $("dock-input").addEventListener("input", () => { $("btn-send").disabled = !$("dock-input").value.trim(); });
  $("dock-input").addEventListener("keypress", e => { if (e.key === "Enter") { const t = $("dock-input").value.trim(); if (t) handleTool("enhance"); } });
  $("btn-send").onclick = () => { const t = $("dock-input").value.trim(); if (t) handleTool("enhance"); };
  // Viewer
  $("viewer-close").onclick = closeViewer;
  $("viewer-bg").onclick = closeViewer;
  document.addEventListener("keydown", e => { if (e.key === "Escape") { closeViewer(); } });
  // Gallery filters
  QA(".gf-btn").forEach(b => b.onclick = () => { QA(".gf-btn").forEach(x => x.classList.remove("active")); b.classList.add("active"); });
}

/* ─── Boot ─── */
(async () => {
  I18N.init();
  await loadConfig();
  bindEvents();
  initLangSwitcher();
  if (S.token) { try { const r = await api("/api/auth/me"); if (r.ok && (await r.json()).ok) await initApp(); else doLogout(); } catch { doLogout(); } }
})();
