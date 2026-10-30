import { create, id, codec } from '@zos/media'
import { readFileSync, writeFileSync, statSync, rmSync } from '@zos/fs'
import { setPageBrightTime } from '@zos/display'
import { createWidget, widget, prop, align, deleteWidget } from '@zos/ui'
import { getDiskInfo } from '@zos/device'
import { log as Logger } from '@zos/utils'
import { showToast } from '@zos/interaction'
import { onGesture, offGesture, GESTURE_LEFT, GESTURE_RIGHT } from '@zos/interaction'
import { BasePage } from '@zeppos/zml/base-page'
import { start as startBgService, stop as stopBgService } from '@zos/app-service'
import { queryPermission, requestPermission } from '@zos/app'

// Голосовые заметки v0.2 — редизайн (Qwen3.8 Max UX).
// Тракт: запись OPUS → base64 → Messaging → side-service → релей:8080 → Nexara → LLM.
// Заметки в data://notes.json (queued|sending|done|error). Аудио удаляется только после расшифровки.
// UI: главный экран ( большая ● запись) ↔ свайп влево → список заметок. Свайп блокируется во время записи.

const log = Logger.getLogger('voice-notes')

const NOTES_FILE = 'data://notes.json'
const B64 = 'ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/'
const MAX_REC_S = 120
const MIN_FREE = 60 * 1024 * 1024
const CHUNK_CHARS = 2048

// Палитра B (бренд «Поток»): синие карточки, акцент #2C6FE8, зелёный ТОЛЬКО для записи
const C = {
  BG: 0x000000,
  SURFACE: 0x16233d,
  CARD: 0x1b2a4a,
  PRIMARY: 0xffffff,
  SECONDARY: 0x8e939a,
  ACCENT: 0x2c6fe8,
  REC: 0x00e676, // запись
  STOP: 0xff1744, // стоп
  SYNC: 0x2c6fe8, // синхронизация = брендовый синий
  ACTION: 0xffffff,
}
// Часы поддерживают Google Emoji — используем настоящие emoji.
// Топу раньше давали НЕ-эмодзи символы (☐ ☑  ⚠ без VS16) — их в шрифте нет.
const TYPE_EMOJI = { note: '📝', idea: '💡', list: '📋', reminder: '⏰', task: '✅' }

// pending — телефон принял (state=PENDING/PROCESSING на телефоне), STT/LLM идёт
// асинхронно. Транскрипт приедет через SYNC → статус станет done.
const ST_WORD = { queued: 'в очереди', sending: 'отправка…', pending: 'обработка…', done: 'готово', error: 'ОШИБКА' }

let pageInstance = null
let notes = []

let rec = null
let recording = false
let startedAt = 0
let recId = 0
let recFile = ''
let tickTimer = null

let draining = false
let drainGen = 0
let relayOk = null
let probeTimer = null
let lastErrorRetry = 0
let detailIdx = -1
let ov = []

let wStatusDot, wQueueBadge, wTimer, wStatus, wBtnRec, wBtnNotes
let wDotMain, wDotList // точки пейджера

let listOpen = false
let nlOv = []
let wNlList, wNlStatus, wNlSend, wNlDotMain, wNlDotList

let deleteArmed = false
let deleteArmTimer = null
let reopenListOnClose = false

// ---------- мелкие утилиты ----------

function errText(e) {
  return (e && e.message ? e.message : String(e)).slice(0, 60)
}

function pad2(n) {
  return (n < 10 ? '0' : '') + n
}

function fmtTime(at) {
  const d = new Date(at)
  return pad2(d.getHours()) + ':' + pad2(d.getMinutes())
}

function kb(size) {
  return Math.round(size / 1024) + 'КБ'
}

function freeSpace() {
  try {
    if (typeof getDiskInfo === 'function') {
      const d = getDiskInfo()
      return d && d.free ? d.free : 0
    }
  } catch (e) {}
  return 0
}

function statusWord(n) {
  return ST_WORD[n.status] || String(n.status)
}

// Есть ли у заметки событие в календаре (пришло с телефона после LLM+Calendar).
function hasCalendar(n) {
  return !!(n.calId || n.calEventId)
}

function typeEmoji(n) {
  return TYPE_EMOJI[n.type] || '📝'
}

function wrapText(t, max) {
  const out = []
  const paras = String(t || '').split('\n')
  for (let pi = 0; pi < paras.length; pi++) {
    const words = paras[pi].split(' ')
    let line = ''
    for (let wi = 0; wi < words.length; wi++) {
      let w = words[wi]
      while (w.length > max) {
        if (line) {
          out.push(line)
          line = ''
        }
        out.push(w.slice(0, max))
        w = w.slice(max)
      }
      if (!line) line = w
      else if ((line + ' ' + w).length <= max) line += ' ' + w
      else {
        out.push(line)
        line = w
      }
    }
    out.push(line)
  }
  if (!out.length) out.push('')
  return out
}

const B64_SLICE = 3072

function abToBase64(bytes) {
  const len = bytes.length
  const parts = []
  for (let s = 0; s < len; s += B64_SLICE) {
    const end = Math.min(s + B64_SLICE, len)
    const groups = ((end - s + 2) / 3) | 0
    const arr = new Array(groups * 4)
    let j = 0
    for (let i = s; i < end; i += 3) {
      const rem = end - i
      const b0 = bytes[i]
      const b1 = rem > 1 ? bytes[i + 1] : 0
      const b2 = rem > 2 ? bytes[i + 2] : 0
      arr[j++] = B64[b0 >> 2]
      arr[j++] = B64[((b0 & 3) << 4) | (b1 >> 4)]
      arr[j++] = rem > 1 ? B64[((b1 & 15) << 2) | (b2 >> 6)] : '='
      arr[j++] = rem > 2 ? B64[b2 & 63] : '='
    }
    parts.push(arr.join(''))
  }
  return parts.join('')
}

