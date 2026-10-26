/**
 * Validation patterns shared with the backend contract (backend/src/main/resources/schemas/node-config.v1.schema.json).
 * configContract.test.ts asserts these strings are identical to the ones in the JSON Schema.
 */

const PATH = '[A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*|\\[[0-9]+\\])*'

/**
 * Safe expression text. Any `{{ ... }}` must contain only a property path such as `input.user.id` or
 * `items[0].name`: no function calls, operators, quotes or code. Anything outside `{{ }}` is literal text, including
 * single braces. There is no JavaScript evaluation anywhere in FlowForge.
 */
export const TEMPLATE_PATTERN = `^[^{]*(?:(?:\\{(?!\\{)|\\{\\{\\s*${PATH}\\s*\\}\\})[^{]*)*$`
export const TEMPLATE_REGEX = new RegExp(TEMPLATE_PATTERN, 'u')

export const VARIABLE_NAME_PATTERN = '^[A-Za-z_][A-Za-z0-9_]{0,63}$'
export const VARIABLE_NAME_REGEX = new RegExp(VARIABLE_NAME_PATTERN, 'u')

/** RFC 9110 token characters. */
export const HEADER_NAME_PATTERN = "^[!#$%&'*+.^_`|~0-9A-Za-z-]+$"
export const HEADER_NAME_REGEX = new RegExp(HEADER_NAME_PATTERN, 'u')

/** http(s) URL, or a value starting with an expression (resolved at run time). */
export const URL_PATTERN = '^(https?://|\\{\\{).+$'
export const URL_REGEX = new RegExp(URL_PATTERN, 'u')

export const MAX_TEXT_LENGTH = 2000
export const MAX_ROWS = 50
export const MAX_TIMEOUT_SECONDS = 300

/** 5 cron fields (minute hour day month weekday) or 6 with leading seconds. The backend parses the expression fully. */
export const CRON_PATTERN = '^[0-9A-Za-z*?/,#-]+(\\s+[0-9A-Za-z*?/,#-]+){4,5}$'
export const CRON_REGEX = new RegExp(CRON_PATTERN, 'u')

/** IANA time zone name shape, e.g. Europe/Berlin. Existence is checked separately. */
export const TIME_ZONE_PATTERN = '^[A-Za-z][A-Za-z0-9_+/-]{0,63}$'
export const TIME_ZONE_REGEX = new RegExp(TIME_ZONE_PATTERN, 'u')

/** Base URL of an OpenAI-compatible API: http(s), no query, fragment or references. */
export const AI_BASE_URL_PATTERN = '^https?://[^\\s{}?#]+$'
export const AI_BASE_URL_REGEX = new RegExp(AI_BASE_URL_PATTERN, 'u')

export const AI_MODEL_NAME_PATTERN = '^[A-Za-z0-9][A-Za-z0-9._:/@+-]*$'
export const AI_MODEL_NAME_REGEX = new RegExp(AI_MODEL_NAME_PATTERN, 'u')

/** Prompt templates use the same reference rules as other text, with a larger size limit. */
export const MAX_PROMPT_LENGTH = 20000

/** Stored credential reference (lower-case UUID), see $defs/credentialId. */
export const CREDENTIAL_ID_PATTERN = '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
export const CREDENTIAL_ID_REGEX = new RegExp(CREDENTIAL_ID_PATTERN, 'u')
