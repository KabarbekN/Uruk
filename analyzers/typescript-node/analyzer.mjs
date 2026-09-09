import path from "node:path";
import ts from "typescript";
import { Rejected } from "../shared/runtime.mjs";

export const descriptor = {
  id: "typescript-node",
  version: "0.1.0",
  contractVersion: "1.0",
  languages: ["TYPESCRIPT", "JAVASCRIPT"],
  frameworks: ["NESTJS", "CLASS_VALIDATOR", "REACT", "REACT_NATIVE"],
  capabilities: [
    "SYMBOLS",
    "IMPORTS",
    "ENDPOINTS",
    "UI_ACTIONS",
    "AUTHORIZATION",
    "VALIDATIONS",
    "CALL_GRAPH",
    "CONDITIONS",
  ],
  executionMode: "SAFE_STATIC",
  metadata: {
    supportsIncremental: false,
    requiresDependencyResolution: false,
    networkPolicy: "DENY",
  },
};
const verbs = new Set([
  "Get",
  "Post",
  "Put",
  "Patch",
  "Delete",
  "Head",
  "Options",
  "All",
]);
const constraints = new Set([
  "Min",
  "Max",
  "MinLength",
  "MaxLength",
  "Length",
  "IsEmail",
  "IsNotEmpty",
  "IsOptional",
  "IsInt",
  "IsPositive",
  "IsUUID",
  "Matches",
  "ValidateNested",
]);

