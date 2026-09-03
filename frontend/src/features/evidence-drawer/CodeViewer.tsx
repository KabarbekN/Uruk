import { useEffect, useRef } from 'react';
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution';
import 'monaco-editor/esm/vs/basic-languages/sql/sql.contribution';
import 'monaco-editor/esm/vs/basic-languages/typescript/typescript.contribution';
import 'monaco-editor/esm/vs/basic-languages/javascript/javascript.contribution';
import 'monaco-editor/esm/vs/basic-languages/python/python.contribution';
import 'monaco-editor/esm/vs/basic-languages/yaml/yaml.contribution';
import 'monaco-editor/esm/vs/basic-languages/xml/xml.contribution';

self.MonacoEnvironment = { getWorker: () => new EditorWorker() };
const languages: Record<string, string> = {
  java: 'java',
  sql: 'sql',
  ts: 'typescript',
  tsx: 'typescript',
  js: 'javascript',
  jsx: 'javascript',
  py: 'python',
  yml: 'yaml',
  yaml: 'yaml',
  xml: 'xml',
};

export default function CodeViewer({
  snippet,
  filePath,
  startLine,
  endLine,
}: {
  snippet: string;
  filePath: string;
  startLine: number;
  endLine: number;
}) {
  const container = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!container.current) return;
    const model = monaco.editor.createModel(
      snippet,
      languages[filePath.split('.').pop() ?? ''] ?? 'plaintext',
    );
    const editor = monaco.editor.create(container.current, {
      model,
      theme: 'vs',
      readOnly: true,
      domReadOnly: true,
      automaticLayout: true,
      accessibilitySupport: 'on',
      ariaLabel: `${filePath}:${startLine}-${endLine}`,
      fontSize: 12,
      lineHeight: 21,
      fontFamily: 'Cascadia Code, Consolas, monospace',
      minimap: { enabled: false },
      scrollBeyondLastLine: false,
      lineNumbers: (line) => String(line + startLine - 1),
      lineNumbersMinChars: String(endLine).length + 1,
      wordWrap: 'on',
      contextmenu: false,
      renderLineHighlight: 'none',
      folding: false,
      padding: { top: 12, bottom: 12 },
      stickyScroll: { enabled: false },
    });
    const decoration = editor.createDecorationsCollection([
      {
        range: new monaco.Range(
          1,
          1,
          Math.min(model.getLineCount(), endLine - startLine + 1),
          1,
        ),
        options: { isWholeLine: true, className: 'evidence-source-line' },
      },
    ]);
    return () => {
      decoration.clear();
      editor.dispose();
      model.dispose();
    };
  }, [snippet, filePath, startLine, endLine]);
  return (
    <div ref={container} className="code-viewer" data-testid="source-viewer" />
  );
}
