const fs = require('fs')
const path = require('path')
const http = require('http')
const qrcode = require('qrcode-terminal')
const pino = require('pino')

const {
  default: makeWASocket,
  useMultiFileAuthState,
  fetchLatestBaileysVersion,
  DisconnectReason
} = require('baileys')

const CONFIG_PATH = path.join(__dirname, 'config.json')

if (!fs.existsSync(CONFIG_PATH)) {
  console.error('Missing config.json. Copy config.example.json to config.json and edit it.')
  process.exit(1)
}

const config = JSON.parse(fs.readFileSync(CONFIG_PATH, 'utf8'))

if (!config.sharedSecret || config.sharedSecret.length < 16) {
  console.error('config.sharedSecret must be at least 16 characters.')
  process.exit(1)
}

const PORT = config.port || 8787
const MIN_GAP_MS = (config.minSecondsBetweenMessages ?? 5) * 1000
const MAX_PER_HOUR = config.maxMessagesPerHour ?? 20
const QUEUE_LIMIT = 20
const DEDUPE_LIMIT = 200
const STALE_QUEUE_MS = 30 * 60 * 1000

let sock = null
let connected = false
let groupJid = config.groupJid || ''
let pairingRequested = false

const queue = []
const seenKeys = new Map()
const sentTimestamps = []
let lastSentAt = 0

function log (...args) {
  console.log(new Date().toISOString(), ...args)
}

function rememberKey (key) {
  seenKeys.set(key, Date.now())
  if (seenKeys.size > DEDUPE_LIMIT) {
    const oldest = [...seenKeys.entries()].sort((a, b) => a[1] - b[1])[0]
    seenKeys.delete(oldest[0])
  }
}

function isDuplicate (key) {
  if (!key) return false
  const seenAt = seenKeys.get(key)
  if (!seenAt) return false
  return Date.now() - seenAt < 24 * 60 * 60 * 1000
}

function hourlyBudgetLeft () {
  const cutoff = Date.now() - 60 * 60 * 1000
  while (sentTimestamps.length && sentTimestamps[0] < cutoff) sentTimestamps.shift()
  return MAX_PER_HOUR - sentTimestamps.length
}

async function deliver (text) {
  if (!connected || !sock) throw new Error('not connected')
  if (!groupJid) throw new Error('groupJid is not configured')
  if (hourlyBudgetLeft() <= 0) throw new Error('hourly limit reached')

  const gap = Date.now() - lastSentAt
  if (gap < MIN_GAP_MS) {
    await new Promise(resolve => setTimeout(resolve, MIN_GAP_MS - gap))
  }

  await sock.sendMessage(groupJid, { text })
  lastSentAt = Date.now()
  sentTimestamps.push(lastSentAt)
  log('sent ->', groupJid, JSON.stringify(text.slice(0, 80)))
}

function enqueue (text, key) {
  if (queue.length >= QUEUE_LIMIT) queue.shift()
  queue.push({ text, key, queuedAt: Date.now() })
  if (key) rememberKey(key)
}

async function flushQueue () {
  while (queue.length && connected) {
    const item = queue[0]

    if (Date.now() - item.queuedAt > STALE_QUEUE_MS) {
      queue.shift()
      log('dropped stale queued message:', item.key || '(no key)')
      continue
    }

    try {
      await deliver(item.text)
      queue.shift()
    } catch (err) {
      log('flush failed, will retry later:', err.message)
      return
    }
  }
}

