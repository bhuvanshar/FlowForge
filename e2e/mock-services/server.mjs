// Mock upstream services for the end-to-end suite (no dependencies; Node 22).
// Runs in its own container on the compose network as http://mock-services, the only host the e2e backend may call.
//
//   GET  /health                      200
//   ANY  /echo[...]                   200 {method, path, query, headers, body}
//   ANY  /status/<code>               <code> {status}
//   ANY  /delay/<ms>                  200 after <ms> (max 60 s)
//   POST /v1/chat/completions         OpenAI-compatible chat completion; the reply is chosen by a marker in the user
//                                     messages: [[malformed]] invalid JSON once, then valid; [[rate-limit]] 429 once
//                                     (Retry-After: 1), then valid; [[fail]] 500 always; otherwise a review classification.
//   GET  /_requests?path=<prefix>     requests received so far (headers included; test inspection only)
//   DELETE /_requests                 forget them
import { createServer } from 'node:http'

const PORT = Number(process.env.PORT ?? 80)
const MAX_BODY = 1024 * 1024
const received = []
const onceSeen = new Set()

function send(res, status, body, headers = {}) {
  const text = typeof body === 'string' ? body : JSON.stringify(body)
  res.writeHead(status, { 'content-type': 'application/json', ...headers })
  res.end(text)
}

function parseJson(text) {
  try {
    return text ? JSON.parse(text) : null
  } catch {
    return text
  }
}

function completion(content, usage = { prompt_tokens: 42, completion_tokens: 17, total_tokens: 59 }) {
  return {
    id: `chatcmpl-e2e-${received.length}`,
    object: 'chat.completion',
    created: Math.floor(Date.now() / 1000),
    model: 'e2e-model',
    choices: [{ index: 0, message: { role: 'assistant', content }, finish_reason: 'stop' }],
    usage,
  }
}

function chat(res, request) {
  const messages = Array.isArray(request?.messages) ? request.messages : []
  // A repair retry appends messages, so markers and the review are looked up across every user message.
  const user = messages.filter((m) => m.role === 'user').map((m) => String(m.content ?? '')).join('\n')
  const key = String(messages.find((m) => m.role === 'user')?.content ?? '')
  const first = !onceSeen.has(key)
  onceSeen.add(key)

  if (user.includes('[[fail]]')) return send(res, 500, { error: { message: 'mock provider failure', type: 'server_error' } })
  if (user.includes('[[rate-limit]]') && first) {
    return send(res, 429, { error: { message: 'slow down', type: 'rate_limit' } }, { 'retry-after': '1' })
  }
  if (user.includes('[[malformed]]') && first) return send(res, 200, completion('Sure! {"sentiment": "negative", '))

  const negative = /late|broken|crushed|refund|terrible/i.test(user)
  const answer = negative
    ? { sentiment: 'negative', urgency: 4, summary: 'Late delivery and damaged packaging.' }
    : { sentiment: 'positive', urgency: 1, summary: 'Happy customer.' }
  return send(res, 200, completion(JSON.stringify(answer)))
}

createServer((req, res) => {
  const chunks = []
  let size = 0
  req.on('data', (chunk) => {
    size += chunk.length
    if (size > MAX_BODY) req.destroy()
    else chunks.push(chunk)
  })
  req.on('end', () => {
    const url = new URL(req.url ?? '/', 'http://mock-services')
    const text = Buffer.concat(chunks).toString('utf8')
    const body = parseJson(text)

    if (url.pathname === '/_requests') {
      if (req.method === 'DELETE') {
        received.length = 0
        onceSeen.clear()
        return send(res, 204, '')
      }
      const prefix = url.searchParams.get('path') ?? ''
      return send(res, 200, received.filter((r) => r.path.startsWith(prefix)))
    }
    if (url.pathname === '/health') return send(res, 200, { status: 'UP' })

    received.push({ method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams), headers: req.headers, body, at: new Date().toISOString() })

    if (url.pathname.startsWith('/echo')) {
      return send(res, 200, { method: req.method, path: url.pathname, query: Object.fromEntries(url.searchParams), headers: req.headers, body })
    }
    const status = url.pathname.match(/^\/status\/(\d{3})$/)
    if (status) return send(res, Number(status[1]), { status: Number(status[1]) })
    const delay = url.pathname.match(/^\/delay\/(\d+)$/)
    if (delay) {
      setTimeout(() => send(res, 200, { delayedMs: Number(delay[1]) }), Math.min(Number(delay[1]), 60_000))
      return
    }
    if (url.pathname === '/v1/chat/completions' && req.method === 'POST') return chat(res, body)
    return send(res, 404, { error: 'not found' })
  })
}).listen(PORT, '0.0.0.0', () => console.log(`mock-services listening on ${PORT}`))
