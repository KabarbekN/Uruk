import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { createRequire } from 'node:module';

const require = createRequire(new URL('../typescript-node/package.json', import.meta.url));
const ignore = require('ignore');
const Ajv = require('ajv/dist/2020.js');
const requestSchema = JSON.parse(fs.readFileSync(new URL('../../analyzer-contract/schemas/v1/request.schema.json', import.meta.url), 'utf8'));
const validateRequest = new Ajv({ strict: false }).compile(requestSchema);
export const hash = text => `sha256:${crypto.createHash('sha256').update(text, 'utf8').digest('hex')}`;
const defaults = new Set(['.git', 'node_modules', '.deps', '__pycache__']);
const generated = new Set(['target', 'dist', 'build', 'generated', 'generated-sources']);
const within = (root, file) => { const rel = path.relative(root, file); return !rel.startsWith(`..${path.sep}`) && rel !== '..' && !path.isAbsolute(rel); };
const exists = file => { try { fs.lstatSync(file); return true; } catch (error) { if (error.code === 'ENOENT') return false; throw error; } };

export class Rejected extends Error {
  constructor(code, message, exitCode = 50) { super(message); this.code = code; this.exitCode = exitCode; }
}

function checkPath(file) {
  let current = path.resolve(file);
  for (;;) {
    if (exists(current) && fs.lstatSync(current).isSymbolicLink()) throw new Rejected('SYMLINK_REJECTED', 'Symlink/reparse path rejected');
    const parent = path.dirname(current);
    if (parent === current) break;
    current = parent;
  }
}

export class Source {
  constructor(file, text, absolutePath = null) {
    this.path = file;
    this.absolutePath = absolutePath;
    this.text = text.replace(/\r\n?/g, '\n');
    this.lines = this.text.split('\n');
    this.offsets = [0];
    for (const line of this.lines.slice(0, -1)) this.offsets.push(this.offsets.at(-1) + line.length + 1);
  }
  evidence(start = 0, end = this.text.length) {
    start = Math.max(0, Math.min(start, this.text.length));
    end = Math.max(start, Math.min(end, this.text.length));
    const row = offset => {
      let low = 0, high = this.offsets.length;
      while (low < high) { const mid = (low + high) >>> 1; if (this.offsets[mid] <= offset) low = mid + 1; else high = mid; }
      return low - 1;
    };
    const first = row(start), last = row(Math.max(start, end - 1));
    return { filePath: this.path, startLine: first + 1,
      startColumn: [...this.text.slice(this.offsets[first], start)].length + 1,
      endLine: last + 1, endColumn: [...this.text.slice(this.offsets[last], Math.min(end, this.offsets[last] + this.lines[last].length))].length + 1,
      snippetHash: hash(this.lines.slice(first, last + 1).join('\n')) };
  }
}

