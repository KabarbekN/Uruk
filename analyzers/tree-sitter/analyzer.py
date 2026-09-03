"""Language-neutral syntax extraction using lazily loaded, pinned tree-sitter grammars."""

import importlib
from pathlib import Path
import sys
from collections import defaultdict

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "shared"))
from runtime import cli

DESCRIPTOR = {"id": "tree-sitter", "version": "0.1.0", "contractVersion": "1.0",
              "languages": ["JAVA", "TYPESCRIPT"], "frameworks": [],
              "capabilities": ["SYMBOLS", "IMPORTS", "ANNOTATIONS", "CONDITIONS", "LOOPS", "ASSIGNMENTS", "LITERALS", "CALLS"],
              "executionMode": "SAFE_STATIC",
              "metadata": {"supportsIncremental": False, "requiresDependencyResolution": False, "networkPolicy": "DENY"}}
GRAMMARS = {".java": ("java", "tree_sitter_java", "language"),
            ".ts": ("typescript", "tree_sitter_typescript", "language_typescript"),
            ".mts": ("typescript", "tree_sitter_typescript", "language_typescript"),
            ".cts": ("typescript", "tree_sitter_typescript", "language_typescript"),
            ".tsx": ("tsx", "tree_sitter_typescript", "language_tsx")}
PARSERS = {}
DECLARATIONS = {"class_declaration": "CLASS", "interface_declaration": "INTERFACE",
                "enum_declaration": "CLASS", "record_declaration": "CLASS",
                "annotation_type_declaration": "INTERFACE", "method_declaration": "METHOD",
                "constructor_declaration": "METHOD", "method_definition": "METHOD",
                "method_signature": "METHOD", "function_declaration": "FUNCTION",
                "function_expression": "FUNCTION", "arrow_function": "FUNCTION",
                "internal_module": "MODULE", "module_declaration": "MODULE"}
SYNTAX = {"package_declaration": "PACKAGE", "import_declaration": "IMPORT", "import_statement": "IMPORT",
          "annotation": "ANNOTATION", "marker_annotation": "ANNOTATION", "decorator": "ANNOTATION",
          "formal_parameter": "PARAMETER", "spread_parameter": "PARAMETER",
          "required_parameter": "PARAMETER", "optional_parameter": "PARAMETER",
          "assignment_expression": "ASSIGNMENT", "augmented_assignment_expression": "ASSIGNMENT",
          "variable_declarator": "ASSIGNMENT", "update_expression": "ASSIGNMENT",
          "for_statement": "LOOP", "enhanced_for_statement": "LOOP", "for_in_statement": "LOOP",
          "while_statement": "LOOP", "do_statement": "LOOP",
          "if_statement": "CONDITION", "ternary_expression": "CONDITION", "switch_expression": "CONDITION",
          "switch_statement": "CONDITION", "method_invocation": "CALL", "call_expression": "CALL",
          "object_creation_expression": "CALL", "new_expression": "CALL"}


def parser_for(extension):
    name, module, factory = GRAMMARS[extension]
    if name not in PARSERS:
        from tree_sitter import Language, Parser
        PARSERS[name] = Parser(Language(getattr(importlib.import_module(module), factory)()))
    return name, PARSERS[name]


def extract(run, source):
    language, parser = parser_for(Path(source.path).suffix.lower())
    tree = parser.parse(source.data)
    file_key = "file:" + source.path
    run.fact("FILE", file_key, source.path, None, source.evidence(), language=language)
    counters = defaultdict(int)

    def text(node):
        return source.data[node.start_byte:node.end_byte].decode("utf-8") if node else ""

    stack = [(tree.root_node, file_key)]
    visited = 0
    while stack:
        run.tick()
        node, owner = stack.pop()
        visited += 1
        if visited > 500000:
            from runtime import Rejected
            raise Rejected("NODE_LIMIT", "Syntax tree exceeds 500000 nodes")
        if node.type == "ERROR" or node.is_missing:
            run.diagnostic("SYNTAX_ERROR", f"Malformed {language}: {node.type}", source.path,
                           node.start_point.row + 1, severity="ERROR")
        kind = DECLARATIONS.get(node.type) or SYNTAX.get(node.type)
        if node.type.endswith("_literal") or node.type in {"string", "number", "true", "false", "null"}:
            kind = "LITERAL"
        if kind:
            name_node = node.child_by_field_name("name")
            name = text(name_node)
            if node.type == "arrow_function" and node.parent and node.parent.type == "variable_declarator":
                name = text(node.parent.child_by_field_name("name"))
            if kind == "CALL":
                name = text(node.child_by_field_name("function")) or name or text(node.child_by_field_name("type"))
                receiver = text(node.child_by_field_name("object"))
                name = f"{receiver}.{name}" if receiver else name
            if kind in {"ANNOTATION", "PARAMETER"}:
                name = name or text(node.child_by_field_name("pattern"))
            signature = ""
            if kind in {"FUNCTION", "METHOD"}:
                parameters = node.child_by_field_name("parameters")
                signature = "(" + ",".join(text(p.child_by_field_name("type")) or "?" for p in parameters.named_children) + ")" if parameters else "()"
            identity = f"{owner}/{kind.lower()}:{name}{signature}" if node.type in DECLARATIONS else f"{owner}/{kind.lower()}"
            index = counters[identity]
            counters[identity] += 1
            key = identity + (f":{index}" if node.type not in DECLARATIONS or index else "")
            properties = {"syntaxKind": node.type, "language": language}
            if kind == "CONDITION":
                expression = node.child_by_field_name("condition") or node.child_by_field_name("value") or node
                properties["expression"] = text(expression)[:4000]
            elif kind not in {"CLASS", "INTERFACE", "METHOD", "FUNCTION", "MODULE"}:
                properties["expression"] = text(node)[:4000]
            if kind == "CALL":
                properties.update({"targetResolution": "UNRESOLVED", "targetExpression": name})
            evidence = source.byte_evidence(node.start_byte, node.end_byte)
            run.fact(kind, key, name or node.type, owner, evidence, **properties)
            run.relation(owner, key, "CONTAINS", evidence)
            if node.type in DECLARATIONS:
                owner = key
        stack.extend((child, owner) for child in reversed(node.named_children))
    if tree.root_node.has_error:
        run.counts["filesFailed"] += 1
        run.unknown.add("MALFORMED_SOURCE")
    else:
        run.counts["filesParsed"] += 1


def analyze(run):
    for source in run.sources(GRAMMARS):
        extract(run, source)


def main():
    return cli(DESCRIPTOR, analyze)


if __name__ == "__main__":
    raise SystemExit(main())