export function analyze(run) {
  const sources = run.sources(
    new Set([".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs"]),
  );
  const files = new Map(
    sources.map((source) => [path.resolve(source.absolutePath), source]),
  );
  const parsed = new Map();
  const options = {
    noEmit: true,
    noLib: true,
    strict: true,
    allowJs: true,
    checkJs: false,
    experimentalDecorators: true,
    target: ts.ScriptTarget.ES2022,
    module: ts.ModuleKind.NodeNext,
    moduleResolution: ts.ModuleResolutionKind.NodeNext,
    types: [],
  };
  const resolve = (name, containing) => {
    if (!name.startsWith(".")) return undefined;
    const base = path.resolve(path.dirname(containing), name);
    const alternatives = [
      base,
      ...[".ts", ".tsx", ".mts", ".cts", ".d.ts", "/index.ts"].map(
        (ext) => base + ext,
      ),
    ];
    if ([".js", ".mjs", ".cjs"].includes(path.extname(base)))
      alternatives.push(base.slice(0, -path.extname(base).length) + ".ts");
    const found = alternatives.find((candidate) =>
      files.has(path.normalize(candidate)),
    );
    return found
      ? { resolvedFileName: found, isExternalLibraryImport: false }
      : undefined;
  };
  // The compiler host can see only previously bounded, decoded source files. It never executes tsconfig/plugins or opens dependencies.
  const host = {
    getSourceFile(fileName, languageVersion) {
      const name = path.resolve(fileName),
        source = files.get(name);
      if (!source) return undefined;
      if (!parsed.has(name))
        parsed.set(
          name,
          ts.createSourceFile(name, source.text, languageVersion, true),
        );
      return parsed.get(name);
    },
    getDefaultLibFileName: () => "",
    writeFile() {},
    getCurrentDirectory: () => run.root,
    getCanonicalFileName: (file) => path.resolve(file),
    useCaseSensitiveFileNames: () => true,
    getNewLine: () => "\n",
    fileExists: (file) => files.has(path.resolve(file)),
    readFile: (file) => files.get(path.resolve(file))?.text,
    getDirectories: () => [],
    resolveModuleNames: (names, containing) =>
      names.map((name) => resolve(name, containing)),
  };
  const program = ts.createProgram([...files.keys()], options, host),
    checker = program.getTypeChecker();
  const keys = new Map(),
    owners = new Map(),
    ordinals = new Map(),
    bindings = new Map();
  const unique = (key) => {
    const count = ordinals.get(key) ?? 0;
    ordinals.set(key, count + 1);
    return `${key}:${count}`;
  };
  const declarationKind = (node) =>
    ts.isClassDeclaration(node)
      ? "CLASS"
      : ts.isInterfaceDeclaration(node)
        ? "INTERFACE"
        : ts.isMethodDeclaration(node) ||
            ts.isMethodSignature(node) ||
            ts.isConstructorDeclaration(node)
          ? "METHOD"
          : ts.isFunctionDeclaration(node) ||
              ts.isArrowFunction(node) ||
              ts.isFunctionExpression(node)
            ? "FUNCTION"
            : ts.isParameter(node)
              ? "PARAMETER"
              : ts.isPropertyDeclaration(node) || ts.isPropertySignature(node)
                ? "DTO_FIELD"
                : ts.isModuleDeclaration(node)
                  ? "MODULE"
                  : null;
  const named = (node) =>
    node.name?.getText() ??
    (ts.isConstructorDeclaration(node)
      ? "constructor"
      : ts.isArrowFunction(node) && ts.isVariableDeclaration(node.parent)
        ? node.parent.name.getText()
        : "anonymous");
  const children = (node) => {
    const list = [];
    ts.forEachChild(node, (child) => {
      list.push(child);
    });
    return list;
  };

  for (const source of sources) {
    const sf = program.getSourceFile(source.absolutePath),
      fileKey = `file:${source.path}`;
    const imports = new Map();
    bindings.set(sf, imports);
    run.fact("FILE", fileKey, source.path, null, source.evidence(), {
      language: "TYPESCRIPT",
    });
    for (const node of sf.statements) {
      if (
        !ts.isImportDeclaration(node) ||
        !ts.isStringLiteral(node.moduleSpecifier)
      )
        continue;
      const module = node.moduleSpecifier.text,
        clause = node.importClause;
      if (clause?.name)
        imports.set(clause.name.text, {
          module,
          imported: "default",
          node: clause.name,
        });
      if (clause?.namedBindings && ts.isNamedImports(clause.namedBindings)) {
        for (const entry of clause.namedBindings.elements)
          imports.set(entry.name.text, {
            module,
            imported: entry.propertyName?.text ?? entry.name.text,
            node: entry.name,
          });
      } else if (
        clause?.namedBindings &&
        ts.isNamespaceImport(clause.namedBindings)
      )
        imports.set(clause.namedBindings.name.text, {
          module,
          imported: "*",
          node: clause.namedBindings.name,
        });
      run.fact(
        "IMPORT",
        unique(`${fileKey}/import:${module}`),
        module,
        fileKey,
        source.evidence(node.getStart(sf), node.end),
        { module },
      );
      if (!resolve(module, source.absolutePath)) {
        run.unknown.add("EXTERNAL_TYPES");
        run.partial.add("CALL_GRAPH");
        run.diagnostic(
          "UNRESOLVED_IMPORT",
          `Dependency or alias ${module} is not available in the bounded compiler host`,
          source.path,
          source.evidence(node.getStart(sf), node.end).startLine,
        );
      }
    }
    const stack = [[sf, fileKey]];
    let visited = 0;
    while (stack.length) {
      run.tick();
      if (++visited > 500000)
        throw new Rejected("NODE_LIMIT", "Syntax tree exceeds 500000 nodes");
      const [node, parent] = stack.pop(),
        kind = declarationKind(node);
      owners.set(node, parent);
      let owner = parent;
      if (kind) {
        const name = named(node),
          signature = node.parameters
            ? `(${node.parameters.map((parameter) => parameter.type?.getText() ?? "?").join(",")})`
            : "";
        const key = unique(
            `${parent}/${kind.toLowerCase()}:${name}${signature}`,
          ),
          evidence = source.evidence(node.getStart(sf), node.end);
        keys.set(node, key);
        owner = key;
        run.fact(kind, key, name, parent, evidence, {
          language: "TYPESCRIPT",
          declaredType: node.type?.getText() ?? null,
        });
        run.relation(parent, key, "CONTAINS", evidence);
      }
      for (const child of children(node).reverse()) stack.push([child, owner]);
    }
    const errors = program.getSyntacticDiagnostics(sf);
    if (errors.length) {
      run.counts.filesFailed++;
      run.unknown.add("MALFORMED_SOURCE");
    } else run.counts.filesParsed++;
    for (const error of errors)
      run.diagnostic(
        "TYPESCRIPT_PARSE_ERROR",
        ts.flattenDiagnosticMessageText(error.messageText, "\n"),
        source.path,
        sf.getLineAndCharacterOfPosition(error.start ?? 0).line + 1,
        "ERROR",
        { diagnosticCode: error.code },
      );
  }

  const literal = (node) => {
    if (!node) return undefined;
    if (ts.isStringLiteral(node) || ts.isNoSubstitutionTemplateLiteral(node))
      return node.text;
    if (ts.isNumericLiteral(node)) return Number(node.text);
    if (node.kind === ts.SyntaxKind.TrueKeyword) return true;
    if (node.kind === ts.SyntaxKind.FalseKeyword) return false;
    if (
      ts.isPrefixUnaryExpression(node) &&
      node.operator === ts.SyntaxKind.MinusToken &&
      ts.isNumericLiteral(node.operand)
    )
      return -Number(node.operand.text);
    if (ts.isArrayLiteralExpression(node)) {
      const values = node.elements.map(literal);
      return values.includes(undefined) ? undefined : values;
    }
    return undefined;
  };
  const decorators = (node) =>
    ts.canHaveDecorators(node) ? (ts.getDecorators(node) ?? []) : [];
  const decoratorInfo = (decorator, sf) => {
    const call = ts.isCallExpression(decorator.expression)
      ? decorator.expression
      : null;
    const expression = call?.expression ?? decorator.expression;
    let binding, imported;
    if (ts.isIdentifier(expression)) {
      binding = bindings.get(sf).get(expression.text);
      imported = binding?.imported;
    } else if (
      ts.isPropertyAccessExpression(expression) &&
      ts.isIdentifier(expression.expression)
    ) {
      binding = bindings.get(sf).get(expression.expression.text);
      imported = binding?.imported === "*" ? expression.name.text : null;
    }
    const identifier = ts.isPropertyAccessExpression(expression)
      ? expression.expression
      : expression;
    if (
      binding &&
      checker.getSymbolAtLocation(identifier) !==
        checker.getSymbolAtLocation(binding.node)
    )
      binding = null;
    return {
      module: binding?.module,
      imported,
      args: call?.arguments ?? [],
      decorator,
    };
  };
  const routeValues = (info) => {
    if (!info.args.length) return [""];
    const value = literal(info.args[0]);
    if (typeof value === "string") return [value];
    if (
      Array.isArray(value) &&
      value.every((entry) => typeof entry === "string")
    )
      return value;
    if (ts.isObjectLiteralExpression(info.args[0])) {
      const prop = info.args[0].properties.find(
        (item) =>
          ts.isPropertyAssignment(item) && item.name.getText() === "path",
      );
      if (prop) {
        const result = literal(prop.initializer);
        return typeof result === "string" ? [result] : null;
      }
    }
    return null;
  };
  const declarationKey = (declaration) => {
    if (!declaration) return undefined;
    if (keys.has(declaration)) return keys.get(declaration);
    if (
      (ts.isVariableDeclaration(declaration) ||
        ts.isPropertyDeclaration(declaration) ||
        ts.isPropertyAssignment(declaration)) &&
      declaration.initializer
    )
      return keys.get(declaration.initializer);
    return undefined;
  };
  const handlerKey = (expression) => {
    if (!expression) return undefined;
    if (ts.isArrowFunction(expression) || ts.isFunctionExpression(expression))
      return keys.get(expression);
    const location = ts.isPropertyAccessExpression(expression)
      ? expression.name
      : expression;
    let symbol = checker.getSymbolAtLocation(location);
    if (symbol && symbol.flags & ts.SymbolFlags.Alias) {
      try {
        symbol = checker.getAliasedSymbol(symbol);
      } catch {
        /* unresolved aliases stay partial */
      }
    }
    return symbol?.declarations?.map(declarationKey).find(Boolean);
  };

  for (const source of sources) {
    const sf = program.getSourceFile(source.absolutePath),
      stack = [sf];
    while (stack.length) {
      const node = stack.pop();
      run.tick();
      const owner = keys.get(node) ?? owners.get(node),
        evidence = source.evidence(node.getStart(sf), node.end);
      for (const decoration of decorators(node)) {
        const info = decoratorInfo(decoration, sf),
          ev = source.evidence(decoration.getStart(sf), decoration.end);
        run.fact(
          "ANNOTATION",
          unique(`${owner}/decorator`),
          info.imported ?? decoration.expression.getText(),
          owner,
          ev,
          {
            module: info.module ?? null,
            expression: decoration.expression.getText(),
          },
        );
        if (
          info.module === "class-validator" &&
          constraints.has(info.imported)
        ) {
          const values = info.args.map(literal),
            dynamic = values.some((value) => value === undefined);
          run.fact(
            "VALIDATION_RULE",
            unique(`${owner}/validation:${info.imported}`),
            info.imported,
            owner,
            ev,
            {
              constraint: info.imported,
              arguments: values.map((value) => value ?? null),
              argumentExpressions: info.args.map((arg) => arg.getText()),
              argumentResolution: dynamic ? "PARTIAL" : "LITERAL",
            },
            "FRAMEWORK_DERIVED",
            0.95,
          );
          if (dynamic) {
            run.unknown.add("DYNAMIC_VALIDATION");
            run.diagnostic(
              "DYNAMIC_VALIDATION",
              "Validation arguments contain expressions that are not statically evaluated",
              source.path,
              ev.startLine,
            );
          }
        }
        if (info.module === "@nestjs/common" && info.imported === "UseGuards") {
          const key = unique(`${owner}/guards`);
          run.fact(
            "AUTHORIZATION_RULE",
            key,
            "UseGuards",
            owner,
            ev,
            {
              guards: info.args.map((arg) => arg.getText()),
              semantics: "GUARD_BINDING",
              guardBehavior: "UNKNOWN",
            },
            "FRAMEWORK_DERIVED",
            0.95,
          );
          run.relation(
            owner,
            key,
            "AUTHORIZED_BY",
            ev,
            "FRAMEWORK_DERIVED",
            0.95,
          );
          run.unknown.add("GUARD_BEHAVIOR");
        }
        if (
          info.module === "@nestjs/common" &&
          verbs.has(info.imported) &&
          ts.isMethodDeclaration(node) &&
          ts.isClassDeclaration(node.parent)
        ) {
          const controller = decorators(node.parent)
            .map((value) => decoratorInfo(value, sf))
            .find(
              (value) =>
                value.module === "@nestjs/common" &&
                value.imported === "Controller",
            );
          if (controller) {
            const prefixes = routeValues(controller),
              routes = routeValues(info);
            if (!prefixes || !routes || prefixes.length * routes.length > 256) {
              run.unknown.add("DYNAMIC_ROUTES");
              run.diagnostic(
                "DYNAMIC_ROUTE",
                "Route cannot be fully resolved from bounded literal paths",
                source.path,
                ev.startLine,
              );
            } else {
              for (const prefix of prefixes)
                for (const route of routes) {
                  const routePath =
                    "/" +
                    [prefix, route]
                      .flatMap((part) => part.split("/"))
                      .filter(Boolean)
                      .join("/");
                  const method = info.imported.toUpperCase(),
                    key = `nestjs:endpoint:${method}:${routePath}:${owner}`;
                  run.fact(
                    "ENDPOINT",
                    key,
                    `${method} ${routePath}`,
                    owner,
                    ev,
                    {
                      httpMethod: method,
                      path: routePath,
                      handlerKey: owner,
                      pathScope: "CONTROLLER_RELATIVE",
                      globalPrefix: "UNKNOWN",
                    },
                    "FRAMEWORK_DERIVED",
                    0.95,
                  );
                  run.relation(
                    key,
                    owner,
                    "ENTRY_TO",
                    ev,
                    "FRAMEWORK_DERIVED",
                    0.95,
                  );
                  for (const guard of decorators(node.parent)
                    .map((value) => decoratorInfo(value, sf))
                    .filter(
                      (value) =>
                        value.module === "@nestjs/common" &&
                        value.imported === "UseGuards",
                    )) {
                    const authKey = `${key}/class-guards`;
                    run.fact(
                      "AUTHORIZATION_RULE",
                      authKey,
                      "UseGuards",
                      key,
                      source.evidence(
                        guard.decorator.getStart(sf),
                        guard.decorator.end,
                      ),
                      {
                        guards: guard.args.map((arg) => arg.getText()),
                        semantics: "INHERITED_GUARD_BINDING",
                        guardBehavior: "UNKNOWN",
                      },
                      "FRAMEWORK_DERIVED",
                      0.95,
                    );
                    run.relation(
                      key,
                      authKey,
                      "AUTHORIZED_BY",
                      ev,
                      "FRAMEWORK_DERIVED",
                      0.95,
                    );
                  }
                }
            }
          }
        }
      }
      if (
        ts.isIfStatement(node) ||
        ts.isConditionalExpression(node) ||
        ts.isSwitchStatement(node)
      ) {
        const condition = node.expression ?? node.condition;
        run.fact(
          "CONDITION",
          unique(`${owner}/condition`),
          "condition",
          owner,
          evidence,
          { expression: condition.getText() },
        );
      }
      if (
        ts.isJsxAttribute(node) &&
        /^on[A-Z]/.test(node.name.getText(sf)) &&
        node.initializer &&
        ts.isJsxExpression(node.initializer)
      ) {
        const event = node.name.getText(sf),
          expression = node.initializer.expression;
        if (expression) {
          const targetKey = handlerKey(expression),
            actionKey = unique(`${owner}/ui:${event}`);
          const expressionText = expression.getText(sf).slice(0, 500);
          run.fact(
            "UI_ACTION",
            actionKey,
            `${event} → ${expressionText}`,
            owner,
            evidence,
            {
              event,
              handlerKey: targetKey ?? null,
              handlerExpression: expressionText,
              framework: "REACT",
              targetResolution: targetKey ? "DECLARED_HANDLER" : "UNRESOLVED",
            },
            "FRAMEWORK_DERIVED",
            targetKey ? 0.94 : 0.72,
          );
          if (targetKey)
            run.relation(
              actionKey,
              targetKey,
              "ENTRY_TO",
              evidence,
              "FRAMEWORK_DERIVED",
              0.94,
            );
          else {
            run.partial.add("UI_ACTIONS");
            run.unknown.add("UNRESOLVED_UI_HANDLERS");
          }
        }
      }
      if (ts.isCallExpression(node) || ts.isNewExpression(node)) {
        let ancestor = node.parent;
        while (
          ancestor &&
          !ts.isDecorator(ancestor) &&
          !ts.isSourceFile(ancestor) &&
          !ts.isStatement(ancestor)
        )
          ancestor = ancestor.parent;
        if (!ancestor || !ts.isDecorator(ancestor)) {
          const signature = checker.getResolvedSignature(node),
            declaration = signature?.declaration;
          const targetKey = declaration && keys.get(declaration),
            name = node.expression.getText(),
            key = unique(`${owner}/call`);
          run.fact(
            "CALL",
            key,
            name,
            owner,
            evidence,
            {
              expression: node.getText().slice(0, 4000),
              targetResolution: targetKey ? "DECLARED_SIGNATURE" : "UNRESOLVED",
              targetKey: targetKey ?? null,
            },
            targetKey ? "STATIC_TYPED" : "STATIC_SYNTAX",
            targetKey ? 0.96 : 0.8,
          );
          if (targetKey)
            run.relation(
              owner,
              targetKey,
              "CALLS",
              evidence,
              "STATIC_TYPED",
              0.96,
            );
          else {
            run.partial.add("CALL_GRAPH");
            run.unknown.add("UNRESOLVED_CALL_TARGETS");
            run.diagnostic(
              "UNRESOLVED_CALL_TARGET",
              `Cannot resolve the declaration of ${name}`,
              source.path,
              evidence.startLine,
            );
          }
        }
      }
      for (const child of children(node).reverse()) stack.push(child);
    }
  }
}