async function startSocket () {
  const { state, saveCreds } = await useMultiFileAuthState(path.join(__dirname, 'auth'))
  const { version } = await fetchLatestBaileysVersion()

  sock = makeWASocket({
    version,
    auth: state,
    logger: pino({ level: 'silent' }),
    browser: ['SolarGridMonitor', 'Chrome', '1.0.0'],
    markOnlineOnConnect: false
  })

  sock.ev.on('creds.update', saveCreds)

  if (config.pairWithPhoneNumber && !state.creds.registered && !pairingRequested) {
    pairingRequested = true
    setTimeout(async () => {
      try {
        const number = String(config.pairWithPhoneNumber).replace(/[^0-9]/g, '')
        const code = await sock.requestPairingCode(number)
        console.log('\n==============================')
        console.log('  PAIRING CODE:  ' + code)
        console.log('==============================')
        console.log('On your main phone: WhatsApp -> Settings -> Linked devices')
        console.log('-> Link a device -> Link with phone number instead')
        console.log('Then type the code above.\n')
      } catch (err) {
        console.error('Could not request a pairing code:', err.message)
        console.error('Remove pairWithPhoneNumber from config.json to use the QR instead.')
      }
    }, 4000)
  }

  sock.ev.on('connection.update', async update => {
    const { connection, lastDisconnect, qr } = update

    if (qr && !config.pairWithPhoneNumber) {
      console.log('\nScan this from WhatsApp on your main phone:')
      console.log('Settings -> Linked devices -> Link a device\n')
      qrcode.generate(qr, { small: true })
    }

    if (connection === 'open') {
      connected = true
      log('connected to WhatsApp')
      await listGroupsIfNeeded()
      flushQueue()
    }

    if (connection === 'close') {
      connected = false
      const status = lastDisconnect?.error?.output?.statusCode
      const loggedOut = status === DisconnectReason.loggedOut

      log('disconnected', status ?? '', loggedOut ? '(logged out)' : '(will reconnect)')

      if (loggedOut) {
        console.error('This device was unlinked. Delete the auth/ folder and pair again.')
        process.exit(1)
      }

      setTimeout(startSocket, 5000)
    }
  })
}

async function listGroupsIfNeeded () {
  if (groupJid) return

  const groups = await sock.groupFetchAllParticipating()
  const rows = Object.values(groups).map(g => ({ jid: g.id, name: g.subject }))

  console.log('\nNo groupJid configured yet. Your groups:\n')
  rows.forEach(r => console.log(`  ${r.jid}   ${r.name}`))
  console.log('\nPut the right jid into config.json as "groupJid" and restart.\n')
}

function readBody (req) {
  return new Promise((resolve, reject) => {
    let data = ''
    req.on('data', chunk => {
      data += chunk
      if (data.length > 64 * 1024) {
        reject(new Error('body too large'))
        req.destroy()
      }
    })
    req.on('end', () => resolve(data))
    req.on('error', reject)
  })
}

function json (res, code, payload) {
  const body = JSON.stringify(payload)
  res.writeHead(code, { 'Content-Type': 'application/json' })
  res.end(body)
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://${req.headers.host}`)

  if (req.method === 'GET' && url.pathname === '/health') {
    return json(res, 200, {
      connected,
      groupJid: groupJid || null,
      queued: queue.length,
      budgetLeftThisHour: hourlyBudgetLeft()
    })
  }

  if (req.method === 'GET' && url.pathname === '/groups') {
    if (req.headers['x-bridge-key'] !== config.sharedSecret) {
      return json(res, 401, { error: 'bad key' })
    }
    if (!connected) return json(res, 503, { error: 'not connected' })

    const groups = await sock.groupFetchAllParticipating()
    return json(res, 200, Object.values(groups).map(g => ({ jid: g.id, name: g.subject })))
  }

  if (req.method === 'POST' && url.pathname === '/send') {
    if (req.headers['x-bridge-key'] !== config.sharedSecret) {
      return json(res, 401, { error: 'bad key' })
    }

    let payload
    try {
      payload = JSON.parse(await readBody(req))
    } catch (err) {
      return json(res, 400, { error: 'invalid json' })
    }

    const text = (payload.text || '').toString().trim()
    const key = payload.key ? payload.key.toString() : null

    if (!text) return json(res, 400, { error: 'text is required' })

    if (isDuplicate(key)) {
      log('duplicate ignored:', key)
      return json(res, 200, { status: 'duplicate' })
    }

    if (hourlyBudgetLeft() <= 0) {
      log('hourly budget exhausted, dropping message')
      return json(res, 429, { error: 'hourly limit reached' })
    }

    if (!connected) {
      enqueue(text, key)
      log('queued while offline:', key || '(no key)')
      return json(res, 202, { status: 'queued', queued: queue.length })
    }

    try {
      await deliver(text)
      if (key) rememberKey(key)
      return json(res, 200, { status: 'sent' })
    } catch (err) {
      enqueue(text, key)
      log('send failed, queued:', err.message)
      return json(res, 202, { status: 'queued', error: err.message })
    }
  }

  return json(res, 404, { error: 'not found' })
})

server.listen(PORT, '0.0.0.0', () => {
  log(`bridge listening on port ${PORT}`)
})

startSocket().catch(err => {
  console.error('failed to start:', err)
  process.exit(1)
})