// ---------- хранилище заметок ----------

let syncCursor = 0 // курсор /sync: последний обработанный updated_at (next_since)

function loadNotes() {
  try {
    const s = readFileSync({ path: NOTES_FILE, options: { encoding: 'utf8' } })
    if (!s) return []
    const d = JSON.parse(s)
    if (d && typeof d === 'object' && typeof d.syncSince === 'number') {
      syncCursor = d.syncSince
    }
    const arr = Array.isArray(d) ? d : (d && d.items) || []
    // Recovery: note со статусом 'sending' прервалась на середине отправки при
    // прошлом запуске — сбрасываем в 'queued', чтобы drain переотправил.
    // 'pending' не трогаем — телефон ещё обрабатывает, транскрипт приедет через SYNC.
    return arr.filter((n) => n && n.id).map((n) => {
      if (n.status === 'sending') n.status = 'queued'
      return n
    })
  } catch (e) {
    log.log('loadNotes err ' + e)
    return []
  }
}

function saveNotes() {
  try {
    writeFileSync({
      path: NOTES_FILE,
      data: JSON.stringify({ ver: 1, items: notes, syncSince: syncCursor }),
      options: { encoding: 'utf8' },
    })
    return true
  } catch (e) {
    log.log('saveNotes err ' + e)
    return false
  }
}

// ---------- рендер главного экрана ----------

function subLine(n) {
  if (n.status === 'done') {
    var txt = n.summary || (n.text ? String(n.text).slice(0, 44) : '(тишина)')
    if (hasCalendar(n)) txt += ' [календарь]'
    return txt
  }
  if (n.status === 'sending') return 'отправка…'
  // v3.4: телефон принял, STT/LLM идёт асинхронно — ждём транскрипт через SYNC.
  if (n.status === 'pending') return 'обработка…'
  if (n.status === 'error') return '! ' + String(n.err || 'ошибка').slice(0, 40)
  return n.dur + 'с · ' + kb(n.size) + ' · ждёт'
}

function statusDotColor() {
  if (recording) return C.REC
  if (relayOk === true) return C.SYNC
  if (relayOk === false) return C.STOP
  return C.SECONDARY
}

function statusText() {
  const queued = notes.filter((n) => n.status === 'queued').length
  const hasSending = notes.some((n) => n.status === 'sending')
  if (recording) return 'Идёт запись…'
  if (draining || hasSending) return 'Отправка… ' + queued + ' в очереди'
  if (relayOk === true) return queued ? queued + ' в очереди' : 'Готово'
  if (relayOk === false) return 'Нет связи · ' + queued + ' ждёт'
  return 'Проверяю связь…'
}

function render() {
  if (wStatusDot) wStatusDot.setProperty(prop.MORE, { color: statusDotColor() })

  const queued = notes.filter((n) => n.status === 'queued').length
  if (wQueueBadge) {
    wQueueBadge.setProperty(prop.MORE, {
      text: queued > 0 ? String(queued) : '',
    })
  }

  if (wTimer) {
    let t = '00:00'
    if (recording) {
      const s = Math.round((Date.now() - startedAt) / 1000)
      t = pad2(Math.floor(s / 60)) + ':' + pad2(s % 60)
    }
    wTimer.setProperty(prop.MORE, { text: t })
  }

  if (wStatus) {
    wStatus.setProperty(prop.MORE, {
      text: statusText(),
      color: recording ? C.REC : C.SECONDARY,
    })
  }

  if (wDotMain && wDotList) {
    wDotMain.setProperty(prop.MORE, { color: listOpen ? 0x4a4a4a : C.ACCENT })
    wDotList.setProperty(prop.MORE, { color: listOpen ? C.ACCENT : 0x4a4a4a })
  }
}

// ---------- запись ----------

function setRecBtn() {
  if (!wBtnRec) return
  if (recording) {
    wBtnRec.setProperty(prop.MORE, {
      x: 125, y: 224, w: 140, h: 140, radius: 70,
      text: '■', text_size: 52,
      normal_color: C.STOP, press_color: 0xd50000,
    })
  } else {
    wBtnRec.setProperty(prop.MORE, {
      x: 125, y: 224, w: 140, h: 140, radius: 70,
      text: '●', text_size: 52,
      normal_color: C.ACCENT, press_color: 0x1e5bc6,
    })
  }
}