export class Run {
  constructor(descriptor) {
    this.descriptor = descriptor;
    this.request = {};
    this.facts = []; this.diagnostics = []; this.factIds = new Set();
    this.factsById = new Map();
    this.truncatedRelations = new Set();
    this.partial = new Set(); this.unknown = new Set();
    this.counts = { filesDiscovered: 0, filesParsed: 0, filesFailed: 0, filesSkipped: 0, sourceBytes: 0, entriesVisited: 0 };
    this.startedAt = new Date().toISOString(); this.deadline = Date.now() + 600000;
    this.outputLimit = 64 * 1024 * 1024; this.bytesUsed = 0;
  }
  tick() { if (Date.now() > this.deadline) throw new Rejected('DURATION_LIMIT', 'Analysis duration limit reached'); }
  diagnostic(code, message, filePath = null, startLine = null, severity = 'WARNING', metadata = {}) {
    if (this.diagnostics.length >= 100) { this.unknown.add('DIAGNOSTICS_TRUNCATED'); return; }
    this.diagnostics.push({ code, message: String(message).slice(0, 800), filePath, startLine, severity, metadata });
  }
  fact(kind, stableKey, name, ownerKey, evidence, properties = {}, origin = 'STATIC_SYNTAX', confidence = 0.8) {
    this.tick();
    const factId = hash(stableKey).slice(7);
    if (this.factIds.has(factId)) return stableKey;
    const value = { contractVersion: '1.0', factId, kind, stableKey,
      subject: { kind, stableKey }, properties: { name, ownerKey: ownerKey ?? '', ...properties }, origin, confidence, evidence: [evidence] };
    const bytes = Buffer.byteLength(JSON.stringify(value)) + 1;
    if (this.bytesUsed + bytes > this.outputLimit - 131072) throw new Rejected('OUTPUT_LIMIT', 'Fact output byte budget reached');
    this.bytesUsed += bytes; this.factIds.add(factId); this.factsById.set(factId, value); this.facts.push(value);
    return stableKey;
  }
  relation(sourceKey, targetKey, edgeKind, evidence, origin = 'STATIC_SYNTAX', confidence = 0.8) {
    const key = `relation:${edgeKind}:${sourceKey}->${targetKey}`;
    const previous = this.factsById.get(hash(key).slice(7));
    if (previous) {
      this.tick();
      if (!previous.evidence.some(item => Object.keys(evidence).length === Object.keys(item).length && Object.keys(evidence).every(field => item[field] === evidence[field]))) {
        if (previous.evidence.length >= 32) {
          this.unknown.add('RELATION_EVIDENCE_LIMIT');
          if (!this.truncatedRelations.has(key)) {
            this.truncatedRelations.add(key);
            this.diagnostic('RELATION_EVIDENCE_LIMIT', 'Relation retains the first 32 distinct evidence locations', evidence.filePath, evidence.startLine);
          }
          return key;
        }
        const addedBytes = Buffer.byteLength(JSON.stringify(evidence)) + 1;
        if (this.bytesUsed + addedBytes > this.outputLimit - 131072) throw new Rejected('OUTPUT_LIMIT', 'Fact output byte budget reached');
        this.bytesUsed += addedBytes;
        previous.evidence.push(evidence);
      }
      return key;
    }
    return this.fact('RELATION', key, edgeKind, sourceKey, evidence,
      { sourceKey, targetKey, edgeKind }, origin, confidence);
  }
  load(requestPath) {
    if (fs.statSync(requestPath).size > 1024 * 1024) throw new Rejected('INVALID_REQUEST', 'Request exceeds 1 MiB', 20);
    let req;
    try { req = JSON.parse(fs.readFileSync(requestPath, 'utf8').replace(/^\uFEFF/, '')); }
    catch (error) { throw new Rejected('INVALID_REQUEST', error.message, 20); }
    if (!validateRequest(req)) throw new Rejected('INVALID_REQUEST', new Ajv().errorsText(validateRequest.errors), 20);
    if (req?.contractVersion !== '1.0') throw new Rejected('INVALID_REQUEST', 'contractVersion must be 1.0', 20);
    for (const field of ['analysisId', 'organizationId', 'projectId']) {
      if (typeof req[field] !== 'string' || !req[field]) throw new Rejected('INVALID_REQUEST', `${field} must be a nonempty string`, 20);
    }
    if (typeof req.component?.rootPath !== 'string') throw new Rejected('INVALID_REQUEST', 'component.rootPath must be a string', 20);
    for (const field of ['requestedCapabilities', 'changedFiles']) {
      if (req[field] !== undefined && (!Array.isArray(req[field]) || req[field].some(item => typeof item !== 'string'))) throw new Rejected('INVALID_REQUEST', `${field} must be a string array`, 20);
    }
    const policy = req.policy ?? {};
    if (typeof policy !== 'object' || Array.isArray(policy)) throw new Rejected('INVALID_REQUEST', 'policy must be an object', 20);
    for (const field of ['includeTests', 'includeGeneratedSources', 'executeBuildScripts', 'resolveDependencies']) {
      if (policy[field] !== undefined && typeof policy[field] !== 'boolean') throw new Rejected('INVALID_REQUEST', `policy.${field} must be boolean`, 20);
    }
    for (const field of ['maxOutputBytes', 'maxDurationSeconds']) {
      if (policy[field] !== undefined && (!Number.isSafeInteger(policy[field]) || policy[field] <= 0)) throw new Rejected('INVALID_REQUEST', `policy.${field} must be a positive integer`, 20);
    }
    this.request = req;
    if (policy.executeBuildScripts || policy.resolveDependencies) throw new Rejected('UNSUPPORTED_EXECUTION_POLICY', 'SAFE_STATIC requires build execution and dependency resolution disabled');
    this.deadline = Date.now() + Math.min(600, policy.maxDurationSeconds ?? 600) * 1000;
    this.outputLimit = Math.min(256 * 1024 * 1024, policy.maxOutputBytes ?? this.outputLimit);
    if (this.outputLimit < 262144) throw new Rejected('OUTPUT_LIMIT', 'At least 262144 output bytes are required');
    const workspace = path.resolve(process.env.SEMANTIC_WORKSPACE ?? '/workspace');
    checkPath(workspace);
    this.workspace = fs.realpathSync(workspace);
    const rel = req.component.rootPath.replaceAll('\\', '/');
    if (path.isAbsolute(rel) || rel.includes(':') || rel.split('/').includes('..')) throw new Rejected('PATH_ESCAPE', 'component.rootPath must remain within /workspace');
    this.root = path.resolve(this.workspace, rel);
    checkPath(this.root);
    if (!within(this.workspace, this.root) || !fs.statSync(this.root).isDirectory()) throw new Rejected('INVALID_REQUEST', 'Invalid component root', 20);
    for (const cap of req.requestedCapabilities ?? []) {
      if (!this.descriptor.capabilities.includes(cap)) {
        this.partial.add(cap); this.unknown.add(cap);
        this.diagnostic('UNSUPPORTED_CAPABILITY', `Capability ${cap} is not supported`);
      }
    }
    if (req.changedFiles?.length) this.diagnostic('FULL_COMPONENT_ANALYSIS', 'Incremental input is analyzed as a full component', null, null, 'INFO');
  }
  sources(extensions) {
    const policy = this.request.policy ?? {}, matcher = ignore();
    const ignorePath = path.join(this.workspace, '.semanticmapignore');
    if (exists(ignorePath)) {
      checkPath(ignorePath);
      if (fs.statSync(ignorePath).size > 65536) throw new Rejected('IGNORE_LIMIT', '.semanticmapignore exceeds 64 KiB');
      matcher.add(fs.readFileSync(ignorePath, 'utf8'));
    }
    // Selecting a nested component must not reopen an excluded parent tree.
    let ancestor = '';
    for (const part of path.relative(this.workspace, this.root).split(path.sep).filter(Boolean)) {
      ancestor += part + '/';
      if (defaults.has(part) || matcher.ignores(ancestor)
        || (!policy.includeGeneratedSources && generated.has(part))
        || (policy.includeTests === false && (['test', 'tests', '__tests__'].includes(part) || part.includes('.test.') || part.includes('.spec.')))) return [];
    }
    const pending = [[this.root, 0]], sources = [];
    while (pending.length) {
      const [directory, depth] = pending.pop(); this.tick();
      if (depth > 64) throw new Rejected('DEPTH_LIMIT', 'Directory nesting exceeds 64 levels');
      const entries = [];
      const scan = fs.opendirSync(directory);
      try {
        let entry;
        while ((entry = scan.readSync())) {
          if (++this.counts.entriesVisited > 100000) throw new Rejected('ENTRY_LIMIT', 'Repository exceeds entry budget');
          entries.push(entry);
        }
      } finally { scan.closeSync(); }
      entries.sort((a, b) => a.name < b.name ? -1 : a.name > b.name ? 1 : 0);
      for (const entry of entries) {
        this.tick();
        const absolutePath = path.join(directory, entry.name), relative = path.relative(this.workspace, absolutePath).split(path.sep).join('/');
        if (entry.isSymbolicLink()) {
          this.counts.filesSkipped++; this.unknown.add('REJECTED_PATHS');
          this.diagnostic('SYMLINK_REJECTED', 'Symlink/reparse point was not followed', relative); continue;
        }
        if (defaults.has(entry.name) || matcher.ignores(relative + (entry.isDirectory() ? '/' : ''))) continue;
        if (!policy.includeGeneratedSources && generated.has(entry.name)) continue;
        if (policy.includeTests === false && (['test', 'tests', '__tests__'].includes(entry.name) || entry.name.includes('.test.') || entry.name.includes('.spec.'))) continue;
        if (entry.isDirectory()) { pending.push([absolutePath, depth + 1]); continue; }
        if (!extensions.has(path.extname(entry.name).toLowerCase())) continue;
        if (entry.name.endsWith('.min.js') || entry.name.endsWith('.min.mjs') || entry.name.endsWith('.bundle.js')) {
          this.counts.filesSkipped++; continue;
        }
        if (!entry.isFile()) throw new Rejected('SPECIAL_FILE_REJECTED', 'Only regular source files can be read');
        if (++this.counts.filesDiscovered > 10000) throw new Rejected('FILE_LIMIT', 'Component exceeds file budget');
        if (fs.statSync(absolutePath).size > 2 * 1024 * 1024) {
          this.counts.filesSkipped++; this.unknown.add('OVERSIZE_FILES'); this.diagnostic('FILE_TOO_LARGE', 'Source exceeds 2 MiB', relative); continue;
        }
        checkPath(absolutePath);
        const fd = fs.openSync(absolutePath, fs.constants.O_RDONLY | (fs.constants.O_NOFOLLOW ?? 0));
        const data = Buffer.alloc(2 * 1024 * 1024 + 1);
        let bytes;
        try { bytes = fs.readSync(fd, data, 0, data.length, 0); } finally { fs.closeSync(fd); }
        this.counts.sourceBytes += bytes;
        if (bytes > 2 * 1024 * 1024 || this.counts.sourceBytes > 64 * 1024 * 1024) throw new Rejected('SOURCE_LIMIT', 'Source byte budget reached');
        try {
          const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(data.subarray(0, bytes));
          if (text.includes('\0')) throw new Error('NUL byte in source');
          sources.push(new Source(relative, text, absolutePath));
        } catch (error) { this.counts.filesFailed++; this.unknown.add('MALFORMED_SOURCE'); this.diagnostic('INVALID_ENCODING', error.message, relative, null, 'ERROR'); }
      }
    }
    return sources.sort((a, b) => a.path < b.path ? -1 : a.path > b.path ? 1 : 0);
  }
  write(output, exitCode = 0) {
    output = path.resolve(output); checkPath(output);
    // Failed request validation can precede load() assigning this.workspace.
    const declaredWorkspace = path.resolve(process.env.SEMANTIC_WORKSPACE ?? '/workspace');
    const workspace = this.workspace ?? (exists(declaredWorkspace) ? fs.realpathSync(declaredWorkspace) : declaredWorkspace);
    if (within(workspace, output)) throw new Rejected('OUTPUT_IN_WORKSPACE', 'Output must be outside the read-only workspace');
    fs.mkdirSync(output, { recursive: true });
    const partial = Boolean(this.partial.size || this.unknown.size || this.counts.filesFailed);
    if (!exitCode) exitCode = partial ? 10 : 0;
    const status = exitCode === 0 ? 'SUCCEEDED' : exitCode === 10 ? 'PARTIAL' : 'FAILED';
    const requested = [...new Set(this.request.requestedCapabilities?.length ? this.request.requestedCapabilities : this.descriptor.capabilities)].sort();
    if (partial) for (const cap of requested) if (this.descriptor.capabilities.includes(cap)) this.partial.add(cap);
    const capabilitiesPartial = [...this.partial].sort();
    const manifest = { contractVersion: '1.0', analyzer: { id: this.descriptor.id, version: this.descriptor.version, imageDigest: process.env.SEMANTIC_IMAGE_DIGEST ?? null },
      status, capabilitiesCompleted: exitCode <= 10 ? requested.filter(cap => !this.partial.has(cap)) : [], capabilitiesPartial,
      capabilitiesFailed: exitCode > 10 ? requested : [], factCount: this.facts.length, diagnosticCount: this.diagnostics.length,
      startedAt: this.startedAt, finishedAt: new Date().toISOString() };
    const payloads = {
      'facts.ndjson': this.facts.sort((a, b) => a.stableKey < b.stableKey ? -1 : a.stableKey > b.stableKey ? 1 : 0).map(f => JSON.stringify(f) + '\n').join(''),
      'diagnostics.ndjson': this.diagnostics.map(d => JSON.stringify(d) + '\n').join(''),
      'coverage.json': JSON.stringify({ contractVersion: '1.0', filesDiscovered: this.counts.filesDiscovered, filesParsed: this.counts.filesParsed, filesFailed: this.counts.filesFailed,
        capabilities: requested.map(name => ({ name, status: exitCode > 10 ? 'FAILED' : this.partial.has(name) ? 'PARTIAL' : 'COMPLETED', limitations: this.partial.has(name) ? [...this.unknown].sort() : [] })),
        metadata: { status, unknown: [...this.unknown].sort(), ...this.counts } }) + '\n',
      'statistics.json': JSON.stringify({ contractVersion: '1.0', factCount: this.facts.length, diagnosticCount: this.diagnostics.length,
        factsByKind: this.facts.reduce((counts, fact) => { counts[fact.kind] = (counts[fact.kind] ?? 0) + 1; return counts; }, {}), metadata: this.counts }) + '\n',
      'manifest.json': JSON.stringify(manifest) + '\n' };
    if (Object.values(payloads).reduce((total, value) => total + Buffer.byteLength(value), 0) > this.outputLimit) throw new Rejected('OUTPUT_LIMIT', 'Result exceeds output budget');
    for (const [name, data] of Object.entries(payloads)) {
      const target = path.join(output, name); checkPath(target);
      if (exists(target)) fs.unlinkSync(target);
      fs.writeFileSync(target, data, { encoding: 'utf8', flag: 'wx' });
    }
    return exitCode;
  }
}

export function cli(descriptor, analyze, args = process.argv.slice(2)) {
  if (args.length === 1 && args[0] === 'describe') { console.log(JSON.stringify(descriptor)); return 0; }
  if (args.length !== 5 || args[0] !== 'analyze' || !args.includes('--request') || !args.includes('--output')) {
    console.error('Usage: semantic-analyzer describe | analyze --request <file> --output <directory>'); return 20;
  }
  const request = args[args.indexOf('--request') + 1], output = args[args.indexOf('--output') + 1];
  if (!request || !output) return 20;
  const run = new Run(descriptor); let exitCode = 0;
  try {
    run.load(request); analyze(run);
    if (!run.counts.filesDiscovered) throw new Rejected('UNSUPPORTED_COMPONENT', 'No supported source files were found', 30);
  } catch (error) {
    exitCode = error.exitCode ?? (error.code === 'ENOENT' ? 20 : 60);
    run.diagnostic(error instanceof Rejected ? error.code : exitCode === 20 ? 'INVALID_REQUEST' : 'INTERNAL_ERROR', error.message, null, null, 'ERROR');
  }
  try { return run.write(output, exitCode); }
  catch (error) { console.error(error.message); return error.exitCode ?? 60; }
}
