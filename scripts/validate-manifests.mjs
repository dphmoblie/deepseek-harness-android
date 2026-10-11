#!/usr/bin/env node
// 清单 JSON Schema 校验器（本仓库自写的极简实现，刻意不引入第三方依赖）。
//
// 为什么自己写：package.json 里没有任何 JSON Schema 库（没有 ajv），而本仓库的纪律是
// 不新增 npm 依赖、不联网安装。所以这里只实现「本仓库 schema 真正用到」的关键字，
// 并且有一条硬规则：**遇到不认识的关键字要报错，不许静默忽略**——否则 schema 里写了
// 一条没生效的约束，验证就会变成永远通过的假门禁。
//
// 支持的关键字：
//   type / required / properties / additionalProperties / items
//   enum / const / minLength / maxLength / pattern / minimum / maximum
//   minItems / maxItems / uniqueItems / minProperties
//   $schema / title / description / examples / default（纯注解，不参与判断）
//
// 与 JSON Schema 的两点自觉差异（都写在注释里，避免以后误以为是标准行为）：
//   1. 只要 schema 写了 required / properties / additionalProperties，实例就必须是对象；
//      标的不是对象时直接报类型错误，而不是像标准那样「跳过这组关键字」。
//   2. additionalProperties 同时支持 false（禁止未声明字段）与「子 schema」（对未声明字段
//      按该 schema 继续校验）两种写法。
//
// 用法：
//   node scripts/validate-manifests.mjs
//     校验仓库内全部受管清单（清单表见 defaultTargets）。
//   node scripts/validate-manifests.mjs --file <清单路径> --schema <schema 路径>
//     用指定 schema 校验单个文件，CI 用它校验构建产物，
//     例如 runtime-artifacts/runtime-manifest.json。
//
// 退出码：0 全部通过；1 有清单不合法；2 用法错误、文件读不到或 schema 本身有问题。