function startRec() {
  if (recording || detailIdx >= 0 || listOpen) return

  const free = freeSpace()
  if (free && free < MIN_FREE) {
    showToast({ content: 'мало места: ' + Math.round(free / 1048576) + 'МБ' })
    wStatus.setProperty(prop.MORE, { text: 'Мало места на часах' })
    return
  }

  try {
    if (!rec) rec = create(id.RECORDER)
    if (!rec) {
      // Возможно, разрешение на микрофон ещё не выдано — запросим и повторим.
      wStatus.setProperty(prop.MORE, { text: 'Запрашиваю микрофон…' })
      ensureMicPermission((ok) => {
        if (!ok) {
          showToast({ content: 'нет доступа к микрофону' })
          wStatus.setProperty(prop.MORE, { text: 'Нет доступа к микрофону' })
          return
        }
        // Разрешение получено — пробуем ещё раз.
        try {
          rec = create(id.RECORDER)
          if (rec) startRec()
          else {
            showToast({ content: 'микрофон занят' })
            wStatus.setProperty(prop.MORE, { text: 'Микрофон занят' })
          }
        } catch (e2) {
          log.log('retry create err ' + errText(e2))
          showToast({ content: 'ошибка микрофона' })
        }
      })
      return
    }
    recId = Date.now()
    recFile = 'data://note_' + recId + '.opus'
    rec.setFormat(codec.OPUS, { target_file: recFile })
    rec.start()
    recording = true
    startedAt = Date.now()
    setRecBtn()
    render()
    showToast({ content: 'запись…' })
    if (tickTimer) clearInterval(tickTimer)
    tickTimer = setInterval(tick, 1000)
  } catch (e) {
    recording = false
    if (rec) {
      try { rec.stop() } catch (e2) {}
      rec = null
    }
    log.log('startRec err ' + e)
    wStatus.setProperty(prop.MORE, { text: '!старт: ' + errText(e) })
    setRecBtn()
  }
}

function tick() {
  const s = Math.round((Date.now() - startedAt) / 1000)
  if (wStatus) wStatus.setProperty(prop.MORE, { text: 'Идёт запись…' })
  if (wTimer) {
    wTimer.setProperty(prop.MORE, { text: pad2(Math.floor(s / 60)) + ':' + pad2(s % 60) })
  }

  if (s >= MAX_REC_S) {
    stopRec()
    showToast({ content: 'автостоп: ' + MAX_REC_S + 'c' })
    return
  }
  if (s % 10 === 0) {
    const free = freeSpace()
    if (free && free < MIN_FREE) {
      stopRec()
      showToast({ content: 'автостоп: мало места' })
      return
    }
  }
}

function stopRec() {
  if (!recording) return
  if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
  recording = false
  try {
    if (rec) rec.stop()
  } catch (e) {
    log.log('stopRec err ' + e)
  }
  setRecBtn()

  const nid = recId
  const file = recFile
  const at = startedAt
  const dur = Math.max(1, Math.round((Date.now() - startedAt) / 1000))

  setTimeout(() => {
    let size = 0
    try {
      const st = statSync({ path: file })
      size = st ? st.size : 0
    } catch (e) {}

    if (!size) {
      wStatus.setProperty(prop.MORE, { text: 'Запись пустая — не сохранена' })
      showToast({ content: 'пустая запись' })
      render()
      return
    }

    notes.unshift({
      id: nid, at: at, dur: dur, size: size,
      status: 'queued', file: file, text: null, err: null,
      type: 'note', summary: '', dueIso: null,
    })
    if (detailIdx >= 0) detailIdx++
    saveNotes()
    render()
    showToast({ content: 'сохранено · ' + kb(size) })
    drain()
  }, 400)
}

// ---------- очередь отправки ----------

function classifyErr(s) {
  return String(s).indexOf('relay HTTP') === 0 ? 'stt' : 'transport'
}

async function sendNote(n) {
  if (!pageInstance) return { kind: 'transport', err: 'нет страницы' }

  let buf
  try {
    buf = readFileSync({ path: n.file })
    if (!buf) return { kind: 'stt', err: 'файл не читается (' + kb(n.size) + ')' }
  } catch (e) {
    return { kind: 'stt', err: 'чтение: ' + errText(e) + ' (' + kb(n.size) + ')' }
  }

  let bytes
  try {
    bytes = new Uint8Array(buf)
  } catch (e) {
    return { kind: 'stt', err: 'байты: ' + errText(e) + ' (' + kb(n.size) + ')' }
  }
  buf = null

  let b64
  try {
    b64 = abToBase64(bytes)
    bytes = null
  } catch (e) {
    return { kind: 'stt', err: 'base64: ' + errText(e) + ' (' + kb(n.size) + ')' }
  }
  if (!b64.length) return { kind: 'stt', err: 'файл пуст (' + kb(n.size) + ')' }

  const total = Math.ceil(b64.length / CHUNK_CHARS)
  const name = 'note_' + n.id + '.opus'
  log.log('send note ' + n.id + ': ' + b64.length + ' b64 / ' + total + ' chunks')

  for (let idx = 0; idx < total; idx++) {
    const data = b64.slice(idx * CHUNK_CHARS, (idx + 1) * CHUNK_CHARS)
    let r
    try {
      const p = pageInstance.request({
        method: 'STT_CHUNK',
        params: { id: n.id, idx: idx, total: total, name: name, language: 'ru', data: data },
      })
      const to = new Promise((_, rej) => setTimeout(() => rej(new Error('chunk timeout 15s')), 15000))
      r = await Promise.race([p, to])
    } catch (e) {
      return { kind: 'transport', err: errText(e) }
    }
    if (!r) return { kind: 'transport', err: 'пустой ответ side (chunk ' + idx + ')' }
    if (r.error) return { kind: classifyErr(r.error), err: String(r.error) }
    // v3.4: телефон принял заметку в асинхронную обработку — STT/LLM идёт в фоне.
    // Транскрипт приедет позже через SYNC; здесь только подтверждаем приём.
    if (r.accepted === true) return { kind: 'accepted', id: r.id }
    if (typeof r.text === 'string') return { kind: 'done', text: r.text || '(тишина)' }
  }
  return { kind: 'transport', err: 'side не собрал транскрипт' }
}

