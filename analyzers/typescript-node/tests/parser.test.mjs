import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import Ajv from 'ajv/dist/2020.js';
import { hash, Source } from '../../shared/runtime.mjs';

const analyzerRoot = path.resolve(fileURLToPath(new URL('..', import.meta.url)));
const outputRoot = path.join(analyzerRoot, '.test-output');
fs.mkdirSync(outputRoot, { recursive: true });
const schemaRoot = path.resolve(analyzerRoot, '../../analyzer-contract/schemas/v1');
const validator = new Ajv({ strict: false, validateFormats: false });
const schemas = Object.fromEntries(['fact', 'request', 'manifest', 'describe', 'diagnostic', 'coverage', 'statistics'].map(name => [name, validator.compile(JSON.parse(fs.readFileSync(path.join(schemaRoot, `${name}.schema.json`), 'utf8')))]));
const request = () => ({ contractVersion: '1.0', analysisId: 'test', organizationId: 'org', projectId: 'project',
  revision: { commitSha: 'fixture', branch: 'main' }, component: { rootPath: '.', languages: ['TYPESCRIPT'], frameworks: ['NESTJS'], buildSystem: 'NPM' },
  requestedCapabilities: ['SYMBOLS', 'ENDPOINTS', 'VALIDATIONS', 'CALL_GRAPH'], changedFiles: [],
  policy: { networkAccess: 'DENY', executeBuildScripts: false, resolveDependencies: false, includeTests: true, includeGeneratedSources: false, maxDurationSeconds: 60, maxOutputBytes: 8388608 } });

function run(files, req = request(), prepare = () => {}) {
  const root = fs.mkdtempSync(path.join(outputRoot, 'run-')), workspace = path.join(root, 'workspace'), output = path.join(root, 'output');
  fs.mkdirSync(workspace);
  for (const [name, contents] of Object.entries(files)) { const target = path.join(workspace, name); fs.mkdirSync(path.dirname(target), { recursive: true }); fs.writeFileSync(target, contents); }
  prepare(workspace, root);
  const input = path.join(root, 'request.json'); fs.writeFileSync(input, JSON.stringify(req));
  const result = spawnSync(process.execPath, [path.join(analyzerRoot, 'semantic-analyzer.mjs'), 'analyze', '--request', input, '--output', output],
    { encoding: 'utf8', env: { ...process.env, SEMANTIC_WORKSPACE: workspace }, timeout: 90000 });
  const artifacts = {};
  if (fs.existsSync(output)) for (const name of fs.readdirSync(output)) {
    const data = fs.readFileSync(path.join(output, name), 'utf8');
    artifacts[name] = name.endsWith('.ndjson') ? data.trim().split('\n').filter(Boolean).map(line => JSON.parse(line)) : JSON.parse(data);
  }
  return { result, artifacts, workspace };
}

function checkArtifacts(artifacts, workspace) {
  for (const [file, schema] of [['manifest.json', 'manifest'], ['coverage.json', 'coverage'], ['statistics.json', 'statistics']]) {
    assert.ok(schemas[schema](artifacts[file]), JSON.stringify(schemas[schema].errors));
  }
  for (const fact of artifacts['facts.ndjson']) {
    assert.ok(schemas.fact(fact), JSON.stringify(schemas.fact.errors));
    for (const evidence of fact.evidence) {
      const lines = fs.readFileSync(path.join(workspace, evidence.filePath), 'utf8').replace(/\r\n?/g, '\n').split('\n');
      assert.equal(evidence.snippetHash, hash(lines.slice(evidence.startLine - 1, evidence.endLine).join('\n')));
    }
  }
  for (const diagnostic of artifacts['diagnostics.ndjson']) assert.ok(schemas.diagnostic(diagnostic), JSON.stringify(schemas.diagnostic.errors));
}

test('compiler resolves local calls; imported Nest aliases, guards and validations retain evidence', () => {
  const fixture = path.join(analyzerRoot, 'tests/fixtures');
  const files = Object.fromEntries(fs.readdirSync(fixture).map(name => [name, fs.readFileSync(path.join(fixture, name), 'utf8')]));
  const { result, artifacts, workspace } = run(files);
  assert.equal(result.status, 10, result.stderr + JSON.stringify(artifacts));
  const facts = artifacts['facts.ndjson'];
  assert.ok(facts.some(f => f.kind === 'ENDPOINT' && f.properties.httpMethod === 'POST' && f.properties.path === '/orders'));
  assert.ok(facts.some(f => f.kind === 'VALIDATION_RULE' && f.properties.constraint === 'Min' && f.properties.arguments[0] === 500));
  assert.ok(facts.some(f => f.kind === 'AUTHORIZATION_RULE' && f.properties.guardBehavior === 'UNKNOWN'));
  assert.ok(facts.some(f => f.kind === 'RELATION' && f.properties.edgeKind === 'CALLS' && f.properties.targetKey.includes('method:create')));
  checkArtifacts(artifacts, workspace);
  assert.deepEqual(facts, run(files).artifacts['facts.ndjson']);
});

