"""Bounded XML/YAML/JSON changelog parse trees with exact original node spans."""

from dataclasses import dataclass
import json
import math
from xml.parsers import expat

import yaml

from source_maps import Region


class ChangelogError(ValueError):
    pass


@dataclass
class Mark:
    value: object
    region: Region

    def get(self, key, default=None):
        return self.value.get(key, default) if isinstance(self.value, dict) else default

    def scalar(self, key, default=None):
        node = self.get(key)
        return node.value if isinstance(node, Mark) and not isinstance(node.value, (dict, list)) else default

    def plain(self):
        if isinstance(self.value, dict):
            return {key: value.plain() for key, value in self.value.items()}
        if isinstance(self.value, list):
            return [value.plain() for value in self.value]
        return self.value


class BoundedLoader(yaml.SafeLoader):
    def __init__(self, stream):
        super().__init__(stream)
        self.level = 0
        self.nodes = 0

    def compose_node(self, parent, index):
        self.nodes += 1
        self.level += 1
        try:
            if self.level > 64 or self.nodes > 100000:
                raise ChangelogError("Changelog exceeds parse depth/node limits")
            if self.check_event(yaml.AliasEvent):
                raise ChangelogError("YAML aliases/merges are outside the supported bounded subset")
            return super().compose_node(parent, index)
        finally:
            self.level -= 1


def parse_mapping(source, is_json=False):
    def reject_constant(value):
        raise ChangelogError("Non-finite JSON values are not supported")

    if is_json:
        json.loads(source.text, parse_constant=reject_constant)
    loader = BoundedLoader(source.text)
    try:
        root = loader.get_single_node()
        if root is None:
            return Mark(None, Region.whole(source))

        def convert(node):
            start, end = node.start_mark.index, node.end_mark.index
            while end > start and source.text[end - 1].isspace():
                end -= 1
            region = Region(source, source.text[start:end], start, end)
            if node.tag not in {"tag:yaml.org,2002:" + value for value in ("map", "seq", "str", "int", "float", "bool", "null", "timestamp")}:
                raise ChangelogError("Unsupported YAML tag: " + node.tag)
            if isinstance(node, yaml.MappingNode):
                result = {}
                for key, value in node.value:
                    if not isinstance(key, yaml.ScalarNode):
                        raise ChangelogError("Changelog keys must be strings")
                    name = key.value
                    if name in result:
                        raise ChangelogError(f"Duplicate changelog key: {name}")
                    result[name] = convert(value)
                return Mark(result, region)
            if isinstance(node, yaml.SequenceNode):
                return Mark([convert(item) for item in node.value], region)
            value = json.loads(region.text) if is_json else node.value if node.tag.endswith(":timestamp") else loader.construct_object(node)
            if isinstance(value, float) and not math.isfinite(value):
                raise ChangelogError("Non-finite numeric values are not supported")
            if not isinstance(value, (str, int, float, bool, type(None))):
                raise ChangelogError("Only scalar JSON-compatible changelog values are supported")
            return Mark(value, region.decoded(value) if isinstance(value, str) else region)

        return convert(root)
    finally:
        loader.dispose()


@dataclass
class XmlNode:
    tag: str
    attributes: dict
    start: int
    end: int
    children: list
    content: list


def parse_xml(source):
    engine = expat.ParserCreate(namespace_separator="}")
    stack, roots = [], []
    data = source.text.encode("utf-8")
    count = 0

    def start(name, attributes):
        nonlocal count
        count += 1
        if len(stack) >= 64 or count > 100000:
            raise ChangelogError("XML changelog exceeds depth/node limits")
        namespace, _, local = name.rpartition("}")
        tag = local if namespace in {"", "http://www.liquibase.org/xml/ns/dbchangelog"} else name
        node = XmlNode(tag, attributes, engine.CurrentByteIndex, 0, [], [])
        (stack[-1].children if stack else roots).append(node)
        stack.append(node)

    def end(name):
        node = stack.pop()
        offset = engine.CurrentByteIndex
        # Expat locates the closing tag, or the byte immediately after a self-closing tag.
        self_closing = not node.children and not node.content and data[offset - 2:offset] == b"/>"
        node.end = offset if self_closing else data.index(b">", offset) + 1

    def content(value):
        if stack:
            stack[-1].content.append((value, engine.CurrentByteIndex))

    def reject(*args):
        raise ChangelogError("XML DTD/entity declarations are rejected")

    engine.StartElementHandler = start
    engine.EndElementHandler = end
    engine.CharacterDataHandler = content
    engine.StartDoctypeDeclHandler = reject
    engine.EntityDeclHandler = reject
    engine.ExternalEntityRefHandler = reject
    engine.Parse(data, True)
    if len(roots) != 1:
        raise ChangelogError("Expected one XML root")

    # Decode each byte interval once, even in documents with many small XML nodes.
    offsets, pending = {0}, list(roots)
    while pending:
        item = pending.pop()
        offsets.update((item.start, item.end))
        offsets.update(offset for _, offset in item.content)
        pending.extend(item.children)
    positions, byte, character = {}, 0, 0
    for offset in sorted(offsets):
        character += len(data[byte:offset].decode("utf-8"))
        positions[offset] = character
        byte = offset

    def convert(node):
        start, end = positions[node.start], positions[node.end]
        region = Region(source, source.text[start:end], start, end)
        values = {key.rsplit("}", 1)[-1]: Mark(value, region.decoded(value)) for key, value in node.attributes.items()}
        children = [(child.tag, convert(child)) for child in node.children]
        text = "".join(value for value, _ in node.content)
        if node.tag in {"sql", "createView", "createProcedure"} and text.strip():
            key = {"sql": "sql", "createView": "selectQuery", "createProcedure": "procedureBody"}[node.tag]
            text_start = positions[node.content[0][1]]
            text_end = text_start + len(text)
            text_region = Region(source, text, text_start, text_end) if source.text[text_start:text_end] == text else region.decoded(text)
            values[key] = Mark(text, text_region)
        if node.tag == "databaseChangeLog":
            return Mark([Mark({tag: value}, value.region) for tag, value in children], region)
        if node.tag in {"rollback", "preConditions"}:
            values["changes"] = Mark([Mark({tag: value}, value.region) for tag, value in children], region)
        elif node.tag == "changeSet":
            changes = []
            for tag, value in children:
                if tag in {"rollback", "preConditions", "validCheckSum", "modifySql", "comment"}:
                    values[tag] = value
                else:
                    changes.append(Mark({tag: value}, value.region))
            values["changes"] = Mark(changes, region)
        else:
            columns = []
            for tag, value in children:
                if tag == "column":
                    columns.append(Mark({"column": value}, value.region))
                elif tag in values:
                    raise ChangelogError(f"Repeated unsupported XML element: {tag}")
                else:
                    values[tag] = value
            if columns:
                values["columns"] = Mark(columns, region)
        return Mark(values, region)

    root = roots[0]
    return Mark({root.tag: convert(root)}, Region.whole(source))


def parse_document(source):
    extension = source.path.rsplit(".", 1)[-1].lower()
    try:
        return parse_xml(source) if extension == "xml" else parse_mapping(source, extension == "json")
    except (yaml.YAMLError, expat.ExpatError, json.JSONDecodeError, RecursionError) as error:
        raise ChangelogError(str(error)) from error