function drain() {
  if (draining) return
  const n = notes.find((x) => x.status === 'queued')
  if (!n) {
    render()
    if (detailIdx >= 0) renderDetail()
    return
  }

  const gen = ++drainGen
  draining = true
  n.status = 'sending'
  saveNotes()
  render()
  if (detailIdx >= 0) renderDetail()

  sendNote(n).then((res) => {
    if (gen !== drainGen) return
    draining = false

    if (res.kind === 'done') {
      n.status = 'done'
      n.text = res.text
      n.err = null
      try {
        rmSync({ path: n.file })
      } catch (e) {}
      relayOk = true
      log.log('note ' + n.id + ' done: ' + String(res.text).slice(0, 80))
      saveNotes()
      render()
      if (detailIdx >= 0) renderDetail()
      showToast({ content: 'расшифровано' })
      setTimeout(drain, 300)
      return
    }

    // v3.4: телефон принял заметку в асинхронную обработку. Часы не ждут STT/LLM —
    // показываем «обработка…», транскрипт приедет через SYNC. Файл НЕ удаляем:
    // единственная копия звука у телефона в cacheDir (Android может вычистить),
    // а при терминальной ошибке часы смогут переотправить.
    if (res.kind === 'accepted') {
      n.status = 'pending'
      n.text = ''
      n.err = null
      relayOk = true
      log.log('note ' + n.id + ' accepted (pending), awaiting SYNC')
      saveNotes()
      render()
      if (detailIdx >= 0) renderDetail()
      showToast({ content: 'отправлено' })
      setTimeout(drain, 300)
      return
    }

    if (res.kind === 'stt') {
      n.status = 'error'
      n.err = res.err
      relayOk = true
      saveNotes()
      render()
      if (detailIdx >= 0) renderDetail()
      showToast({ content: 'ошибка: ' + String(res.err).slice(0, 30) })
      setTimeout(drain, 300)
      return
    }

    n.status = 'queued'
    relayOk = false
    saveNotes()
    render()
    if (detailIdx >= 0) renderDetail()
    wStatus.setProperty(prop.MORE, { text: 'Нет связи: ' + String(res.err).slice(0, 36) })
    log.log('drain stop: ' + res.err)
  })
}

function probeRelay(cb) {
  let done = false
  const finish = (ok) => {
    if (done) return
    done = true
    relayOk = ok
    render()
    if (ok) {
      drain()
      retryErrorNotes()
    }
    if (cb) cb(ok)
  }
  if (!pageInstance) {
    finish(false)
    return
  }
  try {
    const p = pageInstance.request({ method: 'PING', params: {} })
    const timeout = new Promise((_, rej) => setTimeout(() => rej(new Error('timeout 4s')), 4000))
    Promise.race([p, timeout]).then(
      (r) => finish(!!(r && r.relay && r.relay.pong)),
      () => finish(false)
    )
  } catch (e) {
    finish(false)
  }
}

let syncing = false
const SYNC_PAGE = 40 // порция заметок за один /sync-запрос (лимит телефона ≤200)
function syncNotesInnerSince(since) {
  // Докачиваем страницами, пока телефон сообщает has_more: true.
  function page(cur) {
    const p = pageInstance.request({ method: 'SYNC', params: { since: String(cur), limit: String(SYNC_PAGE) } })
    const timeout = new Promise((_, rej) => setTimeout(() => rej(new Error('sync timeout')), 8000))
    return Promise.race([p, timeout]).then((r) => {
      if (!r || !Array.isArray(r.notes)) return Promise.resolve(false)
      let changed = false
      // O(n) merge: строим карту по id один раз.
      const byId = {}
      for (const n of notes) if (n && n.id != null) byId[n.id] = n
      for (const rn of r.notes) {
        if (!rn || !rn.id) continue
        const local = byId[rn.id]
        if (local) {
          if (rn.text && rn.text !== local.text) { local.text = rn.text; changed = true }
          if (rn.type && rn.type !== local.type) { local.type = rn.type; changed = true }
          if (rn.summary && rn.summary !== local.summary) { local.summary = rn.summary; changed = true }
          if (rn.due_iso && rn.due_iso !== local.dueIso) { local.dueIso = rn.due_iso; changed = true }
          if (rn.calId && rn.calId !== local.calId) { local.calId = rn.calId; changed = true }
          if (rn.items) { local.items = rn.items; changed = true }
          // v3.4: телефон закончил обработку → done. Удаляем локальный аудиофайл
          // (держали с accepted — единственная копия была у телефона в cacheDir).
          if (rn.status === 'done' && local.status !== 'done') {
            local.status = 'done'
            local.err = null
            if (local.file) {
              try { rmSync({ path: local.file }) } catch (e) {}
              local.file = null
            }
            changed = true
          }
          // v3.4: терминальная ошибка на телефоне (retry >= MAX) → показываем причину,
          // иначе часы висят на «обработка…» вечно. Файл оставляем — можно переотправить.
          if (rn.status === 'error' && local.status !== 'error') {
            local.status = 'error'
            local.err = rn.last_error || 'не удалось распознать'
            changed = true
          }
        } else {
          notes.unshift({
            id: rn.id, at: rn.ts || rn.id, dur: rn.dur || 0, size: rn.size || 0,
            status: rn.status || 'done', file: null, text: rn.text || null, err: null,
            type: rn.type || 'note', summary: rn.summary || '',
            dueIso: rn.due_iso || null, calId: rn.calId || null,
            items: rn.items || null,
          })
          changed = true
        }
      }
      if (changed) {
        saveNotes()
        render()
      }
      // Движение курсора: next_since приходит от телефона (max(updated)+1).
      const next = typeof r.next_since === 'number' ? r.next_since : null
      if (next != null && next > syncCursor) syncCursor = next
      const more = !!(r.has_more && next != null)
      if (more) return page(next) // докачиваем следующую страницу
      syncCursor = Math.max(syncCursor, cur) // подстраховка монотонности
      saveNotes()
      return Promise.resolve(changed)
    })
  }
  return page(since).then((changed) => {
    if (listOpen) renderNotesList()
    if (detailIdx >= 0) renderDetail()
    log.log('sync: done, cursor=' + syncCursor)
    return changed
  })
}