test('lookalike decorators, comments and strings do not fabricate endpoints or classes', () => {
  const { artifacts } = run({ 'fake.ts': "function Controller(x: string) { return (n: any) => n; } function Get(x: string) { return (n: any) => n; }\n// class Ghost {}\n@Controller('fake') class Local { @Get('fake') run() { return 'class Imaginary {}'; } }" });
  assert.equal(artifacts['facts.ndjson'].filter(f => f.kind === 'ENDPOINT').length, 0);
  assert.deepEqual(artifacts['facts.ndjson'].filter(f => f.kind === 'CLASS').map(f => f.properties.name), ['Local']);
});

test('malformed sources and dynamic routes report partial coverage', () => {
  const { result, artifacts } = run({ 'broken.ts': 'export class Broken { run( {', 'route.ts': "import { Controller, Get } from '@nestjs/common'; @Controller(process.env.ROOT) class A { @Get() run() {} }" });
  assert.equal(result.status, 10);
  assert.ok(artifacts['diagnostics.ndjson'].some(d => d.code === 'TYPESCRIPT_PARSE_ERROR'));
  assert.ok(artifacts['diagnostics.ndjson'].some(d => d.code === 'DYNAMIC_ROUTE'));
  assert.equal(artifacts['facts.ndjson'].filter(f => f.kind === 'ENDPOINT').length, 0);
});

test('ignore patterns, directory exclusions and component boundaries are enforced', () => {
  const req = request(); req.component.rootPath = 'component';
  const { artifacts, workspace } = run({ '.semanticmapignore': 'component/skip.ts\ncomponent/ignored/**\n',
    'component/keep.ts': 'export class Keep {}', 'component/skip.ts': 'class Skip {}', 'component/node_modules/bad.ts': 'class Bad {}',
    'component/target/no.ts': 'class Target {}', 'component/ignored/no.ts': 'class Ignored {}', 'outside.ts': 'class Outside {}' }, req);
  assert.deepEqual(artifacts['facts.ndjson'].filter(f => f.kind === 'CLASS').map(f => f.properties.name), ['Keep']);
  checkArtifacts(artifacts, workspace);
  req.component.rootPath = '../escape';
  assert.equal(run({}, req).result.status, 50);
});

test('directory symlinks and junctions are not followed', () => {
  const { result, artifacts } = run({ 'ok.ts': 'class Ok {}' }, request(), (workspace, root) => {
    const outside = path.join(root, 'outside'); fs.mkdirSync(outside); fs.writeFileSync(path.join(outside, 'secret.ts'), 'class Secret {}');
    fs.symlinkSync(outside, path.join(workspace, 'linked'), os.platform() === 'win32' ? 'junction' : 'dir');
  });
  assert.equal(result.status, 10);
  assert.ok(artifacts['diagnostics.ndjson'].some(d => d.code === 'SYMLINK_REJECTED'));
  assert.deepEqual(artifacts['facts.ndjson'].filter(f => f.kind === 'CLASS').map(f => f.properties.name), ['Ok']);
});

test('schema rejects incomplete and unknown requests; no sources is unsupported', () => {
  assert.equal(run({}, {}).result.status, 20);
  const invalid = request(); invalid.extra = true;
  assert.equal(run({}, invalid).result.status, 20);
  assert.equal(run({}).result.status, 30);
});

test('evidence handles CRLF, BOM and supplementary Unicode in full source lines', () => {
  const text = '\uFEFFconst message = "\u00e9\u{1F600}";\r\nexport function echo() { return message; }\r\n';
  const { artifacts, workspace } = run({ 'unicode.ts': text });
  checkArtifacts(artifacts, workspace);
  const source = new Source('unicode.ts', 'a\u{1F600}b');
  assert.equal(source.evidence(3, 4).startColumn, 3);
});

test('describe follows the canonical v1 schema', () => {
  const result = spawnSync(process.execPath, [path.join(analyzerRoot, 'semantic-analyzer.mjs'), 'describe'], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  assert.ok(schemas.describe(JSON.parse(result.stdout)), JSON.stringify(schemas.describe.errors));
});