import { existsSync, readFileSync, readdirSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const SCRIPTS_DIR = dirname(fileURLToPath(import.meta.url))
const REPO_ROOT = resolve(SCRIPTS_DIR, '..')

/** 受支持的 schema 关键字。不在这里出现的键一律按「schema 有问题」报错。 */
export const SUPPORTED_KEYWORDS = new Set([
  '$schema',
  'title',
  'description',
  'examples',
  'default',
  'type',
  'required',
  'properties',
  'additionalProperties',
  'items',
  'enum',
  'const',
  'minLength',
  'maxLength',
  'pattern',
  'minimum',
  'maximum',
  'minItems',
  'maxItems',
  'uniqueItems',
  'minProperties',
])

/** 判断实例的 JSON 类型（整数单独识别，便于 type: "integer" 生效）。 */
function jsonType(value) {
  if (value === null) return 'null'
  if (Array.isArray(value)) return 'array'
  if (typeof value === 'number') return Number.isInteger(value) ? 'integer' : 'number'
  return typeof value
}

function typeMatches(value, expected) {
  const actual = jsonType(value)
  if (expected === 'number') return actual === 'number' || actual === 'integer'
  if (expected === 'object') return actual === 'object'
  return actual === expected
}

/** 把值渲染成中文报错里可读的片段。 */
function show(value) {
  const text = JSON.stringify(value)
  if (text === undefined) return String(value)
  return text.length > 120 ? `${text.slice(0, 117)}…` : text
}

/**
 * 校验一个已解析的 JSON 值。
 * @returns {string[]} 中文错误列表；空数组表示通过。
 */
export function validateManifest(value, schema, { label = '$' } = {}) {
  const errors = []
  const reported = new Set()
  validateNode(value, schema, label, errors, reported)
  return errors
}

function validateNode(value, schema, path, errors, reported) {
  if (schema === null || typeof schema !== 'object' || Array.isArray(schema)) {
    errors.push(`[schema] ${path}：schema 节点必须是对象`)
    return
  }

  for (const keyword of Object.keys(schema)) {
    if (SUPPORTED_KEYWORDS.has(keyword)) continue
    const key = `${path}|${keyword}`
    if (reported.has(key)) continue
    reported.add(key)
    errors.push(
      `[schema] ${path}：不支持的关键字 "${keyword}"（校验器不会假装它生效；请改用受支持的关键字，或先扩展 scripts/validate-manifests.mjs）`,
    )
  }

  const expectsObject =
    'required' in schema || 'properties' in schema || 'additionalProperties' in schema
  const declaredType = typeof schema.type === 'string' ? schema.type : undefined

  if (declaredType === undefined && expectsObject && jsonType(value) !== 'object') {
    errors.push(
      `${path}：类型应为 object（schema 写了 required/properties/additionalProperties），实际 ${jsonType(value)}`,
    )
    return
  }

  if (declaredType !== undefined && !typeMatches(value, declaredType)) {
    errors.push(`${path}：类型应为 ${declaredType}，实际 ${jsonType(value)}`)
    return
  }

  if ('const' in schema && JSON.stringify(value) !== JSON.stringify(schema.const)) {
    errors.push(`${path}：取值必须严格等于 ${show(schema.const)}，实际 ${show(value)}`)
  }

  if (Array.isArray(schema.enum) && !schema.enum.some(item => JSON.stringify(item) === JSON.stringify(value))) {
    errors.push(
      `${path}：取值必须属于 ${schema.enum.map(show).join('、')}，实际 ${show(value)}`,
    )
  }

  if (typeof value === 'string') {
    if (Number.isInteger(schema.minLength) && value.length < schema.minLength) {
      errors.push(`${path}：字符串长度 ${value.length} 小于最小长度 ${schema.minLength}`)
    }
    if (Number.isInteger(schema.maxLength) && value.length > schema.maxLength) {
      errors.push(`${path}：字符串长度 ${value.length} 超过最大长度 ${schema.maxLength}`)
    }
    if (typeof schema.pattern === 'string') {
      let matched = true
      try {
        matched = new RegExp(schema.pattern, 'u').test(value)
      } catch (error) {
        errors.push(`[schema] ${path}：正则 ${show(schema.pattern)} 无法编译：${error.message}`)
      }
      if (!matched) {
        errors.push(`${path}：字符串不匹配正则 ${schema.pattern}，实际 ${show(value)}`)
      }
    }
  }

  if (typeof value === 'number') {
    if (typeof schema.minimum === 'number' && value < schema.minimum) {
      errors.push(`${path}：数值 ${value} 小于最小值 ${schema.minimum}`)
    }
    if (typeof schema.maximum === 'number' && value > schema.maximum) {
      errors.push(`${path}：数值 ${value} 大于最大值 ${schema.maximum}`)
    }
  }

  if (Array.isArray(value)) {
    if (Number.isInteger(schema.minItems) && value.length < schema.minItems) {
      errors.push(`${path}：数组长度 ${value.length} 小于最小长度 ${schema.minItems}`)
    }
    if (Number.isInteger(schema.maxItems) && value.length > schema.maxItems) {
      errors.push(`${path}：数组长度 ${value.length} 超过最大长度 ${schema.maxItems}`)
    }
    if (schema.uniqueItems === true) {
      const seen = new Map()
      value.forEach((item, index) => {
        const key = JSON.stringify(item)
        if (seen.has(key)) {
          errors.push(
            `${path}：数组存在重复元素（第 ${seen.get(key)} 项与第 ${index} 项都是 ${show(item)}）`,
          )
        } else {
          seen.set(key, index)
        }
      })
    }
    if (schema.items !== undefined) {
      value.forEach((item, index) => validateNode(item, schema.items, `${path}[${index}]`, errors, reported))
    }
  }

  if (value !== null && typeof value === 'object' && !Array.isArray(value)) {
    const keys = Object.keys(value)
    if (Number.isInteger(schema.minProperties) && keys.length < schema.minProperties) {
      errors.push(`${path}：对象字段数 ${keys.length} 小于最少 ${schema.minProperties}`)
    }
    if (Array.isArray(schema.required)) {
      for (const name of schema.required) {
        if (!Object.hasOwn(value, name)) errors.push(`${path}：缺少必需字段 "${name}"`)
      }
    }
    const properties = schema.properties && typeof schema.properties === 'object' ? schema.properties : {}
    for (const [name, child] of Object.entries(properties)) {
      if (Object.hasOwn(value, name)) validateNode(value[name], child, `${path}.${name}`, errors, reported)
    }
    if ('additionalProperties' in schema && schema.additionalProperties !== true) {
      const extras = keys.filter(name => !Object.hasOwn(properties, name))
      const declared = Object.keys(properties)
      for (const name of extras) {
        if (schema.additionalProperties === false) {
          errors.push(
            `${path}：出现未声明的字段 "${name}"（schema 只允许：${declared.join('、') || '（无）'}）`,
          )
        } else {
          validateNode(value[name], schema.additionalProperties, `${path}.${name}`, errors, reported)
        }
      }
    }
  }
}

/** 读取并解析一个 JSON 文件；失败时抛出带中文原因的错误。 */
export function readJson(path) {
  let text
  try {
    text = readFileSync(path, 'utf8')
  } catch (error) {
    throw new Error(`读不到文件 ${path}：${error.message}`)
  }
  try {
    return JSON.parse(text)
  } catch (error) {
    throw new Error(`${path} 不是合法 JSON：${error.message}`)
  }
}

/**
 * 仓库内受管清单表。插件清单按目录动态发现，新增插件不需要改这里；
 * 同时要求至少发现一个，否则「插件目录被改名」会让校验悄悄空转。
 */
export function defaultTargets(root = REPO_ROOT) {
  const targets = [
    {
      file: 'docs/runtime-manifest.example.json',
      schema: 'scripts/schemas/runtime-manifest.schema.json',
    },
    {
      file: 'scripts/fixtures/runtime-manifest.v0.2.3-mobile-323.json',
      schema: 'scripts/schemas/runtime-manifest.schema.json',
    },
    {
      file: 'scripts/mobile-profile.example.json',
      schema: 'scripts/schemas/mobile-profile.schema.json',
    },
  ]

  const pluginsRoot = resolve(root, 'scripts/runtime-profile/plugins')
  const plugins = existsSync(pluginsRoot)
    ? readdirSync(pluginsRoot, { withFileTypes: true })
        .filter(entry => entry.isDirectory())
        .map(entry => entry.name)
        .sort()
    : []
  const withManifest = plugins.filter(id =>
    existsSync(resolve(pluginsRoot, id, 'package.json')),
  )
  if (withManifest.length === 0) {
    throw new Error(
      `没在 scripts/runtime-profile/plugins 下找到任何插件清单（package.json）：目录可能被改名或插件被移走，受管清单表需要同步`,
    )
  }
  for (const id of withManifest) {
    targets.push({
      file: `scripts/runtime-profile/plugins/${id}/package.json`,
      schema: 'scripts/schemas/plugin-package.schema.json',
    })
  }
  return targets
}

function parseArgs(argv) {
  const options = { file: undefined, schema: undefined }
  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index]
    if (arg === '--file' || arg === '--schema') {
      const value = argv[index + 1]
      if (value === undefined || value.startsWith('--')) {
        throw new Error(`${arg} 后面要跟一个路径`)
      }
      options[arg === '--file' ? 'file' : 'schema'] = value
      index += 1
      continue
    }
    if (arg === '--help' || arg === '-h') {
      options.help = true
      continue
    }
    throw new Error(`不认识的参数：${arg}`)
  }
  if ((options.file === undefined) !== (options.schema === undefined)) {
    throw new Error('--file 与 --schema 必须成对给出')
  }
  return options
}