function syncNotes() {
  if (!pageInstance || syncing) return
  syncing = true
  try {
    const cur = syncCursor
    syncNotesInnerSince(cur).then(
      () => { syncing = false },
      () => { syncing = false }
    )
  } catch (e) { syncing = false }
}

function hasActionable() {
  for (let i = 0; i < notes.length; i++) {
    const n = notes[i]
    if (n.status === 'queued') return true
    if (n.status === 'error' && Date.now() - lastErrorRetry > 60000) return true
  }
  return false
}

function retryErrorNotes() {
  if (Date.now() - lastErrorRetry < 60000) return
  let touched = false
  notes.forEach((n) => {
    if (n.status === 'error') {
      n.status = 'queued'
      n.err = null
      touched = true
    }
  })
  if (touched) {
    lastErrorRetry = Date.now()
    saveNotes()
    render()
    drain()
  }
}

function startAutoProbe() {
  if (probeTimer) clearInterval(probeTimer)
  probeTimer = setInterval(() => {
    if (recording || draining) return
    if (relayOk !== true || notes.some((n) => n.status === 'queued' || n.status === 'error')) {
      probeRelay()
    }
    syncNotes()
  }, 45000)
}

function onSendBtn() {
  if (detailIdx >= 0) return
  if (recording) {
    showToast({ content: 'сначала стоп' })
    return
  }
  const queued = notes.filter((n) => n.status === 'queued').length
  if (!queued) {
    if (relayOk === false) {
      probeRelay((ok) => {
        if (!ok) showToast({ content: 'нет связи' })
      })
    } else {
      showToast({ content: 'в очереди пусто' })
    }
    return
  }
  if (relayOk === false) {
    probeRelay((ok) => {
      if (!ok) showToast({ content: 'нет связи' })
    })
  } else {
    drain()
  }
}

// ---------- просмотр расшифровки (оверлей) ----------

function detailActionText(n) {
  if (n.status === 'done') return deleteArmed ? 'Точно?' : 'Удалить'
  if (n.status === 'error') return 'Повтор'
  if (n.status === 'pending') return ''  // обработка на телефоне — действий нет
  return 'Отправить'
}

function renderDetail() {
  if (detailIdx < 0 || !wOvTitle) return
  const n = notes[detailIdx]
  if (!n) {
    closeDetail()
    return
  }

  wOvTitle.setProperty(prop.MORE, {
    text: fmtTime(n.at) + ' · ' + statusWord(n) + ' · ' + n.dur + 'с',
  })

  let lines
  if (n.status === 'done') {
    const head = []
    if (n.summary) head.push(n.summary, '')
    if (hasCalendar(n)) head.push('[в календаре]', '')
    if (n.type === 'list' && n.items && n.items.length) {
      n.items.forEach((it) => {
        if (typeof it === 'object' && it !== null) {
          head.push((it.d ? '[v] ' : '[ ] ') + (it.t || ''))
        } else {
          head.push('[ ] ' + it)
        }
      })
      head.push('')
    }
    if (n.text) {
      head.push('— текст —')
      wrapText(n.text, 26).forEach((l) => head.push(l))
    }
    lines = head.length ? head : ['(пусто)']
  } else if (n.status === 'sending') {
    lines = ['Отправка…', '', 'Запись: ' + n.dur + 'с · ' + kb(n.size)]
  } else if (n.status === 'pending') {
    // v3.4: телефон принял, STT/LLM идёт асинхронно. Транскрипт приедет через SYNC.
    lines = [
      'Обработка…',
      'Телефон расшифровывает',
      'и разбирает мысль.',
      '',
      'Результат появится',
      'сам — через синхронизацию.',
    ]
  } else if (n.status === 'queued') {
    lines = [
      'Запись на часах.',
      n.dur + 'с · ' + kb(n.size),
      '',
      'Отправится сама,',
      'когда будет связь.',
    ]
  } else {
    lines = ['! Ошибка отправки:'].concat(wrapText(n.err || '', 24), ['', 'Нажмите повтор.'])
  }
  wOvList.setProperty(prop.UPDATE_DATA, {
    data_array: lines.map((t) => ({ line: t })),
    data_count: lines.length,
    data_type_config: [{ start: 0, end: Math.max(0, lines.length - 1), type_id: 1 }],
    data_type_config_count: 1,
    on_page: 1,
  })

  wOvMsg.setProperty(prop.MORE, {
    text: relayOk === false && n.status !== 'done' ? 'нет связи' : '',
  })

  // Правая кнопка: если done — ведерко (красная), если ошибка — повтор
  wOvAct.setProperty(prop.MORE, {
    x: 200, y: 378, w: 178, h: 60,
    text: n.status === 'done' ? (deleteArmed ? 'Точно?' : '✕') : detailActionText(n),
    text_size: deleteArmed ? 22 : 36,
    normal_color: n.status === 'done' ? C.STOP : C.ACCENT,
    press_color: 0xd50000,
  })
}

function openDetail(i) {
  if (detailIdx >= 0) return
  const n = notes[i]
  if (!n) return
  reopenListOnClose = listOpen
  if (listOpen) closeNotesList()
  detailIdx = i
  deleteArmed = false
  ov = []

  ov.push(createWidget(widget.FILL_RECT, { x: 0, y: 0, w: 390, h: 450, color: C.BG }))

  // Мета вверху крупнее и выравнивание влево
  wOvTitle = createWidget(widget.TEXT, {
    x: 16, y: 68, w: 358, h: 32,
    text: '', text_size: 24, color: 0x9fb3d9, align_h: align.LEFT,
  })
  ov.push(wOvTitle)

  // Список строк с увеличенным шрифтом 24sp (было 20)
  wOvList = createWidget(widget.SCROLL_LIST, {
    x: 8, y: 104, w: 374, h: 266,
    item_space: 3,
    item_config: [
      {
        type_id: 1,
        item_height: 34,
        item_bg_color: C.BG,
        item_bg_radius: 6,
        text_view: [{ x: 10, y: 2, w: 354, h: 30, key: 'line', color: C.PRIMARY, text_size: 24, align_h: align.LEFT }],
        text_view_count: 1,
      },
    ],
    item_config_count: 1,
    data_array: [{ line: '…' }],
    data_count: 1,
    data_type_config: [{ start: 0, end: 0, type_id: 1 }],
    data_type_config_count: 1,
  })
  ov.push(wOvList)

  wOvMsg = createWidget(widget.TEXT, {
    x: 12, y: 372, w: 366, h: 0,
    text: '', text_size: 0, color: 0xff8a80, align_h: align.CENTER_H,
  })
  ov.push(wOvMsg)

  // Кнопка Назад: крупная (60px), стрелка <
  ov.push(
    createWidget(widget.BUTTON, {
      x: 12, y: 378, w: 176, h: 60, radius: 16,
      normal_color: C.CARD, press_color: 0x14203a,
      text: '<', text_size: 38,
      click_func: () => closeDetail(),
    })
  )

  // Кнопка Действие/Удалить: крупная (60px), красный фон, крестик ✕
  wOvAct = createWidget(widget.BUTTON, {
    x: 200, y: 378, w: 178, h: 60, radius: 16,
    normal_color: C.STOP, press_color: 0xd50000,
    text: '✕', text_size: 36,
    click_func: () => onDetailAct(),
  })
  ov.push(wOvAct)

  renderDetail()
}

function closeDetail() {
  for (let i = 0; i < ov.length; i++) {
    try {
      deleteWidget(ov[i])
    } catch (e) {}
  }
  ov = []
  wOvTitle = null
  wOvList = null
  wOvMsg = null
  wOvAct = null
  detailIdx = -1
  deleteArmed = false
  if (deleteArmTimer) {
    clearTimeout(deleteArmTimer)
    deleteArmTimer = null
  }
  // Если пришли из списка — вернёмся в него, а не на главный.
  if (reopenListOnClose) {
    reopenListOnClose = false
    openNotesList()
    render()
  }
}

function onDetailAct() {
  const n = notes[detailIdx]
  if (!n) {
    closeDetail()
    return
  }

  if (n.status === 'done') {
    if (!deleteArmed) {
      // первое нажатие — вооружить, второе в течение 3с — удалить
      deleteArmed = true
      renderDetail()
      deleteArmTimer = setTimeout(() => {
        deleteArmed = false
        if (detailIdx >= 0) renderDetail()
      }, 3000)
      return
    }
    notes.splice(detailIdx, 1)
    saveNotes()
    closeDetail()
    render()
    showToast({ content: 'удалено' })
    return
  }
  if (n.status === 'sending') {
    showToast({ content: 'уже отправляется' })
    return
  }
  if (n.status === 'pending') {
    // Телефон обрабатывает асинхронно — пересылать снова бессмысленно (dedup на
    // телефоне вернёт {accepted} без работы). Просто информируем.
    showToast({ content: 'обрабатывается на телефоне' })
    return
  }

  n.status = 'queued'
  n.err = null
  saveNotes()
  render()
  renderDetail()
  if (relayOk === false) {
    probeRelay((ok) => {
      if (!ok) showToast({ content: 'нет связи' })
    })
  } else {
    drain()
  }
}

// ---------- экран заметок (свайп влево с главного) ----------

function renderNotesList() {
  if (!listOpen || !wNlList) return
  const arr = notes.length
    ? notes.map((n) => ({ title: fmtTime(n.at) + ' · ' + statusWord(n), sub: subLine(n) }))
    : [{ title: 'Заметок пока нет', sub: '← свайп назад и нажми ●' }]
  wNlList.setProperty(prop.UPDATE_DATA, {
    data_array: arr,
    data_count: arr.length,
    data_type_config: [{ start: 0, end: Math.max(0, arr.length - 1), type_id: 1 }],
    data_type_config_count: 1,
    on_page: 1,
  })

  const queued = notes.filter((n) => n.status === 'queued').length
  if (wNlStatus) {
    wNlStatus.setProperty(prop.MORE, {
      text: queued ? queued + ' ждут отправки' : 'все расшифрованы',
    })
  }
  if (wNlSend) {
    wNlSend.setProperty(prop.MORE, {
      text: queued ? '📤 ' + queued : '📤',
    })
  }
}