const USAGE = [
  '用法：',
  '  node scripts/validate-manifests.mjs',
  '    校验仓库内全部受管清单（更新清单、移动端 profile 清单、各插件清单）。',
  '  node scripts/validate-manifests.mjs --file <清单> --schema <schema>',
  '    用指定 schema 校验单个文件（CI 用它校验构建产物 runtime-manifest.json）。',
].join('\n')

function runCli(argv) {
  let options
  try {
    options = parseArgs(argv)
  } catch (error) {
    console.error(`[清单校验] ${error.message}`)
    console.error(USAGE)
    return 2
  }
  if (options.help) {
    console.log(USAGE)
    return 0
  }

  let targets
  try {
    targets =
      options.file === undefined
        ? defaultTargets()
        : [{ file: options.file, schema: options.schema }]
  } catch (error) {
    console.error(`[清单校验] ${error.message}`)
    return 2
  }

  let failures = 0
  const schemaCache = new Map()
  for (const target of targets) {
    const manifestPath = options.file === undefined ? resolve(REPO_ROOT, target.file) : resolve(target.file)
    const schemaPath =
      options.file === undefined ? resolve(REPO_ROOT, target.schema) : resolve(target.schema)
    let manifest
    let schema
    try {
      if (!existsSync(manifestPath)) {
        throw new Error(
          `清单文件不存在：${manifestPath}（文件被移动或改名后，校验表与调用方都要一起改，不能让校验空转）`,
        )
      }
      if (!schemaCache.has(schemaPath)) {
        if (!existsSync(schemaPath)) throw new Error(`schema 不存在：${schemaPath}`)
        schemaCache.set(schemaPath, readJson(schemaPath))
      }
      schema = schemaCache.get(schemaPath)
      manifest = readJson(manifestPath)
    } catch (error) {
      console.error(`[清单校验] ${error.message}`)
      return 2
    }

    const label = options.file === undefined ? target.file : manifestPath
    const errors = validateManifest(manifest, schema, { label: '$' })
    if (errors.length === 0) {
      console.log(`✓ ${label}（${target.schema}）`)
      continue
    }
    failures += errors.length
    console.error(`✗ ${label}（${target.schema}）`)
    for (const error of errors) console.error(`    - ${error}`)
  }

  if (failures > 0) {
    console.error(`清单 schema 校验：${targets.length} 个文件，${failures} 处问题`)
    return 1
  }
  console.log(`清单 schema 校验：${targets.length} 个文件全部通过`)
  return 0
}

const invokedDirectly =
  process.argv[1] !== undefined && resolve(process.argv[1]) === resolve(fileURLToPath(import.meta.url))
if (invokedDirectly) {
  process.exitCode = runCli(process.argv.slice(2))
}