function openNotesList() {
  if (listOpen || recording || detailIdx >= 0) return
  listOpen = true
  nlOv = []

  nlOv.push(createWidget(widget.FILL_RECT, { x: 0, y: 0, w: 390, h: 450, color: C.BG }))

  // Список заметок: без верхнего заголовка (освобождаем место), начинается прямо под системным хедером.
  // Крупный шрифт: заголовок 24sp, суть 28sp, выравнивание по левому краю.
  wNlList = createWidget(widget.SCROLL_LIST, {
    x: 8, y: 72, w: 374, h: 320,
    item_space: 10,
    item_config: [
      {
        type_id: 1,
        item_height: 112,
        item_bg_color: C.CARD,
        item_bg_radius: 18,
        text_view: [
          { x: 16, y: 10, w: 342, h: 28, key: 'title', color: 0x9fb3d9, text_size: 22, align_h: align.LEFT },
          { x: 16, y: 42, w: 342, h: 60, key: 'sub', color: C.PRIMARY, text_size: 26, align_h: align.LEFT },
        ],
        text_view_count: 2,
      },
    ],
    item_config_count: 1,
    data_array: [{ title: '…', sub: '…' }],
    data_count: 1,
    data_type_config: [{ start: 0, end: 0, type_id: 1 }],
    data_type_config_count: 1,
    item_click_func: (_list, index) => openDetail(index),
  })
  nlOv.push(wNlList)

  wNlStatus = createWidget(widget.TEXT, {
    x: 10, y: 400, w: 370, h: 24,
    text: '', text_size: 18, color: C.SECONDARY, align_h: align.CENTER_H,
  })
  nlOv.push(wNlStatus)

  wNlSend = createWidget(widget.BUTTON, {
    x: 145, y: 422, w: 100, h: 0,
    normal_color: C.ACCENT, press_color: 0x1e5bc6,
    text: '', text_size: 0,
    click_func: () => onSendBtn(),
  })
  nlOv.push(wNlSend)

  // точки пейджера (на списке — правая активная)
  wNlDotMain = createWidget(widget.ARC, {
    x: 183, y: 434, w: 8, h: 8, start_angle: 0, end_angle: 360, color: 0x4a4a4a,
  })
  nlOv.push(wNlDotMain)
  wNlDotList = createWidget(widget.ARC, {
    x: 199, y: 434, w: 8, h: 8, start_angle: 0, end_angle: 360, color: C.ACCENT,
  })
  nlOv.push(wNlDotList)

  renderNotesList()
}

function closeNotesList() {
  for (let i = 0; i < nlOv.length; i++) {
    try { deleteWidget(nlOv[i]) } catch (e) {}
  }
  nlOv = []
  wNlList = null
  wNlStatus = null
  wNlSend = null
  wNlDotMain = null
  wNlDotList = null
  listOpen = false
}

// ---------- жесты (пейджер) ----------

function onGestureCb(event) {
  // Во время записи — блокируем все свайпы, чтобы не потерять запись.
  if (recording) return true

  if (detailIdx >= 0) {
    // На экране детали: свайп вправо — назад.
    if (event === GESTURE_RIGHT) {
      closeDetail()
      return true
    }
    return true // остальные свайпы глушим, чтобы не выкинуло из детали
  }

  if (event === GESTURE_LEFT) {
    // свайп влево → открыть список
    if (!listOpen) {
      openNotesList()
      render()
    }
    return true
  }
  if (event === GESTURE_RIGHT) {
    // свайп вправо → закрыть список (вернуться на главный)
    if (listOpen) {
      closeNotesList()
      render()
      return true
    }
    // на главном — отдаём системе (выход)
    return false
  }
  return false
}

// ---------- передача очереди в фон (App Service) ----------

const BG_SERVICE_FILE = 'app-service/index'
const BG_PERM = 'device:os.bg_service'
const MIC_PERM = 'device:os.mic'

function micPermissionGranted() {
  try {
    const q = queryPermission({ permissions: [MIC_PERM] })
    return Array.isArray(q) && q[0] === 2
  } catch (e) {
    return false
  }
}

function ensureMicPermission(cb) {
  if (micPermissionGranted()) { if (cb) cb(true); return }
  try {
    requestPermission({
      permissions: [MIC_PERM],
      callback: (result) => {
        const ok = Array.isArray(result) && result[0] === 2
        log.log('mic perm result ' + (result && result[0]))
        if (cb) cb(ok)
      },
    })
  } catch (e) {
    log.log('mic perm err ' + errText(e))
    if (cb) cb(false)
  }
}

function bgPermissionGranted() {
  try {
    const q = queryPermission({ permissions: [BG_PERM] })
    return Array.isArray(q) && q[0] === 2
  } catch (e) {
    return false
  }
}

// Разрешение на фоновый сервис спрашиваем ОДИН раз (затем не беспокоим пользователя).
// Флаг храним в отдельном файле, чтобы пережить перезапуск приложения.
function bgPrompted() {
  try {
    const s = readFileSync({ path: 'data://bg.flag', options: { encoding: 'utf8' } })
    return s === '1'
  } catch (e) {
    return false
  }
}

function setBgPrompted() {
  try {
    writeFileSync({ path: 'data://bg.flag', data: '1', options: { encoding: 'utf8' } })
  } catch (e) {}
}

function ensureBgPermission() {
  if (bgPermissionGranted()) return
  if (bgPrompted()) return // уже спрашивали — не пинаем каждый запуск
  try {
    requestPermission({
      permissions: [BG_PERM],
      callback: (result) => {
        log.log('bg perm result ' + (result && result[0]))
        setBgPrompted()
      },
    })
  } catch (e) {
    log.log('bg perm err ' + errText(e))
  }
}

function resetSendingToQueued() {
  let changed = false
  notes.forEach((n) => {
    if (n.status === 'sending') {
      n.status = 'queued'
      changed = true
    }
  })
  if (changed) saveNotes()
}

function hasPending() {
  return notes.some((n) => n.status === 'queued' || n.status === 'sending')
}

function handoffToBackground() {
  drainGen++
  draining = false
  resetSendingToQueued()
  if (hasPending() && bgPermissionGranted()) {
    try {
      startBgService({
        file: BG_SERVICE_FILE,
        complete_func: (o) => {
          log.log('bg start result=' + (o && o.result))
        },
      })
    } catch (e) {
      log.log('bg start err ' + errText(e))
    }
  } else if (hasPending()) {
    log.log('bg perm not granted — очередь ждёт открытия приложения')
  }
}

function takeoverFromBackground() {
  try {
    stopBgService({ file: BG_SERVICE_FILE })
  } catch (e) {}
  drainGen++
  draining = false
  notes = loadNotes()
  resetSendingToQueued()
  render()
  probeRelay()
}

// ---------- сборка UI ----------

function buildWidgets() {
  setPageBrightTime({ brightTime: 10 * 60 * 1000 })

  // Верхняя зона ПОД системным хедером (имя приложения + часы): точка-статус слева + бейдж очереди справа
  wStatusDot = createWidget(widget.ARC, {
    x: 24, y: 78, w: 14, h: 14, start_angle: 0, end_angle: 360, color: C.SECONDARY,
  })

  wQueueBadge = createWidget(widget.TEXT, {
    x: 320, y: 72, w: 50, h: 26,
    text: '', text_size: 22, color: C.SYNC, align_h: align.RIGHT,
  })

  // Большой таймер
  wTimer = createWidget(widget.TEXT, {
    x: 10, y: 96, w: 370, h: 80,
    text: '00:00', text_size: 64, color: C.PRIMARY, align_h: align.CENTER_H,
  })

  // Статус-подпись (без жаргона)
  wStatus = createWidget(widget.TEXT, {
    x: 10, y: 184, w: 370, h: 30,
    text: 'Готово', text_size: 22, color: C.SECONDARY, align_h: align.CENTER_H,
  })

  // Огромная кнопка записи/стопа (синяя в покое, зелёный только у индикатора записи)
  wBtnRec = createWidget(widget.BUTTON, {
    x: 125, y: 224, w: 140, h: 140, radius: 70,
    normal_color: C.ACCENT, press_color: 0x1e5bc6,
    text: '●', text_size: 52,
    click_func: () => {
      if (detailIdx >= 0 || listOpen) return
      if (recording) stopRec()
      else startRec()
    },
  })

  // Точки пейджера (на главном — левая активная). Свайп влево открывает список.
  wDotMain = createWidget(widget.ARC, {
    x: 183, y: 438, w: 8, h: 8, start_angle: 0, end_angle: 360, color: C.ACCENT,
  })
  wDotList = createWidget(widget.ARC, {
    x: 199, y: 438, w: 8, h: 8, start_angle: 0, end_angle: 360, color: 0x4a4a4a,
  })
}

// ---------- жизненный цикл страницы ----------

Page(
  BasePage({
    build() {
      pageInstance = this
      ensureBgPermission()
      ensureMicPermission(null)
      try {
        try { stopBgService({ file: BG_SERVICE_FILE }) } catch (e) {}

        notes = loadNotes()

        let changed = false
        notes.forEach((n) => {
          if (n.status === 'sending') {
            n.status = 'queued'
            changed = true
          }
        })
        if (changed) saveNotes()

        buildWidgets()
        setRecBtn()
        render()

        // Рекордер создаём лениво в startRec() после подтверждения разрешения.
        // Не создаём здесь — на некоторых прошивках create(id.RECORDER)
        // без выданного runtime-разрешения возвращает undefined и блокирует
        // микрофон до перезагрузки.

        try {
          onGesture({ callback: onGestureCb })
        } catch (e) {
          log.log('onGesture err ' + errText(e))
        }

        probeRelay()
        startAutoProbe()
        setTimeout(syncNotes, 2000)
      } catch (e) {
        log.log('build err ' + e)
        try {
          wStatus.setProperty(prop.MORE, { text: '!build: ' + errText(e) })
        } catch (e2) {}
      }
    },

    onResume() {
      takeoverFromBackground()
      startAutoProbe()
    },

    onPause() {
      handoffToBackground()
    },

  onDestroy() {
    if (tickTimer) {
      clearInterval(tickTimer)
      tickTimer = null
    }
    if (probeTimer) {
      clearInterval(probeTimer)
      probeTimer = null
    }
    try { offGesture() } catch (e) {}
    if (recording && rec) {
      try {
        rec.stop()
      } catch (e) {}
      recording = false
      try {
        const st = statSync({ path: recFile })
        if (st && st.size > 0) {
          notes.unshift({
            id: recId, at: startedAt,
            dur: Math.max(1, Math.round((Date.now() - startedAt) / 1000)),
            size: st.size, status: 'queued', file: recFile, text: null, err: null,
            type: 'note', summary: '', dueIso: null,
          })
          saveNotes()
        }
      } catch (e) {}
    }
    rec = null
    handoffToBackground()
  },
  })
)
